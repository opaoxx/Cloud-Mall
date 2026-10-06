# CM-20261007-18：16F TCC 已回滚失败入口收尾评审

## 1. 评审结论与范围

结论：**有限 GO；补齐公开 TCC failure 入口对既有 `ROLLEDBACK` 的安全收尾。**

当 execution 已持久化为 `ROLLEDBACK`，且 Pay receipt/Fence 与 User live TCC 均证明同一原载荷已 `CANCELLED`、无 `DEBIT/APPLIED` 时，`mock-fail`/failure callback 可以复用 16D 的本地快照、User live GET 和短事务 `projectFailed`，把 Pay `PENDING`/`ORDER_SYNC_UNKNOWN` CAS 为 `FAILED` 并返回已确认失败。该路径不创建 cancel 台账、不 begin、不 recover、不调用 TC、Order 或 User 写接口、不改变 decision。

本轮不新增表、开关、扫描器、退款/取消逻辑。已有 execution 不是无 execution 主动取消场景；16E cancel 台账规则继续适用。

## 2. 入口与事务边界

- TCC `mock-fail` 与 callback failure 必须进入独立的 `NOT_SUPPORTED` 编排边界，避免现有 `PayServiceImpl.fail @Transactional` 把 User live GET 或本地投影包进外层事务。Legacy fail 的原事务语义保持不变。
- 入口先读取当前 Pay 持久模式和不可变 tuple；TCC 以外、模式未知/不一致、存在 Legacy intent 均按既有规则处理。
- 读取 execution 不创建记录。只有 state 为 `ROLLEDBACK` 才进入本轮证明；`CLAIMED/BEGIN_UNKNOWN/RUNNING/UNKNOWN/COMMIT_REQUESTED/ROLLBACK_REQUESTED/COMMITTED/CONFLICT` 均返回 `PAY_ORDER_SYNC_UNKNOWN`/202，禁止主动 recover 或决议。

## 3. ROLLEDBACK 证明与投影

### 3.1 事务外证明

1. 在短只读本地事务取得原 Pay/execution/receipt/dispatch 一致快照，核对 payNo、userId、orderNo、amount、XID、operationKey、ledgerKey、`debit_mode=TCC`；复用 `PayTccPublicStateStore.cancelledProof`，不能从当前配置推导。
2. 事务外调用 User live TCC GET，核对同一 XID、原 User branchId、完整 operation/ledger/pay/order/amount，且 `state=CANCELLED`、无 `ledgerApplied`。bridge 镜像或 TC/RPC 返回本身不能替代 User live 证据。
3. 任一查询失败、TC Unknown、证据缺失、载荷/branch 冲突或 ledger 未知均保持原 Pay 状态并返回 202；不写 FAILED。

### 3.2 短事务投影

证据完整后调用既有 `projectFailed` 的 `REQUIRES_NEW` 短事务：锁 Pay 行，重新核对 execution 仍为 `ROLLEDBACK`、XID/载荷一致，且 Pay 状态为 `PENDING` 或 `ORDER_SYNC_UNKNOWN`，再 CAS 为 `FAILED`。已 `FAILED` 返回幂等成功；已 `SUCCESS`/`REFUNDED` 或其他冲突终态返回既有 409；CAS 失败重新读取，不覆盖事实。

投影不改变 execution decision（可为 NONE/COMMIT/ROLLBACK），不修改 receipt/dispatch 终态，不写 cancel 台账，不调用退款或订单取消。

## 4. API 响应与 callback 收尾

- `mock-fail`：已有 ROLLEDBACK 且证明完整时 HTTP 200，响应 Pay status `FAILED`；证明未完成时 `PAY_ORDER_SYNC_UNKNOWN`/202；SUCCESS/REFUNDED 仍 409。
- callback failure：先沿 16D 预绑定 `RECEIVED_FAILURE`，再执行同一 ROLLEDBACK 证明/投影；投影成功后使用已确认的 FAILED Pay 视图调用 callback receipt complete，将日志推进 `FAILED` 并写 `processed_at`，返回 HTTP 200。若证明未知，保留 RECEIVED_FAILURE/NULL 并返回 202；不能把收到日志当作处理完成。
- 已有 execution 但非 ROLLEDBACK：failure 不触发 16E cancel，不调用 coordinator.recover/TC/Order/User 写接口，保持 202。

## 5. 与 16E 的边界

- 无 execution 的 TCC failure 仍由 16E cancel opt-in 处理；本轮不得创建 `pay_tcc_cancel`，也不得把 ROLLEDBACK 当作取消台账 COMPLETED。
- ROLLEDBACK 的失败投影不要求 cancel 台账存在；16E 台账若已存在则属于冲突事实，入口不得覆盖或推进它，返回 202/409 按持久事实处理。
- 不增加自动投影 scanner；只有公开 failure 入口触发本轮 proof/project。后台自动投影属于后续独立评审。

## 6. 最小验收矩阵

1. ROLLEDBACK + Pay/User 双端永久 CANCELLED/no-ledger：mock-fail 返回 200/FAILED，Pay 短事务 CAS 成功。
2. 同上 callback failure：RECEIVED_FAILURE 最终转 FAILED/processedAt，返回 200；重复 callback 仍先核对真实 Pay 状态。
3. ROLLEDBACK 但 User live UNKNOWN、branch/载荷冲突、ledger 已应用或查询失败：202，Pay 不变。
4. `decision=NONE/COMMIT/ROLLBACK` 均可投影，不改 decision；TC 不可达也不能单独证明回滚。
5. CLAIMED/BEGIN_UNKNOWN/RUNNING/UNKNOWN/REQUESTED/COMMITTED/CONFLICT：不 recover、不发 TC/Order/User 写 RPC，返回 202/既有 409。
6. Pay 已 SUCCESS/REFUNDED/FAILED 或并发状态变化：按既有 409/幂等语义返回，不覆盖状态。
7. TCC failure 与 16E cancel 并发：共用现有 Pay 行锁和事实校验；不创建 cancel 台账，不产生相反决议或半完成投影。

## 7. 明确 NO-GO

- 看到 execution 不存在、UNKNOWN 或 TC timeout 就推断 ROLLEDBACK。
- 用 bridge 镜像、receipt 状态、Order 状态或 rollback RPC 响应替代 User live CANCELLED/no-ledger 证明。
- 在外层 `@Transactional` 中执行 User live GET 或 Pay FAILED 投影。
- failure 入口调用 recover/begin/TC、Order.cancel、User refund，或创建/修改 `pay_tcc_cancel`。
- 缺证据先写 FAILED，或将 callback RECEIVED_FAILURE 直接当作已处理。
