# CM-20260925-24 秒杀订单最终配额结算规格评审

## 1. 评审结论

结论：**规格评审 GO，实施前置条件满足；实现和故障注入完成前，CM-24 仍为 NO-GO。**

本轮只处理秒杀订单已经进入 `seckill_reservation=ACCEPTED` 后的最终配额结算：

- 支付成功通过既有 `confirm` 动作确认秒杀配额已经成交；
- 手工取消和超时关单通过既有 `rollback` 动作释放秒杀活动的可售配额；
- 取消释放只恢复活动总配额，**不减少用户累计购买/限购计数**，也不删除原受理 marker；
- 受理阶段的 `ACCEPTED` 语义保持不变，不把它改成支付成功；
- 不新增前端 API，不把秒杀结算伪装成普通库存流水，不改普通商品 `stock_sku` 数量。

当前实现的缺口是：`StockServiceImpl.confirm/rollback` 只查普通 `stock_reservation_request` 和 `stock_flow`，秒杀受理只留下 Redis `seckill:stock`、`seckill:user`、`seckill:accepted` 以及 `seckill_reservation=ACCEPTED`；支付、取消、超时产生的 `order_stock_action` 无法改变秒杀配额。因此取消后活动配额不会恢复，支付后的受理事实也没有独立终态。`StockConsistencyReconciler` 还会在 `seckill:stock` 缺失时按 `stockLimit-count(ACCEPTED)` 无条件重建，这会和 Lua 成功后数据库仍为 `PENDING/UNKNOWN`、以及取消释放操作竞争，产生少扣或双回补风险。

## 2. 真实调用链与不变量

### 2.1 现有链路

1. `OrderServiceImpl.seckill` 调用 `StockClient.seckill`。
2. `StockServiceImpl.seckillAfterSentinel` 以 Redis Lua 原子检查活动窗口、`seckill:stock:{activityId}:{skuId}` 和 `seckill:user:{activityId}:{userId}`，成功后 `DECR stock`、`INCRBY user 1`、写入 `seckill:accepted:{activityId}:{skuId}:{userId}:{idempotencyKey}`。
3. Stock 在本地事务中写 `seckill_reservation=ACCEPTED` 和 `seckill_order_outbox`；`ACCEPTED` 只表示配额受理和可靠消息事实已提交。
4. Order 消费消息后在本地事务中写订单、`seckill_order_dedup=COMMITTED` 和 `ORDER_TIMEOUT` outbox。
5. `OrderServiceImpl.paid` 写 `PENDING_PAYMENT → PAID`，再落 `order_stock_action(CONFIRM)`；`cancel` 和 `consumeTimeout` 写 `PENDING_PAYMENT → CANCELLED`，再落 `order_stock_action(ROLLBACK)`。
6. `OrderStockActionRecovery` 调用 `StockClient.confirm/rollback`，再读取 `StockClient.reservation` 判断最终状态。`PayConsistencyService` 只有看到订单支付状态和库存动作均成功时才结束支付。

### 2.2 结算不变量

- 每个 `orderNo` 最多对应一个秒杀受理事实和一个最终结算事实；同一 `(userId,idempotencyKey)` 仍由原全局唯一约束保护。
- `ACCEPTED`、`OPEN`、`RELEASE_PENDING` 和 `PROJECTION_BLOCKED` 都视为配额已占用；只有持久 `RELEASED` 才从活动可售配额中释放；`CONFIRMED` 永久占用。
- 秒杀配额结算不会写 `stock_flow`，不会更新 `stock_sku.available_quantity/reserved_quantity/sold_quantity`。
- 取消/超时只允许从 `OPEN` 或保留 rollback 方向的 `PROJECTION_BLOCKED` 在已确认订单取消事实后进入释放流程；支付确认只允许从 `OPEN` 或保留 confirm 方向的 `PROJECTION_BLOCKED` 进入 `CONFIRMED`。
- `RELEASED` 与 `CONFIRMED` 是互斥终态。相反动作必须返回冲突并记录告警，禁止补发相反 Redis 操作。
- Redis 是秒杀可售配额投影，不是结算事实源；MySQL 结算表和受理表是事实源。
- 用户累计限购计数 `seckill:user:{activityId}:{userId}` 永不因取消/超时递减；同一幂等键的 `seckill:accepted` marker 保留到活动结束及恢复保留期结束，不能因释放删除。

