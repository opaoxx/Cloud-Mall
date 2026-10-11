# CM-18 16T28：Pay 消费 User 原扣款关联退款集合观察

## 1. 结论与范围

本切片 GO，基线为 `3d7906d`，作为 Pay 对 User 16T11 `original_operation_key=payNo` 关联退款候选集合的只读消费。它只消费 User 已定义的有界关联范围，不扩大为全系统退款、未关联或错 link 的全部事实，不修账本、不补链、不补退，也不授权资金动作。

本切片保留 16T26/16T27 的可信 Pay anchor、事务外 User GET、AuthContext 隔离、双 Pay RR 和 local change 优先。T25、全系统退款、全账户因果、统一证据包和 M2–M4 仍不在范围。

## 2. 入口与 GET-only 调用

新增 default-off：

```text
cloudmall.pay.tcc-user-linked-refund-evidence.enabled=false
```

新增 GET：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation/user-linked-refund-evidence
```

Pay 入口在任何连接或 Feign 前复用 local flag、shared caller/token/payNo/no-Spring-transaction/no-Seata-XID 门；Gateway 禁止该路径的三种编码/前缀变体。事务外通过 GET-only `UserLinkedRefundEvidenceClient` 调用既有 User 路径：

```text
GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/refund-evidence
```

远端 User 的 T9/T11 flag 仍必须开启；未开启、503、空响应或错误 envelope 均为 `USER_EVIDENCE_UNAVAILABLE`，仍执行第二次 Pay capture。沿 3 秒连接、5 秒读取和 Spring 默认 NeverRetry。

第一次 Pay capture 必须得到可信 anchor；无 anchor 不调用 User。User 请求只使用 anchor 的 userId/payNo、固定 `cm18-reconciliation` caller 和实例 callback token，不接受 HTTP 身份覆盖，不传 XID、branch、operation key、ledger key、role 或金额。GET 前保存并清空 AuthContext，成功和异常路径 finally 恢复。User GET 成功或失败后都做第二次独立 Pay RR；除采集时间外 anchor、local snapshot、identifiers、classification、reasons 任一变化优先 `LOCAL_EVIDENCE_CHANGED`。无写、无 RM/TC、无 Store/恢复调用。

## 3. Native User 报告与复用门

Pay 新 DTO 的外层 proof 字段全部保持 JsonNode 原生 token。`UserRefundBaseEvidenceDTO` 继承已有 `UserAccountEvidenceDTO`，仅新增原生 JsonNode `reasonCodes`；旧 UserAccount DTO、旧 T10/T11 API 和既有 User 生产响应不改。

User 关联退款报告必须满足：

- `scope=USER_LINKED_REFUNDS_OBSERVED_ONLY`；
- 原生 `automaticActionsAllowed=false`、`unlinkedLedgerRowsNotInspected=true`、`candidateLimit=100`；
- `classification`、`queryAttempted`、`candidatesComplete`、`observedCount`、`validatedTotalAmount`、`vectorSha256` 全部按原生类型读取；
- `LOCAL_CONFLICT` 直接映射 `USER_EVIDENCE_CONFLICT`；任意 `BLOCKED_LOCAL_EVIDENCE`（包括 `REFUND_TOTAL_PARTIAL`）或其它非外部完整分类映射 `USER_EVIDENCE_INCOMPLETE`，不输出新统计；只有 `BLOCKED_EXTERNAL_EVIDENCE` 才继续校验外层候选摘要。

旧 T10 校验器继续保持原行为：旧 `verify(capture, oldDTO)` 始终调用新的私有校验实现，参数等价于 `fullLinkedRefund=false, allowFixedPartial=false`。新消费者只使用同包 `verifyLinkedRefundBase(capture, newBaseDTO, boolean fullLinkedRefund)`；不改变旧入口的 public API、Feign 契约或其它验证门。

只有嵌套 base 身份、四个 hash、状态/账本、账户和固定退款门全部匹配，且结果为 `MATCHED`，新消费者才可输出整体账户摘要。只有外层关联退款候选已完整且总额等于原 Pay 金额时，才可令 `fullLinkedRefund=true` 并进入 `allowFixedPartial` 分支；此时 base 原生 `reasonCodes` 必须是数组，去掉 `EXTERNAL_EVIDENCE_NOT_QUERIED` 后仍非空，且每个元素都必须是文本 `REFUND_PARTIAL_OBSERVED`，不接受未知值、null 或其它原因，也不改写 base 分类。`fullLinkedRefund` 可解除 Pay `REFUNDED` 情况下 fixed refund 缺失门，但不得单独绕过 base 为 `BLOCKED_EXTERNAL_EVIDENCE` 时的 fixed partial 门；只有上述严格 `allowFixedPartial` 分支可解除固定 `REFUND_PARTIAL_OBSERVED`，且仍要求固定退款存在且 `0 < fixedRefundAmount < originalAmount`，否则归冲突。其它 base 缺证、冲突、projection 缺口和未知原因不因新候选而抬高。

## 4. 外层候选摘要校验

仅当外层是 `BLOCKED_EXTERNAL_EVIDENCE` 且原生 `queryAttempted=true`、`candidatesComplete=true` 时继续：

- `observedCount` 必须为原生整数 0..100；`validatedTotalAmount` 必须为原生金额文本，非负、DECIMAL(18,2) 无损且不超过 Pay anchor 金额；`vectorSha256` 必须为小写 64 位十六进制；
- `observedCount=0` 时 total 必须为 `0` 且 vector 为 empty vector SHA256，不能据此推断全系统没有退款；
- `observedCount>0` 时 total 必须为正且等于 Pay anchor 金额，并且不得小于已核固定退款金额，因为固定退款是本关联集合的子集；外层声称 B_EXTERNAL 但只给正的 partial total 属于已知矛盾，归 `USER_EVIDENCE_CONFLICT`；
- 非空退款集合必须由 base `tccState=CONFIRMED` 且 `ledgerApplied=true` 支持；否则为缺证或冲突，不输出集合摘要；`REFUNDED` 的 Pay snapshot 也必须有 positive、full collection，不能用 zero/empty 候选替代退款证明；
- vector 只在完整集合通过时输出，按 operation key SHA256 升序拼接 `keyHash:amount:balanceBefore:balanceAfter;` 后再 SHA256；不输出原 key、原行、raw body、未知 code、XID 或错误文本。

新响应固定 `scope=PAY_USER_LINKED_REFUNDS_OBSERVED_ONLY`、`automaticActionsAllowed=false`、`unlinkedLedgerRowsNotInspected=true`、`accountCausalityNotProven=true`、Shanghai `collectedAt`、固定分类/reasonCodes、localEvidence、整体匹配才提供的账户摘要，以及：

```text
linkedRefundEvidence={queryAttempted,status,identityMatched,candidatesComplete,
  observedCount,validatedTotalAmount,vectorSha256}
