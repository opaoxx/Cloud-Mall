# CM18 16T223：Pay TCC owned execution decision 规格

## 1. 评审结论与范围

本批 **GO**。T222 已让 execute 持有 claim/bind snapshot，但成功路径仍按 payNo 调用 `decide`；peer 在 Try 成功返回前替换 execution id 或 Pay source 后，旧线程仍可能 setDecision/发 TC。本批只为 owned execute 增加 snapshot decision 门。

## 2. 最小 owned decide

新增 REQUIRES_NEW `decideOwned(expected,xid,decision)`：

1. 复用 `lockedSnapshot(expected)` 的 Pay→execution 锁序、persisted domain、Pay source、八字段 anchor；任何漂移 409，先于方向/终态/SQL；
2. 复用旧 `decide` 的输入 XID/decision 域、相反方向冲突、终态幂等和 `setDecision` affected-row 内核；
3. setDecision 后锁内读回原 payNo，NULL readback 抛 conflict 并回滚本地事务；
4. 返回原决议 PO。

旧 `decide(payNo,xid,decision)` 保留原锁序、优先级、内核与 readback行为，允许其它历史调用；execute 只将 owned PO 路径改为 `decideOwned(owned,xid,decision)`，不得重新 read 获取 owner。SQL/schema/DTO/external API/sendDecision/recover 不改。

## 3. 验收矩阵

### 3.1 Owned native

- 使用实际 Coordinator/Store/Mapper、owned Pay/Intent/Execution 表和 Spring ProxyFactory + TransactionInterceptor/AnnotationTx 本地事务；TC/receipt/Bridge/Evidence 为 mock，不启动 App、TC/RM、新监听。
- callback 成功返回前 peer 修改 execution id 或 Pay-only amount/source，旧代码会 setDecision/继续 TC；修复后 409、无 VO/setDecision/observation/readback，peer 三表事实保持，已 begin 计数保留且不发 commit/rollback。
- COMMIT/ROLLBACK 成功分支 × 8 anchor/source drift、合法同向/终态/相反方向、NULL readback 回滚控制；原快照不变，setDecision 只在合法 anchor 上执行一次。
- 本地事务/peer 交错不证明真实 TC、外部资金、已发 Try 可撤回或完整 read→decision→TC fencing。

### 3.2 Unit

- 适配 T222 旧编排 mock，保留 claim/bind/observeSnapshot 冲突矩阵；覆盖 owned decision 的 input/方向/终态/SQL0/readback null 与原异常优先。
- 断言 drift 在 setDecision 前拒绝，不重新读 owner；合法同向/终态幂等、相反方向 conflict 与旧 decide 行为保持。Unit mock 不冒称真实事务/TC。

## 4. 明确边界

本批不改旧 decide、SQL、schema、外部 API、sendDecision/recover、TC/RPC、历史数据或完整状态机；只保护 execute 持有的原 execution snapshot 不被 peer 载荷/source 漂移覆盖。已发 Try、已 begin、决议提交后 TC 窗口仍由后续范围处理。
