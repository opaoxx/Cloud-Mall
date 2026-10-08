# CM-20260924-20：秒杀 dedup 提交结果评审

## 1. 结论与范围

结论：**GO，仅要求首次秒杀落单的 dedup `markCommitted` 返回值严格为 1。**

当前订单、明细和 outbox 已写入后忽略 `markCommitted` 结果；若源状态不是 `PROCESSING`，更新可能返回 0，但消费仍正常返回并继续 `markResolved`，形成本地订单事实与 dedup 状态不一致。本批只增加结果门，不改变 Mapper SQL、DDL、DTO、消息、状态枚举或其他业务链路。

## 2. 首次消费契约

首次落单在同一事务中完成订单、明细、outbox 后调用 `seckillOrderDedupMapper.markCommitted(userId,key,now)`：

- 返回值必须严格等于 1，才允许继续现有 `markResolved` 和正常消费完成；
- 返回非 1 的整数（0、负数或其他计数）抛本批受控 `IllegalStateException`；Mapper 既有 Runtime/SQL 异常继续由现有 outer catch 包装并保留原 cause；Error 保持现有未捕获、不包装语义。所有失败均不得调用 `markResolved`；
- 由于调用处仍在本地事务内，订单/明细/outbox 和本次 claim 一并回滚；既有外部/已提交 held 记录不被猜测或改写；
- 只保护首次 claim 的条件 UPDATE 受影响行数恰为 1，不替代已有身份、hash、Product、价格、时间和状态核验；不新增二次查询或承诺验证触发器改写后的全部状态。

## 3. 兼容边界

- claim 返回 0 且已有 `COMMITTED` 的重复消息仍在 Product 前按原路径收敛，不重复 Product 或本次提交。
- claim=0 的空/合法缺 recovery 等既有语义保持；本批不修改 Mapper 条件、状态机、历史记录或 recovery worker。
- 成功返回 1 的路径、`markResolved` 参数、消息 ACK/retry 分类和普通 Order 均保持。
- 真实 SQL 触发器故障注入只用于证明 0 结果防护，不宣称真实竞态；unit 覆盖 0/负/异常/2、SQL/Runtime/Error cause 和不 Resolve。

## 4. 数据库/API 语义

`seckill_order_dedup.status` 继续为 `PROCESSING/COMMITTED`，DDL、索引和 Mapper SQL 不变；应用把当前源条件/SQL 语义下 `markCommitted` 的 1 行更新作为事务完成门。无新增公开 API、消息字段、DTO、表或状态。
