# CM-20261009-17DF：订单 Outbox 失败退避写结果评审

## 1. 结论与范围

结论：**GO，仅处理 `recordOutboxFailure` 返回值，禁止把失败写 0 行误报为已记录重试。**

当前 dispatcher 在发送、解析或 confirm 失败后调用 `recordOutboxFailure`，但忽略其 `int` 返回值；当当前行已被 peer 标记为 `PUBLISHED`、资格变化或事件不存在时，SQL 真实影响 0 行，日志仍写“将重试”，形成虚假的退避证据。本批只分类这一次既有 failure writer 的结果，不查询、不补写、不重发、不修复事实。

## 2. Failure writer 结果契约

保留原发送异常、错误裁剪、上海 `nextRetryAt` 计算和一次 `recordOutboxFailure(eventId,error,nextRetryAt)` 调用。调用返回后立即检查线程中断；已提交的数据库写不因中断撤回：

- 返回 `1`：只记录精确表示“失败退避已记录”的告警，同时保留原 error 摘要和 `nextRetryAt`；不得宣称消息已完成或 MQ/DB 原子成功。
- 返回 `0`：只记录精确表示“失败退避更新 0 行，本次未确认记录退避，保留当前事实”的告警；0 行可能来自并发状态变化、缺行，或特定 affected-rows 语义下匹配但 SET 无变化，本批不查询、不推断。不得声称 retry 已记录、peer 已完成或当前事实属于哪一种状态。不得追加 `SELECT`、第二次 `UPDATE`、failure 二写、重发、proof 查询或事实修复。
- 返回非 `0/1`：抛 `OptimisticLockingFailureException`（`DataAccessException`），异常必须携带原始发送/解析异常作为 cause；沿 CS 单候选数据库并发异常隔离，告警并继续后续候选，不再次写 failure。

failure writer 自身抛出的 `DataAccessException`、其他 RuntimeException、`Error` 及其 cause 中的明确线程中断继续沿原 CS/CU/错误通道处理：数据库异常不转成第二次 failure 写，RuntimeException 不被伪装为已记录，Error 不捕获；中断恢复标志并停止后续动作。返回 `1` 只表示本次原 SQL 的影响行数为一，不是 full-row 证明，也不承诺未来调度；返回 `0` 不证明完整事实，任何结果都不保证未来一定重试、完成或实现 MQ/SQL 原子性。本条不扩展全局 JDBC affected-rows 模式支持。

## 3. 不变量与兼容边界

- `recordOutboxFailure` SQL、饱和 attempts、eventId/type/status/attempts eligibility、错误剪裁、退避间隔和数据库列不变。
- `markOutboxPublished`、17DD publication facts、17DE 到期 DLX/TTL 路由、confirm/returned、payload/envelope、CorrelationData、扫描和消息字段不变。
- 失败 writer 返回 0 不启动新的事实读取；不能把它当作 proof 失败或 peer 成功来改写原行。
- 失败 writer 返回非 0/1 只保护并发计数异常；不推断消息是否进入 Rabbit、不追加补偿状态、不生成新 eventId。

## 4. 验收矩阵

- 真实 MySQL 隔离库：Rabbit 使用发送失败替身；发送回调先通过真实 SQL 将同一事件标记 `PUBLISHED`，随后替身抛出原发送异常，failure UPDATE 返回 0。核对整行保持 peer 的 `PUBLISHED` 事实、只发送一次、无 proof 查询、日志不声称 retry 已记录；之后独立健康 `PENDING` 事件可正常发布并完成。
- unit：覆盖 writer 返回 `0`、`1`、`-1`、`2`，以及 writer 返回后线程中断；验证 `0` 不查事实、不二写、不重发，`1` 仅记录已写入一行，非 `0/1` 为带原异常 cause 的 Optimistic(DataAccess)，中断不新增写。
- 回归 writer SQL 异常、RuntimeException、Error 和 cause interruption，确认既有隔离/传播边界不变；不通过日志文本推断数据库完整状态。

## 5. 交付边界

本评审只冻结规格，backend 按本文件及 API/DB 相邻条款实现和验收。本轮不修改业务代码、测试、Schema、API、消息或 Maven 配置。