## 3. 状态机与动作语义

新增独立结算状态，不改变 `seckill_reservation.status`：

```text
OPEN ── confirm ──> CONFIRMED
OPEN ── rollback ─> RELEASE_PENDING ──> RELEASED
RELEASE_PENDING ──Redis结果未知──> RELEASE_PENDING
任意投影故障 ──> PROJECTION_BLOCKED ──恢复扫描──> 原动作方向
```

`seckill_reservation=ACCEPTED` 仍表示受理；订单创建完成后可由对账补充 `ORDER_CREATED`，但不把它直接当作 `CONFIRMED`。

### 3.1 confirm

- 按 `orderNo` 锁定结算行，校验调用操作键为 `orderNo:CONFIRM`。
- `OPEN`、`RELEASE_PENDING` 以外的投影阻塞状态在订单动作方向明确时可恢复；`OPEN` 只有在订单事实为 `PAID` 或 `COMPLETED`、且没有已知取消事实时才允许确认。实际订单状态校验由 order 侧动作状态保证，Stock 不跨库读取订单。
- 在同一 MySQL 事务中将状态 CAS 到 `CONFIRMED`，写结算审计字段；不写普通 `stock_flow`，不改变 `seckill:stock`、`seckill:user`。
- 存量 `ACCEPTED` 即使缺少 Redis op marker，也允许在订单已有确认事实时持久化为 `CONFIRMED`；这不会猜测 Redis 释放，也不会补写 marker。
- 已为 `CONFIRMED` 时返回幂等成功；已为 `RELEASED`/`RELEASE_PENDING` 时返回 `SECKILL_SETTLEMENT_CONFLICT`。

### 3.2 rollback

- 按 `orderNo` 锁定结算行。只有 `OPEN` 或已有 rollback 方向的 `PROJECTION_BLOCKED` 可以创建/恢复 `RELEASE_PENDING`；`CONFIRMED` 返回冲突；`RELEASED` 返回幂等成功。
- `OPEN → RELEASE_PENDING` 必须先独立提交数据库事务，固定 `revision=2` 和唯一 `operationKey=orderNo:ROLLBACK`，然后由事务提交后的恢复器执行 Redis Lua。Redis 回执丢失时保持 `RELEASE_PENDING`，只记录 `last_error` 并按相同 operationKey 重试，不改结算 revision，也不把动作方向降成无法判定的 `UNKNOWN`。
- Redis Lua 必须以唯一操作 marker 和期望 revision 原子完成：
  - 受理 Lua 成功时已经在同一原子脚本中初始化 `seckill:quota:op:{orderNo}` 为 `RESERVED|{orderNo}|{acceptanceRevision}`，并初始化或递增活动全局 revision；释放 Lua 只能匹配该 `RESERVED` marker 和当前结算 revision，或匹配同一 operationKey+结算 revision 的 `APPLIED` marker；
  - marker 已是匹配的 `APPLIED`：返回已应用，不再次 `INCR`；
  - marker 缺失、为其他 orderNo/operationKey/revision、配额 key 缺失、活动元数据缺失或全局 revision 缺失：不增加配额，保持 `RELEASE_PENDING` 并标记 `PROJECTION_BLOCKED`/待恢复；不得补写旧 marker；
  - 禁止用 `SET stockLimit-count` 与释放 `INCR` 并行修复同一活动。
- Redis 成功后，以 operationKey、结算 revision 和 Lua 返回的活动全局 `redis_revision` CAS `RELEASE_PENDING → RELEASED`；CAS 失败时重新读取结算事实，若已 `RELEASED` 不重复释放，若是相反终态则告警并进入 `CONFLICT`。
- Stock 的既有 `/reservations/{orderNo}/rollback` 保持响应结构，内部先按结算表分流；普通订单继续走原 `stock_reservation_request`。

