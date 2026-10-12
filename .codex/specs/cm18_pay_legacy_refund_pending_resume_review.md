# CM-18 16T86：Pay Legacy REFUND_PENDING resume

## 1. 结论与范围

GO。退款 prestage 已将 PayRecord 置为 REFUND_PENDING 后，若进程在 User refund RPC 前中断，后续恢复面对 APPLIED DEBIT、订单 CANCELLED 且库存成功时仍先走 generic `markOrderUnknown/updateOwnedStatus`。该写只允许部分 active 状态，导致恢复循环无法进入 `refundCancelled`。

本批只修已扣款取消场景的路由顺序，使 REFUND_PENDING/部分 prestage 能恢复；不改退款证据或终态事务。

## 2. 路由顺序

保持已有优先级：

1. User 已有 refund marker 且订单取消/库存成功：继续原 `confirmRefundLedger` → `finishRefunded`；
2. User 无 debit 且订单取消：继续原 `failWithoutDebit`，不发 refund；
3. User 已 APPLIED DEBIT、无 refund marker，订单 `CANCELLED` 且库存 `SUCCEEDED`：在 generic `markOrderUnknown/updateOwnedStatus(DEBITED)` 之前直接进入 `refundCancelled`；
4. 其余 fresh debit/pending/paid 路径保持原 generic stage 与 RPC 顺序。

该分支复用 T80/T81 owned `REFUND_PENDING` stage、T77 ledger confirmation、固定 `refund:<payNo>` POST、T82 本地两表 terminal transaction 和 T79 token/deadline；不得把可变 REFUND_PENDING 改回 ORDER_SYNC_UNKNOWN，也不得对无 debit 取消误发退款。

## 3. 失败与保持项

退款 RPC 故障、缺证、owner/expiry/mode 失效仍沿原 retry/REFUND_PENDING；已完成 marker 不重复退款。订单非 CANCELLED、库存未成功、User DEBIT REJECTED/404 或退款证据不完整不得新发 refund。owner/lease 失效的已 owned stage 不使用普通 fallback。

不改 SQL、状态字段、token/30 秒 lease、DTO/API、User/Order RPC、T77 proof、T80–T85、TCC/RM、分笔退款或全服务 fencing；不宣称跨服务原子性。

## 4. 验收

- Unit：禁止 generic stage stub 仍能完成取消退款；覆盖 marker、无 debit、APPLIED 无 marker、故障 retry、缺证和 owner/expiry/mode 失效。
- Owned MySQL：PayRecord REFUND_PENDING 与 intent INIT/DEBITED/ORDER_SYNC_UNKNOWN/REFUND_PENDING 均可在 APPLIED full User ledger + 取消/库存成功事实下恢复；首次 refund fault 保持 pending，下一次重领成功进入 REFUNDED。验证无 debit/paid 重发、refund key 稳定、事实及资源清理。
- 为保持原 stage/零行测试语义，使用 PENDING_PAYMENT fixture 验证 generic stage 失败；不得放宽生产门或把该 fixture 当退款恢复证据。
- 不启动完整 App/RM/TC，不伪称完整退款集合/跨服务 source fencing。

## 5. 交付边界

实现仅允许移动/增加 `processClaimed` 的已扣款取消分支判定及必要测试/JavaDoc；不修改 Mapper SQL、User refund、terminal transaction 或其它状态路径。
