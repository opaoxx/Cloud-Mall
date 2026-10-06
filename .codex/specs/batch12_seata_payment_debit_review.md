# CM-20261006-18：Pay/User TCC 扣款切片规格评审

## 1. 评审结论

本轮结论为：**Pay/User 扣款切片设计 GO；12A“持久路由及隔离保护”可实施，12B/12C 真实 TCC 扣款本轮 NO-GO。**

现有 `PayConsistencyService` 先提交 `pay_debit_intent`，再通过 Feign 调用 User 的本地扣款事务。User 当前在用户行锁内直接减少 `mall_user.balance` 并写 `balance_ledger(DEBIT, APPLIED)`；Pay 通过 `payNo` 查询账本事实后推进 Order。该模型已经承担支付结果未知、超时、重复回调和退款恢复，不能在入口简单添加 `@GlobalTransactional`，也不能用 AT undo 删除已经观察到的资金账本。

后续真实迁移只选择“Pay 扣款接收事实 + User 余额冻结/确认/释放”这一纵向切片。Order/Stock 继续使用现有本地事务、intent、outbox、UNKNOWN 和补偿模型。Pay/User 采用 Seata 1.5.2 TCC；两服务的该入口必须关闭 AT 数据源代理，避免 TCC 资金事实被 AT undo 误处理。现有 LEGACY 交易不迁移，默认为 `LEGACY`；只有明确开关开启且支付入口创建的新 intent 才使用 `TCC`。

本轮允许落地的最小阶段是 12A：在 Pay 两张现有表和支付路由上持久化 `LEGACY/TCC` 模式，默认只允许 `LEGACY`，并对尚未实现的 TCC 模式执行 fail-fast 隔离。这样未来打开配置不会把 TCC 意图默默降级为普通扣款。12A 不新增冻结余额、TCC receipt 或 TCC HTTP 端点，也不改变现有 LEGACY 业务行为。真实 TCC 进入 12B 前，backend 必须查本地 Seata 1.5.2 JAR 和官方源码确认回调签名与 Fence 行为。

## 2. 本轮目标和边界

### 2.1 纳入 TCC 的事实

| 服务 | TCC 事实 | Try | Confirm | Cancel |
| --- | --- | --- | --- | --- |
| Pay | `pay_debit_receipt` | 以 `operation_key/xid` 建立扣款接收记录，状态 `TRYING`，校验 payNo、订单、用户、金额和唯一尝试归属 | 将接收记录置为 `CONFIRMED`，只记录事实，不再次扣款 | 将 `TRYING` 置为 `CANCELLED`；若已确认不得覆盖 |
| User | `balance_tcc_debit` | 锁定用户行，按可用余额减少 `balance`，增加 `frozen_balance`，写 `TRYING` 冻结记录 | 记录 `CONFIRMED`，并且只插入一条 `balance_ledger(DEBIT, APPLIED)`；确认阶段不得再次减少可用余额 | 置为 `CANCELLED`，锁定用户行释放冻结额；不得写已完成的资金账本，不得将已确认记录回滚 |

`mall_user.balance` 继续表示可用余额；`frozen_balance` 只用于展示和扣款可用性计算。若当前表不存在冻结字段，必须增加 DECIMAL(18,2) `frozen_balance NOT NULL DEFAULT 0.00`，并先做一次历史数据回填为 0。当前 LEGACY 扣款仍直接减少 `balance`，不能把历史账本转换成 TCC 冻结记录。

### 2.2 不纳入 TCC 的事实

- Order 订单状态、订单月表、`order_stock_action`、`order_reservation_intent`。
- Stock 库存数量、库存流水、Redis 库存投影和恢复任务。
- RabbitMQ、Redis 缓存、购物车、ES、日志和监控。
- 已存在的 `balance_ledger` DEBIT/REFUND 行及历史 `pay_debit_intent`。

