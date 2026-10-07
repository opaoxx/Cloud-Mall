# CM-20261007-20：订单创建时间 DATETIME(3) 精度评审

## 1. 评审结论与范围

结论：**GO，限定修复常规订单与秒杀落单写入时间的毫秒精度和 offset 解释；不改变 API、幂等键、事件号、状态机、默认 dynamic-monthly 或 Sharding 接入。**

当前常规创建用纳秒 `now()` 生成 orderNo/月表并写入 DATETIME(3)，月末四舍五入可能使物理表月份与 stored `created_at` 不一致。秒杀消费保留事件 offset 的原始纳秒值，直接 `toLocalDateTime()` 可能把 UTC 等非 +08 offset 当作错误上海本地时间，并产生错误月份。

## 2. 统一时间值对象

新增纯工具/值对象 `OrderCreationTime.normalize(OffsetDateTime)` 或等价实现：

1. 将输入按 same instant 转为项目固定 `+08:00`；
2. 向下截断到毫秒：`nano = (nano / 1_000_000) * 1_000_000`；
3. 校验结果为 MySQL DATETIME 合法年份 `1000..9999`；非法或转换越界抛出明确参数/消息错误；
4. 不访问时钟、数据库、DataSource，不生成 orderNo，不改变 offset 之外的业务语义。

## 3. 常规订单创建

`OrderServiceImpl.create` 在确定创建时刻后只采样一次 `now`，先 normalize，再用同一 normalized value：

- 生成 orderNo 的 `yyyyMM` 和订单主表名；
- 计算 expireAt（normalized created + 30 分钟）；
- 写订单、明细、幂等记录、timeout outbox 的 created/updated/expire 字段；
- 写常规 reservation intent 的 created/updated 时间。

不得用未规范化 now 再次生成月份、表名或上述写入时间。库存 operation、幂等键、补偿状态、markReserved/markCommitted 等现有状态时间辅助调用不在本轮统一改写，除非它们直接复用创建时间字段契约。

## 4. 秒杀订单消费

`occurredAt` 继续保留现有缺省/非法处理：缺省使用当前时间，非法继续按现有异常/消息重试或死信语义处理；显式 occurredAt 解析后必须调用同一 normalize，再用于订单 createdAt、expireAt、明细 createdAt 和 timeout outbox 的 created/updated/occurred 时间。

秒杀原有 orderNo、eventId、Idempotency-Key、requestHash、dedup 和状态机不改变。`tableFromNo(orderNo)` 的既有订单号归属规则本轮不重算、不改订单号；因此 Stock 候选 orderNo、payload 独立 now、缺 occurredAt 的旧消息跨月恢复等问题明确留待后续评审，不通过猜测修改订单号或表名。

## 5. 边界和事务

- normalize 是纯函数；输入 offset 先转换为 +08，再截断，不能直接 `toLocalDateTime()` 丢失 offset 语义；
- 月末 `23:59:59.999500+08` 归档到 `.999`，不会四舍五入到下月；
- UTC 等非 +08 offset 按同一 instant 得到正确上海本地月份；
- normalize 结果越过 9999 年或低于 1000 年时明确失败；
- 不改全部 `now()` 辅助方法、支付/库存/取消时间、Order 状态 CAS 或 Seata 事务边界；
- 默认 dynamic-monthly 继续使用规范化后的 orderNo/month 与写入时间，不启用 Sharding/shadow。

## 6. 验收矩阵

1. 常规创建固定时间覆盖月末纳秒、跨月表归属、订单/明细/outbox/intent 时间字段均为同一毫秒值。
2. 秒杀显式 UTC occurredAt、非 +08 offset、月末纳秒和缺省 occurredAt 均按既有缺省/非法语义处理，写入时间为规范化上海毫秒。
3. orderNo、eventId、Idempotency-Key、requestHash、Stock 调用载荷和状态机保持原值；不修 Stock 候选跨月或旧消息缺 occurredAt。
4. normalize 纯单测覆盖整毫秒、向下截断、年界限、offset 换算和越界错误。
5. 真实 MySQL 使用默认 Order Service/Mapper 验证常规与秒杀写入；核对月表、DATETIME(3) 字段、timeout outbox、intent 和原库 checksum。
6. 与 17B 算法 precise/range 月边界对照；缺表、默认 dynamic-monthly、Pay/Order/Stock 交易回归保持原语义。

## 7. 实施门与范围声明

实现限定在纯时间工具、两条创建路径的最小调用点和相关测试；不新增 API 字段、不改变 orderNo/eventId/idempotency、不迁移 Sharding、不修改 Stock/旧消息恢复/全局 now 辅助方法。本轮完成只表示创建写入与 DATETIME(3) 一致，不表示所有订单时间或跨服务时间问题已闭环。

常规路径固定最小测试接缝：`OrderServiceImpl` 增加私有 `Clock creationClock = Clock.system(ZoneOffset.ofHours(8))` 字段，仅用于创建时刻采样；不注册为 Bean、不外部配置、不改变现有 public 构造器/API。实现使用 `OrderCreationTime.normalize(OffsetDateTime.now(creationClock))`，测试通过既有 `ReflectionTestUtils` 替换为 `Clock.fixed` 固定月末时刻，真实 Service 验收其 orderNo/月表和所有创建时间字段。秒杀显式事件直接 `normalize(occurredAt(event))`，不引入新的 clock 接缝。MySQL 验收必须记录 `DATETIME(3)` 月末 `.999500` 四舍五入风险已由实际 CAST 复现，并证明规范化后不会把常规订单写入错误月份。
