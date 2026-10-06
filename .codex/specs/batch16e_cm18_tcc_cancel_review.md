# CM-20261007-18：16E TCC 主动取消（无 execution）评审

## 1. 结论与有限范围

结论：**有限 GO；只支持尚无 TCC execution 的主动取消，通过独立取消台账持久抢占首次 begin，再在事务外调用 Order.cancel 并由有界恢复器核对订单/库存事实。已有 execution 一律 202，不改 decision、不发相反 TC RPC。本轮允许在完整取消证据下将台账 COMPLETED 与 Pay FAILED 同一短事务提交，不扩展 ROLLEDBACK 终态投影。**

本轮只处理 TCC `PENDING` 支付的用户主动取消。Pay 记录在 Order 明确 `CANCELLED` 且 `stockActionStatus=SUCCEEDED` 前保持 `PENDING`；只有取消台账持久抢占且始终没有 execution/dispatch/receipt、并取得完整 Order/Stock 取消证据时，才与台账 `COMPLETED` 在同一短事务 CAS Pay `FAILED`。该 FAILED 不代表 TCC 回滚，不调用 User refund，也不覆盖任何已有 execution。

## 2. 开关与公开 API

- 新增 `cloudmall.pay.tcc-cancel.enabled=false`，默认关闭；它是独立 opt-in，不属于 16D 显式 TCC 创建的必需依赖。开关开启时必须已有 entry/coordinator/receipt/bridge/recovery/delivery 全部开启；关闭时停止新的取消请求和调度，但取消表仍初始化，且所有资金入口仍强制查询 cancel gate。Legacy 支付不进入本服务。
- 复用现有受保护 `POST /api/payments/{payNo}/mock-fail` 与内部回调 failure，不新增公网 endpoint 或字段。TCC 且无 execution 的请求成功创建取消意图后：
  - 取消已确认完成（Order `CANCELLED` + Stock `SUCCEEDED`，且 Pay FAILED 已同事务提交）：返回 HTTP 200，响应状态为 FAILED；
  - 取消已认领但 Order/Stock 结果未知或处理中：返回 `PAY_ORDER_SYNC_UNKNOWN`/202；
  - Pay 为 SUCCESS/REFUNDED：既有 409；FAILED：幂等返回；
  - 已有 execution（任意非空记录，包括 CLAIMED/BEGIN_UNKNOWN/RUNNING/UNKNOWN/REQUESTED/终态）：不创建取消台账、不发任何 RPC、不改 execution，返回 202；已有 ROLLEDBACK 继续走 16D 投影。
- 金额、用户、支付模式和 Gateway Bearer/RedisToken 鉴权沿用 16D；未知/不一致 mode 仍 503。TCC 不创建或读取 `pay_debit_intent`。

## 3. `pay_tcc_cancel` 台账

新增表（迁移和 `schema.sql` 均需可重复）：

```sql
CREATE TABLE IF NOT EXISTS pay_tcc_cancel (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  state VARCHAR(32) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NOT NULL,
  lease_owner VARCHAR(128),
  lease_until DATETIME(3),
  last_error VARCHAR(512),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pay_tcc_cancel_pay(pay_no),
  KEY idx_pay_tcc_cancel_due(state,next_retry_at,lease_until)
);
```

允许状态：`REQUESTED`（本地抢占已提交，尚未完成 Order 事实核对）、`ORDER_CANCELLED`（Order 已明确 CANCELLED，但库存动作尚未 SUCCEEDED）、`CANCEL_UNKNOWN`（RPC/查询结果未知，待恢复）、`COMPLETED`（Order CANCELLED + stock SUCCEEDED 且 Pay FAILED 已在同一短事务 CAS）、`CONFLICT`（状态、载荷或已支付事实冲突，人工核验）。不增加 `FAILED` 台账状态；Pay FAILED 只能作为 `COMPLETED` 的同事务投影，绝不能提前写入。

台账冻结 payNo、userId、orderNo、amount；任何载荷不一致返回 `PAY_TCC_CANCEL_CONFLICT`/409，不重建、不换键。恢复字段只用于调度 lease，不拥有 execution/XID/decision。