TCC Confirm 之后，沿用现有 Pay → Order.paid 和 Order → Stock CONFIRM 的流程；Order 同步失败进入现有 `ORDER_SYNC_UNKNOWN`，不能由 TCC 回调猜测订单决议。取消路径必须先确认 Order 已取消且 Stock 回滚成功，再创建现有 `refund:<payNo>` 反向账本；TCC Cancel 只处理仍在 `TRYING` 的冻结。

## 3. 阶段计划和准入条件

### 阶段 12A：持久路由及隔离保护（本轮 GO）

1. 为 `pay_record` 增加 `debit_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY'`，为 `pay_debit_intent` 增加同名字段及默认值。历史行回填为 `LEGACY`；`PayDebitIntentService.ensureIntent` 必须从对应 `pay_record` 复制并校验模式，不得因未来配置变化改写旧 intent。
2. 新建支付记录时冻结当前配置模式。本阶段配置只能为 `LEGACY`；配置为 `TCC` 或未知值时 Pay 启动 fail-fast，避免服务运行后接收无法执行的 TCC。配置缺失按 `LEGACY` 处理，且启动配置检查仍须明确记录。
3. `PayConsistencyService` 在 claim 之前读取并校验持久化 `debit_mode`，并在任何 User/Order 调用之前再次由已认领 intent 校验。`LEGACY` 才能进入现有扣款/退款路径；`TCC` 或未知模式返回 `PAY_DEBIT_MODE_UNAVAILABLE`、HTTP 503（内部调用/回调结果未知），不调用 LEGACY 扣款或退款，也不修改 lease、attempt、status 或重试时间。
4. 补充 Mapper/PO、迁移兼容测试、旧数据测试、重复 ensureIntent 模式冲突测试和 route isolation 测试。必须证明默认 LEGACY 入口与现有 PayConsistencyService 行为相同。
5. 保存本地 Seata 1.5.2 JAR 证据：已核验 `@LocalTCC`、`@TwoPhaseBusinessAction(useTCCFence=true)` 存在；官方 Fence 说明为 <https://seata.apache.org/blog/seata-tcc-fence/>。实现阶段必须使用 Fence；空 rollback 可能只写 `SUSPENDED` 而不调用业务 Cancel，因此恢复器须读取 Fence 证据，不能等待业务 receipt 永远变为 UNKNOWN。

阶段 12A 完成标准：两张现有表迁移可重复执行；旧 pay_record/intent 均解析为 LEGACY；新建记录只接受 LEGACY；TCC/未知配置 fail-fast；持久化模式不一致时返回 503 且不走普通扣款、不修改恢复租约；全现有支付契约和回归测试保持通过。12A 完成不能将 CM-18 标为 closed。

### 阶段 12B：TCC Try/Confirm/Cancel 实现（下一实施阶段）

只实现 Pay/User 新 TCC 入口，保持现有 `/api/payments` 请求和 Pay `success/callback` 外部契约不变。新支付入口按已冻结的模式写入 intent；存量 intent 的空值统一视为 `LEGACY`，不得把已有 LEGACY intent 自动迁移。Pay 在 global body 开始后、调用两端分支前独立保存 `payNo → XID` 外部锚点；Pay receipt Try 先于 User Try。每个 XID/资源最多注册一个对应分支。

阶段 12B 必须先补单元测试，再做真实 Seata 1.5.2 两服务联调。正常提交、业务拒绝、重复 Try、重复 Confirm、重复 Cancel、Confirm 后 Cancel、空回滚、悬挂和回调顺序错乱均需在本地状态机层通过。

### 阶段 12C：真实故障验收（闭环条件）

必须使用真实 Seata 1.5.2 TC 和 Pay/User 进程，验证：Try 后 Pay/User 任一进程中断、TC 暂失、响应丢失、重复回调、Confirm/Cancel 延迟、同一 payNo 多次支付尝试。最终检查 `mall_user.balance`、`frozen_balance`、`balance_tcc_debit`、`balance_ledger`、`pay_debit_receipt` 和 Pay intent，证明无双扣、无负余额、无冻结泄漏、无重复 APPLIED 账本。

## 4. 数据库契约

### 4.0 12A 现有 Pay 表模式迁移（本轮唯一数据库变更）