```

失败、缺证、冲突、超限或 base 未整体匹配时，账户字段、count、total、vector 均为 null。成功发起 User GET 后 `externalSystemsNotQueried` 仅保留 `ORDER/STOCK/TC/MQ`；未发起时包含 `USER`。最高分类为 `BLOCKED_REMAINING_EVIDENCE`，不表示退款结清、原扣款来源完整或允许补退。

## 5. 验收与边界

- Unit 覆盖 native token/type、scope/flags、0/positive/partial/full total、empty hash、非 CONFIRMED、固定 partial 解除条件、身份/hash/state/account 门、source local/conflict、count/vector、无 anchor、双 capture、AuthContext 恢复、no POST/no retry/no raw identifiers。
- Owned Pay/User 使用真实 User 16T11 Controller/readOnly RR/Mapper、Feign Contract/global headers 和随机 loopback；Pay 两次 RR，User 基线 10+N SELECT。覆盖 0/1/100/101，multiple keys、fixed+other keys、无 fixed 但完整候选、Pay REFUNDED、partial、CI/null/duplicate/bad row/counter、并发新增退款不混读、503 及参数隔离。
- 100 条新候选按有界规则验证，101 条只返回哨兵并停止新候选核验；不把旧 T11 的局部范围误称为全系统结清。原 Pay/User 数据库 checksum、资金事实、owned DB/HTTP/executor/classloader 必须 finally 清理；不启动业务 App、TC、RM 或固定端口。
- 保留 T10/T26/T27、User T11、基础 gate/Gateway 和资金回归；不改既有 User/Pay API、旧 flags、DDL、索引、资金写状态机或 T25/统一包。
