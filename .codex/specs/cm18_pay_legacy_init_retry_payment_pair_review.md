# CM-18 16T154：Pay Legacy INIT retry payment pair

## 1. 结论与范围

GO。T152 INIT retry gate 只核 current intent INIT；其 EXISTS 只核 PayRecord LEGACY。PayRecord 已进入 ORDER_SYNC_UNKNOWN/REFUND_PENDING、intent 仍 INIT 时，旧 INIT retry 仍可清 lease/error/nextRetry。

本批只收紧 canonical INIT retry 的 payment/intent pair，不改其它 retry target、Service 状态计算、stage/terminal 或 RPC。

## 2. EXISTS pair guard

在 `recordRetryOwnedLegacy` 现有 intent INIT 条件与 T149 refund gate 中，EXISTS payment 条件追加目标 status 保护：

`(BINARY ? <> 'INIT' OR payment.status = 'PENDING')`

新增绑定为 retry target status，放置在 EXISTS 条件对应位置；canonical INIT 只允许 current intent INIT + payment PENDING。payment status 的既有列 CI 语义保留，目标 status 不 trim/casefold。

其它 target（ORDER_SYNC_UNKNOWN/REFUND_PENDING）、owner/deadline/mode/key、SET、512 截断、退避、T149/T152 条件保持；COUNT/claim/远端 fencing 不扩展。

## 3. 验收

- Native：payment-only advanced/CI/尾空格 + intent INIT 的 INIT retry affectedRows=0，facts/lease/error 保持；healthy PENDING 允许；其它合法 retry target 不变。
- Service：旧 DEBIT lease 读取后 payment advanced，T151 permission 已拒绝且 INIT retry 不写/不发 RPC；lease 到期补 APPLIED+PAID 或 CANCELLED+库存 proof 后可 SUCCESS/REFUNDED，不新增 debit/paid。
- Unit/owned：target status/EXISTS/owner/lease 与 T152/T149 回归，资源 checksum/清理，不启动 App/RM/TC。

## 4. 交付边界

实现仅允许修改 `recordRetryOwnedLegacy` 的 EXISTS payment phase 条件与参数绑定、必要 JavaDoc/测试；不得修改其它 SQL、Service、stage/terminal、claim 或 API。
