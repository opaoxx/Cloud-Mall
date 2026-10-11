# CM-18 16T27：Pay 消费 User 历史终态冻结归属观察

## 1. 结论与范围

本切片 GO，作为 Pay 消费 User 16T24 历史 `CONFIRMED`/`CANCELLED` 冻结候选的有界只读观察。它只消费 User 16T24 已定义的终态候选报告，不扩大 User 的候选范围，不修复历史事实，不计算历史金额总额，不比较当前钱包，也不授权资金动作。

本切片保留 16T26 的 Pay 锚点、双次 Pay 快照、AuthContext 隔离、原 T10 账户验证和错误脱敏。16T26、User 16T24、T11/T25、统一证据包、全历史覆盖、M2–M4 发布和故障矩阵仍是独立范围。

## 2. Pay 入口与 User GET

新增 default-off 配置：

```text
cloudmall.pay.tcc-user-terminal-frozen-ownership-evidence.enabled=false
```

新增 GET：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation/user-terminal-frozen-ownership-evidence
```

入口在连接或 Feign 前复用 Pay local flag、shared `caller/token/payNo/no-Spring-transaction/no-Seata-XID` 门；Gateway 禁止该路径的三种编码/前缀变体。Pay 侧不新增或依赖其它 User/Stock/Order 采集 flag；远端 User 16T24 自己的 T9/T24 门仍必须开启，未开启、503、空响应或错误 envelope 均归 `USER_EVIDENCE_UNAVAILABLE`，并且仍必须执行第二次 Pay capture。

第一次 Pay capture 必须得到可信 anchor。没有 anchor 时不调用 User，返回本地分类并追加 `LOCAL_ANCHOR_NOT_TRUSTED`。有 anchor 时，在第一段 RR 事务结束后调用 GET-only `UserTerminalFrozenOwnershipEvidenceClient`，只传 anchor 的 `userId/payNo`、固定 `cm18-reconciliation` caller 和实例 callback token；不接受请求方覆盖身份，不传 XID、branch、operation key、ledger key、role 或其它资金参数。沿现有 3 秒连接、5 秒读取和 Spring 默认 NeverRetry。

GET 前保存并清空 AuthContext，GET 后在 `finally` 恢复成功和异常前的上下文。User GET 成功或失败后都执行第二次独立 Pay RR capture；除采集时间外，anchor/local snapshot/identifiers/classification/reasons 任一变化优先返回 `LOCAL_EVIDENCE_CHANGED`。无写、无 RM/TC、无 Store/恢复调用。

## 3. User 报告的原生校验

新增 User 响应 DTO 的外层 proof 字段全部保留 JsonNode 原生 token；`baseEvidence` 使用已有 `UserAccountEvidenceDTO`。消费者必须复用已有 `PayTccUserAccountEvidenceService.verify` 的完整身份、金额、四个 hash、状态/账本、账户和固定退款门；该方法继续只作同包复用，不新增逻辑或公开 HTTP/Feign 契约。只有嵌套 T10 结果为 `userAccountEvidence.status=MATCHED` 才能继续，T10 的 partial、incomplete、conflict 均不得被本切片抬高。

外层必须精确满足：

- `scope=USER_TERMINAL_FROZEN_OWNERSHIP_OBSERVED_ONLY`；
- 原生 boolean `automaticActionsAllowed=false`、`otherDebitStatesNotInspected=true`、`attemptOnlyCancelledNotInspected=true`、`accountCausalityNotProven=true`、`terminalFenceMayBeExpired=true`；
- 原生整数 `candidateLimit=100`；
- `classification=BLOCKED_EXTERNAL_EVIDENCE`，且原生 boolean `queryAttempted=true`、`candidatesComplete=true`；
- `observedCount`、`validatedConfirmedCount`、`validatedCancelledCount` 均为原生整数，范围 0..100，且两种终态计数之和等于 `observedCount`；
- `vectorSha256` 为小写 64 位十六进制文本。`observedCount=0` 时必须是空向量 SHA256；任何未验证、超限或错误情形不输出计数和向量。

原生计数缺失、非整数、越界，或向量缺失/不是小写 64 位十六进制，归 `USER_EVIDENCE_INCOMPLETE`。字段均为合法原生类型但两种计数之和不等于 `observedCount`、零候选不是空向量 SHA256，或嵌套 T10 `tccState=CONFIRMED` 而 `validatedConfirmedCount=0`，归 `USER_EVIDENCE_CONFLICT`，不输出计数/向量。`tccState=CANCELLED` 不强制 `validatedCancelledCount>=1`，因为原空取消可能只有 attempt、没有终态冻结候选；不得因此宣称取消来源已完整证明。

源分类映射固定为：`LOCAL_CONFLICT` → `USER_EVIDENCE_CONFLICT`；`BLOCKED_LOCAL_EVIDENCE` 或其它非外部完整分类 → `USER_EVIDENCE_INCOMPLETE`；只有外部完整分类才进入上述字段校验。缺字段、类型错误、错误 flag、非法计数和错误 hash 均为缺证，非原始错误文本；不得回显 User 原始 reason、候选 payNo、operation key、XID、branch、ledger key、错误或响应 body。

## 4. Pay 输出

新增不可变 `PayTccUserTerminalFrozenOwnershipReconciliationVO`，固定输出：

- `scope=PAY_USER_TERMINAL_FROZEN_OWNERSHIP_OBSERVED_ONLY`；
- `automaticActionsAllowed=false`；
- `otherDebitStatesNotInspected=true`、`attemptOnlyCancelledNotInspected=true`、`accountCausalityNotProven=true`、`terminalFenceMayBeExpired=true`、`otherRefundsNotInspected=true`；
- Shanghai `collectedAt`、Pay 脱敏 `localEvidence`、固定分类与 reason codes；
- 完整终态报告最终匹配时才提供嵌套 T10 `userAccountEvidence`；终态候选仍缺证或冲突时，账户摘要整体为 null，不回显部分统计；
- `userTerminalFrozenOwnershipEvidence={queryAttempted,status,identityMatched,candidatesComplete,observedCount,validatedConfirmedCount,validatedCancelledCount,vectorSha256}`。

成功完整观察的最高分类仍为 `BLOCKED_REMAINING_EVIDENCE`。缺证、冲突、超限和 User 不可用时，summary 中的计数、hash 和嵌套未验证账户字段保持 null。成功发起 User GET 后 `externalSystemsNotQueried` 仅保留 `ORDER/STOCK/TC/MQ`；未发起时保留 `USER` 及其它四项。任何结果都不输出资金终态、历史金额 total、账户因果、自动动作或全历史结论。

## 5. 验收与边界

- Unit 覆盖 native token/type、flag/scope、count sum、empty hash、CONFIRMED 无终态候选、CANCELLED 空候选、嵌套 T10 身份/hash/state、partial/conflict、无 anchor、AuthContext 恢复、local change 优先、503 不重试、无 POST 和脱敏。
- Owned Pay/User 验证真实 User 16T24 Controller/Mapper、Feign Contract/global headers、随机 loopback、两次 Pay RR；User 读取为 10+6N SELECT，N=0/1/100/101 覆盖，101 只返回哨兵并不继续逐 peer 核验。包含目标 `CONFIRMED` 与 `CANCELLED`、其它终态、仅 attempt 空取消、缺 peer、错误 mode/branch/XID/Fence/账本/快照、过期终态 Fence、同 pay DEBIT 冲突和 CI 候选场景。
- 并发 writer 新增终态行不得混入既有 RR；固定 caller/token、无身份覆盖、无 retry、无写请求。原 Pay/User 两库全表 checksum 与资金事实不变，owned DB、HTTP、executor、classloader 在 finally 清理。
- 不启动业务 App、RM、TC 或固定端口，不宣称真实资金全链、全历史、未关联退款、全账户因果或发布回退已完成。CM18 M1–M4 未因本切片闭环，CM20/CM21 仍等待 CM18 整体完成。
