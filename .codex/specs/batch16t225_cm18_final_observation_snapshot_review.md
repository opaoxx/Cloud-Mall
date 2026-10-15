# CM18 16T225：Pay TCC final observation snapshot 规格

## 1. 评审结论与范围

本批 **GO**。T224 仅保护 TC 发送前；execute/recover 末次 `observe(payNo)` 会重新读并可能采用 peer 替换后的 execution/Pay 身份，旧 TC 结果因此可写新行。本批只保护末次观察入口，复用 decision snapshot gate。

## 2. 最小末次观察改动

本批**完全不修改 `PayTccExecutionStore`**，不新增 Store API、SQL 或观察写入口。仅在 Coordinator 内提取同类私有内核：

- 原私有 `observe(String payNo)` 改为 `return observe(executions.read(payNo))`；
- 新私有 `observe(PayTccExecutionPO execution)` 保留原 terminal、NULL prebegin、Bridge/Evidence、BizException/Runtime 诊断逻辑，Bridge 参数使用 `execution.payNo`；
- execute 使用 `owned = executions.decideOwned(owned, xid, decision)`，`sendDecision(owned)`；TC 返回后执行 `VO.from(observe(executions.verifyDecisionSnapshot(owned)))`；
- recover 使用 `VO.from(observe(executions.verifyDecisionSnapshot(current)))`。

因此末次仍经过原 Coordinator 的 Bridge/Evidence 观察流程；`verifyDecisionSnapshot` 只在该流程前复核原 snapshot，不直接调用 Store `observeSnapshot` 替代 Bridge/Evidence。末次门不从 payNo 新 read 获取 owner，不持本地锁发 RPC；冲突沿 T220/T224 传播，execute finally 保持。合法 mutable 进度、同向终态、NONE/普通 begin failure 仍按原流程；不撤回已发 TC/Bridge。Store 旧 `observe(payNo,state,error)` 的 locked+merge 实现保持不动。

## 3. 验收矩阵

### 3.1 Owned native

- 使用 actual Coordinator/Store/Mapper/owned execution/Pay/Intent 与本地事务代理；TC/Bridge/Evidence/receipt 为 mock，不启动 App、TC/RM、新监听。
- execute/recover × 两方向 × 8 identity/source drift=32，peer 在 TC 成功返回后、末次观察前替换 execution/Pay；修复后 gate 409、无 VO/末次按 payNo read/Bridge/Evidence/observe 写，peer 三表事实不变，已发 TC 计数如实保留。
- 覆盖反向/NONE 8组、最新三终态12组、mutable进度4组（每组 `recoveryAttempts=9`、Pay.status=`SUCCESS`）；合法 anchor 允许末次只读。T224 terminal 控制的末次 payNo read 预期改为空列表。测试 manifest 精确包含本批4项、T217–T224各4项32项与2项 prebegin=38项，仅计实际 Coordinator/观察方法，不包含无关 Legacy/租约 SQL 矩阵。
- 本地 peer 事务交错不证明真实 TC、远端并发、资金 source 或完整 fencing；不持锁调用 RPC，不撤回已发动作。

### 3.2 Unit

- Store/Coordinator mock 适配 T220–T224；Recovery 8组（入口/方向×拒绝末次/终态），Execute 3组普通 begin/bind/decide 阶段，断言末次使用原 owned PO、无新的 payNo owner read，冲突时无末次 observation/readback，finally unbind。完整定向专项为29类/38 native方法，不冒称全仓 native 总数。
- 保留旧 observe、verifyDecisionSnapshot、sendDecision 的冲突/异常/terminal/NONE 矩阵；不扩状态机、API 或 SQL 组合。

## 4. 明确边界

本批只保护 final observation 前的本地 snapshot 身份，不保护 gate→RPC、RPC→回写之外的全部窗口，不改变 TC client、决议算法、历史数据、Store 其它入口或外部 API，不宣称 TC/远端资金/全流程 fencing 闭环。

首次预检中 fixture 诊断重复计数导致的一次失败必须与业务失败分开归档；修正后再执行完整定向专项，结果未出前不宣称通过。
