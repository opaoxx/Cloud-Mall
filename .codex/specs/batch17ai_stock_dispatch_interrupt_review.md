# CM-20261007-20：Stock dispatcher 中断边界评审

## 1. 评审结论与范围

结论：**GO，限定修复 Stock 秒杀 marker/outbox dispatcher 的线程中断 checkpoint；不改变状态、lease、cursor、attempts、Rabbit 拓扑或业务 API。**

当前 Future.get 中断被 generic Exception 吞掉 interrupt flag，dispatcher 可能继续处理后续 marker/outbox；marker 与 outbox 阶段也缺少一致的中断检查。

## 2. 中断 checkpoint

`dispatch` 必须在以下位置检查 `Thread.currentThread().isInterrupted()`，命中立即 return：

- dispatch 入口；
- marker 阶段结束、outbox 扫描开始前；
- 每个 marker 候选 GET 前与 GET 返回后；
- `markSeckillUnknown` 返回后；
- accept 异常 catch 中调用 alreadyCommitted 查询前；
- 每个 outbox claim 前；
- dispatchOne 入口；
- ownsLease 查询前与查询返回后；
- publisher confirm 成功后、markPublished 前。

Future.get 捕获 InterruptedException 必须恢复 interrupt flag、记录 WARN 并 return：

- 不 recordFailure；
- 不 markPublished/markUnknown/accept；
- 不继续下一条候选；
- 不清理当前 lease、不回退已推进 cursor/claim attempts。

不得遍历异常 cause 猜测中断；只按明确 InterruptedException 和当前线程 flag 处理。

## 3. 状态与在途边界

- 已提交的 marker UNKNOWN/accept、outbox claim 和 lease 保持数据库事实；中断不回滚、不猜测；
- confirm 尚未返回时发送结果未知，保留原 lease/状态，后续依赖 lease、eventId/hash 和 outbox CAS；
- confirm 已成功但 markPublished 前中断，不写完成，已发消息不可撤回；
- guard 查询慢返回期间若设置中断，返回后不得发送/完成；
- 正常无中断路径完全沿用原 marker/outbox 逻辑。

## 4. 验收矩阵

1. 入口预中断：零 marker/outbox 查询、零 claim/写入。
2. marker GET 前/后中断：不写 UNKNOWN、不 accept、不进入后续 outbox 阶段。
3. marker 写成功后中断：保留已提交事实和 cursor，不处理下一条。
4. accept 失败路径中断：不进行 alreadyCommitted 猜测查询，不继续后续候选。
5. outbox claim 后中断：lease/attempts 保留，不发送；后续到期可恢复。
6. confirm 等待中断或 confirm 返回后 markPublished 前中断：不 recordFailure/markPublished，线程 flag 保留。
7. ownsLease 慢查询返回期间中断：不发送；无中断时正常放行。
8. 真 MySQL + Redis/Rabbit 替身验证零扫描、零错误写、lease/cursor/attempts 保持；测试 finally 清理线程中断标志。

## 5. 明确排除

本轮不新增状态/API/schema，不续租、不回滚已提交操作、不保证 DB/Redis/RPC 全链路取消或在途消息撤回，不改变调度批次/游标公平和 Rabbit 网络语义。
