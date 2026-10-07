# CM-20261007-20：订单列表 DATETIME(3) 查询精度评审（阶段 D）

## 1. 评审结论与范围

结论：**GO，限定修复现有 dynamic-monthly 订单列表的查询窗口精度；不启用 Sharding、不改变默认物理表链路、不新增请求字段或接口。**

当前 `OrderServiceImpl.list` 以纳秒级 `OffsetDateTime` 计算月份，但通过 `Timestamp.from(OffsetDateTime.toInstant())` 把窗口交给 MySQL `DATETIME(3)`。这会使不可存储的纳秒边界参与月份选择或 SQL 比较，导致边界订单误包含、漏查或命中不存在月份后错误返回 503。

## 2. API 与时间语义

继续保持既有 `[startTime,endTime)`、任意合法 ISO-8601 offset 按同一 instant 规范到 `Asia/Shanghai` 的契约。服务在一次请求开始时只采样一次 Shanghai `now`，再计算缺省 start/end；不得分别调用 now 造成窗口漂移。

规范化步骤：

1. 解析输入或缺省值，并按同一 instant 转换到 `Asia/Shanghai`；
2. 校验原始 `start < end`，原始 `start >= end` 继续返回既有 400 参数错误；
3. 将两个端点向上取整到可存储的 DATETIME(3) 毫秒：已有整毫秒保持不变，存在纳秒余数则加到下一个毫秒；
4. 校验规范化后的 `[normalizedStart,normalizedEnd)` 仍为合法 MySQL DATETIME 年份 `1000..9999`。端点向上取整溢出 `10000-01-01` 时返回 400，不使用超范围 exclusive 哨兵；
5. 若规范化后 start==end，返回 HTTP 200 的空分页结果（`total=0,items=[]`），不计算月份、不访问任何订单表、不因缺表返回 503；规范化后反转属于 400 防御性错误；
6. SQL 的 start/end 参数、月份 first/last 计算和分页都使用同一个规范化窗口。

例如：

- `2026-12-31T23:59:59.999000001+08:00` 到 `2027-01-01T00:00:00+08:00` 规范化后相等，返回空页且不查 SQL；
- end 为 `2027-01-01T00:00:00.000000001+08:00` 时规范化为 `00:00:00.001`，包含新月首个可存储毫秒 `00:00:00.000`；
- 9999 年末端点向上取整越界时 400，避免使用 MySQL 不支持的哨兵时间。

## 3. 窗口值对象与查询链路

提取纯时间窗口值对象（建议 `domain.dto.OrderQueryWindow` 或等价 `util` 类型），至少包含规范化 start/end、firstMonth/lastMonth 及空窗口标志。该类型不得持有 DataSource、请求对象或业务副作用。

`OrderServiceImpl.list` 使用该值对象：

- 空窗口在任何 count/list SQL 前直接返回既有分页结构；
- 非空窗口按规范化 end 的前一个可存储毫秒计算 lastMonth，维持既有左闭右开跨月遍历；
- `OrderSqlMapper.countOrders/findOrderPage` 接收规范化的毫秒时间参数；
- 缺表、数据库异常和结果不完整继续整体返回 `ORDER_QUERY_SHARD_UNAVAILABLE`/503；
- 不改变订单写入 created_at、expire_at、outbox、库存动作和恢复任务的时间表示。

## 4. 与 17B 月路由契约

默认 dynamic-monthly 仍由应用按月份选择物理表；本轮不接入 ShardingDataSource。规范化窗口的每个端点都是合法 DATETIME(3) LocalDateTime，交给 17B 算法时遵守 Shanghai 本地字段和毫秒精度契约。未来 Sharding range adapter 必须复用相同窗口值，不能重新按纳秒或 JVM 默认时区计算。

缺少 `created_at` 分片条件导致框架广播的行为不在本轮接入；后续 adapter 必须明确禁止无界广播并保持 17B fail-closed 规则。

## 5. 验收矩阵

1. 纳秒 start/end 规范化后相等：200 空页、无订单 SQL、即使目标月份缺表也不返回 503。
2. end 为新月首毫秒后 1ns：规范化后包含新月首毫秒；start 端为首毫秒后 1ns 时不包含首毫秒。
3. 月末 999ms、跨月、跨年、任意 ISO offset 与非 `+08:00` instant 逐项验证；数据库使用 Shanghai/session 约定。
4. 原始 start==end、start>end、规范化溢出 10000 年分别保持 400 语义。
5. 默认 start/end 使用同一个 now 快照；长请求和边界月不会出现 start/end 漂移。
6. 规范化非空窗口的 count/items、排序、分页、缺表 503 与修复前正常毫秒范围结果一致。
7. 真实 MySQL + 默认 Order Service/Mapper 验证纳秒/月边界、offset、缺表；与 17B 算法的 precise/range 边界对照。
8. 默认 dynamic-monthly、Seata 独立演练、Pay/Order/Stock 既有交易回归不受影响。

## 6. 实施门与范围声明

实现限定在窗口值对象、查询入口和必要 Mapper 参数测试；不改 API 字段、Sharding profile、DataSource、订单写入时间、其他业务时间辅助方法或默认物理表方案。17D 完成只表示订单查询与 DATETIME(3) 事实对齐，不表示 CM-20 Sharding 主链路已接入。
