# CM-20260924-18：真实业务迁入 Seata AT 第二阶段评审

## 1. 评审结论

本轮对真实订单、库存、支付和余额账本调用链进行逐行核对后，结论是：**当前版本不允许把现有普通下单、支付成功、取消或超时关单直接迁入 Seata AT，本轮对真实业务迁移作 no-go 处理。**

当前代码已经形成一套明确的本地事实与补偿模型：订单先以 `REQUIRES_NEW` 提交 `order_reservation_intent(INIT)`，库存再用订单号幂等预扣并提交 MySQL 事实，订单随后提交订单、幂等记录、超时 outbox 和 `COMMITTED`；支付则先持久化支付意图，再调用用户余额账本，最后以订单 CAS 和库存动作恢复未知结果。Seata AT 的参与分支会在第一阶段本地提交并登记 undo，再由 TC 在第二阶段统一决议；现有恢复任务却把若干第一阶段已提交行当作最终业务事实，二者的可见性和补偿时序尚未统一。

因此，当前轮次只能完成迁移边界评审和实施前置清单，不能提交真实业务实现。已经完成的 `batch6_seata_global_transaction_review.md` 只证明独立 Order/Stock 演练表的 TM、TC、RM、XID 和 undo_log 可工作，不能作为真实主链路迁移授权。

## 2. 逐条核对的现状证据

### 2.1 普通下单 create

`OrderServiceImpl.create` 当前调用顺序为：

1. 在订单数据库以 `OrderReservationIntentService.createIntent` 的 `@Transactional(REQUIRES_NEW)` 提交 `order_reservation_intent(INIT)`；该行必须在远程库存调用前可见。
2. 通过 `StockClient.reserve` 调用库存服务。库存 `reserve` 先独立认领 `stock_reservation_request`，再锁定 `stock_sku`，更新 `available_quantity/version`，写入 `stock_flow` 和 `stock_cache_repair`，最后将请求置为 `RESERVED`。
3. 库存成功后，订单本地事务写入月表订单、订单明细、幂等记录、TTL/DLX 超时 `order_outbox`，并清理购物车。
4. 订单提交后再把 reservation intent 标记为 `COMMITTED`。远程响应或进程结果未知时，保留 `INIT/RESERVE_UNKNOWN`，由恢复任务使用同一订单号查询和补偿。

如果在 `create` 上增加 `@GlobalTransactional`，`REQUIRES_NEW` 意图仍会先本地提交：若它参与同一 XID，AT 二阶段可能将恢复锚点回滚；若刻意不参与 XID，它虽可保留，却必须另行定义与全局决议的状态对应关系。如果删除 `REQUIRES_NEW`，库存调用发生在尚未提交的订单事务中，订单服务进程或 Feign 超时会留下一个未决的远程库存写入，而现有恢复任务依赖“意图已经提交”才能接管。库存端还会把 `stock_cache_repair` 当作提交后的 Redis 投影任务，Seata 回滚 MySQL 后不能撤回已经执行的 Redis Lua 写入。

### 2.2 订单 paid/cancel/confirm

`OrderServiceImpl.paid` 和 `cancel` 在各自的本地事务中先用状态 CAS 将订单改为 `PAID` 或 `CANCELLED`，再写入 `order_stock_action(CONFIRM|ROLLBACK)`；事务提交后，`OrderStockActionRecovery` 在事务外调用 Stock。这样设计的关键语义是：订单状态已提交但库存远程结果未知时，必须保留动作并重试，不能把订单状态回滚到 `PENDING_PAYMENT`。

把 `paid` 或 `cancel` 包装成 Seata 全局事务会改变这个语义：Stock 分支失败时订单状态可能被 AT 回滚，但支付服务或用户账本已经观察到 `PAID`，或者旧的动作恢复器已经开始处理；Stock 的 Redis 版本投影和 RabbitMQ 相关副作用也不会跟着回滚。仅把 Order 和 Stock 加入 AT，仍不能覆盖已存在的 pay/user 外部事实。

