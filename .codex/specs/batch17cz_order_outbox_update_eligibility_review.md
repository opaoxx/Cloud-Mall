# CM-20260924-20：订单 Outbox 更新资格精确评审

## 1. 结论与范围

结论：**GO，仅修复订单 outbox 发布完成与失败记录两个 UPDATE 的执行资格条件。**

扫描和当前 PO 已要求精确 `ORDER_TIMEOUT` 与非负 attempts，但两个 UPDATE 仍可能在扫描后 event_type 或 attempts 改变时改写异常行。本批只补执行时资格条件。

## 2. SQL 条件

保留 CY 的 event_id 候选与无长度精确比较、CX 的 status CAST、CQ 的 attempts 饱和：

- `markOutboxPublished` 追加 `CAST(event_type AS BINARY)=CAST('ORDER_TIMEOUT' AS BINARY)` 与 `attempts>=0`；
- `recordOutboxFailure` 追加 `CAST(event_type AS BINARY)=CAST('ORDER_TIMEOUT' AS BINARY)`，保留既有 `attempts>=0`；
- 方法签名、SET 字段、参数绑定、返回值和原 status/event identity 条件不变。

0 行完成继续 CV 精确 event/status proof；0 行 failure 保持原返回语义。非 0/1 完成计数、SQL 异常、中断和后续候选沿既有 CV/CS/CU 处理。

## 3. 兼容与验收

- 真实隔离 MySQL 覆盖 direct 错 type/负 attempts 两个 UPDATE 0 行且整行不变；合法 0/MAX attempts 的 ORDER_TIMEOUT 正常完成/失败记录。
- callback/confirm 后 type/count 改变时不再误写原身份；后续健康候选可发布。Rabbit 仅替身。
- 不改变 scan/PO/proof 投影、DDL/index、状态、API、DTO、消息、历史坏行或 MQ/SQL 原子性承诺；只保护执行时资格。

