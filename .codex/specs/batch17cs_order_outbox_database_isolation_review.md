# CM-20260924-20：订单 Outbox 数据库异常隔离评审

## 1. 结论与范围

结论：**GO，限定订单 outbox dispatcher 对单候选 DataAccessException 的隔离。**

当前 failure 写入或成功后的 `markOutboxPublished` SQL 异常会被普通 catch 当作发送失败再次写 failure，或直接中断后续候选。本批只调整异常边界，不猜测 Rabbit 已发送事实，不改变 SQL、退避、状态或消息政策。

## 2. 异常边界

- `dispatchPending` 扫描 `findPendingOutbox` 仍在候选循环外；扫描 SQL/DataAccessException 继续向外传播，不把无候选扫描故障伪装为单行跳过。
- 候选循环对 `dispatchOne` 捕获 `DataAccessException`，记录告警并继续后续候选；不强制修改不存在的 claim/lease 状态、不重发、不追加写入。
- `dispatchOne` 在普通 `catch(Exception)` 前增加 `catch(DataAccessException)` 并重新抛出：`markOutboxPublished` 成功确认后若 SQL 失败，不再调用 `recordOutboxFailure`；failure writer 的 DataAccessException 由候选层隔离一次。
- 非数据库 Runtime（Rabbit/parse/confirm 等）保持原流程：`markOutboxPublished` 前的异常记录一次 failure 并告警；failure writer 自身的非 DB Runtime 继续向外传播并结束当前调度循环，不由循环层捕获。Error 不捕获。
- 不承诺网络发送与数据库完成写原子；已 confirm 但 published 写失败时不猜测、撤回或补发。

## 3. 兼容与验收

- 真实 MySQL 触发器覆盖首候选 failure 写失败、`markOutboxPublished` 写失败和后续候选正常发布；失败/完成写失败时原持久字段保持，不承诺不存在的 claim 或 lease 行为。
- unit 覆盖 scan DataAccess 传播、候选 DataAccess 隔离、failure writer 不二次写、非 DB Runtime 原有记录和 Error 传播。
- 保留 CQ/CR 的 attempts 饱和/快照 guard、Unicode clipping、状态/identity/payload/created/published/退避、Rabbit confirm/returned 语义；不扩中断、全局 DB 停机或读后竞态。

## 4. 数据库/API 语义

`order_outbox` DDL、SQL 条件、状态和索引不变；本批仅定义 dispatcher 对单候选数据库异常的处理边界，不新增 API、DTO、消息字段或重试策略。