```sql
ALTER TABLE pay_record
  ADD COLUMN debit_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE pay_debit_intent
  ADD COLUMN debit_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
UPDATE pay_record SET debit_mode='LEGACY' WHERE debit_mode IS NULL OR debit_mode='';
UPDATE pay_debit_intent SET debit_mode='LEGACY' WHERE debit_mode IS NULL OR debit_mode='';
```

实际迁移必须先检查列是否存在后再执行 `ALTER`，保证本地初始化器和已有数据库重复启动不会失败。允许值暂时为 `LEGACY/TCC`，但 12A 运行时只接受 `LEGACY`；TCC 值只作为未来迁移和隔离测试数据，不得进入现有扣款路径。

`pay_record.debit_mode` 是支付记录的模式事实；`pay_debit_intent.debit_mode` 是恢复路由的不可变快照。两者不一致、缺失或未知时，Pay 返回 `PAY_DEBIT_MODE_UNAVAILABLE`（HTTP 503），并停止当前尝试。该响应表示支付结果未知/路由不可用，调用方应按原 payNo 查询和恢复，不得换新 Idempotency-Key 重新扣款。

以下三张 TCC 表和 `mall_user.frozen_balance` **不属于 12A 变更**，只能在 12B 重新评审并冻结真实 Try/Confirm/Cancel 回调后新增：

### 4.1 Pay `pay_debit_receipt`

```sql
CREATE TABLE IF NOT EXISTS pay_debit_receipt (
  id BIGINT PRIMARY KEY,
  operation_key VARCHAR(128) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  pay_no VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  mode VARCHAR(16) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pay_debit_receipt_operation(operation_key),
  UNIQUE KEY uk_pay_debit_receipt_xid(xid),
  UNIQUE KEY uk_pay_debit_receipt_pay_no(pay_no),
  KEY idx_pay_debit_receipt_state(state, updated_at)
);
```

`operation_key` 固定为 `tcc-debit:<payNo>`，不会因重试生成新键；`xid` 是 Seata 事务标识，仅用于回调幂等和审计。`payNo` 唯一保证一个支付记录只能有一个活动扣款尝试。若业务确实允许重新支付，必须生成新的 payNo/Idempotency-Key，并且旧尝试已进入明确终态；不得在同一 payNo 下并行第二笔扣款。

### 4.2 User `balance_tcc_debit`

```sql
CREATE TABLE IF NOT EXISTS balance_tcc_debit (
  id BIGINT PRIMARY KEY,
  operation_key VARCHAR(128) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  pay_no VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  state VARCHAR(16) NOT NULL,
  balance_before DECIMAL(18,2) NOT NULL,
  balance_after_try DECIMAL(18,2) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_balance_tcc_operation(operation_key),
  UNIQUE KEY uk_balance_tcc_xid(xid),
  UNIQUE KEY uk_balance_tcc_pay_no(pay_no),
  KEY idx_balance_tcc_user_state(user_id, state, updated_at)
);
```

`state` 只允许 `TRYING`、`CONFIRMED`、`CANCELLED`。TRY 余额不足时不创建可确认的冻结，返回业务拒绝；若需要记录拒绝审计，单独使用拒绝表/现有 LEGACY ledger，不能伪造 `TRYING`。确认后 `balance_ledger` 的 `operation_key` 仍使用 `payNo`，保证现有 Pay 查询和退款键契约不变；插入前必须以唯一键幂等校验金额、用户、订单和状态。

### 4.3 `mall_user` 冻结字段

```sql
ALTER TABLE mall_user
  ADD COLUMN frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00;
```

Try 的行锁更新必须满足 `status=1`、`balance >= amount`，执行 `balance = balance - amount, frozen_balance = frozen_balance + amount`；Cancel 执行反向释放，且必须满足 `frozen_balance >= amount`。Confirm 只减少 `frozen_balance`，不改变 `balance`，随后写入 APPLIED 账本。任何条件更新影响行数不是 1 都进入未知/冲突，不允许再次盲目重试扣款。

