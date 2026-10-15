# CM18 16T224：Pay TCC decision send snapshot 规格

## 1. 评审结论与范围

本批 **GO**。T223 保护了决议提交，但 `sendDecision(chosen/current)` 在本地决议事务提交后直接按旧 PO 调 TC；peer 可在此期间替换 id、Pay source 或 decision，旧线程仍先发错误 TC，再在末次观察才冲突。本批只增加 TC 调用前的本地 decision snapshot preflight。

## 2. Decision preflight

新增 REQUIRES_NEW `verifyDecisionSnapshot(expected)`，复用 T223/T218/T219 的 `lockedSnapshot` Pay→execution/域/八字段/source 门，再精确要求 `current.decision == expected.decision`，返回锁内 current。

`sendDecision` 在任何 TC try/catch 之前调用 gate；冲突直接传播，execute outer/finally 和 recover 原异常路径保持。之后按 current state 做原 terminal early return：相同身份/方向的最新 terminal 不再 RPC；NONE 仍 no-op。非 terminal COMMIT/ROLLBACK 使用 expected 的 XID/方向调用 TC，RPC 异常继续用原 expected snapshot 写诊断。Gate 不在本地锁中发 RPC，避免回调锁 Pay 死锁；mutable state 不作为版本条件，不新增状态组合。

不改 TC client、SQL/schema、外部 API、旧 Store 接口、决议算法或其它 observation；不宣称 preflight→RPC 完整 fencing。

## 3. 验收矩阵

### 3.1 Owned native

- 使用 actual Coordinator/Store/Mapper 与 Spring 本地代理；TC/Bridge/Evidence/receipt 为 mock，不启动 App、TC/RM、新监听。
- execute/recover × COMMIT/ROLLBACK × 8 anchor/source drift：peer 在 decideOwned 或 observeSnapshot 事务提交后、Coordinator 返回前修改 execution/Pay；修复后 gate 409、无 VO/后续 decision-observation-readback，peer 三表 facts 保持，TC 仅此前 begin 或0。
- 覆盖反向 decision、清空 decision、same identity 最新进度、terminal、NONE no-op、NULL/SQL门；相同方向合法最新 terminal 不 RPC，非 terminal 仅当前方向调用。
- 使用外层 test interceptor 注入独立 peer 事务，不在本地锁内发 RPC；本地交错不证明真实 TC/远端并发或完整 fencing。

### 3.2 Unit

- Coordinator/Store/TC mock 覆盖 gate identity/source/decision/terminal/NULL/SQL0 与异常传播，保 T220/T221 矩阵；冲突前不调用 TC/observe/readback，finally unbind。
- 保留 RPC exception 的原 snapshot 诊断、NONE no-op、反向/同向/terminal 方向语义；不把 mock 当真实 TC。

## 4. 明确边界

本批不撤回 begin/Try/已发 RPC，不保护 gate 通过后的 preflight→TC 窗口，不改变 sendDecision/observeSnapshot 之外的状态机、SQL、API、历史数据、Pay source 全因果或 Seata fencing。专项未完成前不得宣称闭环。