## 4. 与首次 begin、bridge/receipt 的互斥门

所有“资金入口认领”必须按同一锁顺序先锁 `pay_record` 行，再检查 `pay_tcc_cancel`，最后写各自事实：

1. `PayTccCancelStore.claim`：`SELECT pay_record FOR UPDATE` → 核对 TCC、PENDING、完整载荷及无 execution/dispatch/receipt → INSERT 唯一 cancel 行 → 提交；事务内不调用 Order。
2. `PayTccExecutionStore.claim`：锁 pay_record 后，若存在任意 cancel 台账（包括 CONFLICT）即拒绝 execution；已有 execution 只返回原事实，不能被取消请求覆盖。
3. 旧内部 `PayTccBridgeService.prepare/dispatch` 和 `PayTccDispatchPersistence.prepare` 也必须先锁 pay_record 并拒绝已有 cancel 台账；不能因为“尚无 execution”就绕过取消抢占。`PayTccReceiptStore.claim` 已锁 pay_record，还必须检查 cancel 台账后才可认领 receipt。
4. 取消 claim 同时拒绝已有 dispatch/receipt；这样旧 bridge.prepare、receipt.prepare 与 cancel 的竞争只能由 pay_record 行锁决定，不产生“取消已提交但分支仍能新建”的窗口。

首次 TCC success 的顺序仍是 Order `PENDING_PAYMENT` 预检后进入 coordinator；若 cancel 行已存在，execution claim 必须失败并由公开入口返回取消处理中/已完成语义。已有 execution 的 success/failure 只能 recover/观察，不得把 cancel 台账改成相反决议。

## 5. Service/API 方法契约

- `PayTccCancelService.request(PayVO)`：公开入口调用；必须以 `Propagation.NOT_SUPPORTED` 执行，使 PayServiceImpl 既有 `@Transactional` 外层不包住 Order RPC。内部先调用 `PayTccCancelStore.claimOrRead` 的短 `REQUIRES_NEW` 事务，提交后再调用/查询 Order，最后用 token CAS 更新台账。
- `PayTccCancelStore.claimOrRead(payNo, request, now)`：锁 pay_record 与 cancel 事实，首次 winner 插入 `REQUESTED`；已有台账只核对完整载荷并返回，不创建第二行。cancel gate 查询不受 cancel flag 影响。
- `PayTccCancelStore.claimRecovery(payNo, token, leaseUntil, now)` / `finish(payNo, token, nextAt, error, state)`：随机 token CAS；不能由 lease 接管 execution 或修改 Pay 状态。
- `PayTccCancelService.recoverDue()`：单实例单线程、有界批量（默认 20）、总预算 10 秒；只读取 due 台账并调用同一 `request/reconcile`，不新建台账、不重新 begin。
- Order 调用必须在本地事务之外：先 GET payment-state；`PENDING_PAYMENT` 才 POST cancel；响应未知后重复 GET；`CANCELLED` 但 stock 非 `SUCCEEDED` 进入 `ORDER_CANCELLED` 并退避；`CANCELLED + SUCCEEDED` 后，重新在短事务锁 pay_record/cancel/检查 execution、dispatch、receipt 均不存在，CAS Pay `PENDING→FAILED` 与台账 `→COMPLETED` 同时提交。

请求/恢复均需校验返回 orderNo、userId、payAmount、订单状态和 stockActionStatus。Order `PAID/COMPLETED`、载荷冲突、stock `CONFLICT` 或金额不一致进入 `CONFLICT`，不写 Pay FAILED、不退款、不调用 User。

## 6. 失败、并发和恢复语义

