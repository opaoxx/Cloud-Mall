# CM-18 16T77：Pay Legacy refund ledger confirmation

## 1. 结论与范围

GO。Pay `LEGACY` consistency flow 当前在已取消且库存成功时，仅凭 User debit operation 返回的 `refundOperationKey` 或退款 POST 的 APPLIED response 就直接完成 REFUNDED，未证明退款账本的实际字段。部分退款、错误 operation key/type/state、NULL amount 或错误金额可能被误结清。

本批只在 Pay Legacy refund 终态前增加一次 User operation GET 证据；不扩展 TCC、User source/snapshot、退款集合、全账户因果或跨服务原子性。

## 2. 共享 refund ledger proof

新增私有 `confirmRefundLedger(intent, refundKey)`，仅在已有订单取消、库存成功及原路径优先级通过后调用：

- refundKey 非 null、非 blank；
- `UserClient.operation(intent.userId, refundKey, intent.userId)` 返回成功 envelope 与非 null body；
- body.operationKey 精确等于 refundKey，type 精确 `REFUND`，state 精确 `APPLIED`；
- body.amount 可解析为正数、无损 DECIMAL(18,2)，且数值等于 intent.amount；拒绝 NULL、负/零、非法 scale/capacity 或无法解析文本。BigDecimal 可解析且数值等价的表示（包括指数表示）不新增词法拒绝规则。

任何失败（包括 User 404/非成功 envelope、body 缺失、key/type/state/amount 不符）都抛现有重试链可处理的 Runtime/业务失败：不得 `markRefunded`、`finish(REFUNDED)` 或伪造 FAILED；不把 GET 404 当作“没有扣款”或支付失败。GET/Feign/DAO 异常沿现有 catch 与 retry 传播，不新增错误码或自动补退款。

## 3. 两条入口与优先级

- marker 路径：原 `readDebitOperation`、订单状态/库存条件及 marker 取消优先级保持；确认使用返回的实际 `operation.refundOperationKey`，通过 `confirmRefundLedger` 后才 `finishRefunded`。marker 缺失/坏订单等原有更早分支不因本批扩大查询。
- POST `refundCancelled`：保持原 `markRefundPending`/`updateOwnedStatus` 与固定 `refund:<payNo>` POST 写入及失败留在 `REFUND_PENDING`；成功 response 必须先确认 operationKey 精确为固定 key、state APPLIED，再调用 `confirmRefundLedger(intent, "refund:" + intent.payNo)`，通过后才 finish。不得再次 debit/refund 或补写。
- LEGACY 模式隔离保持；TCC、正常 paid、订单/库存读、租约、状态字段和既有 retry priority 不改。

## 4. 保持项与非目标

不改 User API/Feign DTO、SQL、数据库、Pay 状态机、锁、租约、消息、TC/RM 或退款 key 生成。只证明 Pay 可见的单条 User operation 字段，不验证 User 原 DEBIT、退款 snapshot、关联退款全集、账户余额或跨服务因果。合法历史自定义全额 refund key（marker 路径）仍可通过；固定 POST key 必须 exact。

## 5. 验收

- Unit：marker 与 POST 分别覆盖部分金额、unknown/REJECTED state、wrong/CI key、NULL/坏金额、404、非成功 envelope、Feign fault；失败只进入原 retry/REFUND_PENDING，不 finish。
- Healthy：full APPLIED refund、尾零 amount、marker 自定义 key、POST 固定 key 均在一次确认 GET 后完成；不重复 debit/refund。
- Owned Pay/User：Pay owned mapper 与受控 Feign 替身验证持久 retry/终态；另以 owned User 实际 DEBIT/refund 事务及 operation Controller GET 提供 partial/full 金额和 marker 协议事实。两者不是完整跨服务 HTTP/TC 联调，不把 mock User 当真实资金链。坏退款行/响应不改变 Pay/User source facts，合法终态完成；测试覆盖 LEGACY 隔离、checksum 与资源清理，不启动完整 App/RM/TC。
- 验证不宣称全退款集合、完整 User 资金 provenance 或跨 SQL/HTTP 原子窗口已闭环；保留 T70–T76 与旧一致性回归。

## 6. 交付边界

实现仅允许增加 Pay `PayConsistencyService` 的私有确认 helper、两条 Legacy 终态调用及必要测试/JavaDoc；不修改公共 API、Feign DTO 或其它服务。