### 3.3 重复与相反动作

| 已有状态 | confirm | rollback |
| --- | --- | --- |
| `OPEN` | 提交 `CONFIRMED` | 提交 `RELEASE_PENDING` 并执行唯一释放 |
| `PROJECTION_BLOCKED`（confirm方向） | 持久化确认，不写 Redis | `409 SECKILL_SETTLEMENT_CONFLICT` |
| `PROJECTION_BLOCKED`（rollback方向） | `409 SECKILL_SETTLEMENT_CONFLICT` | 恢复原 `RELEASE_PENDING` 方向 |
| `RELEASE_PENDING` | `409 SECKILL_SETTLEMENT_CONFLICT`，不确认 | 重试同一 operationKey，不改 revision |
| `CONFIRMED` | 幂等成功 | `409 SECKILL_SETTLEMENT_CONFLICT` |
| `RELEASED` | `409 SECKILL_SETTLEMENT_CONFLICT` | 幂等成功 |

## 4. 数据库设计与 Mapper 契约

### 4.1 `seckill_quota_settlement`

Stock 启动时以幂等 DDL 或正式迁移创建；禁止在请求线程隐式删除、重建或清空数据。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | `BIGINT PRIMARY KEY` | 本地 ID |
| `order_no` | `VARCHAR(64) UNIQUE NOT NULL` | 秒杀订单唯一结算键 |
| `activity_id` / `sku_id` / `user_id` | `BIGINT NOT NULL` | 固定受理维度，不可改写 |
| `idempotency_key` | `VARCHAR(128) NOT NULL` | 原受理幂等键 |
| `quantity` | `INT NOT NULL` | 本轮固定为 1，需 `>0` |
| `accepted_redis_revision` | `BIGINT` | 受理 Lua 同一原子脚本返回的活动全局 revision；旧 ACCEPTED 为空时不得猜测补 marker |
| `status` | `VARCHAR(32) NOT NULL` | `OPEN/RELEASE_PENDING/RELEASED/CONFIRMED/CONFLICT/PROJECTION_BLOCKED`；投影阻塞必须由 `operation_key` 保留原动作方向 |
| `revision` | `BIGINT NOT NULL` | 结算事实 revision；受理 OPEN 为 1，释放方向固定为 2；与活动全局 Redis revision 分离 |
| `operation_key` | `VARCHAR(160)` | 当前结算动作唯一键 |
| `redis_revision` | `BIGINT` | 受理/结算 Lua receipt 中观察到的活动全局 revision |
| `last_error` | `VARCHAR(512)` | 最近可恢复错误，不写敏感凭据 |
| `next_retry_at` | `DATETIME(3)` | 恢复时间 |
| `lease_owner` / `lease_until` | `VARCHAR(128)` / `DATETIME(3)` | 恢复器数据库租约 |
| `created_at` / `updated_at` | `DATETIME(3) NOT NULL` | Asia/Shanghai 语义 |

约束和索引：`uk_seckill_settlement_order(order_no)`、`uk_seckill_settlement_operation(operation_key)`、`idx_seckill_settlement_retry(status,next_retry_at,lease_until)`、`idx_seckill_settlement_activity(activity_id,sku_id,status)`。同一订单只允许一行；不允许通过更新维度字段修复冲突。

结算行必须在 `seckill_reservation` 从 `PENDING/UNKNOWN` 成为 `ACCEPTED` 的同一 Stock 本地事务中插入 `OPEN`，并保存受理 Lua receipt 的活动全局 `redis_revision`。实现可使用 `INSERT ... SELECT` 从已锁定的受理事实读取不可变维度，禁止请求线程另查后拼接可变字段。存量 `ACCEPTED` 行缺少结算记录时，reconciler 可在锁定原 ledger 后以 `INSERT ... SELECT` 补建 `OPEN`；这不改变 `ACCEPTED` 语义。

