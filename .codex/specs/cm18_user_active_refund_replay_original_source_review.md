# CM-18 16T105：User active refund replay original source

## 1. 结论与范围

GO。active existing refund replay 当前只证明 REFUND row；原 DEBIT 被删、未 APPLIED、错身份/snapshot 或原金额小于该 refund 时仍可能 200，且不读取当前源。

本批只补 active existing replay 的原 DEBIT source proof，不扩 owner/freeze/TC、退款集合或完整 replay 因果。

## 2. Replay source proof

在 T73 `ensureRefundMatches(existing, userId, request)` 通过后、返回 VO 前，active 用户复用现有 `selectDebitForUpdate(request.originalOperationKey)`：

- source 必须通过 T70 `isProvenRefundOriginalDebit`（精确 key、DEBIT/APPLIED、origin NULL、user/pay/order、合法 amount/snapshot/delta）；
- `existing.amount` 必须合法且不大于 source.amount；
- source 缺失、删除、错误状态/tuple/snapshot/金额或单笔 refund 超过原额，沿 `BALANCE_REFUND_INVALID`/409 固定“原扣款不存在或未应用”；
- DAO/DataAccess 异常原样传播，不伪装为成功/NOT_FOUND。

已持有 User 行锁，source 点读顺序复用 fresh/disabled；不新增 SQL/API/字段/写入。

## 3. 保持项与边界

disabled existing 仍先 T70/T74 source/TCC proof 并保持 404；active existing 合法 replay 不读取 current wallet、不 credit/insert、不读取 T75 集合或 freeze。T73 row proof、fresh T97–T104、SQL/锁/事务/API/TCC/Redis 保持。

不修历史 source、不证明 owner/Fence、全部退款集合、账户因果或跨服务 fencing。

## 4. 验收

- Unit：source missing/非 APPLIED/错 user/pay/order/key/snapshot、refund amount 超原额、DAO fault；T73 row/disabled source 优先与 no-write 断言。
- Native：真实 Legacy/TCC refund 完成后删除/修改原 DEBIT，active HTTP/service 409、四表 facts 不变；healthy wallet 变化/NULL、历史多退款、active fresh、disabled replay 保持。
- 查询预算：active existing 仅新增一个 source point read；资源 finally 清理，不启动 RM/TC/App。

## 5. 交付边界

实现仅允许在 active existing replay 的 T73 row proof 后增加 source point read/amount 门、必要 JavaDoc/测试；不得修改 disabled/fresh 或退款集合写入。
