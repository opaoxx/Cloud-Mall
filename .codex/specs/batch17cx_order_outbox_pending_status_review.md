# CM-20260924-20：订单 Outbox PENDING 状态精确评审

## 1. 结论与范围

结论：**GO，仅修复订单 outbox 三个 SQL 的 PENDING 状态比较精度。**

现有 status 列排序规则可能把 `pending`、`PENDING ` 等别名当作 `PENDING`，使扫描、发布完成或失败写改写 timeout 事件；本批使用 `CAST(... AS BINARY)` 精确状态条件，并在当前 PO 层再次 guard。

## 2. SQL 与当前快照门

以下三个 SQL 的原 `status='PENDING'` 改为 `CAST(status AS BINARY)=CAST('PENDING' AS BINARY)`：

- `findPendingOutbox`：过滤必须位于 LIMIT 前，保留 CW 的 event_type、CQ attempts、due、排序、limit、投影；
- `markOutboxPublished`：仅精确 PENDING 可更新，0 行仍按 CV publication proof；
- `recordOutboxFailure`：仅精确 PENDING 可更新，0 行沿既有返回语义。

`dispatchOne` 在 attempts 和 event_type guard 后、parse 前要求 `"PENDING".equals(outbox.status)`：null、空格、大小写、其他状态 warn+return，不 parse、不发送、不完成、不失败写，后项继续。该 guard 只保护当前 PO，不查数据库新状态。

保留 CQ–CW、CU–CV 的中断、attempts、event_type、publication proof、DB 隔离、due/order/limit 和消息语义；不修历史坏状态，不承诺 MQ/SQL 原子性或读后并发保护。

## 3. 验收与数据库/API 语义

- 真实 MySQL 隔离测试覆盖 status 别名占据最早 50 窗口、合法 PENDING 发布、直接完成/失败写的精确条件、confirm 后状态变化；Rabbit 仅替身。
- unit 覆盖 null/别名/其他状态和合法 PO；错误状态全字段保持，后续合法候选继续。
- `order_outbox.status VARCHAR(32)` DDL、索引、状态枚举、API/DTO/消息不变；该 CAST 语法避免 MySQL 8.0 的 BINARY 表达式弃用警告，不新增 schema 或状态。CW 的 event_type BINARY warning 边界不在本批扩大。