### 4.2 Mapper 语义

新增 Mapper 方法必须封装 SQL，Service 不嵌入 SQL：

- `findSettlementForUpdate(orderNo)`、`findSettlement(orderNo)`；
- `insertOpenIfAbsent(reservationFact)`，只允许从匹配的 `ACCEPTED` 受理事实补建；
- `transitionOpenToConfirmed(orderNo, operationKey, expectedRevision)`；
- `transitionToReleasePending(orderNo, operationKey, expectedRevision)`；
- `markReleased(orderNo, operationKey, redisRevision)`；
- `markProjectionBlocked(orderNo, operationKey, expectedRevision, error, nextRetryAt)`；恢复扫描必须保留 confirm/rollback 原动作方向；
- 带租约的 `findDue/claim/renew/finish/retry`；
- `countActiveSettlements(activityId,skuId)`：统计 `OPEN/RELEASE_PENDING/CONFIRMED`，不统计 `RELEASED`，并将 `PROJECTION_BLOCKED` 计为占用。

所有状态更新必须带原状态、revision 和 operationKey 条件；影响行数为 0 时必须重新读取事实，不能根据当前线程结果猜测成功或失败。

### 4.3 PO 与审计

新增 `domain.po.SeckillQuotaSettlementPO` 与对应 Mapper/SQL；PO 一一映射表字段。业务规则放在 `service`，恢复租约和 SQL 放在 Mapper。至少记录 `operation_key`、旧/新状态、revision、错误分类和时间；不另建普通 `stock_flow` 记录冒充成交或回滚。

## 5. Redis 投影与版本协议

保留现有受理 Key 兼容性。受理 Lua 必须在同一原子脚本中完成 quota 扣减、用户计数增加、accepted marker 写入、op marker 初始化和活动全局 revision 初始化/递增：

| Key | 语义 | 结算规则 |
| --- | --- | --- |
| `seckill:stock:{activityId}:{skuId}` | 活动可售数量 | 受理 Lua `DECR`；唯一取消释放 Lua `INCRBY 1` |
| `seckill:user:{activityId}:{userId}` | 用户累计受理/限购计数 | 只增不减，取消不得修改 |
| `seckill:accepted:{activityId}:{skuId}:{userId}:{idempotencyKey}` | 原受理 marker | 取消和支付都保留至恢复保留期结束 |
| `seckill:quota:op:{orderNo}` | 受理/结算 Redis 操作 marker | 受理时为 `RESERVED|orderNo|acceptanceRevision`；释放成功后为 `APPLIED|orderNo:ROLLBACK|settlementRevision|redisRevision` |
| `seckill:quota:revision:{activityId}:{skuId}` | 活动全局投影操作计数 | 只由受理/结算 Lua 原子初始化或递增；与单订单 `seckill_quota_settlement.revision` 不混用 |

现有 `seckill:stock` 是字符串，首版不得直接改为 hash，避免活动发布和旧 Lua 类型冲突。op marker、全局 revision 和 stock value 必须由同一 Lua 脚本原子维护。所有 quota 写入只能经过受理 Lua或唯一结算 Lua；复杂受控重建属于独立恢复流程，不纳入本轮实现。禁止 Java 侧 `get` 后 `set/incr`。

### 5.1 缺失 Key 的安全语义

这是本轮的硬门槛：

- `seckill:stock` 缺失时，取消释放不得 `INCRBY`，因为无法证明该 key 是否曾被受理 Lua 成功写入，也无法排除对账线程即将重建；结算保持 `PROJECTION_BLOCKED`，配额视为仍占用。
- 活动元数据、`seckill:accepted` marker、受理 op marker 或活动全局 revision 缺失时，结果同样进入 `PROJECTION_BLOCKED`，不自动释放、不删除用户计数；存量 ACCEPTED 缺 op marker 不能猜测补写。
- `StockConsistencyReconciler` 不得再对缺失的活动 quota key 无条件 `SET(stockLimit-countAccepted)`。缺失时只记录带 activity/SKU 的待恢复审计事实；活动保持关闭或不可售，直到受控重建任务在全量受理/结算事实、Lua 窗口和租约保护下完成。
- 受控重建必须另行评审活动级租约、全量事实快照和版本协议；本轮不实现，也不把它作为取消请求的补偿路径。