### 2.3 支付成功和余额账本

`PayServiceImpl.success/callback` 进入 `PayConsistencyService.initiate/process`：

1. 先独立保存 `pay_debit_intent`，再按租约处理。
2. User 侧 `UserServiceImpl.debitFromLedger` 在本地事务中锁定用户行，按唯一 `operation_key` 写 `balance_ledger` 并扣减余额；该账本是资金事实，不是可随订单 AT 任意删除的临时行。
3. 扣款后 Pay 将支付记录置为 `ORDER_SYNC_UNKNOWN/DEBITED`，调用 Order 的支付 CAS；Order 再提交 `order_stock_action(CONFIRM)`，由 Stock 恢复器确认库存。
4. 订单明确取消且库存回滚成功时，Pay 以唯一 `refund:<payNo>` 操作键创建退款，User 在本地事务增加余额并写 REFUND 账本。

在 Pay、User、Order、Stock 四库之间直接使用 `@GlobalTransactional`，无法撤回已提交余额账本、Redis 余额加速缓存、订单状态、库存版本投影或已经交付的消息。若保留现有 `pay_debit_intent`、`ORDER_SYNC_UNKNOWN`、`REFUND_PENDING`，则 AT 的回滚结果和恢复器会同时推进同一状态机；若删除这些状态，则必须重写结果未知、退款和重复回调的全部语义，超出本轮最小改动范围。

## 3. Seata 参与边界核对

| 事实或副作用 | 当前归属 | 是否可由 AT 直接回滚 | 评审意见 |
| --- | --- | --- | --- |
| 订单月表、订单明细、订单幂等 | Order | 条件可行 | 需要代理数据源、同库 `undo_log`，并重定义 outbox 与未知结果 |
| `order_reservation_intent`、`order_stock_action` | Order | 技术可回滚，业务不应直接回滚 | 它们是恢复接管所需的已提交事实，删除会丢失恢复入口 |
| `stock_sku.available_quantity/version` | Stock | 条件可行 | 必须和 flow、reservation request 同事务，且禁止提交后 Redis 投影继续写入 |
| `stock_flow`、`stock_reservation_request` | Stock | 条件可行 | 当前唯一键和幂等恢复依赖这些行长期存在，AT 回滚后需重新定义重复请求结果 |
| `stock_cache_repair` | Stock | MySQL 行可回滚 | Redis 已写出的版本投影不能由 undo_log 撤回，必须延后到全局提交后再投影 |
| `pay_record`、`pay_debit_intent` | Pay | 技术可回滚 | intent 是未知结果恢复的锚点，不能和远程扣款一起简单删除 |
| `balance_ledger`、用户余额 | User | 条件可行 | 资金账本只能通过明确退款或反向账本修复，不能用 AT 回滚掩盖已观察事实 |
| Redis Lua、购物车、余额加速缓存 | Redis | 否 | 必须采用版本投影、幂等键和对账恢复 |
| RabbitMQ publish、TTL/DLX、outbox confirm | 消息系统 | 否 | 必须使用事务外 outbox/publisher confirm；全局回滚不能撤回已消费消息 |
| ES、Zipkin、Prometheus、日志 | 外部系统 | 否 | 只能最终一致或记录审计，不能纳入 AT |

## 4. 两条候选迁移路径及其当前结论

### 4.1 方案 A：订单创建 + 普通库存预扣迁入 Order/Stock AT

目标是把订单主表、明细、库存请求、库存数量、库存流水和缓存修复意图纳入同一个 XID；Redis 只在全局提交后由 after-commit 投影，超时消息只通过同一事务写入 outbox。

需要同时完成：

