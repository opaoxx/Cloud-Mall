# CM-18 16T110：User refund replay collection

## 1. 结论与范围

GO。existing refund replay 已通过 T73/T105/T106/T108/T109 单行、source、owner 与 bounded DEBIT 门，但仍忽略退款总集合；orphan/bad row 或累计超额可能继续 200。

本批只补 existing replay 的 linked/payment 双集合证明，不改变 fresh helper 或写路径。

## 2. Replay collection gate

在 existing 全部既有门通过后、返回 VO 前调用 `requireRefundReplayCollection(original, existing)`：

1. 读取现有 linked LIMIT101，复用 T75 `provenRefundedAmount(original, linked)` kernel；
2. 读取现有 payment LIMIT102，复用 T102 `provenRefundPaymentMembership(original, linked, linkedTotal, paymentRows)`；
3. 当前 existing REFUND 必须属于已证明 linked/payment membership，operationKey、amount、before/after snapshot exact；
4. linkedTotal 必须不超过 original.amount。

null/超限/坏行/wrong-origin/unknown state/identity mismatch/membership mismatch/超额均沿 `BALANCE_REFUND_INVALID`/409 固定累计证据 message；DAO 异常原样传播。不把 request.amount 再加一次，不新增 source/owner/query。

## 3. 保持项与边界

active source/owner、disabled T74/T106、T73 row、T109 bounded DEBIT 优先级保持；合法 partial/full/100 集合与 current wallet NULL replay 保持。existing 不 credit/insert/cache，不改变 VO、API、SQL/锁/事务/TCC/GET。

fresh 继续原 T102 helper/超额算法；本批不证明其它 pay、全账户、receive/Fence、source fencing 或完整退款因果。

## 4. 验收

- Unit：null/missing current/wrong copy/membership mismatch/kernel/DAO/old gates priority；existing 不重复资金写，request amount 不重复累计。
- Native：active Legacy/TCC/disabled TCC existing refund 加超额、orphan、wrong-origin、bad state/user/pay/CI/101 rows，POST/service 409、四表 facts 不变；合法 partial/full/100 集合保持。
- 查询预算为 existing 新增 linked+payment 两次纯读；fresh 回归保持原预算，资源 finally 清理，不启动 RM/TC/App。

## 5. 交付边界

实现仅允许新增 existing replay collection helper/复用 T75/T102 kernel、必要 JavaDoc/测试；不得修改 fresh、owner/freeze 或写入路径。
