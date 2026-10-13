# CM-18 16T139：Pay Legacy REFUNDED order recheck

## 1. 结论与范围

GO。`finishRefunded` 复用较早 Order CANCELLED+stock success 结果；User refund RPC/ledger GET 期间订单可能转 PAID/COMPLETED、缺失或库存回滚未知，仍被写 REFUNDED。

本批只在 REFUNDED terminal 前增加末次 Order proof，不改已发 User refund、不补退、不写 Order。

## 2. Final order gate

`finishRefunded` 在 `markOwnedLegacyRefundPending` 之前，再调用现有 `readOrderState(intent)`，复用 envelope/orderNo/userId/amount/four-status proof，并要求精确 `CANCELLED` + `stockActionStatus=SUCCEEDED`；通过后原 stage/`commitOwnedLegacyTerminal(...REFUNDED)` 顺序不变。

只有合法读取成功但 status 非 CANCELLED 或 stock 非 SUCCEEDED 才抛新 Runtime “订单取消与库存回滚证据未证明退款完成”。缺失、identity/amount/envelope/Feign fault 保原 `readOrderState` 异常；均沿现有 catch，不 terminal、不撤回已发 User.refund、不猜补退或 Order 写。post-refund 路径 durable intent 已 REFUND_PENDING 时保持该方向；marker 路径 intent 仍 INIT/DEBITED 且 PayRecord 仍 PENDING 时沿原 ORDER_SYNC_UNKNOWN，不新增 intent stage/SQL。marker 路径已有早期 order read 不替代末次 read。

## 3. 保持项与边界

SUCCESS/FAILED 不增加 Order GET；RPC 与本地 terminal transaction 分离。初始 marker/no-marker 的原 retry 方向、owner/lease、T77 ledger proof、T80–T138 保持。只观察末次 Order，不宣称 GET→terminal fencing 或跨服务原子性。

## 4. 验收

- Unit/native：marker 与 post-refund 两路径 ×末次 state/stock/envelope/identity/money/fault，非法不 REFUNDED；正确末次 read 与同 pay 补证可恢复，稳定 refund key 不重复退款。覆盖 after-paid→二次 GET 取消→refund→finishRefunded 末次第三次 Order GET，以及 marker/post-refund 各自末次第二次 GET。
- Owned Pay 两表 + controlled Order/User Feign，原 Pay checksum/pool cleanup，不启动业务/RM/TC；断言已发 RPC 不回滚。

## 5. 交付边界

实现仅允许在 `finishRefunded` stage 前增加末次 `readOrderState`/stock gate、必要 JavaDoc/测试；不得修改 refund RPC、commitOwned terminal、SUCCESS/FAILED 或 Order API。
