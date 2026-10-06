# CM-20261007-18：16R ORDER_SYNC_UNKNOWN TCC 取消重协调评审

## 1. 评审结论

结论：**有限 GO；为已取得交付尝试、但结果仍为 ORDER_SYNC_UNKNOWN 的 COMMITTED TCC 增加独立 `CANCEL_RECONCILING` 状态。**

16O/16P 的 `CANCEL_REQUESTED` 仍表示尚未取得 paid 写许可，不能复用到 ORDER_SYNC_UNKNOWN。进入 ORDER_SYNC_UNKNOWN 说明某个 worker 已经完成 READY→ORDER_SYNC_UNKNOWN stage，Order.paid 可能已经发出或正在发出；取消请求只能持久化“参与最终事实仲裁”的意图，不能保证取消胜出，也不能撤回在途 paid RPC。

本轮复用 `cloudmall.pay.tcc-cancel.enabled`、delivery 现有表和 lease；新增状态字符串 `CANCEL_RECONCILING`，不新增 schema、flag、XID、branch、Try、退款键、公网 endpoint 或 Order 业务模型。

## 2. 取消请求窗口

在 Pay TCC、原 execution `COMMITTED/COMMIT`、完整 Pay receipt/Fence/tuple、无 16E cancel/Legacy intent gate 的前提下：

- `READY` 仍按 16O/16P 改为严格 `CANCEL_REQUESTED`；
- `ORDER_SYNC_UNKNOWN` 改为 `CANCEL_RECONCILING`，保留 attempts/owner/lease_until/next_retry_at/immutable tuple，并用同一 DATETIME(3) now 将 next_retry_at 提前为 LEAST；
- 已有 `CANCEL_RECONCILING` 请求幂等重放，不修改 metadata；
- `CANCEL_REQUESTED` 遇 ORDER_SYNC_UNKNOWN 不改状态，继续严格取消语义；
- `REFUND_PENDING`、SUCCESS、REFUNDED、冲突 tuple、BEGIN_UNKNOWN 和其他 execution 状态拒绝改向。

request 只落库返回 `PAY_ORDER_SYNC_UNKNOWN`/202，不调用 TC、Order、User，不写 Pay FAILED。cancel flag 关闭后，已持久化的 CANCEL_RECONCILING 仍由 delivery recovery 继续清算；关闭 flag 只禁止新请求。

## 3. Order/Stock 最终仲裁依据

现有 Order `cancel` 与 `paid` 均只接受 `PENDING_PAYMENT`，通过状态条件更新 CAS，并在同一本地事务插入唯一 `order_stock_action`（ROLLBACK 或 CONFIRM）。因此 cancel 与 paid 并发时由 Order 状态 CAS 决定唯一胜者，stock action 与订单状态原子提交：

- cancel 先成功：Order `CANCELLED`，后续 paid 返回状态冲突；只有 stock action `SUCCEEDED` 才退款；
- paid 先成功：Order `PAID`，后续 cancel 返回状态冲突；只有 stock action `SUCCEEDED` 且无退款事实才把 Pay/delivery 收敛为 SUCCESS；
- CAS 响应未知时必须重新 GET Order payment-state，不能凭 HTTP 响应猜方向。

该仲裁不改变 Order 模块；Pay 只读取最终 `status + stockActionStatus`。

## 4. CANCEL_RECONCILING worker 状态机

worker claim 后必须重读最新 delivery state 并核验两端资金 `CONFIRMED/APPLIED`：

