# CM-18 16T25：User 同支付号原账本只读观察评审

## 1. 结论与范围

结论：**GO，观察同一 `pay_no` 下全部有界账本候选（DEBIT/REFUND）；不改变 16T11 关联退款、User 写 API、资金状态机、DDL、索引或其他服务。**

本切片补足 T11 只按 `original_operation_key=payNo` 的盲区；不宣称全系统退款、未关联错 link、全部冻结归属、余额因果或 CM18 M2–M4。

## 2. 入口与查询

新增 default-off：`cloudmall.user.tcc-payment-ledger-evidence.enabled=false`。

新增 GET：

```text
GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/payment-ledger-evidence
```

复用 T9 local flag、token/caller/positive ID/ASCII payNo/noTx-noXID/no-store/Gateway gate；不要求 T11 flag，不接受 HTTP 身份覆盖。直接复用 T11 Reader 的 Spring proxy（REQUIRED 加入新外层 readOnly RR），不调用旧 T11 Service（其 Reader 本身纯只读）。

`BalanceLedgerMapper.findPaymentLedgerCandidates(payNo)` 只读 `WHERE pay_no=? ORDER BY id LIMIT 101`，不预过滤 type/state/original key，不用 BINARY/OR 全表组合。超过 100 立即缺证，不逐 key COUNT；`countOperationCandidates(key)` 复用既有方法，不新增接口。

## 3. Ledger proof

每行核验 userId/payNo/orderNo 精确、operationKey 非空<=128、operationType 仅 DEBIT/REFUND、state=`APPLIED`、正 DECIMAL(18,2) amount、非负 before/after 且 delta 正确；DEBIT 要求 `before-after=amount`，REFUND 要求 `after-before=amount`；重复 exact operationKey 为冲突。每 key 复用既有 `countOperationCandidates(key)`，null/0/负数为缺证，>1 为冲突。

DEBIT 必须满足 base tccState=`CONFIRMED`、ledgerApplied=true、operationKey=payNo、originalOperationKey=NULL、恰好一行，且 DEBIT.amount 精确等于 base amount；无 DEBIT 为缺证，多/错 DEBIT 为冲突。TRYING/CANCELLED 应无 DEBIT/REFUND，存在时冲突。

REFUND 的 `originalOperationKey` 必须精确 payNo；错 link 为 `PAYMENT_REFUND_ORIGIN_UNPROVEN` 缺证，不把任意原 key 当固定退款。仅 REFUND 候选金额总和不得超过原 amount；DEBIT 金额单独要求等于原 amount。partial 总额允许观察并保留缺证，全额才可完成关联总额证明。

## 4. 输出与分类

source 只接受 T11 `UserLinkedRefundEvidenceVO`：`LOCAL_CONFLICT`、`candidatesComplete=false` 或其他 source 缺证不查询新 pay 候选；仅接受 source=`BLOCKED_EXTERNAL_EVIDENCE`，或 source=`BLOCKED_LOCAL_EVIDENCE` 且 candidatesComplete=true、去除 `EXTERNAL_EVIDENCE_NOT_QUERIED` 后只剩 `REFUND_TOTAL_PARTIAL`。source 身份/金额缺失先阻塞。partial 保持 base `BLOCKED_LOCAL_EVIDENCE`，不抬高。

全部新 pay 行验证后，REFUND count/total 必须分别等 source observedCount/validatedTotalAmount；差异为冲突。响应固定 `scope=USER_PAYMENT_LEDGER_OBSERVED_ONLY`、`automaticActionsAllowed=false`、`otherPaymentRowsNotInspected=true`、`accountCausalityNotProven=true`、`linkedEvidence`（原 T11 evidence）、`queryAttempted`、`observedCount`、`candidateLimit=100`、`candidatesComplete`、`validatedDebitCount`/`validatedRefundCount`（Integer）、`validatedRefundTotalAmount`（String）和 vector SHA。只有完整新范围验证才输出 counts/hash；partial 可输出当前观察摘要但不授权补退。

vector 按 operationKey hash 排序，拼接 `keyHash:type:amount:before:after;` 后 SHA-256；不输出 raw rows/key/error/token。新 scope覆盖同 pay 已观察的错 link/未 link rows，但不证明原扣款因果或其他 pay 的全系统退款。最高 `BLOCKED_EXTERNAL_EVIDENCE`，外部 PAY/ORDER/STOCK/TC/MQ 未查询。

## 5. 验收边界

覆盖无/坏 DEBIT、错 link、type/state/money/delta、duplicate/count、partial/full、100/101、source priority、CI alias、同 RR/no-write、并发 orphan、原库 checksum/owned 清理；预算为 base 9 + linked 1 + N key COUNT + new pay 1 + M key COUNT，N/M<=100、最多211；有效100新行（1 DEBIT+99 REFUND）最多210，101新行停止新 COUNT。不改既有 User API/写状态机/DDL/index、T11/T24/资金回归。
