# CM-20260924-20：库存动作首次写入 DATETIME(3) 时间评审

## 1. 评审结论与范围

结论：**GO，限定规范化 `OrderStockActionMapper.insert` 首次写入的 occurredAt。**

BI 已保护 action 身份/必填字段，但当前 occurredAt 仍直接绑定 MySQL `DATETIME(3)`。亚毫秒输入在秒/月末边界可能发生数据库舍入跨界，非 strict 或超出数据库范围的时间还可能产生错误默认/写入行为，导致 action 队列事实不可读。

本轮仅规范 `insert` 的 3 个时间绑定，不修改其他时间字段、状态 CAS、Service/API、schema、lease、attempts、Stock/Feign、恢复器或历史数据。

本规格 supersede BI 关于“不处理 DATETIME(3) precision”的排除项，仅作用于该 Mapper `insert` 的 occurredAt；BI 的身份/类型/id/orderNo 校验和 UUID 误报澄清保持。

## 2. 时间规范化契约

在通过 BI 既有输入门后、执行任何 SQL 前，将 LocalDateTime 按项目固定上海本地语义转换并复用既有 `OrderCreationTime.normalize`：

```java
LocalDateTime normalized =
    OrderCreationTime.normalize(occurredAt.atOffset(ZoneOffset.ofHours(8))).toLocalDateTime();
```

契约：

- 使用 Shanghai `+08:00` 的本地字段解释，不把 DATETIME 当作 UTC instant；
- 向下截断到毫秒，不四舍五入，不跨秒/月；
- 只允许年份 1000..9999；转换/范围异常抛 `IllegalArgumentException`；
- `next_retry_at`、`created_at`、`updated_at` 三个 INSERT 参数全部使用同一 normalized 值；
- 已是整毫秒且年份合法的输入保持原本地值；不改变 orderNo、type、operationKey、status、attempts 或行数。

时间非法时零 SQL、零新行、原行保持；不 trim、归一化订单号或改变其他字段。

## 3. 数据库与边界

- 保留 BI 的 plain INSERT、DuplicateKeyException/连接/表/约束异常传播、唯一键和事务语义。
- 不改 schema、DATETIME(3) 列、SQL 结构、HTTP/API、状态机、恢复/读取逻辑或其他 Mapper 时间字段。
- 不把本轮描述为全项目时间闭环；Order 普通状态、outbox、其他 action/update 方法和 Product 指标另行处理。
- 不修改 UUID 生成器；BI 已核验 JDK17 v4 UUID version 位使 `Math.abs(UUID...MSB)` 的 0/MIN 极值误报不成立，本批不扩展全局唯一性保证。

## 4. 验收矩阵

1. 真实 strict/non-strict MySQL 验证 `.999499/.999500/.999999`、秒边界/月末、年末最大值先 floor 不越界；三列都保存同一毫秒 normalized 值。
2. 最小年份 1000 的合法毫秒可保存；999、0、10000 或其他超范围 LocalDateTime 在 SQL 前抛 IllegalArgumentException，原行/零行保持；不伪造数据库 NULL 时间列。
3. 正常整毫秒输入原样保存，合法 64 code point orderNo、两种 actionType、原 key/status/attempts 语义和 DuplicateKey/SQL 异常回归保持。
4. JDBC/Mapper 单测验证非法时间零交互；回归 BI 身份/type/key、AY/AV/AW/BG 读取、Recovery、Creation 和 transaction 测试，原订单 checksum 与随机 fixture 清理完整。
5. Stock/RPC 替身不宣称真实网络事故；不把本批描述为所有服务/消息/订单时间字段已完成统一。

## 5. 明确不承诺

本轮只规范库存动作首次 INSERT 的 DATETIME(3) 三列绑定，不修其他 action 更新时间、订单/intent/outbox 时间、年月路由、历史数据或全局时间策略。