1. Order GET 为 `PENDING_PAYMENT`：用同态 `stage(CANCEL_RECONCILING)` 核验有效 lease，再调用同一 orderNo 的 Order.cancel；随后再次 GET。
2. Order 为 `CANCELLED + stockActionStatus=SUCCEEDED`：同态确认取消方向，进入原 `REFUND_PENDING`，使用唯一 `refund:<payNo>` POST/GET；响应未知保留 REFUND_PENDING，APPLIED 后 REFUNDED。
3. Order 为 `PAID/COMPLETED + stockActionStatus=SUCCEEDED` 且 User 没有任何退款事实：同态 stage SUCCESS，表示取消未赢；不得退款。
4. Order/Stock 查询未知、stock PENDING/UNKNOWN/CONFLICT、User/Pay proof 未知：保持 CANCEL_RECONCILING，记录错误并退避，不 paid、不 refund、不 FAILED。
5. 旧 worker 若持有原 ORDER_SYNC_UNKNOWN lease，可依据最终 Order 事实同向 stage SUCCESS 或 REFUND_PENDING；不能再 stage ORDER_SYNC_UNKNOWN 覆盖 CANCEL_RECONCILING。已发出的 paid RPC 无法撤回。

严格 `CANCEL_REQUESTED` 遇 PAID/COMPLETED 仍保持原冲突语义，不转为 SUCCESS；16R 不处理售后已 SUCCESS 取消。

## 5. Lease、锁和状态 CAS

request、claim、ensure、stage 使用 `pay_record → pay_tcc_delivery` 锁序。ORDER_SYNC_UNKNOWN→CANCEL_RECONCILING 只允许当前状态和完整 lease/tuple 条件匹配；有效 lease 不被接管，过期/释放后由新 worker claim。状态转换护栏必须至少保证：

```text
CANCEL_REQUESTED -> CANCEL_REQUESTED | REFUND_PENDING
CANCEL_RECONCILING -> CANCEL_RECONCILING | REFUND_PENDING | SUCCESS
ORDER_SYNC_UNKNOWN -> ORDER_SYNC_UNKNOWN | CANCEL_RECONCILING | REFUND_PENDING | SUCCESS
```

`CANCEL_REQUESTED` 不允许 SUCCESS；`CANCEL_RECONCILING` 不允许回写 ORDER_SYNC_UNKNOWN。每次 CAS 与 PayRecord 更新同一本地事务；CAS 失败回滚 Pay 状态更新。重复取消不增加 attempts、不改 owner/lease/tuple。

## 6. API 与回调边界

继续复用 mock-fail/callback failure，无新增 endpoint。failure/callback failure 返回 202 并保持 callback `RECEIVED_FAILURE`；不得伪造 Pay FAILED。最终 Pay SUCCESS 或 REFUNDED 的 failure 请求返回既有 409；不自动完成失败 callback。16R 不扩 BEGIN_UNKNOWN、TC 决议、售后取消或 Legacy 路径。

## 7. 验收矩阵

1. ORDER_SYNC_UNKNOWN、paid 尚未发出但已 stage：取消变为 CANCEL_RECONCILING，Order cancel 成功后唯一退款。
2. 原 paid 已发出并获胜：Order PAID+stock SUCCEEDED，CANCEL_RECONCILING 收敛 SUCCESS，不退款。
3. 取消先赢：迟到 paid 被 Order CAS 拒绝，最终 CANCELLED+stock SUCCEEDED 后退款一次。
4. cancel/paid 响应未知、进程重启、stock 未完成/未知/冲突：保留 CANCEL_RECONCILING 退避，不猜方向。
5. request 与 claim/stage 多实例竞争无死锁；有效 lease 保留，释放/过期后继续恢复。
6. cancel flag 关闭后已有 CANCEL_RECONCILING 仍能恢复；新请求保持既有 202。
7. 严格 CANCEL_REQUESTED 遇 PAID、REFUND_PENDING/SUCCESS/REFUNDED、BEGIN_UNKNOWN 和 Legacy 回归保持不变。

## 8. 实施门与范围

实现前冻结上述状态转换、Order CAS 事实和 lease 锁序；提交前必须完成真实 MySQL/Order/Stock/Pay 竞争、响应丢失、重启、唯一退款和 callback 回归。不得修改 Order 业务状态机或库存动作模型。本轮不支持售后已 SUCCESS 取消。
