# CM18 16T225：Pay TCC final observation snapshot 规格

## 1. 评审结论与范围

本批 **GO**。T224 仅保护 TC 发送前；execute/recover 末次 `observe(payNo)` 会重新读并可能采用 peer 替换后的 execution/Pay 身份，旧 TC 结果因此可写新行。本批只保护末次观察入口，复用 decision snapshot gate。

## 2. 最小末次观察改动

Store 提取 snapshot observation 内核：

- 保留旧 `observe(payNo,state,error)`：先 `read(payNo)`，再委托 `observe(PayTccExecutionPO expected,...)` 内核，维持缺404/terminal/NULL prebegin/Bridge/Biz/Runtime诊断规则；
- `observeSnapshot(expected,...)` 继续复用 Pay→execution/域/8anchor/source/merge 门；
- 新 `verifyDecisionSnapshot(expected)` 后，execute 的 `sendDecision` 使用决议返回的原 owned PO；TC 返回后末次改为 `observeSnapshot(verifyDecisionSnapshot(owned),...)`；recover 同理以当前 snapshot 复核后末次观察。

末次门不从 payNo 新 read 获 owner，不持本地锁发 RPC；冲突沿 T220/T224 传播，execute finally 保持。合法 mutable 进度、同向终态、NONE/普通 begin failure 仍按原流程；不撤回已发 TC/Bridge。

## 3. 验收矩阵

### 3.1 Owned native

- 使用 actual Coordinator/Store/Mapper/owned execution/Pay/Intent 与本地事务代理；TC/Bridge/Evidence/receipt 为 mock，不启动 App、TC/RM、新监听。
- execute/recover × 两方向 × 8 identity/source drift，peer 在 TC 成功返回后、末次观察前替换 execution/Pay；修复后 gate 409、无 VO/末次按 payNo read/Bridge/Evidence/observe 写，peer 三表事实不变，已发 TC 计数如实保留。
- 覆盖反向/NONE、最新同向终态、普通 UNKNOWN/进度与 NULL/SQL 门；合法 anchor 允许末次只读。测试 manifest 只包含本批及 T217–T224 的实际 Coordinator/观察方法，不能把无关 Legacy/租约 SQL 矩阵计入本批 native 数量。
- 本地 peer 事务交错不证明真实 TC、远端并发、资金 source 或完整 fencing；不持锁调用 RPC，不撤回已发动作。

### 3.2 Unit

- Store/Coordinator mock 适配 T220–T224，断言末次使用原 owned PO、无新的 payNo owner read，冲突时无末次 observation/readback，finally unbind。
- 保留旧 observe、verifyDecisionSnapshot、sendDecision 的冲突/异常/terminal/NONE 矩阵；不扩状态机、API 或 SQL 组合。

## 4. 明确边界

本批只保护 final observation 前的本地 snapshot 身份，不保护 gate→RPC、RPC→回写之外的全部窗口，不改变 TC client、决议算法、历史数据、Store 其它入口或外部 API，不宣称 TC/远端资金/全流程 fencing 闭环。
