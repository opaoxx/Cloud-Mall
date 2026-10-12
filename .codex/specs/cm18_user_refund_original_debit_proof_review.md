# CM-18 16T70：User refund 原 DEBIT proof

## 1. 结论与范围

本切片 GO，修正 User refund fresh source 仅核 user/pay/order/APPLIED、忽略 DEBIT type、originalOperationKey、金额和余额 delta，导致坏 snapshot/alias/NULL amount 可 credit 或 NPE。只补原扣款字段门，不改退款写状态机/SQL/锁序/API。

## 2. Shared original DEBIT predicate

fresh `selectDebitForUpdate(request.originalOperationKey)` 返回后，active refund 与 disabled TCC refund 共用纯字段 predicate：

- original 非 null，operationKey 精确等 request.originalOperationKey；
- operationType=`DEBIT`、state=`APPLIED`、originalOperationKey 必须 null；
- userId/payNo/orderNo 精确等 request；
- amount 正且合法 DECIMAL(18,2)，balanceBefore/After 非负合法 DECIMAL(18,2)，且 `before-after=amount`；
- null、empty/literal null/self/other origin、错 identity/type/state、坏 amount/delta/NULL 均不通过。

active fresh bad source 沿 `BALANCE_REFUND_INVALID`/409，无 credit/insert；disabled 入口 bad source 沿既有 `COMMON_NOT_FOUND`/404，且先于 `confirmedTccRefund` 防止 NPE。原 operationKey candidate SQL/锁/写序保持，不新增 SELECT。

existing refund replay 不新增 source query/扩大范围；fresh confirm 插入原 null、旧 TCC attempt/user status 优先、CANCELLED/退款合计和其它字段保持。

## 3. 验收与边界

- Unit：active/disabled fresh 与 replay，origin empty/literal/self/other/null、bad money/delta、healthy null/zero-after/trailing-zero、DB fault；断言 code/status、无 credit/insert。
- Owned SQL/HTTP：真实 Legacy/TCC DEBIT 修改 origin/amount/snapshot 后 active 409/no write，disabled 404/no write；native BalanceTccState/real confirm replay facts 不变，source checksum/资源清理。
- 不改 request DTO validation、Mapper SQL、DDL、锁/CAS/RM/TC、退款全量/账户因果/历史修复；不新增 API 或自动动作，不启动 App/RM/TC。

本切片只冻结 refund 原扣款证明，不扩展完整退款 provenance 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
