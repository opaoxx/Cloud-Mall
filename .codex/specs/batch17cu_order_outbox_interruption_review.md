# CM-20260924-20：订单 Outbox 中断边界评审

## 1. 结论与范围

结论：**GO，仅为订单超时 outbox dispatcher 增加可观察中断检查点。**

当前 `Future.get` 的 `InterruptedException` 进入普通 broker failure 路径，可能写 failure、清除中断标志并继续候选。本批保留已发送/已提交事实，不承诺撤回消息或全链路原子取消。

## 2. 检查点与停止语义

在入口、scan 返回、每候选前、`dispatchOne` 入口、parse/构建完成送出前、send 返回及 `Future.get` 前、get 返回/confirmed 后且 `markOutboxPublished` 前、异常检查和 failure writer 前检查线程中断：

- 在任一检查点发现中断后恢复标志并 return，阻止尚未开始的 parse、Rabbit、failure/complete 写和后续候选；已经开始的异步发送/数据库操作不能被该检查点撤回或原子取消；
- `Future.get` 的 InterruptedException 单独恢复标志并 return，不把发送结果猜为失败；
- private `interrupted(Throwable)` 识别当前 flag 或 cause 链中的明确 InterruptedException，使用 IdentitySet 防循环，不扫描 suppressed；SocketTimeoutException/普通 InterruptedIOException 不自动视为取消；
- scan Runtime 若明确中断则停并保标志，其余按原传播；候选 DataAccess 中断停并保标志，其余沿 CS 隔离；Error 不捕获。

发送或 SQL 已发生不能撤回；不猜测 PUBLISHED，不追加本候选 failure/complete，不改变 SQL、Schema、50、10 秒退避、confirm timeout、Rabbit、DTO 或历史事实。

## 3. 异常兼容与验收

- failure writer Runtime 仅在明确中断时恢复标志并停止，否则按既有传播；非 DB 普通 timeout 保持原 failure/后续候选语义。
- 真实 MySQL + 可控 Rabbit future 验证发送首行后中断只保留原行、后行不发送；入口/scan/parse/confirm/完成前/failure 前中断均停止未来动作。
- unit 覆盖包装 InterruptedException、cause 循环、普通 timeout、DataAccess/Error 和原正常路径；不宣称网络线程可取消或已发消息可撤回。

## 4. 数据库/API 应用语义

本批不改变 outbox DDL、状态、attempts、next_retry、SQL 或公开 API；只规定 dispatcher 的线程中断控制流和可观察 checkpoint。
