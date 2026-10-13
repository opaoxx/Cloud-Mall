# CM-18 16T178：Pay receipt claim source identity

## 1. 结论与范围

GO。ReceiptStore.claim locks payment/dispatch by dispatch.payNo but only verifies mode/user/order/amount/XID/key; CI alias rows can form a claimed receipt for another original pay.

本批只补 claim source payNo identity，不改 read/lock 其它返回来源。

## 2. Exact source gate

在原 cancel gate 后、existing receipt lookup 前，payload proof 增加：

- `dispatch.payNo` non-null；
- `payment.payNo` 与 `dispatch.payNo` 精确等；
- `anchor.payNo` 与 `dispatch.payNo` 精确等；
- request operation/ledger key 既有 non-null/canonical 校验保持。

不匹配沿既有 `PAY_TCC_RECEIPT_CONFLICT/409`，不 insert/LocalTCC。SQL 查询/锁顺序 payment→dispatch→cancel→receipt、claim/validate、状态/金额/DTO/API 保持；dispatch 非 null 时等值比较已蕴含两来源非 null。

## 3. Existing replay与验收

existing receipt conflict/validate 仍在 source gate 后；精确来源 claim 成功一条 CLAIMED，重放 false，missing/mode/payload/cancel gate 优先保持。source foreign/space/null 仅对象模拟（生产列约束/collation不扩展）。

- Owned actual Pay/Dispatch/Receipt Mapper clones：payment/anchor 独立或同时 CI 大小写 alias 变异由真实 SQL 返回并被拒，Pay/Intent/Dispatch/Receipt facts 不变；foreign/space/null 仅对象模拟。Facade/Store 错来源不调用 resource。
- matching/winner/replay 控制、资源 cleanup，不运行真实 TCC/TC，不宣称 read/lock/source 全部 identity 已闭环。

## 4. 交付边界

实现仅允许在 ReceiptStore.claim 原 payload if 增加 payment/anchor payNo exact gate、必要 JavaDoc/测试；不得修改 SQL、锁、callback、read/lock 或状态机。