- Order、Stock 两库都接入 Seata 代理数据源、同版本 `undo_log`、可达 TC 和 XID Feign 传播；不能只增加注解。
- 删除或改造 `REQUIRES_NEW` 的 `order_reservation_intent` 提交点，使恢复锚点在全局事务未知时仍有独立可观察状态；这需要引入全局尝试账本或在 AT 外先写一条不可回滚的尝试记录。
- 将 `stock_sku`、`stock_reservation_request`、`stock_flow`、`stock_cache_repair` 的同事务边界、唯一键和重复请求状态重新冻结；AT 回滚不能留下“请求不存在但库存已扣”或“流水回滚但数量未回滚”。
- 将购物车删除从订单本地事务中移出，或增加可恢复的购物车删除 outbox；Redis 删除不能依赖 AT 回滚。
- 只有全局提交后才能执行 Redis 版本投影和 RabbitMQ publisher confirm；全局未知期间必须冻结恢复器，避免 AT RM 尚未决议时旧补偿先回滚库存。
- 为 TC 断线、Order/Stock 进程中断、Feign 超时、提交后 Redis/Rabbit 失败和重复请求重新定义 `UNKNOWN` 状态及恢复优先级。

结论：**业务上可行，但不满足本轮最小改动约束。** 它需要改变当前订单/库存的核心状态机和恢复时序，至少涉及 Order、Stock、Redis 投影、Rabbit outbox、恢复器和数据库契约，不能作为本轮直接实现。

### 4.2 方案 B：支付扣款阶段的 Pay/User AT（相对更安全，但本轮仍不实施）

目标是只将“扣款阶段”的 Pay/User MySQL 写入放进同一个 Pay/User XID：Pay 的支付记录状态和 User 的 `mall_user.balance + balance_ledger` 由同一全局事务提交；订单支付同步仍在全局提交后通过现有幂等 Feign 调用完成。`pay_debit_intent` 在进入全局事务前继续作为独立、不可回滚的恢复锚点，不能为了 AT 删除它。

这条路径比方案 A 更安全，原因是扣款阶段当前没有 RabbitMQ、库存 Redis Lua 或商品缓存写入；源码中 `balance:debit:{userId}:{operationKey}` 目前只有 User 的写入和契约测试，没有业务读取者，因此它不是余额事实来源。但它仍不是“加一个注解即可”的修复：缓存写入必须从 User 的 AT 本地事务中移出到全局提交后的投影，或改成明确允许丢失的加速缓存；余额事实只能由 `mall_user` 与 `balance_ledger` 共同决定。

需要同时完成：

- Pay 和 User 均接入 Seata 代理数据源、`undo_log`、XID 传播及分支故障恢复；同一 XID 只覆盖扣款阶段，不能把 Order 加入该切片。
- `pay_debit_intent` 先以独立本地事务提交 `INIT`，并在全局事务内以同一 `payNo/operationKey` 做状态条件更新；全局回滚后 intent 必须仍可查询，状态由恢复器推进为可重试的 `INIT` 或明确的 `DEBIT_UNKNOWN`，禁止生成第二个操作键。
- User 的余额行锁、余额条件扣减、`balance_ledger(DEBIT)` 插入必须在同一 User 分支中完成；`operation_key` 唯一键、`user_id/pay_no/order_no/amount` 完整载荷校验和拒绝扣款记录不能改变。
- `balance:debit:*` 不得在 AT 分支提交前写入。推荐在全局提交回调后写入；若回调失败，下一次账本读取应正常工作并重建缓存。该 Key 不是资金事实，也不能作为未知结果判断依据。
- Pay 的 `pay_record` 在全局事务内只能推进到受控中间态；全局提交后再标记 `DEBITED`/进入现有 `ORDER_SYNC_UNKNOWN`，随后调用 Order.paid。订单同步失败仍由现有恢复器处理，不能把 Pay/User AT 当作完整支付链路原子性。
- 退款始终使用现有 `refund:<payNo>` 反向账本操作。不能通过 AT 删除已对外可见的 DEBIT，也不能在全局未知时直接退款。

