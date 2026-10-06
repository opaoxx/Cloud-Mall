# CM-20261006-18：16C TCC 交付与唯一退款评审

## 1. 评审结论

结论：**GO，实施内部 TCC delivery worker 和持久交付台账；先修复 TCC 已确认账本对禁用账户的退款边界。**

本轮只衔接已经由同一 XID 确认的 Pay receipt 和 User TCC 事实。跨服务不能声称使用同一数据库快照：Pay 侧只读核对本地 execution/receipt/Fence 一致快照，再通过 User 只读接口读取完整 TCC tuple；不能把 `bridge.recover` 的缓存/镜像终态当作 User live GET。

- 两端均 `CONFIRMED` 且 User 有匹配 `balance_ledger(DEBIT,APPLIED)` 后，才允许调用 Order.paid。
- Order 明确 `PAID/COMPLETED + stockActionStatus=SUCCEEDED` 后，交付成功。
- Order 明确 `CANCELLED + stockActionStatus=SUCCEEDED` 后，才允许唯一 `refund:<payNo>` 退款。
- `ROLLEDBACK`、UNKNOWN、库存动作未成功或状态冲突均不得交付、不得退款。

公开支付入口继续 LEGACY；现有 `PayConsistencyService` 的 Legacy guard 和恢复器不改、不混用。16C 只增加独立 TCC delivery service、台账和受控内部恢复任务。

当前 User `refundInternal` 使用 `selectActiveForUpdate`，禁用用户会阻挡已确认资金退款，这是本批必须先修的前置边界。TCC 已确认账本退款应锁定存在的用户行，不要求用户仍 active；仍必须保留原扣款载荷、唯一退款键、原账本 APPLIED 和退款上限校验。该变化不允许绕过余额/账本事务，也不允许 Legacy 支付猜退款。

## 2. 范围与排除

### 纳入

- `pay_tcc_delivery` 交付台账、PO、Mapper、独立 Service 和有界恢复扫描。
- Pay TCC 两端终态完整证据核对。
- Order paid 幂等调用、只读 payment-state 核对和 stock action 最终状态判断。
- 唯一 `refund:<payNo>` User 退款，包含禁用账户已确认 TCC 账本边界。
- 进程中断、Feign 响应未知、Order/Stock 恢复和退款响应未知的持久恢复事实。

### 排除

- `/api/payments`、Legacy `PayConsistencyService`、Legacy pay intent 和 Legacy 自动恢复路径。
- 新的公网接口、Order/Stock 数据模型改造、Seata AT/TCC 重新编排。
- `ROLLEDBACK` 订单交付、未知状态下的补偿猜测、非唯一退款键。

## 3. 交付台账

```sql
CREATE TABLE IF NOT EXISTS pay_tcc_delivery (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  state VARCHAR(32) NOT NULL,
  refund_operation_key VARCHAR(128) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NOT NULL,
  lease_owner VARCHAR(128),
  lease_until DATETIME(3),
  last_error VARCHAR(512),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pay_tcc_delivery_pay(pay_no),
  UNIQUE KEY uk_pay_tcc_delivery_xid(xid),
  UNIQUE KEY uk_pay_tcc_delivery_refund(refund_operation_key)
);
```

允许状态：

- `READY`：仅表示已从本地 COMMITTED execution 派生的待核验交付意图，不表示远程 User/Order 已确认。
- `ORDER_SYNC_UNKNOWN`：Order paid 响应或状态查询未知。
- `REFUND_PENDING`：已确认订单取消和库存回滚，退款请求结果未知。
- `SUCCESS`：订单 PAID/COMPLETED 且库存动作 SUCCEEDED。
- `REFUNDED`：唯一退款已 APPLIED。
- 持久矛盾记录在 `last_error` 并退避，保留当前业务状态；不把网络失败永久写成 CONFLICT。

`refund_operation_key` 固定为 `refund:<payNo>`，不可改变。状态只能单向推进；UNKNOWN 不覆盖 SUCCESS/REFUNDED，不能把 ROLLEDBACK 重新置 READY。

## 4. 交付种子与每次 RPC 前核对

首轮只在本地 Pay 事务内从 `pay_tcc_execution.state=COMMITTED` 派生 READY 交付意图，按 payNo 唯一插入；不要求此时 User 可用，也不在该事务内调用 User/Order RPC。ROLLEDBACK execution 直接跳过，不建 job。

