# CM-20261007-20：秒杀恢复数据库失败隔离评审

## 1. 评审结论与范围

结论：**GO，限定隔离 `replayDue` 单条候选的数据库写失败和避免重复完成写；不改变表、状态、批量上限、退避、Rabbit 拓扑或全局数据库故障语义。**

当前问题：候选处理处于无保护循环中，单条 claim/验真/完成写的 DataAccessException 可能中断后续候选；发送 try 同时包住 markReplayed，完成写失败会被误判为发送失败并再次完成；verifyStockFact 的 markVerification 失败被外层 catch 再次写，可能重复更新或遮蔽首个数据库错误。

## 2. 候选级异常边界

- `findDue` 扫描查询失败属于扫描级失败，继续向外传播并结束本轮；不伪造候选、不吞数据库不可用。
- 逐条候选的 claim、认领后快照、lease guard、Stock 验真和本地状态写 DataAccessException 只影响当前行：记录 warn 后 continue，不处理后续发送/完成，不强行释放 lease、不猜状态；原 lease/事实由数据库和后续到期 claim 收敛。
- 只捕获明确的 Spring DataAccessException；不捕获 Error 或任意 RuntimeException 当作数据库故障。中断 17S 仍按 interrupt 规则立即停止本轮。

## 3. 发送与完成写分离

每条候选固定分段：

```text
send/confirm try → 保存发送结果/错误 → try 外最多一次 markReplayed
```

发送/confirm 异常只记录发送失败结果；随后最多调用一次 `markReplayed` 安排原退避。`markReplayed` 的 DataAccessException 直接传播/记录为当前候选失败，不再次调用 markReplayed，不把完成写错误当作第二次发送，不继续该候选的其他写操作。17J/17L 的 lease、状态和 attempts CAS 继续最终裁决。

## 4. verifyStockFact 写失败

`verifyStockFact` 中的 `markVerification` DataAccessException 必须直接向候选处理层传播，由候选级 DataAccessException 隔离；外层不能再次调用 `markVerification` 写 VERIFYING，避免重复写和掩盖首个数据库错误。非数据库业务/解析异常仍沿既有目标状态和错误语义处理，不扩大捕获范围。

## 5. 不变边界

- 50 条候选上限、claim/lease 规则、退避算法、状态枚举、payload/eventId/hash、Rabbit 发送和 17K/17S 中断语义保持不变；
- 数据库全局不可用时不保证后续候选成功，只保证单条异常不无故中断同批可独立处理的其他行；
- 不新增表、字段、状态、轮次、重试主题或外部 API；不强制清理失败 row 的 lease。

## 6. 验收矩阵

1. 真实 MySQL 对 id1 的 claim/REPLAYED/verification 状态更新注入触发器失败，id1 原字段/lease 保持，id2 仍可继续处理。
2. 发送成功但 markReplayed 失败：只调用一次 markReplayed，不二次发送/完成，不影响后续候选。
3. verifyStockFact markVerification 失败：只发生一次写尝试，首个 DataAccessException 向上隔离，不重复写 VERIFYING。
4. 扫描 SQL 失败仍结束本轮并传播；逐条 DataAccessException 只跳过当前行。
5. 中断、业务 RuntimeException、Error 不被错误归类为数据库故障；17J/K/L lease、attempts 和原 Rabbit/ACK 回归保持。
6. 原库 checksum、状态、payload、eventId/hash 和 50 条候选上限不变；不把该批验收表述为数据库全局故障下的自动成功。

## 7. 实施门与范围声明

实现限定在 replayDue 候选循环、send/confirm 与完成写分段、verifyStockFact 异常传播；不改变数据库 schema、业务状态机、全局重试策略或网络原子性边界。