## 5. API、Feign 和事务契约

### 5.1 Pay → User TCC 内部接口

12A 不新增这些 TCC HTTP/Feign 端点。它只冻结 `PAY_DEBIT_MODE_UNAVAILABLE` 协议：当持久化模式为 `TCC`、未知值或 Pay record 与 intent 不一致时，内部接口和回调处理返回 HTTP 503；不得退回 `/balance/debits` 的 LEGACY 扣款或退款。调用方应保留同一 payNo 等待后续恢复。该错误不推进 attempts、lease、status 或 next_retry_at，避免未实现的模式被错误标记为失败。

新接口建议保持内部路径隔离：

- `POST /api/internal/users/{userId}/balance/tcc-debits/try`
- `POST /api/internal/users/{userId}/balance/tcc-debits/confirm`
- `POST /api/internal/users/{userId}/balance/tcc-debits/cancel`
- `GET /api/internal/users/{userId}/balance/tcc-debits/{operationKey}`（仅恢复/审计，TRYING 返回 `UNKNOWN` 语义给外部编排）

请求必须包含 `operationKey、xid、payNo、orderNo、amount`；`X-User-Id` 继续由内部鉴权校验。Confirm/Cancel 还必须携带 Seata 分支所需的上下文，由实现按本地 JAR API 绑定，禁止信任客户端 body 伪造 XID。

返回统一 `ApiResponse`：Try 返回 `TRYING` 或业务拒绝；Confirm 返回 `CONFIRMED` 和 `balance_ledger` operationKey；Cancel 返回 `CANCELLED`。重复同载荷调用返回原状态；同 operationKey 载荷不一致返回 409；已 Confirm 的 Cancel 返回 `CONFIRMED` 并记录冲突，不释放余额。

### 5.2 Pay 内部 TCC 收据接口

Pay 的 TCC 资源只允许由支付编排调用，不开放给前端：

- Try 创建或复用 `pay_debit_receipt`。
- Confirm 只把相同 payload 的 `TRYING` 改为 `CONFIRMED`。
- Cancel 只把相同 payload 的 `TRYING` 改为 `CANCELLED`。

Pay 入口仍先独立提交 `pay_debit_intent(INIT, debit_mode=TCC)`，然后在一个 Seata 全局事务中调用 Pay receipt Try 和 User debit Try。两边均 Try 成功后由 TC 调用 Confirm；全局失败由 TC 调用 Cancel。全局事务返回未知时，Pay 恢复器只能查询两端持久记录并按状态矩阵继续，不能因为 TC 历史已清除、`getStatus=Unknown` 就猜测 Confirm 或 Cancel。

### 5.3 Order 后续流程

只有 Pay/User 两端都明确 `CONFIRMED` 后，Pay 才将现有 intent 推进为 `DEBITED/ORDER_SYNC_UNKNOWN` 并调用 Order.paid。Order 仍由原有 CAS 和 Stock action 保证幂等。若 Order 已明确 CANCELLED 且 Stock action 已成功，不能再让新的 TCC 扣款进入 Try；若支付已经 Confirm，继续使用现有退款流程。

## 6. 空回滚、悬挂和重复回调规则

| 场景 | Pay receipt | User TCC debit | 结果 |
| --- | --- | --- | --- |
| Cancel 先于 Try（空回滚） | 创建 `CANCELLED` 防悬挂标记或记录拒绝回调 | 创建 `CANCELLED` 防悬挂标记，不减少余额 | 后续 Try 看到同 operationKey 直接拒绝，不冻结 |
| Try 已取消后迟到 | 保持 `CANCELLED` | 保持 `CANCELLED` | 返回幂等取消，不执行 Try |
| Confirm 重复 | `CONFIRMED` 不变 | `CONFIRMED` 不变，不能重复写 APPLIED | 返回原确认结果 |
| Cancel 重复 | `CANCELLED` 不变 | `CANCELLED` 不变，不能再次加回余额 | 返回原取消结果 |
| Confirm 后 Cancel | 保持 `CONFIRMED` | 保持 `CONFIRMED`，不释放余额 | 报告冲突，交恢复器核对两端 |
| Try 重复且载荷一致 | 返回当前状态 | 返回当前状态 | 不重复扣减 |
| Try 重复且载荷不同 | 409 | 409 | 标记数据冲突，停止自动推进 |
| TC/服务响应丢失 | 记录保留 `TRYING` | 记录保留 `TRYING` | 查询持久状态后再由恢复器处理 |

