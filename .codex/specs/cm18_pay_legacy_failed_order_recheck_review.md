# CM-18 16T157：Pay Legacy FAILED Order recheck

## 1. 结论与范围

GO。`failWithoutDebit` 末次 User absence/REJECTED proof 后直接 FAILED；Order 可能在此期间转 PAID/COMPLETED 或取消条件消失，T153 无法阻挡远端变化。

本批只在 User failure source proof 通过后增加一次 Order recheck，不改资金、stage、terminal、User proof 或其它终态。

## 2. Order gate

在 `failWithoutDebit` User source gate 通过后、terminal transaction 前调用一次 `readOrderState(intent)`：

- absence (`expectedRejected=false`)：当前 status 必须精确 CANCELLED；
- REJECTED (`expectedRejected=true`)：保留原语义，当前 PENDING_PAYMENT 或 CANCELLED 均可失败；PAID/COMPLETED 拒绝；
- 未知/envelope/identity/amount/Feign fault 沿现有 `readOrderState` 异常；合法读取但不满足上述状态抛固定 Runtime “订单当前状态未证明支付失败”。

不新增 stock success 要求；无扣款失败不需要库存证据。失败沿原 retry，不 terminal，不写 Order/资金。

## 3. 保持项与验收

Order GET 仅在 User failure proof 通过后执行；User source gate 失败不增加 Order read。T133–T153、terminal tuple/lease/SQL/API/RPC/Retry 保持。

- Unit/native：absence/rejected × early cancel/pending ×末次 PAID/COMPLETED/unknown/identity/fault；不 FAILED/不资金或 Order 写，补 Order 取消可 FAILED。
- absence 补 APPLIED+PAID 后同 pay SUCCESS，不追加 debit/paid；健康 absence CANCELLED、rejected PENDING/CANCELLED 保持。
- Owned Pay + controlled User/Order Feign，原 checksum/资源清理，不启动 App/RM/TC；不宣称 GET→terminal fencing。

## 4. 交付边界

实现仅允许在 `failWithoutDebit` 增加末次 `readOrderState`/状态 gate、必要 JavaDoc/测试；不得修改 User source proof、terminal、stage、资金写或其它终态。