- 取消 claim 提交前崩溃：没有台账，后续 success 可正常首次 begin；不能在无持久抢占时调用 Order.cancel。
- 台账提交后进程崩溃：后续 success 因 cancel 行存在而不能 begin；取消恢复器按原 payNo 继续查询/取消。
- Order.cancel 响应超时/连接断开：写 `CANCEL_UNKNOWN` 或保留请求状态，返回 202；禁止猜测已取消。
- Order 已 CANCELLED、库存动作 UNKNOWN/PENDING：写 `ORDER_CANCELLED`，持续恢复；不能提前 COMPLETED 或 Pay FAILED。若同事务最终 CAS 发现已有 execution/dispatch/receipt 或 Pay 已非 PENDING，必须保持未知/冲突，不覆盖事实。
- 首次 begin 与取消并发：双方先锁 pay_record；先提交 execution 的一方使 cancel claim 返回 202/冲突，先提交 cancel 的一方使 execution claim 拒绝。任何一方不得在持有本地事务锁时调用远程服务。
- 已有 execution：取消只观察，不调用 coordinator.recover，不调用 TC/User/Order；该请求返回 202，交给原 TCC 恢复链。

恢复 lease 默认 60 秒，退避 `5 * 2^(attempt-1)` 秒上限 300 秒，attempt 上限 31；迟到 token 只能凭自身 token CAS 释放，不得清理新 owner 的 lease。坏记录/CONFLICT/长期 UNKNOWN 不得占满合法执行窗口。

## 7. 数据库与规格同步

- `pay_tcc_cancel` 的 PO/Mapper/迁移必须只归 Pay；取消表由无条件 initializer 建立，即使 cancel flag 关闭也必须存在。不改 Order 表、支付状态枚举或 User 账本。
- `PayServiceImpl.fail` 的 Legacy 路径保持原事务语义；TCC 取消必须经过独立 `NOT_SUPPORTED` 服务，确保 Order.cancel 事务外执行，不把整个 fail 方法粗暴改为非事务而破坏 Legacy。
- 16D 的 TCC ROLLEDBACK 投影规则保持不变，本轮不新增终态/退款/决议逻辑。

## 8. 最小验收矩阵

1. 默认关闭；TCC 显式开启且缺任一依赖时启动失败，Legacy 创建/失败回归不触发 cancel 表。
2. 无 execution 的 TCC cancel 只有一个唯一台账；取消仍在处理中时 Pay 保持 PENDING 并返回 `PAY_ORDER_SYNC_UNKNOWN`/202，完成时台账 `COMPLETED` 与 Pay `FAILED` 已同事务提交并返回 HTTP 200。
3. cancel claim 与 coordinator execution claim 并发：同 pay_record 行锁下只有一个 winner；另一方不创建 execution/台账第二行。
4. cancel 与 bridge.prepare、receipt.prepare 并发：旧入口检查 cancel gate，不产生独立 dispatch/receipt。
5. Order.cancel 成功响应丢失、服务重启、库存动作延迟：恢复最终只在 CANCELLED + SUCCEEDED 且无 execution/dispatch/receipt 时同事务 `COMPLETED + Pay FAILED`，其他情况不改 Pay FAILED。
6. Order 已 PAID、stock CONFLICT、金额/用户/订单载荷冲突：台账 CONFLICT，返回 409/202，禁止退款和资金 RPC。
7. 已有 execution（包括 BEGIN_UNKNOWN、COMMITTED、ROLLEDBACK）取消均 202 且不改 decision；ROLLEDBACK 仍由 16D 专门投影。
8. 多实例随机 lease、退避、迟到 token、批量预算和重复恢复均通过；所有 RPC 发生在本地事务提交之后。

## 9. NO-GO

- 用 `pay_record.status=FAILED` 或普通 `pay_debit_intent` 冒充取消抢占/完成。
- 只查询 execution 不存在就直接 Order.cancel；不持久化取消台账就发远程请求。
- cancel、execution、bridge dispatch、receipt claim 任一入口绕过 pay_record 行锁或 cancel gate。
- 在 `@Transactional` 事务内执行 Order.cancel，或通过取消请求改 TCC decision/调用相反 TC RPC。
- 把 Order CANCELLED 或 RPC 返回当作库存已成功，提前标记 COMPLETED/Pay FAILED；Pay FAILED 与取消台账不在同一短事务 CAS。
