# CM-18 16T29：Pay 消费 User 同支付号原账本观察

## 1. 结论与范围

本切片 GO，基线为 `b658c92`，作为 Pay 对 User 16T25 同一 `pay_no` 下全部有界账本候选（DEBIT/REFUND）的只读消费。它补足 T28 只消费 `original_operation_key=payNo` 关联候选的范围盲区，不修账本、不补链、不补退、不授权资金动作。

本切片不宣称其它支付号、全账户因果、全历史退款、统一证据包或 CM18 M2–M4 已完成；T10/T26/T27/T28 既有范围保持独立。

## 2. Pay 入口与 User GET-only 调用

新增 default-off：

```text
cloudmall.pay.tcc-user-payment-ledger-evidence.enabled=false
```

新增 GET：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation/user-payment-ledger-evidence
```

入口在任何连接或 Feign 前复用 Pay local flag、shared caller/token/payNo/no-Spring-transaction/no-Seata-XID 门；不依赖其它 Pay 诊断 flag，Gateway 禁止该路径的三种编码/前缀变体。事务外通过 GET-only `UserPaymentLedgerEvidenceClient` 调用既有 User 16T25：

```text
GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/payment-ledger-evidence
```

远端 User T9/T25 flag 仍生效，不要求 User T11 flag。沿 3 秒连接、5 秒读取和 Spring 默认 NeverRetry。第一次 Pay capture 得到可信 anchor 后才调用 User；只传 anchor userId/payNo、固定 `cm18-reconciliation` caller 和实例 callback token，不接受请求参数覆盖，不传 XID、branch、operation key、ledger key、role 或金额。

GET 前保存并清空 AuthContext，成功和异常 finally 恢复。User GET 成功或失败后都执行第二次独立 Pay RR capture；除 collectedAt 外 anchor、local snapshot、identifiers、classification、reasons 任一变化优先 `LOCAL_EVIDENCE_CHANGED`。无 anchor 不发 User GET；无写、无 RM/TC、无 Store/恢复调用。

## 3. Native source 与 T28 复用

Pay 新 DTO 的外层 proof 字段全部保持 JsonNode 原生 token，`linkedEvidence` 使用 Pay 16T28 已有的 `UserLinkedRefundEvidenceDTO` 承载 User 16T25 报告；该 DTO 不属于 User 16T25 服务。T28 的纯 `verify` 仅由 private 改为同包 static 复用，不改变旧逻辑或旧契约。

User 报告必须满足：

- `scope=USER_PAYMENT_LEDGER_OBSERVED_ONLY`；
- 原生 `automaticActionsAllowed=false`、`otherPaymentRowsNotInspected=true`、`accountCausalityNotProven=true`、`candidateLimit=100`；
- source `LOCAL_CONFLICT` 直接映射 `USER_EVIDENCE_CONFLICT`；任意 `BLOCKED_LOCAL_EVIDENCE`（包括 partial、错 origin、超限）或其它缺证直接 `USER_EVIDENCE_INCOMPLETE`，不查询新账本、不输出新统计；只有 `BLOCKED_EXTERNAL_EVIDENCE` 才继续；
- 嵌套 T28 必须整体 `MATCHED` 才能输出账户和关联退款摘要。source 的身份、金额或关联范围缺证不抬高。

外层 `queryAttempted=true`、`candidatesComplete=true` 后，`observedCount`、`validatedDebitCount`、`validatedRefundCount` 必须为原生整数 0..100，`validatedRefundTotalAmount` 必须为原生非负 DECIMAL(18,2) 文本，`vectorSha256` 必须为小写 64 位十六进制。新账本范围包含原 DEBIT，因此：

- `validatedDebitCount + validatedRefundCount = observedCount`；
- REFUND 数量和总额必须分别等于 T28 `linkedEvidence.observedCount` 与 `linkedEvidence.validatedTotalAmount`；
- base 为 `CONFIRMED` 且 `ledgerApplied=true` 时，必须恰好有一条有效 DEBIT；
- base 为 `TRYING` 或 `CANCELLED` 且没有账本时，DEBIT/REFUND/count/total 均为 0，并输出 empty vector hash；不据此推断其它支付号或全账户无退款；
- 0 候选只表示当前同 pay 范围为空，不能替代全系统退款证明。

## 4. Ledger 集合输出与分类

新增不可变 `PayTccUserPaymentLedgerReconciliationVO`，固定输出：

- `scope=PAY_USER_PAYMENT_LEDGER_OBSERVED_ONLY`、`automaticActionsAllowed=false`、`otherPaymentRowsNotInspected=true`、`accountCausalityNotProven=true`、Shanghai `collectedAt`；
- Pay `localEvidence`、固定分类/reasonCodes、整体匹配才提供的 User 账户摘要与 T28 `linkedRefundEvidence`；
- `paymentLedgerEvidence={queryAttempted,status,identityMatched,candidatesComplete,observedCount,validatedDebitCount,validatedRefundCount,validatedRefundTotalAmount,vectorSha256}`；
- User GET 已发起时 `externalSystemsNotQueried` 只保留 `ORDER/STOCK/TC/MQ`，未发起时包含 `USER`。

DEBIT 与 REFUND 的身份、`APPLIED` 状态、正 DECIMAL(18,2) 金额、非负余额和 delta 必须逐行核验；DEBIT 使用 `before-after=amount`，REFUND 使用 `after-before=amount`。DEBIT 的 operation key 必须为 payNo、`originalOperationKey` 必须为 null、金额必须等原 base 金额且只能一条；REFUND 的 origin 必须精确 payNo，错 origin 归当前范围缺证，不把任意 key 猜成原扣款。重复 key、错 tuple/type/state/money/delta、多个 DEBIT 或 DEBIT 缺失均不得输出完整新摘要。每个 key 的全局 COUNT 沿 User 既有只读方法复用，不能用 hash 代替唯一性。

仅在新范围全部验证且未超限时输出 count/total/vector；失败、缺证、冲突、101 哨兵或 T28/base 未整体匹配时，账户、linked evidence、count、total、vector 均为 null。vector 按 operation key SHA256 升序拼接 `keyHash:type:amount:before:after;` 后 SHA256，不输出原 key、原行、XID、error、token、unknown code 或响应 body。最高分类为 `BLOCKED_REMAINING_EVIDENCE`，不表示全支付号退款结清、全账户因果成立或允许任何资金动作。

## 5. 查询预算与验收

User 侧在 16T25 外层 RR 中复用 T11/T9 Reader REQUIRED，加入同一 User 连接；Pay 侧为两次独立 RR，T28 仅复用纯 validator，不复用其 Reader。基线 11 次（Pay/User 原报告、同 pay 候选各自固定查询）外加有界 key COUNT，N/M 均不超过 100，总预算最多 211 次。合法 100 条新行（1 DEBIT+99 REFUND）最多 210 次；101 条立即停止新 key COUNT，只返回哨兵缺证。

Unit 与 owned MySQL/Feign 验收覆盖 native types、source priority、partial/unknown origin、DEBIT/REFUND 缺失与冲突、count/total/vector、0/1/100/101、同 pay NULL/wrong link、CI alias、重复 key、并发 orphan 不混读、GET failure/no retry/AuthContext 恢复/local change/no anchor/参数隔离/no-store/脱敏。实际 User 16T25 Controller/readOnly Mapper 与 global headers 参与验证；Pay 12 SELECT、User 11+R+N SELECT；原 Pay/User checksum、资金事实、owned DB/HTTP/executor/classloader finally 清理。

不改既有 User/Pay API、旧 flags、DDL、索引、资金写状态机、T10/T26/T27/T28；不把 T28 orphan probe（NULL `originalOperationKey` 仍未被 T28 查询）误称为旧入口故障，新增入口只观察本同 pay 有界集合。保留 T10/T26/T27/T28、User T25、基础 gate/Gateway 和资金回归；CM18 整体及 CM20/CM21 仍未闭环。
