# CM-18 16T13：User TCC 状态查询金额证据门评审

## 1. 结论与范围

结论：**GO，限定在既有 `UserTccStateQuery.read` 只读查询中补正金额证据门；不改变 User 写状态机、RM/Fence、HTTP/Feign 字段、DDL、flags 或其他业务路径。**

当前查询在 `receive.amount.toPlainString()`、attempt/debit/confirmed ledger 比较前直接使用金额；历史 NULL 会抛 500，零/负数/超 DECIMAL(18,2)/非无损两位精度的四来源相等事实可能被报告为 TRYING/CONFIRMED/CANCELLED。该门只阻止坏原证据输出。

## 2. 查询门契约

在 receive 身份门通过后、任何金额输出或比较前，必须对 `receive.amount` 执行 `validPositiveMoney`：非 null、`>0`、不超过 `9999999999999999.99`、`stripTrailingZeros().scale()<=2`。`1.000` 属于合法无损金额；本批不校验历史 `balanceBefore/balanceAfter` 增量。

读取 attempt 后，在原 tuple/数值比较前要求 attempt.amount 通过同一门；读取 debit 后要求 debit.amount 通过同一门；CONFIRMED 分支读取 ledger 后要求 ledger.amount 通过同一门，再执行原 `compareTo` 和身份/state/Fence 校验。任一金额缺失、零、负、超容量或非无损精度统一走既有 `USER_TCC_STATE_CONFLICT`/409。

金额门必须早于 `toPlainString`、`compareTo` 和 `ledgerApplied`/状态结果输出；receive 缺失仍 404，DataAccess/连接异常沿原 503 包装链。未知 state、Fence 缺失/状态和终态无 Fence 的既有语义不改变。

## 3. 不变边界

- 只修改原查询类的只读验证；不改 UserTccStateQuery 方法签名、VO/API/Feign 字段、HTTP gate、RM/Fence 调用、资金写 SQL、DDL、索引或 flags。
- 不校验/修复余额前后增量、历史 ledger 因果、账户 frozen 聚合或其他表金额；16T9–16T12 取证遇到原查询 409 时沿既有 source conflict/缺证分类。
- 读取仍为原 readOnly RR；不增加 SELECT、锁、写入、重试、TC/RM 或自动处置。

## 4. 验收矩阵

- Unit：attempt、debit、CONFIRMED ledger 各来源分别覆盖 NULL、zero、negative、excess scale、overflow；合法 `1.000` 保持；坏值直接 409，不产生 500 或终态输出。receive NULL 已由 16T9 `requireReceive` 金额门拒绝，本批不重复定义该源的 500/分类行为。
- Owned MySQL：六来源坏金额与完整合法 TRYING/CONFIRMED/CANCELLED 事实，验证原库 checksum、行完整性和并发读不写；旧 state/http/16T9–16T12 取证回归保持。
- 原 `UserTccController` state GET 继续使用 global handler，不新增 no-store 契约；该 HTTP 只验证 409 和固定错误 code、无资金写入。16T9–16T12 诊断 Controller 仍验证 409/no-store。本批不启动 RM/TC，不改生产数据。

16T12 兼容验收中，若历史 legacyNull 夹具的 receive.amount=NULL 已由本批金额门返回 409，peer 查询沿既有 catch-409 语义归 `FROZEN_PEER_PERSISTED_CONFLICT`；不把该用例放宽回 Runtime/缺证，也不要求 base source bad amount 改成对象分类，既有 HTTP 409/no-store 保持。

## 5. 交付边界

本评审只冻结 User TCC 状态查询的金额证据门，不扩展账户、退款、Pay/Order/Stock/TC/MQ 证明，也不宣称 CM18 M1–M4 已闭环。
