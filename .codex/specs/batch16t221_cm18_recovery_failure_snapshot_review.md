# CM18 16T221：Pay TCC recovery failure snapshot 规格

## 1. 评审结论与范围

本批 **GO**。`sendDecision` 的 TC 回包丢失 catch 仍按 payNo 调用旧 observe，窗口内 execution/Pay 被替换时可能把旧诊断写入新行；private observe 的 BizException catch 直接对 nullable code 调用 `contains`，NULL code 会 NPE。本批复用 T218/219 snapshot+Pay source 门与 T220 conflict helper，只修两条恢复失败路径。

## 2. 两处最小修正

### 2.1 `sendDecision`

TC commit/rollback 抛异常时改为 `executions.observeSnapshot(execution, "UNKNOWN", exceptionClass)`，使用发送前 execution snapshot，保留 T218/219 的 Pay→execution 锁序、source/anchor 比较、merge、截断与 SQL 语义。snapshot conflict 原样传播：recover 直接结束，execute 外层 T220 传播并 finally 清理 RootContext；已发 TC 不撤回，不保护 observe→TC 全窗口。

### 2.2 private `observe`

BizException catch 复用 T220 `rethrowConflict(failure)`，安全处理 `code == null`：含 CONFLICT 原对象传播，其它 BizException 继续 `observeSnapshot(execution, "UNKNOWN", failure.code)`；普通 Runtime 仍沿原 UNKNOWN snapshot。不得按 HTTP 409 泛化，不改变 NULL/OTHER/lowercase code 诊断语义。

不改 Store/Mapper/SQL/schema、决议算法、API、sendDecision 其它分支、TC 调用、历史数据或其它 Coordinator 入口。

## 3. 验收矩阵

### 3.1 Owned native

- 使用 actual Coordinator、Store/Mapper、owned execution/Pay/Intent 三表与本地 REQUIRES_NEW proxy；TC/Bridge/Evidence/receipt 为 mock，不启动 App、TC/RM、新监听。
- recovery `execute/recover × COMMIT/ROLLBACK × 5 anchor drift（id/xid/user/order/amount）+3 Pay-only drift（mode/order/amount）=32` 组合：peer 通过独立工作线程/TT 在故障窗口提交变化，snapshot conflict、三表保持。Pay/user/order/amount peer 变化通过 PayNo 对齐 Pay；execution id 为随机值，不以固定 id 假设匹配。已发 TC 调用计数如实记录且非零，不伪称为零。
- healthy `execute/recover × COMMIT/ROLLBACK × UNCHANGED/COMMITTED/ROLLEDBACK/CONFLICT=16` 控制保持；terminal mock TC callback 的同线程 JDBC autocommit 注入仅验证缓存返回，不是真实 TC 证明。sendDecision TC failure 后使用旧 execution snapshot；Bridge BizException code null 不 NPE，沿 UNKNOWN snapshot；NULL code 场景仅 NONE 方向保 UNKNOWN/SQL NULL 且 immutable/recovery/Pay/Intent 不变。
- native 仅证明本地 snapshot/Pay source 保护与异常分类，不证明真实 TC/外部资金/完整 fencing；peer 交错不是生产并发证明。

### 3.2 Unit

- actual Coordinator 配 mock Store/TC/Bridge/Evidence，断言诊断使用原 chosen execution 对象；两入口×双方向×诊断接受/拒绝=8，冲突终止末次 read/observe/execute 写回，finally 清理 RootContext。
- 覆盖三决议×NULL/OTHER/lower/conflict=12 business 控制，TC Runtime/BizNULL/TC_CONFLICT=3与 BridgeRuntime/EvidenceConflict/缓存终态=3 控制，旧决议/未知流程保持。

## 4. 明确边界

本批只保护 recovery failure 的 snapshot 写回与 nullable conflict 分类，不撤回已发 TC，不改变 chosen 决议、claim/bind、private observe 其它调用、SQL/API、读→RPC/TC窗口或完整 source/资金因果。专项未完成前不得宣称闭环。
