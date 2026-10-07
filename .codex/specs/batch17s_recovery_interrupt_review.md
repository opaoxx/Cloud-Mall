# CM-20261007-20：秒杀恢复中断语义评审

## 1. 评审结论与范围

结论：**GO，限定修复 `SeckillMessageRecovery` 恢复线程中断的可观察 checkpoint 和 interrupt flag 保留；不改变状态机、lease、Rabbit 拓扑或 ACK/NACK 调度契约。**

当前 replayDue 与 DLQ 延迟重投都使用 `Future.get(5000)`，InterruptedException 被通用 Exception 捕获后线程中断标志被清除；replayDue 可能继续标记 REPLAYED/处理后续记录，DLQ 分支也可能在中断状态丢失后继续执行确认路径。

## 2. replayDue 中断边界

- replayDue 入口在扫描前检查 `Thread.currentThread().isInterrupted()`；已中断立即 return，不查询/claim任何记录。
- 每条候选 claim 前再次检查中断；已中断立即 return，不启动该条 claim，也不触碰后续行。
- publisher confirm `Future.get` 捕获 InterruptedException 时必须先 `Thread.currentThread().interrupt()`，再结束当前条处理；不调用 `markReplayed`，不把发送结果未知推断为成功/失败，不处理后续候选。
- 当前 lease 由既有过期机制自然回收；不强行清理、不新增状态、不回滚已发送消息。
- 非中断异常继续沿既有退避/完成写路径；17J/17K lease/state CAS 保持不变。

## 3. DLQ 延迟重投中断边界

`republishForDelayedRetry` 等待 confirm 被 InterruptedException 打断时必须向既有 retryFailure 分支返回；不新增 ACK、重复 publish 或改变 17R 分支结构。既有一次 NACK/requeue 规则继续适用，并在该既有 NACK 分支的 finally 中恢复线程中断标志——必须在 NACK 尝试之后恢复，NACK 成功和 NACK 抛错都不能丢失 interrupt：

- 中断后最多一次 NACK(requeue=true)；
- NACK 成功后结束当前处理；
- NACK 失败抛异常，保留原 retryFailure cause，并将 nackFailure suppressed；
- 无论 NACK 成功/失败，NACK 尝试结束后的 finally 必须恢复线程 interrupt flag；不能先恢复 flag 再尝试网络 NACK；
- 不能在中断标志下追加 ACK 或再次发布。

已由 Rabbit 发送出去的消息无法撤回；中断处理只保证本地 checkpoint、lease 和后续重试可观察，不宣称 broker exactly-once 或全链路取消。

## 4. 不变边界

- 17R 的持久失败/重投/ACK/NACK 分支保持不变；ACK 异常不重新进入 persist/retry。
- 不新增状态、表、API、Rabbit exchange/queue、lease 续期或补偿 RPC。
- 不把中断当作业务冲突、CONFLICT、RESOLVED 或 REPLAYED；不改 eventId、payload、hash。
- 17J 完成写和 17K 发送前 lease guard 继续裁决迟到 worker；中断本轮不扩大为分布式原子取消。

## 5. 验收矩阵

1. replayDue 入口预置中断：不查询、不 claim、不改数据库。
2. 第一条处理后在下一条 claim 前中断：第一条状态按既有结果保留，后续不 claim。
3. publish confirm 等待中断：恢复 interrupt flag，不 markReplayed，不处理第二条，原 lease 可自然到期。
4. DLQ 重投 confirm 等待中断：只走一次 NACK/requeue，NACK 成功/失败均恢复 interrupt flag；失败异常保留 cause/suppressed。
5. 正常非中断 ACK、NACK、重投、JSON 幂等和 MySQL 持久化回归保持通过。
6. 真实 MySQL 验证无 claim/DB 变化、首条发送结果未知、后续行未处理；Channel/Rabbit 为替身时不宣称真实 broker 网络撤回能力。

## 6. 实施门与范围声明

实现限定在中断检查、Future 等待异常分支和 JavaDoc/测试；不改变业务状态机、lease schema、消息格式、ACK/NACK 拓扑或历史事实。本轮只保证可观察 checkpoint 和 interrupt flag 正确保留。
