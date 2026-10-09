# CM-18 16T2：Pay/User 资金只读证据采集范围评审

## 1. 结论与位置

结论：**GO，冻结为 CM18 M1 的第二个只读切片；不改变 16T1 `inspect` 行为，不代表 User/Order/Stock/TC/MQ 证据齐全，也不代表 CM18 闭环。**

16T1 只读取 Pay 本地六类事实。16T2 在已有 Pay 本地报告能够提供可信 TCC 锚点时，额外读取 User 的现有 TCC state GET，用来核对同一 `payNo`、原 XID、User branch、operation key、ledger key 和资金载荷。User 查询完成后再次读取 Pay 本地 RR 快照；两次本地快照或锚点变化即阻塞，避免把不同时间的 Pay/User 事实拼成一个结论。

## 2. 入口和安全门

新增独立、默认关闭的配置：

```text
cloudmall.pay.tcc-user-evidence.enabled=false
```

新增内部 GET：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation/user-evidence
```

- 16T1 的 `cloudmall.pay.tcc-reconciliation.enabled`、callback token、caller=`cm18-reconciliation`、payNo 语法、Gateway 双层路径阻断和 `validateForRead`/context 409 保持；16T2 额外检查自己的 flag，两个 flag、共享认证门任一关闭或未授权均在任何 Pay/User DB、Feign 或事务前结束。16T1 原 `inspect` 行为不改变。
- 不接受 caller 提供的 XID、branch、operation key、ledger key、userId 或金额。所有外部查询参数必须来自第一次 Pay 本地 RR 快照中的可信锚点。
- wrapper 先完成 flag、token、caller、payNo、active Spring transaction、Seata XID 检查，再进入独立 ReadService；不继承调用方资金事务或全局事务。
- Gateway 不新增公网路由；直接 Pay 内部端口和既有 internal callback 认证用于隔离测试。User GET 的 caller 固定为已有 `cloud-mall-pay`，`X-User-Id` 使用本地核验出的 userId，callback token 使用实例配置值。

wrapper 的共享 `validateForRead` 必须在进入任一 reader 事务前校验 enabled、callback token、caller、payNo、Spring active transaction 和 Seata XID；User evidence wrapper 还必须校验自己的 flag。不得把校验放到 Feign 调用之后或已有读写事务中。

## 3. 可信 Pay 锚点

第一次本地读取由 local reader 的 `capture` 方法完成，在同一 RR 事务中返回内部 DTO `{localReport, trustedAnchor}`；原 16T1 `inspect` 仍只返回原 report，不露出 anchor。不得调用 `Store.read`、`bridge.recover`、coordinator、TC 或任何写接口。只有同时满足以下条件才允许发 User GET：

1. payment 身份、模式和基础 tuple 已核验为 TCC，且没有 `LOCAL_CONFLICT`、Legacy/TCC 混用或未知状态。
2. execution、dispatch、receipt 和必要 Fence 事实存在；execution 有非空原 XID，state/decision 组合合法且不是 `BEGIN_UNKNOWN`/`UNKNOWN`，没有缺失的 User branch 或 Pay branch。
3. execution/dispatch/receipt 的 payNo、userId、orderNo、amount、operation key、ledger key、XID 一致；operation key 必须是原 `tcc-debit:<payNo>`，ledger key 必须是原 payNo。
4. local classification 必须为 `BLOCKED_EXTERNAL_EVIDENCE`；execution/dispatch/receipt/Fence 完整，state 不是 `UNKNOWN`/`BEGIN_UNKNOWN`，两个 branch 都为正且不同。该锚点只作为查询参数来源，不授权任何资金动作。

内部 `trustedAnchor` 字段固定为 `payNo,userId,orderNo,amountString,xid,operationKey,ledgerKey,payBranchId,userBranchId,executionState,decision`，不能从 HTTP 输入构造，不能进入 response、普通日志或持久化。

`LEGACY_NOT_INSPECTED`、`BLOCKED_BEGIN_UNKNOWN`、`LOCAL_CONFLICT`、execution/dispatch/receipt/Fence 缺失或锚点不完整时不发 User GET，沿固定本地阻塞分类返回。无 XID、未知 XID 或无法证明原 branch 的记录不能通过重新 begin、Try、branch 注册或猜测补齐。

## 4. User GET 只读边界

- 只复用现有 `UserTccClient.state` GET 和 User `UserTccHttpService.state` → `UserTccStateQuery.read` 只读链；该链必须保持 `@Transactional(readOnly=true,isolation=REPEATABLE_READ)`，不绑定 RootContext、不注册分支、不认领 receive、不写 attempt/debit/ledger/Fence。沿现有 Feign 3 秒连接/5 秒读取超时，最多额外一次 GET retry；永不 POST。
- 不复用 `UserTccClient.prepare`、Pay bridge、任何 Store/coordinator/recover，也不调用 Confirm/Cancel/refund；任何 Feign transport、HTTP 4xx/5xx、响应解析失败或 User 数据库不可用均只产生阻塞分类。
- User 响应必须完整核对 `userId`、`payNo`、`orderNo`、金额、operation key、ledger key、XID、User branch、state 和 `ledgerApplied`。金额按 `BigDecimal` 数值比较，不能字符串截断或重算；User branch 必须等于 Pay dispatch 的 `userBranchId`，且不同于 Pay receipt branch。
- User 返回 `CONFIRMED`/`CANCELLED` 或 `ledgerApplied=true` 也只是外部证据的一部分；本批不输出资金终态，不设置 `automaticActionsAllowed=true`，不改变 Pay 或 User 任何记录。

## 5. 双次 Pay 快照与分类

1. 第一次 Pay RR `capture` 生成 16T1 本地 report 和内部锚点；采集结束后事务必须关闭。
2. 事务外调用一次 User state GET；不得在 Pay 事务锁、Seata XID 或 User 事务之间交叉持有锁。
3. User 返回后重新建立第二个 Pay RR 只读快照，比较 payment、execution、dispatch、receipt、Fence、Legacy intent 的事实摘要及所有锚点字段。
4. 任一本地资金/协议锚点、local snapshot、identifiers、classification 或 reason 摘要改变，返回 `LOCAL_EVIDENCE_CHANGED`；忽略 `collectedAt`，不宣称检测所有 retry/lease metadata 改变。

16T2 允许的结果分类：

| classification | 条件 | 处理 |
| --- | --- | --- |
| 原 local 分类 + `LOCAL_ANCHOR_NOT_TRUSTED` | Legacy、BEGIN_UNKNOWN、LOCAL_CONFLICT 或锚点不完整 | 不发 User GET |
| `LOCAL_EVIDENCE_CHANGED` | User GET 前后 local capture 不一致 | 丢弃本次组合结果，保留原事实 |
| `USER_EVIDENCE_UNAVAILABLE` | User GET 超时、HTTP/Feign/DB 不可用或 404 | 固定 reason code，不写 Pay/User |
| `USER_EVIDENCE_INCOMPLETE` | User 响应有 body 但 state/branch/ledger 等可验证字段缺失，`ledgerAppliedPresent=false`，或 Pay execution 已 `COMMITTED/ROLLEDBACK` 而 User 仍为合法 `TRYING` | 阻塞，不推断扣款/回滚 |
| `USER_EVIDENCE_CONFLICT` | User 与 Pay anchor 的 payload、XID、branch、ledger 或相反终态矛盾 | 固定冲突码并阻塞 |
| `BLOCKED_REMAINING_EVIDENCE` | Pay 与 User 只读证据一致 | 明确仍缺 Order/Stock/TC/MQ，不输出终态 |

16T2 response 固定 `scope=PAY_USER_READ_ONLY`、`automaticActionsAllowed=false`、`collectedAt`、原 16T1 local report，以及 `userEvidence={queryAttempted,status/state whitelist,identityMatched,ledgerApplied(nullable),xidSha256,branchIdSha256}`。User GET 未发起时 `externalSystemsNotQueried` 仍包含 USER；GET 已发起后只移除 USER，ORDER/STOCK/TC/MQ 保持。`BLOCKED_REMAINING_EVIDENCE` 是最高成功层级，不能命名为 TCC committed/cancelled、debit/refund success 或任何自动动作许可。所有分类只输出固定 reason codes、白名单状态和字段级 SHA-256 指纹；不输出 raw XID、branch、operation/ledger key、User error 或凭证。

Pay execution 已为 `COMMITTED` 或 `ROLLEDBACK`、Pay receipt/dispatch 已为对应终态，而 User 返回合法 `TRYING` 时，User 终态证据仍未完成，必须归 `USER_EVIDENCE_INCOMPLETE`（可使用固定 `USER_TERMINAL_EVIDENCE_PENDING` reason），不得归 `BLOCKED_REMAINING_EVIDENCE`。User 与 Pay 已证明相反终态时仍归 `USER_EVIDENCE_CONFLICT`。

## 6. 验收与取证

- Unit：default-off、token/caller/payNo/context 门在任何 reader/Feign 前拒绝；无 trusted XID、BEGIN_UNKNOWN、Legacy、本地冲突、缺 execution/branch/Fence 不发 User GET；caller 不可覆盖 XID/branch/userId/amount。
- Unit：User state 的完整匹配、金额差异、pay/order/user/XID/key 差异、User branch 等于 Pay branch、相反终态、`ledgerApplied` 与状态冲突、UNKNOWN/缺字段、404/503/解析失败分别进入固定 blocked/conflict 分类；金额使用 BigDecimal，不使用 double；不调用 Pay/User 写方法。Pay 侧接收 VO 保留 primitive `ledgerApplied` 的既有 JSON/消费者语义，额外增加 `@JsonIgnore` 的 `ledgerAppliedPresent` 和 `@JsonSetter("ledgerApplied")` 的 `Boolean` setter；缺字段或 null 时 `ledgerAppliedPresent=false`，collector 必须归类 `USER_EVIDENCE_INCOMPLETE`。
- 真实 owned Pay/User 数据库：Pay 两次快照和 User 查询前后六类 Pay 事实、User attempt/debit/ledger/receive/Fence 行数及 checksum 不变；本批不声称读取账户 balance/frozen 汇总，不能产生 APPLIED 账本、第二 branch、Try、Confirm、Cancel、refund 或 lease/attempt 变化。
- 通过实际 User 只读 Service/Mapper 的最小 HTTP/Feign transport 或 minimal MockMvc 验证认证和只读语义；不启动业务应用、固定端口或 TC。测试用 sibling User classes/target/classes URLClassLoader 加载真实 User GET Query/Controller，自建随机 loopback HTTP server 只允许 GET，Feign 使用真实 SpringMvcContract/decoder；任何 POST 或写调用立即失败，finally 关闭监听。覆盖 User 不可用、别单、金额变化、状态变化、同 branch、无 APPLIED 账本和 User GET 后 Pay 本地并发变化。
- 16T2 不查询 Order、Stock、TC、MQ，不建立外部 Collector，不实现人工写事实；完成后仍须回到 CM18 closure plan 的 M1–M4。

## 7. 交付边界

本文件与相邻 API/DB 契约已冻结，GO 后端可按本文实施。User response 的 null body、null 或非成功外层 `ApiResponse`（除既有可信 ErrorDecoder 分类外）归类 `USER_EVIDENCE_UNAVAILABLE`；只有现有 ErrorDecoder 明确产生 `PAY_TCC_DISPATCH_CONFLICT` 的 409 才能归类 `USER_EVIDENCE_CONFLICT`，其他 HTTP 409 不得被解释为资金冲突。Pay 侧 VO 的 `ledgerAppliedPresent` 仅用于区分缺证，不改变 User API、Feign 字段或其他消费者语义。
