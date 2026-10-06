# CM-20261007-18：16O COMMITTED TCC 未发起交付窗口取消评审

## 1. 评审结论

结论：**有限 GO；仅允许尚未发起 delivery 的 COMMITTED TCC 支付持久化取消意图，复用现有 delivery 台账；不宣称支持全部 COMMITTED 取消。**

当前方案有一个必须先冻结的锁顺序风险：`PayTccDeliveryStore.ensure/stage` 已按 Pay → delivery 锁定，但原 `claim` 通过带 JOIN 的 UPDATE 间接锁定 payment、execution、delivery，未显式保证同一顺序。若新增取消请求按 Pay → delivery 锁定，可能与原 claim 的数据库执行计划形成反向等待。16O 只有在先规范化 claim 锁顺序后才可实施：claim 事务必须先锁 Pay 行，再锁 delivery 行，随后执行只更新 delivery 的 CAS；不能让带 JOIN 的 UPDATE 决定锁顺序。

本轮复用 `cloudmall.pay.tcc-cancel.enabled`，不新增表、字段、flag、XID、branch、Try、退款键或公网 endpoint。新增 delivery 状态 `CANCEL_REQUESTED` 属于现有 `state` 字符串枚举扩展，不改变表结构。

## 2. 允许的取消窗口

只有同时满足以下条件，failure/mock-fail 或 failure callback 才能在短本地事务内写入 `CANCEL_REQUESTED`：

- Pay 持久 `debit_mode=TCC`，状态为 `PENDING` 或 `ORDER_SYNC_UNKNOWN`；
- 原 execution 存在、`state=COMMITTED`、`decision=COMMIT`，XID 和完整原 tuple 一致；
- delivery 不存在，或 delivery 为 `READY` 且 `attempts=0`、`lease_owner IS NULL`、`lease_until IS NULL`；
- Pay receipt/Fence 和 execution 固定载荷可核验；
- Pay、execution、delivery 的 payNo/userId/orderNo/amount/XID/operationKey/ledgerKey/refundOperationKey 完整一致。
- 不存在任何 16E `pay_tcc_cancel` 台账，也不存在 Legacy `pay_debit_intent`；发现任一事实即拒绝改向并保留原资金结果。

delivery 缺失时在同一短事务创建原 immutable tuple 的 `CANCEL_REQUESTED` 台账，固定 `refund:<payNo>`。已有 READY 条件满足时只做状态 CAS。任何已 claim 记录（包括 attempts>0、有效或过期 lease）、`ORDER_SYNC_UNKNOWN`、`REFUND_PENDING`、`SUCCESS`、`REFUNDED` 或其他异常状态均不得改向；入口返回既有 202/409 语义并保持原事实。

已有 `CANCEL_REQUESTED` 只校验完整 tuple 后幂等返回，不重建台账、不增加 attempts、不修改 owner/lease，不调用远程服务。取消意图一旦提交，即使后来关闭 cancel flag，也必须由 delivery scheduler 按该状态继续恢复；该状态不得重新走 paid 分支。

## 3. 锁顺序与死锁护栏

所有新增和已有 delivery 本地写入必须统一使用：

```text
pay_record FOR UPDATE → pay_tcc_delivery FOR UPDATE → execution/其他只读事实
```

### 3.1 claim 必须先锁后 CAS

`PayTccDeliveryStore.claim` 先 `payments.selectForUpdate(payNo)`，再 `deliveries.selectForUpdate(payNo)`，核对 state、nextRetryAt、lease 和完整 tuple；随后调用只更新 `pay_tcc_delivery` 的语义化 CAS Mapper。若保留原 JOIN UPDATE，必须保证这些行已按相同顺序锁定后才执行，并以明确的 owner/lease/state CAS 收尾；不得让未预锁定的多表 UPDATE 决定反向锁顺序。claim 在 CANCEL_REQUESTED 上允许认领，但不能将其改成普通交付状态。

### 3.2 取消请求与既有写入

取消请求使用同一 Pay → delivery 锁顺序；ensure、stage、claim、requestCancel 至多一方先取得 Pay 行。取消 CAS 与 claim CAS 的胜者由 delivery 行锁决定：

- claim 先提交：attempts 已增加或 lease 已存在，取消请求拒绝改向；
- requestCancel 先提交：状态为 CANCEL_REQUESTED，claim 只能按取消分支处理；
- 任一事务失败必须整体回滚，不留下半个状态。

不在持有本地锁期间执行 Order/User/TC RPC。已发出的 RPC 不承诺撤回，只用状态和 lease CAS 丢弃迟到本地结果。

## 4. delivery 状态和工作器护栏

`findDue`、claim 候选必须包含 `CANCEL_REQUESTED`，且仍受到期时间和 lease 条件限制。`CANCEL_REQUESTED` 不得进入普通 paid 分支。

工作器取得 lease 后先读取最新 delivery state：

