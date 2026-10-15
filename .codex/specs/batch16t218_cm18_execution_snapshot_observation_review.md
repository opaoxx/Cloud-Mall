# CM18 16T218：Pay TCC execution snapshot observation 规格

## 1. 评审结论与范围

本批 **GO**。Coordinator 在 Bridge/Evidence 期间读取 execution 后，当前 `observe(payNo,state,error)` 只按 payNo 锁当前行；若行被替换为新 XID/user/order/amount/id，旧证据仍可能写入新 execution。范围限定为 read 关联的 snapshot observation，不扩展全流程 fencing 或远端事实。

## 2. Snapshot observe API

在 ExecutionStore 增加内部 public 语义方法 `observeSnapshot(PayTccExecutionPO expected, String state, String error)`，`expected == null` 沿既有 conflict。方法保持 `REQUIRES_NEW`、现有 execution `FOR UPDATE` 锁序（不新增 Pay 锁）、error 截断、state/decision/terminal merge 与原 SQL affected-row 语义：

1. 使用 `expected.payNo` 锁当前行；缺行沿既有 conflict；
2. 先对当前行复用既有 `validatePersistedExecution`，再对 expected 做同域验证；
3. 精确比较不可变 snapshot：`id/payNo/xid/userId/orderNo/amount/operationKey/ledgerKey`。字符串/Long 用 `Objects.equals`，金额两端非 null 且 `compareTo==0`；任一漂移 409，早于 terminal early return、state merge 或 SQL；
4. 不比较 state、decision、branch/lease、诊断、recovery attempts/times 等可变进度；anchor 相同后复用原 `mergeObservation`。

保留旧 `observe(String payNo,...)`，供非 snapshot 关联的 UNKNOWN/命令诊断使用；两者共享私有 merge kernel。无新增 SQL/schema/锁类型/JSON API。

## 3. Coordinator 使用范围

仅将私有 read 关联 `observe` 的四条写回路径改用 snapshot：

- NULL prebegin 的 UNKNOWN；
- Bridge/Evidence 正常返回的 state；
- BizException 非 CONFLICT 的 UNKNOWN；
- Runtime/unavailable 的 UNKNOWN。

`execute` 的 tryFailure、`sendDecision`、TC commit/rollback 诊断及其它旧 observe 调用保持。cached terminal 仍由 T217 read/source 域门保护；不撤回已发 Bridge/TC，不保证 Pay 后续漂移或读→RPC fencing。

## 4. 验收矩阵

### 4.1 Owned native

- 使用真实 Store/Mapper/Coordinator 与 owned execution/Pay/Intent 三表；Bridge/Evidence/TC 为 mock，使用 Spring ProxyFactory + AnnotationTransactionAttributeSource 验证实际事务传播，不启动业务 App、TC/RM、新监听。
- 两条 red：Bridge answer 在返回旧结论/抛 503 前，由另一 owned JDBC 事务替换并提交 execution；旧实现按 payNo 写新行。修复后 id/xid/user/order/amount/op/ledger 任一漂移均 409，三表 snapshot 不被旧结果改写。
- 覆盖5类合法 anchor 漂移（id、xid、userId、orderNo、amount）× COMMITTED/ROLLEDBACK/UNKNOWN 三种 incoming 结果=15组；PayNo/operationKey/ledgerKey 漂移由原 persisted 域门优先拒绝，不计作 snapshot 独立证据。另覆盖合法 snapshot 的 incoming state/error、当前 mutable state/decision/diagnostic/recovery 进度变化；保持原 merge、terminal 防倒退、SQL affected=1/0、错误截断和 readback。
- literal expected null、缺行/异常 PO 与 SQL0 等由 Unit seam 覆盖；native 覆盖 peer 替换与提交后的 anchor 变化。本地有界工作线程事务交错不证明真实 TC/远端并发或完整 TOCTOU fencing、远端 User 或历史 repair。

### 4.2 Unit

- 覆盖 NULL expected、缺行、5类合法 anchor drift（id/xid/userId/orderNo/amount）、金额数值等价、incoming state/error、当前 mutable 进度与旧 merge kernel；PayNo/operationKey/ledgerKey 坏值由原域门优先，snapshot mismatch 无 update/observe 后续调用。
- 保持旧 `observe(payNo,...)` 非 snapshot 诊断行为与其它 Coordinator/Store API；不把合法 snapshot proof 当完整 source 证明。

## 5. 明确边界

本批不改变 execution SQL/schema、claim/bind/decide/locked/read、Coordinator execute/sendDecision、Bridge/TC API、Pay source/资金状态机或所有读后窗口；只保护指定 read 关联的旧 execution snapshot 不覆盖当前不同 anchor。测试 Spring proxy/程序化 JDBC 仅证明本地方法事务边界，不证明 Seata 全局事务。
