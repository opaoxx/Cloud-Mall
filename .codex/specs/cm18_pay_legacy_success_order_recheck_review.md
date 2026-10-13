# CM-18 16T142：Pay Legacy SUCCESS Order recheck

## 1. 结论与范围

GO。`finishSuccess` 当前只在 T138 User DEBIT 末次复核后直接进入本地终态；Order PAID/COMPLETED + Stock SUCCEEDED 依据来自更早读取，Order 可能在 User GET 期间转 CANCELLED/PENDING 或库存动作失效。

本批只在 SUCCESS terminal 前增加一次 Order recheck，不改退款/FAILED/claim/terminal lock。

## 2. Success Order gate

`finishSuccess` 保持 `requireLatestDebitProof` 优先，随后在本地 terminal transaction 外调用一次现有 `readOrderState(intent)`，复用 envelope/orderNo/userId/amount/four-state proof，并要求当前 status PAID 或 COMPLETED 且 stockActionStatus=SUCCEEDED。

合法读取但缺 PAID/COMPLETED 或库存成功证据，抛固定 Runtime “订单支付与库存确认证据未证明支付成功”；其它 readOrderState 身份/金额/envelope/Feign 异常保原语义。均沿 catch/retry，不撤回已发 debit/paid，不自动退款，不 terminal。

## 3. 保持项与验收

三类 SUCCESS 来源（direct paid、after-paid、fresh debit）统一门；正常和同 pay 补证后重试可 SUCCESS，不重复 debit/paid。退款/FAILED/claim/lease/T141 两表 lock/API/SQL 不变；SUCCESS 额外 Order GET 一次，其它预算不变。

- Unit/native：三来源 ×末次状态/库存/identity/envelope/fault，坏证不 SUCCESS；合法末次通过。
- Owned Pay 两表 + controlled User/Order Feign，原 checksum/资源清理，不启动 App/RM/TC；不宣称末次 GET→terminal fencing。

## 4. 交付边界

实现仅允许在 `finishSuccess` 中增加末次 Order read/status gate、必要 JavaDoc/测试；不得修改 refund/FAILED/terminal transaction/Order API。