1. 重新核对 Pay receipt/Fence 和 User live `CONFIRMED/APPLIED`，证据未知或冲突时保持 CANCEL_REQUESTED 并退避；
2. GET Order payment-state；
3. Order 为 `PENDING_PAYMENT` 时调用同一 orderNo/userId 的 `Order.cancel`，随后再次 GET；
4. Order 为 `CANCELLED` 但 stock action 未 `SUCCEEDED` 时保持 CANCEL_REQUESTED 并退避；
5. 只有 `CANCELLED + stockActionStatus=SUCCEEDED` 才在本地短事务将 delivery `CANCEL_REQUESTED→REFUND_PENDING`、Pay 状态同步为 REFUND_PENDING，然后用固定 `refund:<payNo>` 调 User refund，并以同键 GET 核对；
6. 退款 APPLIED 且账本完整匹配后沿原路径推进 `REFUNDED`；退款响应未知保留 REFUND_PENDING，使用原键重试。

Order 为 `PAID/COMPLETED`、载荷冲突或 stock `CONFLICT` 时保持 CANCEL_REQUESTED，记录冲突和退避；不得调用 Order.paid、User refund 或将其改为普通 delivery。Order cancel 响应未知、Order 查询未知和 User 证据未知同样保持 CANCEL_REQUESTED。

## 5. 公开 failure 与回调边界

- cancel flag 关闭时仍保持原 16D/16H/16K 202 边界；开启时只有上述窗口可写 CANCEL_REQUESTED。
- 取消请求入口只落库并返回 HTTP 202，不调用 TC、Order、User，不写 Pay FAILED。
- `callback-failure` 继续保留 `RECEIVED_FAILURE`，不得因意图落库伪造 FAILED。
- 最终 REFUNDED 仍沿既有已处理 409 语义；是否新增 callback receipt 已 REFUNDED 的专门响应不在本轮范围。
- Pay FAILED 仍只由 16F/G 的永久双端回滚证据投影负责；16O 不把 CANCEL_REQUESTED 或退款请求结果直接投影 FAILED。

## 6. 类与方法冻结

为便于分工，建议冻结以下最小 seam：

- `PayTccCommittedCancelService.request(PayVO)`：`NOT_SUPPORTED` 公开户失败编排；只调用本地 store，返回既有 202，不调用 RPC。
- `PayTccCommittedCancelStore.request(PayVO)`：`REQUIRES_NEW` 锁 Pay→delivery，核对 execution、receipt/Fence、16E cancel 和 Legacy intent gate，创建或 CAS `CANCEL_REQUESTED`。
- `PayTccDeliveryStore.claim(String payNo,String token)`：补齐 Pay→delivery 显式锁序和 CANCEL_REQUESTED 候选护栏。
- `PayTccDeliveryStore.stageCancelRequested(...)`/等价语义方法：只在当前 lease 有效时更新错误/退避或 `CANCEL_REQUESTED→REFUND_PENDING`，不允许普通 SUCCESS/ORDER_SYNC_UNKNOWN 回写。
- `PayTccDeliveryServiceImpl.deliver(String payNo)`：claim 后重新读取最新 state；CANCEL_REQUESTED 进入 cancel 分支，禁止调用 `Order.paid`。
- `PayTccDeliveryMapper.findDue/claim/updateState`：增加 CANCEL_REQUESTED 的候选与精确状态 CAS；不改变表结构。

上述名称是实现 seam 契约；若沿用现有类名，必须保持相同事务边界、锁顺序和状态语义。

## 7. 验收矩阵

1. COMMITTED + Pay PENDING + 无 delivery：同事务创建 CANCEL_REQUESTED，返回 202，无远程调用。
2. COMMITTED + READY/attempts=0/无 owner/无 lease：状态 CAS 为 CANCEL_REQUESTED；attempts、owner、lease 和 tuple 保持不变。
3. READY 已有 attempts、有效/过期 lease、ORDER_SYNC_UNKNOWN、REFUND_PENDING、SUCCESS、REFUNDED：拒绝改向，不调用 RPC。
4. claim 与 requestCancel 并发：统一 Pay→delivery 锁序，无死锁；至多一方胜出，另一方保留事实并返回 202/409。
5. CANCEL_REQUESTED worker 遇 PENDING_PAYMENT：只调用 cancel，不调用 paid；随后按订单/库存事实重试。
6. CANCEL_REQUESTED 遇 CANCELLED+stock pending/unknown：保持 CANCEL_REQUESTED；遇 CANCELLED+SUCCEEDED 才唯一退款。
7. Order PAID/COMPLETED、stock CONFLICT、User/Pay证据未知：不 paid、不 refund、不 FAILED，状态可重试且可观测。
8. cancel flag 关闭后已有 CANCEL_REQUESTED 仍可由 delivery scheduler 清算；新 failure 不再创建意图。
9. 旧 READY/ORDER_SYNC_UNKNOWN 已发起交付仍保持 16C 保守语义，不被 16O 改向。

## 8. 实施门与范围声明

实现前先冻结锁顺序、状态转换和真实 MySQL 竞争测试方案；实现提交前必须通过该方案的锁顺序/死锁验收。需要覆盖锁顺序、状态 CAS、过期 lease、重复请求、进程重启、Order cancel 响应未知、库存延迟、唯一退款和 callback 回归。16O 只支持“未发起 delivery”窗口，不处理已 claim 的 READY/ORDER_SYNC_UNKNOWN、已成功交付、已退款或其他 COMMITTED 取消场景。
