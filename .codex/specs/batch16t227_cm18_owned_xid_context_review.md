# CM18 16T227：Pay TCC execute owned XID context 规格

## 1. 评审结论与范围

本批 **GO**。execute 目前只在 begin 后校验 RootContext XID；bind、receipt、Bridge、Evidence 期间 context 被清空或替换时，仍可能继续 Try、decide 或发送原 XID TC 请求。本批只保护 execute 调用边界的 owned XID 一致性，不声称已复现真实 Seata 故障。

## 2. Owned context 门

新增私有 `requireOwnedContext(String xid)`：xid 必须非 null 且精确等于 `RootContext.getXID()`，否则抛既有 `PAY_TCC_EXECUTION_CONFLICT/409`，不新增外部错误码。

在 execute 中增加检查点：

1. `bindOwnedXid` 成功后；
2. `receipts.prepare` 返回后（拆出局部 receipt VO，非 TRYING 也检查）；
3. `bridge.dispatch` 返回后；
4. inner ordinary catch 在 `rethrowConflict` 后、诊断写回前；
5. inner try/catch 结束后、`decideOwned` 前；
6. `decideOwned` 返回后、`sendDecision` 前。

context drift 直接 conflict，阻止诊断、决议、后续 Try/TC；已 begin/bind/已发 Try/已提交事实不撤回。业务 conflict 先由原 `rethrowConflict` 传播；ordinary failure 仅在 context 仍匹配时沿原 UNKNOWN/rollback 流程。begin 失败和既有 begin-context mismatch 保持，finally 仍清本次 RootContext。

不改 Store/SQL/DTO/Feign/recovery/TC client/状态机，不持锁调用 RPC，不改 ThreadLocal 代理实现或其它入口。

## 3. 验收矩阵

### 3.1 Owned native

- 使用 actual Coordinator/Store/Mapper/owned execution/Pay/Intent 与本地事务代理；TC/receipt/Bridge/Evidence 为受控依赖，不启动 App、TC/RM、新监听。
- 两 red：bind 事务提交后 callback 前 RootContext 改 foreign；receipt 回包后清 RootContext。修复后 context conflict、无诊断/决议/后续 Try/TC，已 begin/bind/既有 Try/提交事实如实保留。
- 覆盖 bind/receipt/bridge/evidence/ordinary-error/decide 等阶段 × clear/foreign；same-context 正控制与业务 conflict 优先控制保持。native manifest 为 T226 42 + 本批4 =46，逐名核 XML。
- context drift 由测试 advice/fixture 注入，不冒称真实 Seata/远端资金/fencing；不保护 callee 内部登记、本地事务元数据或 gate 后 RPC 全窗口。

### 3.2 Unit

- 复用 ExecuteConflict fixture，新增两组 context clear/foreign，覆盖 receipt/Bridge/Evidence/ordinary catch/decide 检查点、业务 conflict 优先与 finally unbind；不修改旧 T220–T226 矩阵。
- 断言 no diagnostic/decision/TC after drift，ordinary same-context 保持旧流程；Unit 不冒称真实 TC/ThreadLocal 代理。

## 4. 明确边界

本批只校验 execute 调用边界的原 XID，一旦 context drift 不继续后续步骤；不撤回已发动作，不修改 recover、Store、TC/RM、历史数据，不保证真实 TC/资金/source/完整 fencing。
