# CM-18 16T73：User refund replay ledger proof

## 1. 结论与范围

GO。existing refund replay 当前只核 user/type/origin/pay/order/amount，未核返回账本的精确退款 operationKey、APPLIED 状态及 balance snapshot。CI 候选、REJECTED/未知状态、NULL 或坏 delta 因此可能被当作成功 replay，甚至在 amount/after 为 NULL 时 NPE。

本批只收紧 `ensureRefundMatches` 的纯字段 proof；不重新查询原 DEBIT、不读取当前 wallet、不 credit、不 insert、不修历史账本。

## 2. Replay proof

在 existing refund 返回 200 前，必须同时满足：

- `existing.operationKey` 精确等于 `request.refundOperationKey`；
- `existing.userId`、`operationType=REFUND`、`originalOperationKey`、`payNo`、`orderNo` 与请求精确匹配；
- `state=APPLIED`；
- `amount` 非 null、严格为正、无损落在既有 DECIMAL(18,2) 域，且与 request amount 数值相等；
- `balanceBefore`、`balanceAfter` 非 null、非负、无损落在同一金额域，且 `balanceAfter - balanceBefore = amount`。

任一条件失败，沿既有 `IDEMPOTENCY_CONFLICT`/409 和原固定 message 返回；不得新增错误码、把坏候选当 NOT_FOUND，或暴露原始账本值。

## 3. 调用顺序与保持项

- caller/request 金额门、用户锁与 existing 查询顺序保持。
- disabled 用户 existing replay 仍先执行 T70 原 DEBIT/TCC proof，失败继续 404；通过后再执行本 replay proof。active existing replay 不增加 source 查询，仍不扩大读取范围。
- fresh refund 的 T70 source proof、T71 请求金额域、T72 wallet/ledger 原子门完全不变。
- 保留 response 字段、current wallet 变化/NULL 不影响合法 replay 的语义；不改 SQL、锁、事务、DTO/API、缓存、TCC/RM/TC、退款合计或全账户因果。

## 4. 验收

- Unit：active/disabled existing replay 覆盖 operationKey case/别名、type/origin/user/pay/order、REJECTED/未知 state、amount/before/after null、负数、非法 scale/capacity、错误 delta；全部 409、无第二次 credit/insert/source query（disabled 原有 proof 除外）。
- 正例：APPLIED、精确 tuple、合法尾零、zero before、capacity 上限保持 200；current wallet 变化或 NULL 不改变 replay 结果。
- Owned MySQL/HTTP：真实已写 REFUND 仅修改 owned row 模拟坏事实，CI 候选仍被返回后由字段门拒绝；原账本/attempt/user facts 与 checksum 不变，公开接口只返回固定 409。资源 finally 清理，不启动 RM/TC/App。
- 不覆盖退款总集合、原扣款 counter、attempt 额外因果或全账户证明；保留 T70–T72 回归。

## 5. 交付边界

实现仅允许修改 `ensureRefundMatches` 及必要 JavaDoc/测试断言。CM18 其他资金证据与 CM20/CM21 仍未闭环。