该策略优先保证不超卖和不双回补；Redis 丢失后的可售恢复由独立、可审计的后续恢复流程完成。

## 6. Order/Pay/Feign 契约兼容

- 不新增前端 API。`OrderStockActionRecovery` 继续以 `orderNo:CONFIRM`/`orderNo:ROLLBACK` 调用既有 Feign；Stock 根据结算表自动分流秒杀或普通库存。
- `GET /api/stock/reservations/{orderNo}` 对秒杀返回既有 `orderNo/status/items` 结构，`status` 映射为 `RESERVED`、`CONFIRMED` 或 `ROLLED_BACK`；`RELEASE_PENDING`、`PROJECTION_BLOCKED` 和未确认回执统一向旧调用方映射为 `UNKNOWN`，内部可增加 `scene=SECKILL`、`settlementStatus`、`revision`、`redisRevision`，旧调用方忽略新增字段。
- `CONFIRMED` 是支付动作成功的库存事实；`ROLLED_BACK` 是取消/超时释放成功的库存事实。对外兼容状态 `UNKNOWN`、内部 `RELEASE_PENDING` 和 `PROJECTION_BLOCKED` 均不能让订单动作标记 `SUCCEEDED`。
- `PayConsistencyService` 的现有条件保持：只有订单 `PAID/COMPLETED` 且 Stock 动作 `SUCCEEDED` 才结清支付；只有订单 `CANCELLED` 且 Stock 动作 `SUCCEEDED` 才允许退款。秒杀 rollback 未确认时禁止退款完成。
- `paid` 已提交后不得因 confirm 暂时未知回退订单状态；动作恢复器持续重试。cancel/timeout 只在订单仍为 `PENDING_PAYMENT` 时建立 rollback；CAS 失败不得创建相反动作。
- 对于订单尚未异步落库但 Stock 已 `ACCEPTED` 的事实，不能由 Stock 自行释放；先由既有消息恢复链路创建订单并产生超时 outbox。没有订单事实的历史受理进入 `PROJECTION_BLOCKED/人工核验`，不得凭猜测回补。旧 `order_stock_action=SUCCEEDED` 只做只读审计，不能自动猜测结算已完成。

## 7. 历史数据与迁移

1. 备份并审计现有 `seckill_reservation`、`seckill_order_outbox`、订单表、`order_stock_action` 和 Redis 活动 Key。
2. 停止旧版 Lua 发行者，先切换到同一版本的受理 Lua；旧 Lua 与新 Lua 不得并行写同一活动。对每条 `ACCEPTED/ORDER_CREATED` 受理记录补建 `OPEN` 结算行；缺少受理 op marker 时仍 fail-closed，不补写 marker。若订单已为 `PAID/COMPLETED` 且已有确认动作事实，可补到 `CONFIRMED`；若订单已 `CANCELLED` 且已有明确 `ROLLED_BACK` Stock 事实，可补到 `RELEASED`。
3. 缺少订单、状态冲突、缺少活动元数据、缺少 Redis marker 或无法确定是否已释放的记录标为 `PROJECTION_BLOCKED`，保留证据，禁止自动更改 quota；PROJECTION_BLOCKED 由恢复扫描持续处理，但不改变原动作方向。
4. 迁移完成前禁止增加 `uk_seckill_settlement_order` 之外的可变合并逻辑；同订单多事实不得静默选最新记录。

本次只读审计已确认两条存量 `ACCEPTED`：