结论：**相较方案 A，这条 Pay/User 扣款切片是后续真实迁移的优先候选，但当前仍不满足最小改动约束，不能在本轮直接实施。** 它必须先补齐全局未知状态查询、Seata 提交后缓存投影、Pay 记录中间态和恢复器唯一推进权；在这些契约冻结前，直接实施会把资金账本和已有退款恢复并行化。

## 5. 本轮冻结的 no-go 边界

本轮不得执行以下改动：


- 不得在 `OrderServiceImpl.create/paid/cancel`、`PayServiceImpl.success/callback` 或 `UserServiceImpl.debit` 上直接增加 `@GlobalTransactional`。
- 不得把真实 `stock_sku`、`balance_ledger` 或订单月表用于新的受控故障演练。
- 不得删除 `REQUIRES_NEW` 意图、`ORDER_SYNC_UNKNOWN`、`STOCK_ACTION_UNKNOWN`、`REFUND_PENDING` 或恢复租约，除非对应的新状态机和迁移方案先经评审。
- 不得把 Redis、RabbitMQ、购物车删除、余额缓存和 ES 写入描述为 Seata 分支资源。
- 不得在全局事务内执行不可逆外部调用后再依赖 AT 回滚抵消副作用。

允许继续保留第一阶段独立演练入口作为 Seata 基础设施回归，但它不能写真实业务表、不能替代真实迁移验收，也不能在台账中标记 CM-18 全部闭环。

## 6. 若后续重新立项，必须先完成的评审项

下一次真实迁移评审必须先冻结以下内容，并由 backend 按冻结规格实施：

1. 选择单一纵向切片：优先考虑“订单创建 + 库存预扣”或“支付记录 + 余额扣款”，不得同时迁移四个服务。
2. 明确参与的 MySQL 表、每张表的 undo_log、隔离级别、唯一键、版本字段和 AT 回滚后状态；列出不参加 AT 的 intent/outbox 表及其恢复锚点。
3. 冻结 XID 传播：Order/Pay → Feign → Stock/User 的请求头、RootContext 绑定、服务间超时和 TC 不可达响应；禁止由客户端 body 传入可信 XID。
4. 冻结提交后的副作用顺序：全局提交 → Redis 版本投影 → Rabbit publisher confirm；每一步失败都要有可重试操作键和恢复租约。
5. 冻结未知状态：全局提交前进程中断、提交后响应丢失、TC 断线、Redis/Rabbit 失败分别进入什么状态，哪个恢复器拥有唯一推进权。
6. 冻结 API/Feign、消息 envelope、Redis key 和数据库迁移契约；若不改变现有契约，必须证明旧恢复器不会与新 AT 分支竞争。
7. 先完成独立故障矩阵：正常提交、业务异常、Order/Stock 或 Pay/User 进程中断、TC 断线、Feign 断链、Redis/Rabbit 已产生副作用、同操作键并发；每项必须核对 MySQL、undo_log、Redis、Rabbit 和最终状态。
8. 真实迁移实现前必须补齐参与库 `undo_log`、代理 DataSource、XID 日志/脱敏审计和清理策略，并复跑现有普通下单、支付、关单、库存恢复测试。

## 7. 最终状态与台账措辞

CM-18 当前状态应记录为：**第一阶段独立 Order/Stock Seata AT 演练已完成；第二阶段真实业务迁移经评审 no-go，订单、库存、支付和余额主链路仍由现有 intent/outbox/UNKNOWN/补偿模型负责。**

本评审没有修复业务 Bug，也没有改变 API、数据库、Redis Key、消息字段或服务行为。下一批应转向不依赖本次 Seata 迁移的独立缺陷，例如 CM-19 商品搜索未使用 Elasticsearch、CM-20 Sharding-JDBC 仅配置未落地，或另行立项完成上述方案 A/B 的详细迁移规格后再开发。
