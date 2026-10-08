# CM-20260924-20：订单 Outbox 可处理候选扫描评审

## 1. 结论与范围

结论：**GO，仅在 `OrderOutboxMapper.findPendingOutbox` 的 SQL eligibility 过滤中排除负 attempts。**

CR 已保护 dispatcher 快照，但原扫描按 next_retry_at/id LIMIT 50，最早负 attempts 行可长期占满窗口，使合法行饥饿。本批把 `attempts>=0` 放在 LIMIT 前，避免坏计数占据候选窗口。

## 2. SQL 契约

保留现有：

- `status='PENDING'`；
- Shanghai 当前时间的 `next_retry_at<=now`；
- `ORDER BY next_retry_at,id`；
- 投影字段、参数语义和 `LIMIT Math.max(1, limit)`。

仅增加 `AND attempts>=0`，并确保过滤发生在 `LIMIT` 前。0、MAX-1、MAX 均可扫描；-1/INT_MIN 不被返回、不更新、不删除、不归零。

扫描 SQL 错误继续传播；未来候选、非 PENDING、未到 retry 时间仍按原规则排除。CR dispatcher 快照 guard 保留，继续保护当前已读 PO 的 raw seam/当前计数门；不承诺数据库读取后的并发变化保护。

## 3. 兼容与验收

- 真实 MySQL：50 个最早负 attempts 后的 0/MAX 有效行能直接返回并发布；坏行完整字段保持；混合未来/non-PENDING/时间相同按原 `next_retry_at,id` 顺序；limit 0/负数归一为 1。
- 验证扫描错误传播、CQ/CR/CS 回归；不扩展 payload、状态、lease、并发读后变化、一般 DB 故障或历史坏行修复。
- 不新增索引、参数、DTO、API、消息或 DDL；负值只被 eligibility 过滤，不自动修复。

## 4. 数据库/API 应用语义

`order_outbox.attempts INT NOT NULL DEFAULT 0` 和既有索引保持；本批只规定扫描层在 LIMIT 前排除负值，不能描述为全局异常数据清理或所有坏字段饥饿问题的通用解决。
