# CM-18 16T10：Pay 消费 User 账户/冻结/固定退款观察评审

## 1. 结论与范围

结论：**GO，作为 Pay 消费 16T9 User 只读观察的独立切片；不改变 User 16T9、Pay 16T1–16T9、资金写状态机、退款 API 或其他服务。**

本切片只把 User 账户/冻结/固定退款观察作为 Pay 侧摘要证据，不执行任何资金决议，不把 User 观察包装成统一跨服务终态。

## 2. 入口与调用边界

新增 default-off：

```text
cloudmall.pay.tcc-user-account-evidence.enabled=false
```

新增内部 GET：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation/user-account-evidence
```

- 仅依赖 Pay 16T1 local flag、本批 flag 与 shared `validateForRead`（caller/token/ASCII payNo/no SpringTx/no SeataXID）并在连接前校验；不依赖 User/Stock/Order 采集 flags。
- 成功和 BizException 错误均 `Cache-Control: no-store`，Gateway 禁止；不接受 HTTP 覆盖 userId、XID、branch、operation/ledger key、金额或退款键。
- 第一次 Pay `ReadService.capture` 必须返回可信原 anchor；无 anchor 沿原 local 分类加 `LOCAL_ANCHOR_NOT_TRUSTED`，不发 User GET。
- Pay RR 结束后通过 GET-only `UserAccountEvidenceClient` 调用既有 User 16T9 GET，固定 path 参数来自 anchor userId/payNo，header 只用 caller=`cm18-reconciliation`、实例 callback token；不传 caller user/role/XID，不绑定 TC，不 POST。沿 3s/5s 与 Spring 默认 Never retry。
- GET 窗口保存/clear/finally 恢复 AuthContext，防全局 Feign header 污染；不改全局拦截器。

## 3. User DTO native 解析

接收 16T9 response 为内部 DTO，仅保留 JsonNode 原生 token，不直接绑定普通 POJO：scope、automaticActionsAllowed、otherRefundsNotInspected、userId、payNo、orderNo、amount、tccState、ledgerApplied、classification、identifiers、account、refund 共 13 个属性；未知属性忽略。

- scope 必须 native text 且精确 `USER_ACCOUNT_FROZEN_REFUND_OBSERVED_ONLY`；automaticActionsAllowed 必须 native boolean 且精确为 false，otherRefundsNotInspected 必须 native boolean 且精确为 true，否则 `USER_EVIDENCE_INCOMPLETE`。不能把 JSON 数字/字符串/boolean 强转为 boolean。
- userId 必须 native integral positive Long；payNo/orderNo 必须 native text；账户 balance/frozenBalance/退款金额必须 native text，不能把 JSON 数字/boolean 强转金额；账户金额须为正/非负且符合 DECIMAL(18,2) 范围，且与原 Pay amount 数值一致。
- identifiers 必须 native object；xid/userBranch/operation/ledger 四个 SHA-256 指纹必须精确匹配原 Pay anchor。缺失/类型错误为 `USER_EVIDENCE_INCOMPLETE`，已知错误身份、金额或 hash 为 `USER_EVIDENCE_CONFLICT`。
- tccState 白名单 `TRYING/CONFIRMED/CANCELLED/UNKNOWN`；`CONFIRMED` 必须 ledgerApplied=true，TRYING/CANCELLED 必须 false，UNKNOWN 为缺证。沿既有终态规则，COMMITTED↔CANCELLED、ROLLEDBACK↔CONFIRMED 为冲突；Pay 已终态+User TRYING 为缺证。

## 4. Source classification 与双 Pay capture

User response 失败（任何 4xx/5xx/409、transport、null body、null/error envelope）统一 `USER_EVIDENCE_UNAVAILABLE`，不靠异常猜资金。User source classification 必须 native text：

- `LOCAL_CONFLICT`：固定返回 `USER_REPORTED_LOCAL_CONFLICT` 冲突分类，不解析或回显 User 的坏金额/错误码；
- `BLOCKED_LOCAL_EVIDENCE`：固定返回 `USER_EVIDENCE_INCOMPLETE`，不抬高为外部匹配，也不回显坏金额；
- `BLOCKED_EXTERNAL_EVIDENCE`：才继续验证 account/refund 全部结构。

User GET 成功或失败后再次独立 Pay RR capture；忽略 collectedAt，原 local report、snapshot、identifiers、classification、reasons、trustedAnchor 必须相等，否则优先 `LOCAL_EVIDENCE_CHANGED`。

## 5. Account 与 refund 证明

仅当 source=`BLOCKED_EXTERNAL_EVIDENCE` 才核验：

- account native object/present=true、status ACTIVE/DISABLED、balance/frozen native text nonnegative DECIMAL(18,2) money，tryingTotal native text nonnegative aggregate money（最多 65 digits、scale<=2），`frozenProjectionMatched=true` 且 tryingTotal 与 frozenBalance 数值一致；源真假摘要矛盾为 conflict，缺字段为 incomplete。允许合法其他冻结，不要求 frozen=0 或等于旧 ledger balanceAfter。
- refund native object/present=false 时，其余固定摘要字段必须显式 null；缺字段为 incomplete，不推断无其他退款。present=true 时核验 APPLIED、identityMatched=true、正金额<=原金额、balanceBefore/After 非负且 delta=amount、退款 operation/original key SHA 匹配。部分正退款为 `REFUND_PARTIAL_OBSERVED` 缺证；TRYING/CANCELLED+APPLIED 退款为冲突。
- Pay paymentState=REFUNDED 时缺固定/非全额退款仍只 incomplete；原 COMMITTED+完整退款合法，不猜 Pay 退款结束，不因历史 ledger 与当前账户余额不同直接冲突。

## 6. 输出与验收

成功最高 `BLOCKED_REMAINING_EVIDENCE`，固定 `scope=PAY_USER_ACCOUNT_OBSERVED_ONLY`、native `automaticActionsAllowed=false`、native `otherRefundsNotInspected=true`、local Pay evidence 与 User account/refund 摘要。User GET 未发起时 `externalSystemsNotQueried=[USER,ORDER,STOCK,TC,MQ]`；已发起后只保留 `[ORDER,STOCK,TC,MQ]`。仅输出已验证的 native 文本金额、白名单状态、queryAttempted/status/identityMatched、SHA 指纹，不输出 raw body、未知 code、XID/token/key/error。

- Unit 覆盖 native type、缺字段、flag/hash、状态、部分退款、退款 proof、context/no-DB gate、变化优先；错误响应 no-store。
- Owned Pay/User 真实库：实际 16T9 Controller/readOnly RR/Mapper，通过随机 loopback + 真实 Feign Contract/global headers；Pay 2×6 SELECT + User 9 SELECT/同 RR/no writes，header 固定且 AuthContext 恢复，503 不 retry。原库 checksum、所有资金事实和 owned 资源 finally 清理。

## 7. 交付边界

本切片只消费 User 16T9 的账户/冻结/固定退款观察，不代替全部退款、全部冻结行归属、统一证据包或 CM18 M2–M4；不改变既有 User API/Feign/资金写状态机。