- `202609ccf4f9a1616544f494ce4d29ec8e89ef`：订单已为 `COMPLETED`，`request_hash` 为空，无 `order_stock_action`；仅在订单归属、金额、订单状态日志和受理维度逐项核验通过后，补建 `OPEN` 并生成唯一 `CONFIRM` 动作，随后由 Stock 持久 `ACCEPTED` 事实确认配额。不得新建 outbox、修改订单状态或用户余额；不得因为终态名称直接猜测已确认。
- `2026097f032391ea0040ee91620a3471dea3a5`：订单已为 `PAID`，其余审计条件相同；按同一只读核验和补 `CONFIRM` 动作流程处理。

其余历史记录为 `REJECTED`，不补结算行、不改 Redis。历史 `ACCEPTED` 若只缺 marker、缺订单或状态证据不完整，全部保留 `PROJECTION_BLOCKED`，不得通用跨库自动猜测；上述两条是本轮唯一允许进入人工核验后补确认动作的明确清单。

## 8. 故障与并发测试门槛

必须由 backend/qa 在实现后完成并记录：

1. 受理后支付确认：只新增结算行和 `CONFIRMED`，不新增普通 `stock_flow`，Redis stock/user 数量不变化。
2. 受理后手工取消：stock 下降 1 后只恢复 stock 1，user 累计计数不变，accepted marker 不变；重复 rollback 不再增加 stock。
3. 超时关单与手工取消并发：只有一个 `RELEASE_PENDING`/Redis operation marker，最终 `RELEASED`，不双回补。
4. 支付与取消相反并发：一个终态成功，另一个得到 `SECKILL_SETTLEMENT_CONFLICT`，不产生相反 Redis 写入或退款。
5. Redis Lua 响应丢失、连接断开、DB CAS 失败：状态保持 `RELEASE_PENDING` 或进入带原动作方向的 `PROJECTION_BLOCKED`，恢复任务以同 operationKey 查询，不重复 `INCR`。
6. 删除 quota key、受理 op marker、元数据或活动 revision 后调用 rollback：不自动增加可售配额；对账不再无条件重建；恢复记录可见并告警。
7. acceptance 与 reconciler 并发：受理 Lua 已成功但受理仍 `PENDING/UNKNOWN` 时，reconciler 不释放/重建覆盖；受理最终为 `ACCEPTED+OPEN` 后配额计数一致，op marker 和活动 revision 均来自同一 Lua receipt。
8. Stock 重启和恢复任务多实例竞争：数据库 lease/revision 保证单一执行；重复 confirm/rollback 的最终 GET 结果稳定。
9. 存量 `ACCEPTED`、已支付、已取消、历史未知订单迁移后逐类核对；不得生成幽灵订单或普通库存流水。
10. 运行已有 order/payment/stock 契约测试、Stock 模块测试、故障注入和适用 Maven 验证；测试未通过不得标记 CM-24 完成。

## 9. 开发顺序与审查门槛

1. 先完成本规格评审和历史数据只读审计；不得先改 `StockServiceImpl`。
2. 新增表、PO、Mapper 和状态/错误契约；先通过迁移、唯一约束和 Mapper 单测。
3. 实现 Stock 秒杀结算分流、Redis Lua 操作 marker/revision 和恢复器；普通库存路径回归不变。
4. 修改 reconciler 的缺 key 关闭策略，并补充受理、结算、对账竞争测试。
5. 联调 OrderStockActionRecovery、OrderServiceImpl、PayConsistencyService；确认 Feign 仅使用既有 confirm/rollback/GET。
6. 完成真实 Redis/MySQL/RabbitMQ 故障演练、历史数据核验、`git diff --check` 和模块构建后，才允许在 `bugfix_log.md` 标记根因、方案和验证完成。

任何实现若选择在缺失 quota key/受理 op marker/活动 revision 时直接 `SET` 或 `INCRBY`，让取消减少 `seckill:user`，让回执未知的 `RELEASE_PENDING` 改成无方向 `UNKNOWN`，或让旧 Lua 与新 Lua 并行写同一活动，均违反本规格，评审结论自动变为 NO-GO。
