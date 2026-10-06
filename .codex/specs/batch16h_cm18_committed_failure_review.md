# CM-20261007-18：16H COMMITTED TCC failure 结果语义评审

## 1. 评审结论与范围

结论：**有限 GO；只修正 COMMITTED execution 失败入口的结果分类，不在本轮实现 COMMITTED 后用户取消。**

已有 TCC execution 为 `COMMITTED` 且双端资金已 `CONFIRMED/APPLIED` 后，用户主动取消需要一条新的持久意图链：阻止 paid 交付竞态、持久 Order cancel 意图、事务外 Order cancel 恢复、复用原 delivery 的唯一退款键并最终核对 REFUNDED。现有 16E `pay_tcc_cancel` 明确只接受无 execution，因此不能把 COMMITTED 记录塞入该表，也不能让 failure 直接调用 Order.cancel、修改 TCC decision、调用 User refund 或强行 `delivery.deliver`。

本轮只保证 failure 入口对已存在交付状态给出准确既有响应：

- Pay `SUCCESS`：既有 409；
- Pay `REFUNDED`：既有 409；
- Pay `FAILED`：既有幂等成功；
- Pay `REFUND_PENDING`：返回既有 `PAY_REFUND_PENDING`/202，明确退款处理中；
- Pay `PENDING` 或 `ORDER_SYNC_UNKNOWN`：返回 `PAY_ORDER_SYNC_UNKNOWN`/202，明确没有执行 COMMITTED 后取消，不猜测已取消或已退款。

不新增表、字段、flag、scheduler 或新的远程写接口，不改 Order/Stock/User/TC 状态机。

## 2. 当前可复现缺口

`PayTccPaymentService.failure` 当前先按 Pay 状态只特判 SUCCESS/REFUNDED/FAILED；对 COMMITTED 且 Pay 已被 delivery 原子推进为 `REFUND_PENDING` 的记录，会继续进入“已有 execution 非 ROLLEDBACK”分支并返回通用 `PAY_ORDER_SYNC_UNKNOWN`/202。退款事实已经明确存在时，响应应保留 `PAY_REFUND_PENDING`/202，避免把已进入退款流程的记录错误描述为普通扣款未知。

这只是响应语义修正，不代表已支持 COMMITTED 用户取消。COMMITTED + PENDING/ORDER_SYNC_UNKNOWN 仍是“资金已确认、订单交付尚未完成”的未决事实，failure 不得改变它。

## 3. COMMITTED failure 路由契约

1. 入口在 `NOT_SUPPORTED` 边界读取 Pay 状态与 execution；不调用 coordinator.recover、TC、Order cancel、Order paid、User refund，不创建/修改 16E cancel 台账。
2. execution 为 `COMMITTED` 且 Pay 为 `REFUND_PENDING` 时，直接返回 `PAY_REFUND_PENDING`/202；不重复 POST 退款，不创建新退款键。
3. execution 为 `COMMITTED` 且 Pay 为 `PENDING`/`ORDER_SYNC_UNKNOWN` 时，返回 `PAY_ORDER_SYNC_UNKNOWN`/202；该响应不创建取消意图，客户端继续使用原 payNo 等待既有 delivery/恢复事实。
4. Pay SUCCESS/REFUNDED/FAILED 沿用现有入口契约。任何状态与 execution/交付台账不一致的已证实冲突，沿用原冲突/未知语义，不覆盖 Pay 状态。
5. ROLLEDBACK 继续 16F proof→project；CLAIMED/BEGIN_UNKNOWN/RUNNING/UNKNOWN/REQUESTED/COMMIT_REQUESTED/ROLLBACK_REQUESTED 继续 202，不主动决议。

## 4. 为什么不在本轮实现 COMMITTED 取消

安全的 COMMITTED 取消至少需要冻结：

- 与 `pay_tcc_delivery` 的互斥优先级，阻止同一订单同时 paid 和 cancel；
- 独立取消意图状态、Order cancel 的事务外调用和未知恢复；
- Order `CANCELLED + stock SUCCEEDED` 后只使用 `refund:<payNo>`，核对 User 原 DEBIT 与 REFUND 账本事实；
- Pay `REFUND_PENDING→REFUNDED`、delivery 状态和重复请求的同向 CAS；
- 进程重启、多实例 lease、已 SUCCESS/已退款和 Order 已 PAID 的冲突处理。

现有 delivery worker 的 `ensure` 只能从 COMMITTED+PENDING 派生交付任务，不能表达用户主动取消的持久意图；直接调用 `deliver` 会把“订单仍待支付”解释为 paid，而直接调用 refund 又绕过 Order/Stock 取消事实。因此本轮明确 NO-GO 扩展该流程。

## 5. API 与数据契约

- 不新增公网 endpoint、请求字段、数据库表或配置开关。
- 复用现有 `PAY_REFUND_PENDING`/202、`PAY_ORDER_SYNC_UNKNOWN`/202、支付已处理 409 和 FAILED 幂等语义。
- callback failure 继续 16D callback receipt 预绑定/真实结果完成规则；COMMITTED+REFUND_PENDING 只返回 202，不把 `RECEIVED_FAILURE` 提前写成 FAILED。
- Gateway Bearer/RedisToken、金额/时间、原 payNo 和既有内部 callback token 保持不变。

## 6. 最小验收矩阵

1. 构造 COMMITTED + Pay REFUND_PENDING + delivery REFUND_PENDING：mock-fail 与 callback failure 均返回 `PAY_REFUND_PENDING`/202，不调用任何远程写接口、不重复退款。
2. COMMITTED + Pay PENDING/ORDER_SYNC_UNKNOWN：返回 `PAY_ORDER_SYNC_UNKNOWN`/202，不创建 cancel 台账、不调用 Order cancel/paid、TC 或 User refund。
3. COMMITTED + Pay SUCCESS/REFUNDED/FAILED：保持既有 409/幂等；不改变 delivery 或账本。
4. ROLLEDBACK、未决 execution 和载荷/状态冲突：分别沿 16F/既有 202/冲突契约处理，不被 COMMITTED 分支吞掉。
5. 多次 failure/callback 重放不生成新退款键、不推进 callback 终态，除非已有真实业务结果已明确完成。

## 7. 明确 NO-GO

- 将 COMMITTED 记录写入 16E `pay_tcc_cancel`，或用 cancel 台账阻止/覆盖既有 delivery。
- 从 failure 入口直接调用 Order.cancel、Order.paid、User refund、TC 决议或修改 execution decision。
- 以 COMMITTED+PENDING 推断订单已取消/应退款，或以 `REFUND_PENDING` 返回普通未知并触发另一条补偿。
- 本轮新增 COMMITTED cancel 表、退款意图、scheduler、flag 或跨服务数据模型。
