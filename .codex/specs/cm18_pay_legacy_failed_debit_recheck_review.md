# CM-18 16T140：Pay Legacy FAILED debit recheck

## 1. 结论与范围

GO。FAILED 目前可能依据早期 User 404 或 REJECTED 读，在 Order 查询期间原 DEBIT 已 APPLIED/变化/带 refund marker，仍直接写 FAILED。

本批只在 FAILED terminal 前增加末次 User debit proof，不改 success/refund/TCC 或终态事务。

## 2. Explicit source mode

`failWithoutDebit` 改为显式 `expectedRejected` 参数，两个调用分别传入：

- operation REJECTED 分支：true；
- operation null + Order CANCELLED 分支：false。

不保留旧无参数 overload。FAILED 本地终态短事务前执行一次 `readDebitOperation(intent)`：

- expectedRejected=false：只允许 404/null absence；任何 APPLIED/REJECTED/marker/identity变化 → 固定 Runtime “原扣款账本未证明支付失败”；
- expectedRejected=true：必须非 null，复用原 key/type/amount proof，state 精确 REJECTED，refundOperationKey 精确 null；缺失或变化同固定 Runtime；
- envelope/body/Feign/validate 异常保原语义。

失败沿原 catch/retry，不自动退款/新 debit/paid，不修改 SQL/API/lease。

## 3. 保持项与验收

GET 在 terminal transaction 外；已发 RPC 不回滚。健康 absence 404→404、稳定 REJECTED→FAILED；补 APPLIED 后下一次取消退款/修正 REJECTED 可恢复。T138 SUCCESS、T139 REFUND、T134–T137 与 T79–T139 保持。

- Unit/native：两 FAILED 来源 ×末次 APPLIED/marker/identity/money/state/null/envelope/fault；bad 不 FAILED，healthy 正常。
- Owned Pay + controlled User/Order Feign，原 Pay checksum/pool cleanup，不启动 App/RM/TC；FAILED GET 预算 +1，其它终态预算不变。

## 4. 交付边界

实现仅允许调整 `failWithoutDebit` 签名、末次 debit proof/调用及必要 JavaDoc/测试；不得修改 refund/success、terminal transaction 或 User/Order API。