操作键、XID、payNo 三者均需持久化。恢复器不得只依据 Redis、HTTP 202、进程内状态或 TC 已清理后的 Unknown 决定资金结果。

## 7. LEGACY、缓存和并发边界

- `pay_debit_intent.debit_mode` 新增 `LEGACY/TCC`。NULL、旧行和历史支付一律解释为 `LEGACY`；只允许新建 intent 时按配置写入 TCC。
- LEGACY 入口继续调用现有 `/balance/debits`，继续写 `balance_ledger(DEBIT, APPLIED)` 并直接减 `balance`；不能读取或改写 TCC 冻结。
- TCC Try 期间用户可用余额为 `balance`，账户总余额展示为 `balance + frozen_balance`，可用余额展示为 `balance`。前端和现有 `/users/me` VO 必须明确字段语义后再改响应；本阶段不得静默把冻结额混入可用余额。
- TCC Confirm/Cancel 不依赖 `balance:debit:*` Redis key。缓存只能在全局提交之后投影，失败可重建；缓存缺失不能触发重新 Try。
- 同一用户并发多笔 TCC Try 通过 `select ... for update` 和可用余额条件更新串行化；同一 payNo 只允许一个 operationKey。余额不足的第二笔应明确拒绝，不能等待并重试造成重复扣款。
- Pay 多次回调必须复用同一 payNo 和 operationKey；新的 Idempotency-Key 只有在上一笔明确 FAILED/REFUNDED 且业务允许重新支付时才能生成新 payNo。

## 8. 验收矩阵和闭环标准

实现完成前至少通过以下矩阵：

1. TCC 正常提交：Pay/User 两端 `TRYING → CONFIRMED`，一条 APPLIED 账本，余额可用额减少一次，冻结归零。
2. 业务失败：余额不足不生成可确认冻结，Pay 最终 FAILED，余额与账本不变。
3. Try 后 Pay/User 进程中断：恢复后重复回调只收敛一次。
4. TC 暂失和响应丢失：不依据超时猜测结果，持久状态恢复后继续正确回调。
5. 空回滚和悬挂：Cancel 先到时不产生负冻结，迟到 Try 不扣余额。
6. Confirm/Cancel 重复和乱序：无双扣、无双释放，Confirm 后 Cancel 不改变资金。
7. 同 payNo 并发回调：只有一个 TCC operationKey，只有一条 APPLIED DEBIT。
8. LEGACY 回归：旧 intent、旧回调、退款和 PayConsistencyService 现有测试行为不变。
9. 真实 Seata 1.5.2：检查 TC 分支记录、Pay/User 持久记录、余额、冻结、账本和最终 Order/Stock 状态。

闭环要求是所有资金断言均以 MySQL 持久事实为准；Redis、HTTP 响应和 TC 历史查询只能作为辅助证据。任何出现余额负数、冻结泄漏、两条 APPLIED、操作键载荷冲突未阻断、或 Unknown 被猜决议的情况，CM-18 仍保持未闭环。

## 9. 本轮工作状态

- 规格文件：本文件。
- 评审结论：**12A 持久路由及隔离保护 GO；12B/12C 真实 TCC 扣款 NO-GO，需在 Fence 和真实回调契约冻结后实施。**
- 本次规格修订未写业务实现；backend 可按本文件实施 12A。12A 完成后 CM-18 仍保持进行中。
- 现有 CM-18 独立 AT demo：继续保留，不能代表真实支付迁移已完成。
- 版本策略：backend 实现阶段必须单独提交，提交前执行本规格的单元、构建、真实 TC 故障矩阵；不要把本规格与实现混在一个提交中。
