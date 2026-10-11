# CM-18 16T11：User 原扣款关联退款候选观察评审

## 1. 结论与范围

结论：**GO，补充 User 侧原扣款 `original_operation_key=payNo` 关联退款候选的只读观察；不改变 16T9 账户/冻结/固定退款观察、退款写 API、资金状态机或其他服务。**

本切片只覆盖原账本关联候选，不宣称全系统任意退款、未关联错 link、全部退款系统事实或 M2–M4 闭环。

## 2. 入口与 RR 读取

新增 default-off：

```text
cloudmall.user.tcc-refund-evidence.enabled=false
```

新增内部 GET：

```text
GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/refund-evidence
```

- 复用 16T9 local flag 与抽出的无 DB `validateForRead`（callback token、caller=`cm18-reconciliation`、positive userId、ASCII payNo、无 SpringTx/SeataXID）；成功、BizException、Long 绑定异常均 no-store，Gateway 禁止。
- wrapper 先校验两 flag和共享门，再进入单外层 `@Transactional(readOnly=true,isolation=REPEATABLE_READ)`；不接收 HTTP operation/XID/key/金额，不 begin、不 bind RootContext、不调用写 API。若 base classification 已为 `LOCAL_CONFLICT`，不查询退款候选，保留 base 冲突并令 `queryAttempted=false`。
- 外层复用原 `UserTccReconciliationReadService.inspect` 的 9 SELECT/同 RR，再执行 `BalanceLedgerMapper.findLinkedRefundCandidates(payNo)`：`original_operation_key=#{payNo}`、CI 候选、ORDER BY id、LIMIT 101（100+sentinel），不以 BINARY WHERE 预过滤 type/state/amount，不用 OR pay_no 造成全表扫描。
- 候选超过 100（实际 101）立即 `BLOCKED_LOCAL_EVIDENCE/REFUND_CANDIDATE_LIMIT_EXCEEDED`，不执行逐 key 查询、不输出 total/vector hash。每个候选 operation key 再调用 `countOperationCandidates(key)`，最多 100 次有界 COUNT；必须恰好 1。

## 3. 候选逐行证明

每个候选必须精确核对：

- userId、payNo、orderNo、`originalOperationKey=payNo`；operationKey 非空、长度<=128 且不等于原 ledgerKey；
- operationType=`REFUND`、state=`APPLIED`；amount 正且符合 DECIMAL(18,2)，balanceBefore/After 非负且 `after-before=amount`；amount/balanceBefore/After null 为缺证，负数、零金额、精度非法或 delta 错误为冲突；
- `countOperationCandidates(operationKey)==1`。COUNT null、0、负数或异常为缺证；大于 1、CI 别名或去 unique 的重复 key 为冲突，不用 key hash 猜唯一；
- duplicate exact key、身份/原 key/type/state/金额/delta 任何错配为本地冲突；坏候选不输出 raw row。

原 User base state 必须为 `CONFIRMED` 且 ledgerApplied，才能证明非空退款关联原扣款；UNKNOWN 为缺证，已知 TRYING/CANCELLED 且存在 APPLIED 退款为本地冲突。候选金额总和不得超过原金额，超额为冲突；合法多笔不同 key 部分退款允许观察。

总额小于原金额返回 `REFUND_TOTAL_PARTIAL` 缺证，不自动补退；若所有当前原 key 关联候选均完整验证，可输出已观察部分的 `validatedTotalAmount` 和 vector hash，但不代表未关联退款不存在，也不授权补退。若 16T9 base 只有固定退款键的 `REFUND_PARTIAL_OBSERVED` 缺口，且所有关联候选完成核验、总额等于原金额，可移除这一项缺口；其他 base 缺证/冲突优先保留，不因候选补全而抬高。

0 候选只表示本 `original_operation_key=payNo` 关联范围未观察到退款，不表示全系统没有退款。保留合法历史 unlinked/refund 错 link 的不确定性。

## 4. 输出与分类

响应固定：`scope=USER_LINKED_REFUNDS_OBSERVED_ONLY`、`automaticActionsAllowed=false`、`unlinkedLedgerRowsNotInspected=true`、上海 `collectedAt`、原 base evidence、`queryAttempted`、`observedCount`、`candidatesComplete`、`validatedTotalAmount` 和 `hash` 摘要；`candidatesComplete` 只表示当前 `original_operation_key=payNo` 的 CI 候选未截断且已全部验证，不表示财务结清、无未关联退款或无错 link；`externalSystemsNotQueried=[PAY,ORDER,STOCK,TC,MQ]`。

候选 vector 仅在全部候选完成核验且未截断时，按 operationKey SHA-256 升序拼接 `keyHash:amount:balanceBefore:balanceAfter;` 再 SHA-256；partial 总额也可输出已观察部分 total/vector hash，但不授权补退。不输出 raw key、rows、token、XID、error 或未知 code；坏/源缺证/超限不输出 total/vector hash。

分类保留 base 优先级：base `LOCAL_CONFLICT`、base 缺证、候选补充缺证/冲突均不抬高；完整 base+候选观察最高 `BLOCKED_EXTERNAL_EVIDENCE`，不输出资金终态或自动退款许可。

## 5. 验收边界

- Unit：CI alias、tuple/type/state/null/negative/zero/delta、partial/full、多 key、duplicate key、>100 sentinel、source 缺证/冲突、无原 confirmed、0 DB gate、no-store、候选上限和 vector ordering。
- Owned User MySQL：真实单 RR 查询，9 base SELECT + 1 candidate SELECT + N key COUNT；并发插入退款不混读；原库全表 checksum、owned 清理和无写事实保持。
- 既有 User refund API、资金写状态机、DDL/索引不改；允许只在 owned 库演练 legacy duplicate/unique 异常，不把该演练变成生产迁移。

## 6. 交付边界

本文件只冻结原扣款关联退款候选观察；不代替全系统退款、全部冻结归属、Pay/Order/Stock/TC/MQ 统一包或 CM18 M2–M4。
