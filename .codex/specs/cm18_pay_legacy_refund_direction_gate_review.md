# CM-18 16T137：Pay Legacy refund direction gate

## 1. 结论与范围

GO。intent/PayRecord 已进入 REFUND_PENDING，但 User APPLIED debit 无 refund marker 时，Order PENDING_PAYMENT/PAID/COMPLETED 可让恢复流程回写 DEBITED、发 paid 或 SUCCESS；远端退款可能仍在执行，不能恢复正向支付。

本批只保护 APPLIED operation 的退款方向，不改 operation-null/REJECTED 的 T134–T136 门。

## 2. Direction gate

在 Order envelope/identity/status proof 后、refund marker 分支和 generic stage/paid/terminal 前，若：

- operation 非 null 且已通过原 APPLIED debit validation；并且
- intent.status 或 observed PayRecord status 精确 `REFUND_PENDING`

则必须 Order status 精确 `CANCELLED` 且 stockActionStatus=`SUCCEEDED`，否则抛固定 Runtime “退款恢复阶段缺少订单取消或库存回滚证据”。沿原 catch 保 `REFUND_PENDING`，不执行 stage、paid、User debit/refund RPC 或 terminal。

满足取消/库存证据后，继续原 marker confirmRefundLedger/finishRefunded 或无 marker 的 refundCancelled。operation null/REJECTED 仍由 T134–T136 优先处理。

## 3. 保持项与验收

不改 SQL/RPC/API/字段/flag/lease、T79–T136、正常退款 marker/取消成功路径、非退款方向、TCC/Order/Stock 协议；不宣称远端退款执行或 source fencing。

- Unit/native：REFUND_PENDING intent/PayRecord × PENDING/ORDER_SYNC_UNKNOWN/REFUND_PENDING 与 INIT/DEBITED/ORDER_SYNC_UNKNOWN 意图，Order 四态 × Stock 四态，除 CANCELLED+SUCCEEDED 外拒绝；marker/合法退款与补 Order cancel+rollback 后 REFUNDED 控制。
- Owned Pay 两表 + controlled User/Order Feign，source checksum/资源清理，不启动 App/RM/TC；断言无 stage/paid/资金 RPC/terminal。

## 4. 交付边界

实现仅允许在 APPLIED operation 流程增加 refund direction gate、固定 Runtime/JavaDoc/测试；不得修改 operation-null、REJECTED 或正常退款分支。