每次 Order 或退款 RPC 前都必须重新核对 Pay 本地 proof 和 User live GET 的完整 TCC tuple：

1. `pay_tcc_execution.state=COMMITTED`、decision=COMMIT、XID 与请求一致。
2. Pay receipt 为 `CONFIRMED`，branch/XID/载荷一致，Pay Fence/永久 receipt 证据可核验。
3. User bridge GET 返回 `CONFIRMED`，User branch/XID/载荷一致，`ledgerApplied=true`。
4. User APPLIED DEBIT 的 operationKey 为 `payNo`，金额、用户和订单一致。

任一条件缺失或 Unknown 时保留 READY/当前重试状态，记录错误并退避；不调用 Order、不退款。READY 不代表远程证据已经齐全。

## 5. Order/Stock 交付状态机

### 5.1 支付交付

1. 以 `pay_no` 唯一认领 READY 台账，短事务写租约后在事务外先 GET Order payment-state。
2. 只有订单仍为 PENDING_PAYMENT 才调用 `OrderClient.paid(orderNo,userId)`；paid POST 无论返回成功、失败或超时，都必须再次 GET payment-state。
3. 只有 `PAID/COMPLETED + stockActionStatus=SUCCEEDED` 才写 SUCCESS；PAID 但 stock pending/unknown 只能等待，不得退款。
4. HTTP/Feign 超时、空响应或状态未知写 ORDER_SYNC_UNKNOWN，保留原 payNo/XID，后续只读查询/幂等 paid，不创建新支付键。Order CANCELLED 只有再次 GET 确认 `CANCELLED + SUCCEEDED` 才允许退款；库存动作 PENDING/UNKNOWN/CONFLICT 时保持 UNKNOWN。

### 5.2 唯一退款

付款交付前必须先读取 User DEBIT operation；若 `refundOperationKey` 已存在任何退款事实，禁止再次调用 Order.paid，必须等待/确认订单 CANCELLED + stockActionStatus=SUCCEEDED 后，使用固定 `refund:<payNo>` POST 完整校验并 GET 核验结清。

仅在明确 `CANCELLED + stockActionStatus=SUCCEEDED` 且 User 原扣款 APPLIED 时调用 User refund：

- `refundOperationKey=refund:<payNo>`；
- `originalOperationKey=payNo`；
- payNo/orderNo/userId/amount 必须与原 TCC receipt 和账本一致；
- 重复请求返回同一 APPLIED 退款事实；
- 退款总额不得超过原扣款；
- 响应未知写 REFUND_PENDING，只能用同键查询/重试。

退款成功后写 REFUNDED；退款失败、响应未知或持久矛盾保留 REFUND_PENDING 并记录错误退避，禁止换退款键或再次增加余额。已有 User refund GET 只返回 operation/type/state/amount，不能仅凭 GET 判定完整退款成功；每次恢复必须先用同一 `refund:<payNo>` POST，让 User 校验原订单/用户/金额，再 GET 核验退款账本。

## 6. User TCC 退款前置修复

### 6.0 退款响应字段兼容阻断

当前 User `BalanceRefundVO` 的 JSON 字段为 `refundOperationKey`，而 Pay `UserClient.refund` 沿用 `BalanceView.operationKey` 反序列化。退款写入可能已经成功，但 Pay 因 `refund.data.operationKey()` 为空而持续保持 `REFUND_PENDING`，阻断后续 delivery 收敛。

最小修复：在 Pay `UserClient.BalanceView` 的 `operationKey` record component 上增加 Jackson `@JsonAlias("refundOperationKey")`，同时兼容原扣款响应的 `operationKey` 和退款响应的 `refundOperationKey`，不改变任何线上 JSON 输出字段和 Legacy 业务线路。补充真实 JSON 反序列化契约测试，至少覆盖两个字段名、APPLIED 状态和金额。

修复后必须使用原 payNo、原 `refund:<payNo>` 键重新调用真实退款恢复链路，验证 Pay 读到同一退款操作键、状态 APPLIED、delivery 进入 REFUNDED，再检查余额/账本并清理隔离测试库。禁止手工改资金或账本绕过该契约。

现有 User `refundInternal` 的锁用户步骤必须针对匹配的 TCC confirmed 账本允许锁定存在用户，因为已确认资金必须支持账户被禁用后的退款释放：

