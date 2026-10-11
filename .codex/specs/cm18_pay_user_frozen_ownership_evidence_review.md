# CM-18 16T26：Pay 消费 User 当前 TRYING 冻结归属观察评审

## 1. 结论与范围

结论：**GO，作为 Pay 消费 16T12 当前 TRYING ownership 的只读切片；不改变 User 16T12、Pay 16T1–16T25、资金写状态机或其他服务。**

## 2. 入口与 GET-only 调用

新增 default-off `cloudmall.pay.tcc-user-frozen-ownership-evidence.enabled=false` 与：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation/user-frozen-ownership-evidence
```

复用 Pay local flag、shared gate、callback/caller/payNo/context 门，不新增 Pay 侧 User/Stock/Order 采集 flag 依赖；远端 User 既有 T9/T12 启用门仍生效，未开启/503 归 `USER_EVIDENCE_UNAVAILABLE`，且不得省略第二次 Pay capture。第一次 Pay `capture` 必须有 trusted anchor，事务结束后通过 GET-only `UserFrozenOwnershipEvidenceClient` 调用 User 16T12，固定 anchor userId/payNo、caller/token，不传 XID/branch/operation/key/role。沿 3s/5s 与 Never retry；AuthContext save/clear/finally 恢复。User GET 成功/失败后再次 Pay capture，除 collectedAt 外 summary/anchor 改变优先 `LOCAL_EVIDENCE_CHANGED`。

## 3. Native source proof

baseEvidence 固定为 `UserAccountEvidenceDTO`，其 13 个字段保持 JsonNode 原生 token。新消费者必须复用原 `PayTccUserAccountEvidenceService.verify` 的完整 user/pay/order/amount、四 hash、state/ledger、account、fixed refund 门；仅将该 private static 方法改为同包 package-private static，不改逻辑或公开 API。只有 User 结果 `userAccountEvidence.status=MATCHED` 才继续；任一 T10 partial/incomplete/conflict 不解除。其余 User DTO 外层 proof 字段使用 JsonNode；scope 必须 `USER_TRYING_FROZEN_OWNERSHIP_OBSERVED_ONLY`，automaticActionsAllowed=false，terminalFrozenRowsNotInspected=true，candidateLimit=100；native integral counts/ids、native text amounts/hashes。source `LOCAL_CONFLICT` → `USER_EVIDENCE_CONFLICT`，`BLOCKED_LOCAL_EVIDENCE`/其他 classification → `USER_EVIDENCE_INCOMPLETE`；仅 `BLOCKED_EXTERNAL_EVIDENCE` 且 queryAttempted/candidatesComplete=true 才继续。

observedCount 必须 native integral 且范围 0..100；count>0 必须 total>0，count=0 必须 total=0 且 vectorSha256 为 empty vector hash。total 必须是 native text、非负 DECIMAL(18,2) 且等 base account.frozenBalance/tryingFrozenTotal；vectorSha256 必须 lowercase 64 hex。任何 source/base 缺证不抬高、不输出 raw body/key/XID/error；source partial/other freeze 不消除原缺口。

## 4. Output 与边界

响应固定 `scope=PAY_USER_TRYING_FROZEN_OWNERSHIP_OBSERVED_ONLY`、`automaticActionsAllowed=false`、`terminalFrozenRowsNotInspected=true`、`otherRefundsNotInspected=true`、collectedAt、Pay localEvidence、validated User account evidence（仅完整 MATCHED 时）、以及 `userFrozenOwnershipEvidence={queryAttempted,status,identityMatched,candidatesComplete,observedCount,validatedFrozenTotal,vectorSha256}`。User GET 未发起时 externalSystemsNotQueried 包含 USER；发起后只保留 ORDER/STOCK/TC/MQ。

最高 `BLOCKED_REMAINING_EVIDENCE`，不输出资金终态、自动动作、余额因果或全冻结历史。16T26 不改既有 User/Pay API、flags、RM/TC/写状态机，不接入 T11/T24/T25 统一包，不做 rollout。

## 5. 验收边界

- Unit：native types、missing/flag/hash/state/count/total/vector、empty/other freeze、source conflict/partial、no anchor/no DB/context、local change priority、never POST/no raw identifiers。
- Owned Pay/User：Pay 2×6 SELECT、User 10+5N SELECT，同 RR/单连接，真实 User 16T12 Controller/readOnly Mapper、Feign/global headers；原库 checksum、资金 facts、owned DB/HTTP/classloader/executor finally 清理，503 不 retry。
- 覆盖 N=0/1/100/101、peer missing/conflict、writer 并发新增 TRYING 不混读；不宣称跨库原子或资金终态。
