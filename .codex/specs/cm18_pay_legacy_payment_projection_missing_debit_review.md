# CM-18 16T135：Pay Legacy payment projection missing debit

## 1. 结论与范围

GO。T134 只看 intent.status；INIT intent 可能对应 PayRecord 已进入 ORDER_SYNC_UNKNOWN/REFUND_PENDING。User operation 404 时仍可能追加 debit 或把取消误标 FAILED，且 retry 初值可能覆盖退款方向。

本批复用 claim 后已有 `requireLegacyRoute` 的 PayRecord tuple read，显式传递已验证 payment projection，防止支付记录状态与 intent 状态脱节。

## 2. Projection proof and gate

将 route helper 返回已验证 `PayRecordPO`，不新增 SQL；claim 前、claim=0 重读与 claim 成功后保持原检查点/次数。claim 成功后将 payment 传入 processClaimed，并在 Order proof 后、operation-null 的 CANCELLED/fresh 门前：

- payment.status 精确 `PENDING` 才允许 operation-null fresh debit/CANCELLED FAILED；
- `ORDER_SYNC_UNKNOWN` 或 `REFUND_PENDING` 等已进入恢复阶段时，operation 404 抛 Runtime “原支付记录已进入资金恢复阶段但扣款账本缺失”，不发 debit/paid/refund/terminal；
- catch 初始 retryStatus 以已验证 payment 的 ORDER_SYNC_UNKNOWN/REFUND_PENDING 为准，后续 durable REFUND_PENDING 优先仍保留，避免故障把退款方向写回 INIT。

已观察 APPLIED/REJECTED operation 继续原逻辑；补证账本后的下一次 process 可沿原成功/退款恢复。

## 3. 保持项与边界

不改 SQL/RPC/Feign/字段/flag、claim/lease/token、T133/T134、T79–T132。payment read 后状态漂移、历史 PayRecord/intention 错配和完整跨表 fencing不在本批。

## 4. 验收

- Unit/native：INIT × payment 两恢复态 × Order 四态 × Stock 四形态；非 INIT operation404 不发 debit/paid/refund/terminal，retry 方向保持。
- Owned Pay 两表 + controlled User/Order Feign：补 APPLIED 后下一次 process 可 SUCCESS 或 REFUNDED，debit/退款次数不重复；checksum/资源清理，不启动业务服务/RM/TC。
- 保留 INIT PENDING 原 fresh/cancel/T133 行为与 TCC/退款回归；不宣称观察后漂移已 fencing。

## 5. 交付边界

实现仅允许让 route helper 返回已验证 PayRecord、传入 processClaimed，并增加 payment status gate/retry 初值，必要 JavaDoc/测试；不得修改 SQL/API/lease/RPC 协议。
