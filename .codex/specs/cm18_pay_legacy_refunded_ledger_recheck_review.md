# CM-18 16T155：Pay Legacy REFUNDED ledger recheck

## 1. 结论与范围

GO。T77 refund ledger proof 发生在较早阶段，User refund ledger 可能在后续 Order/stock GET 期间缺失、变 partial、改 key/type/state；`finishRefunded` 仍可结清 REFUNDED。

本批只在最终 Order CANCELLED+Stock SUCCEEDED gate 后再复核同一 refund ledger，不增加退款或改变 source/terminal。

## 2. Final refund proof

`finishRefunded` 签名增加 `refundKey`，在 Order gate 通过后、原 terminal TX 前调用 `confirmRefundLedger(intent, refundKey)`：

- marker caller 传 operation.refundOperationKey，保合法历史 custom key；
- POST caller 传固定 `refund:<payNo>`；
- key/type/state/amount proof 与 T77 相同，partial/null/404/envelope/fault/错 key 均 Runtime，沿原 catch/retry，不 REFUNDED；
- 早期 T77 proof、User refund POST、Order gate 顺序保持；不撤销已发 refund、不补退款、不猜失败。

成功后进入原 `commitOwnedLegacyTerminal(...REFUNDED)`，GET 在事务外；SUCCESS/FAILED、stage/owner/lease/API/SQL/TCC 不改。

## 3. 验收与边界

- marker/post-refund/after-paid 三路径 ×末次 proof partial/full/key/type/state/null/404/envelope/fault；坏证不 REFUNDED，补同 key 证据可恢复，不重复 refund。
- healthy tail-zero/exponent/custom key 保持；readOrder gate 失败不新增 User GET。refund GET 预算从1→2，整体 User operation 从2→3；源锁/rollback 保持。
- Owned Pay + controlled User/Order Feign，原 checksum/资源 cleanup，不启动 App/RM/TC；仅证明单条 Pay-visible refund，不宣称集合/source/GET→terminal fencing。

## 4. 交付边界

实现仅允许给 `finishRefunded` 增加 refundKey、末次 `confirmRefundLedger` 与必要 JavaDoc/测试；不得修改 T77 proof、refund POST、Order gate、terminal TX 或其它状态。
