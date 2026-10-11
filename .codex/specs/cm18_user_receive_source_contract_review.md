# CM-18 16T16：User TCC receive 原始来源契约评审

## 1. 结论与范围

结论：**GO，限定在既有 `UserTccStateQuery.read` 的 receive 原始来源门；不改变 T9 receive gate、HTTP/RM、资金写、DDL、SQL、API 或 flags。**

当前 Query 只核 receive.userId/operationKey/xid 和金额，历史坏 receive 即使与其他来源“相互一致”也可能输出 UNKNOWN/TRYING/终态。本批只补 canonical receive metadata 只读校验。

## 2. Receive metadata 门

在原 receive 身份门之后、任何 Mapper 后续读取、金额输出或 state 返回之前，必须要求：

- `receive.state` 精确为 `CLAIMED`；
- `receive.userId` 为正 Long；
- `receive.payNo` 为 ASCII `[A-Za-z0-9_-]{1,64}`；
- `receive.operationKey` 精确为 `tcc-debit:<receive.payNo>`，`receive.ledgerKey` 精确为 `receive.payNo`；
- `receive.orderNo` 非空白且长度<=64；
- `receive.xid` 非空白且长度<=128；
- `receive.branchId` 为 null 或正 Long；
- receive.amount 继续沿 16T13 正金额/DECIMAL(18,2)/无损 scale 门单独校验。

任一 metadata 不符统一原 `USER_TCC_STATE_CONFLICT`/409；receive 缺失仍 404。合法 `CLAIMED + branchId=null` 是 POST 后尚未 bind 分支的正常来源，允许继续原 UNKNOWN/TRYING/Fence 语义；不得要求 branch 已存在。

## 3. 不变边界

- 只增加原 Query 的 receive metadata guard；不增加 SELECT、参数、SQL、DDL、锁、写、RM、TC、HTTP/API 或 flags。
- T9 HTTP `UserTccHttpService.validate`、ReceiveStore.claim/bindBranch 的既有 canonical 规则保持；旧诊断 receive 错误仍沿既有 409/no-store 语义。
- 16T9–16T15 的 source classification/peer 缺证沿既有 error mapping；合法 receive 才能在无 attempt 时输出 UNKNOWN。
- 不修复历史 receive、不删除 row、不改变资金状态机，case/padded/null state 或固定键错误只读冲突。

## 4. 验收矩阵

- Unit：合法 amount 下 receive state、payNo、operation/ledger、order/xid/user/branch 每个坏变体均 409；相互一致的非 canonical ledger/op 和 known states 不得输出状态；合法 CLAIMED+branch null 保持。
- 真实 owned MySQL：case/padded/null metadata、旧 peer Query；四个现有路由中，原 User state GET 只验证固定 code/409并保持缓存契约，另外三个 User 诊断 GET 验证固定 code/409/no-store；原库 checksum、无写、资源清理保持。
- 保持旧 User state GET 缓存契约、T9–T15 取证和原 RR/Fence/金额门回归；T9 是诊断保护切片，不是原 TCC HTTP validate 的轮号；原 HTTP/claim 契约保持。不启动 RM/TC，不扩展 API/DDL。

## 5. 交付边界

本评审只冻结 User receive 来源身份/状态契约，不扩展 User 全财务因果、Pay/Order/Stock/TC/MQ 或 CM18 M2–M4。