```sql
SELECT * FROM mall_user WHERE id=? FOR UPDATE;
```

仅当原扣款对应 TCC `balance_debit_attempt(mode=TCC,state=CONFIRMED)`、原 `balance_ledger(DEBIT,APPLIED)`、用户/订单/金额全部匹配时，禁用用户才允许退款；禁用 Legacy 原扣款仍保持原 404 行为，active Legacy 退款行为不变。所有路径仍必须执行：

- 原扣款账本存在且 `DEBIT/APPLIED`；
- user/pay/order/amount 与退款请求完整一致；
- 唯一 refundOperationKey 幂等；
- 已退款总额不超过原扣款；
- 用户余额加回和 REFUND ledger 同一本地事务；
- TCC 退款不能删除原 DEBIT，不能修改原账本状态。

若原扣款不存在、为 REJECTED、金额不一致或原键不是 TCC confirmed 账本，返回冲突，不改变余额。该边界修复不改变 Legacy 业务的 pay guard，Pay Legacy 恢复器仍不调用 TCC delivery。

## 7. 恢复与并发

- Java 契约固定为 `PayTccDeliveryService.deliver(String payNo)` 和 `PayTccDeliveryScheduler.recoverDue()`；配置 `cloudmall.pay.tcc-delivery.enabled=false` 默认关闭，开启时必须依赖 coordinator、receipt、User bridge、Seata 和未代理 Pay DataSource。
- 首轮扫描先把已 COMMITTED execution 的原 payload 种成 READY delivery job；后续只扫描到期 job，每批最多 20 条、单线程、10 秒预算、随机 token lease 60 秒，退避 5..300 秒，attempt 饱和 31。
- delivery worker 每实例单线程、每批最多 20 条；短事务 claim lease，RPC/Feign 在事务外执行。
- lease 只保护交付调度，不改变 execution/receipt/User ledger 决议；过期旧请求可同 operationKey 幂等重叠。
- READY/ORDER_SYNC_UNKNOWN 只调用 Order GET/paid/payment-state；REFUND_PENDING 每次先用同一 refund key POST，再 GET 核验完整账本事实。
- `SUCCESS/REFUNDED` 不再自动推进；ROLLEDBACK execution 不创建 job；持久矛盾只记录 error 和退避，保留当前状态。
- PayRecord 的 SUCCESS/ORDER_SYNC_UNKNOWN/REFUND_PENDING/REFUNDED 更新必须与 delivery 台账状态在同一 Pay 本地事务中完成；重试只能同向推进，不能从 REFUND_PENDING 回退到普通交付状态。
- 任何 Unknown 都保留原 XID、payNo、receipt、ledger 和 Order/Stock 状态，不凭 TC Unknown 或 HTTP 失败猜成功/退款。

## 8. 验收矩阵

1. 两端 CONFIRMED、User APPLIED ledger、Order PAID+stock SUCCEEDED → SUCCESS。
2. 两端 CONFIRMED、Order CANCELLED+stock rollback SUCCEEDED → 唯一 refund APPLIED → REFUNDED。
3. 两端 ROLLEDBACK → ROLLEDBACK_SKIPPED，无 Order paid、无退款。
4. User/Pay 任一端 UNKNOWN、Order/Stock UNKNOWN/CONFLICT/PENDING → 不交付、不退款。
5. Order paid 响应丢失、重试和恢复 → 同 orderNo 幂等，最终只成功一次。
6. 退款响应丢失、重复退款和进程重启 → `refund:<payNo>` 只产生一条 APPLIED REFUND。
7. 禁用用户 TCC confirmed 退款 → 余额释放一次，退款账本 APPLIED，原 DEBIT 保留。
8. 两个 delivery 实例竞争、旧 lease 迟到完成 → 状态 CAS 和唯一键阻止双交付/双退款。
9. Legacy 支付、Legacy refund、PayConsistencyService 回归保持通过；公开入口仍不进入 TCC delivery。

## 9. 边界

- **GO**：内部 TCC delivery worker、Order/Stock 最终事实门控、唯一 TCC refund 和禁用账户退款前置修复。
- **NO-GO**：公网支付切换、Legacy/TCC 混用、ROLLEDBACK 退款、Unknown 退款、Order/Stock 事务改造和完整生产闭环。
