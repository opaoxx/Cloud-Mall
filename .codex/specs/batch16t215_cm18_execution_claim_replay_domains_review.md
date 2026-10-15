# CM18 16T215：Pay TCC execution claim replay 域规格

## 1. 评审结论与范围

本批 **GO**。`ExecutionStore.claim` existing execution 分支目前只核 request tuple，坏 XID/state/decision 可与 tuple 一致后静默返回 false，与 read/locked 的 409 域门不一致；payment/existing amount null 还可能在 `compareTo` 处 NPE。本批只补已有 row 的 persisted domain 门与 null amount 异常收敛，不改变 fresh gate 或 replay 语义。

## 2. 最小修正

在支付行原 tuple 条件中先增加 `payment.amount != null`，NULL 沿既有 execution conflict 409，不进入 `compareTo` 或后续 claim。

existing execution 非 null 时，在原 `validate(existing, request)` 之前调用已存在的 `validatePersistedExecution(existing, request.payNo)`；该门复用 T210–T213 的 payNo/XID/state/decision/full payload/fixed-key 域顺序。通过后保留原 `validate` 的 request tuple、金额数值等价与 exact derived keys，然后返回 `false`。

不要求 existing replay 的 Pay status 为 PENDING，不新增 cancel/dispatch/receipt 查询，不重新认领、不 begin TC；SUCCESS/REFUNDED 等 Pay 状态与已有合法 execution 仍返回 false。Fresh 路径、validateRequest、公开 API、SQL、锁序、事务、组合规则和其它入口不改。

## 3. 验收矩阵

### 3.1 Owned native

- 使用真实 ExecutionStore/Mapper/TransactionTemplate 与 owned Pay/Intent/Execution 三表；Pay/Intent 模式与 tuple 显式匹配 request，不启动 App、TC/RM、新监听或 Docker。
- 旧 red：空 XID/未知 state 等 tuple 匹配 existing 原本静默 false；修复后 existing claim 返回 409，三表事实不变。
- bad replay 域覆盖 11 变体（XID blank3、state坏4、decision坏4）×两 seed state ×三 Pay status =66，均在 replay/写入前 409；positive raw state9 × decision3 × XID NULL/valid ×三 Pay status =162，仅验证域读取/replay false，不证明组合合法或 TC/资金因果。
- 四个合法 payload 模板覆盖真实首次 claim insert=1/winner=true，再 replay=false；唯一 execution 与 Pay/Intent 保持。其它 cancel/dispatch/receipt 为受控依赖，不重新认领或 begin。
- payment/existing amount NULL 只在 Unit PO seam 验证，不改 NOT NULL DDL；原库指纹、owned DROP、池关闭保持。

### 3.2 Unit

- 覆盖 payment.amount null、existing.amount null，均在 compareTo 前既有 409，无 NPE、无后续 Mapper 写；existing bad XID/state/decision 门优先。
- 覆盖 replay raw 域、Pay status、合法 payload fresh/replay 与 tuple 数值等价；Unit Mapper 可 mock，断言不查 cancel/dispatch/receipt、不改变 existing replay false。

## 4. 明确边界

本批不改 read/locked helper、不重构其它写入口、不改变 Pay status/TC begin/fresh 状态机、SQL、事务、API 或历史数据，不证明 existing execution 来源、组合状态机或读后 fencing；只确保 claim 对已有坏域与 NULL 金额返回一致 conflict，并保留合法 replay 语义。
