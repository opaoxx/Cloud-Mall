# CM-18 16T133：Pay Legacy fresh debit proof

## 1. 结论与范围

GO。Legacy recovery 在 User operation 404 时，当前仍可能对 PAID/COMPLETED 订单发新 User debit，造成重复扣款风险；User debit APPLIED response 也未精确核 operationKey，别 pay/NULL/CI alias 可继续 Order paid/终态。

本批只补 fresh no-operation 的订单决策与 debit response identity，不改已观察 APPLIED、退款、TCC 或跨服务 fencing。

## 2. Fresh order gate

在 `operation == null` 分支、owned lease/debit RPC 前：

- 只有精确 `PENDING_PAYMENT` 才允许发 User debit；
- `CANCELLED` 继续原无扣款 FAILED；
- `PAID`/`COMPLETED` 抛 Runtime “订单已支付但未观察到原扣款事实”，不发 debit/paid/refund/terminal，沿原 INIT/ORDER_SYNC_UNKNOWN/REFUND_PENDING retry。

## 3. User debit response gate

在 envelope/body/APPLIED 既有门后增加 `Objects.equals(intent.payNo, debit.data.operationKey())`；不 trim/casefold。NULL/别 pay/CI alias/其它状态沿原“用户余额扣款未确认” Runtime，不能进入 DEBITED stage、Order.paid、refund 或 terminal。

合法 pending + exact APPLIED 保持原流程；已观察 APPLIED + PAID/COMPLETED 仍沿原 SUCCESS，CANCELLED + stock success 仍沿 refund。

## 4. 保持项与验收

不改 API/Feign fields、SQL、锁/lease/token、TCC/RM、RPC headers、User/Order status whitelist、T79–T132。已发 debit/paid 不回滚，catch 只按原 retry 保护。

- Unit/native：operation404 × PAID/COMPLETED/CANCELLED/PENDING；ACK key null/别 pay/CI/exact；断言首坏状态不发 debit，坏 ACK 不 stage/terminal。
- Owned Pay 两表/Mapper、User/Order controlled Feign；原 Pay checksum/资源清理，不启动业务 App/RM/TC。
- 不宣称 source read-after-write 或跨服务完整因果。

## 5. 交付边界

实现仅允许调整 `processClaimed` fresh operation-null gate 与 debit response exact key 校验、必要 JavaDoc/测试；不得改已观察 operation、退款、TCC 或数据库写协议。
