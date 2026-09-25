# 第 3 批一致性缺陷评审规格

## 范围与评审结论

本规格冻结 CM-20260924-04（余额扣款成功后订单/库存同步失败）、CM-20260924-06（普通库存 Redis/MySQL 分歧）和 CM-20260924-13（Feign 写请求结果未知）的最小实现契约。实现只使用现有 MySQL、Redis、OpenFeign、RabbitMQ 和定时恢复任务，不新增中间件。

本批的共同原则是：每个远程写操作有持久化操作键和查询接口；未知结果保留在恢复状态，不能猜测失败；事实库提交和补偿意图必须在同一个本地事务中落盘；重试沿用原业务键，禁止生成第二个订单或第二笔扣款。

订单库存动作统一采用两阶段协议：订单状态 CAS 与 `order_stock_action`（确认或回滚）意图在同一订单本地事务中提交，事务提交后才调用 stock。远程响应未知时只更新动作恢复字段，不回滚已经提交的订单状态；恢复任务通过库存查询和同一操作键收敛。

支付恢复查询 `GET /api/internal/orders/{orderNo}/payment-state` 必须返回 `stockActionStatus`。支付服务只有在 `PAID + SUCCEEDED` 时将扣款结清；只有在 `CANCELLED + SUCCEEDED` 时发起退款。历史存量终态若没有动作行，订单服务必须查询 stock reservation 持久状态后再兼容映射：`PAID + CONFIRMED` 才返回 `SUCCEEDED`，`CANCELLED + ROLLED_BACK` 才返回 `SUCCEEDED`；库存查询失败/无记录返回 `UNKNOWN`，`PAID + ROLLED_BACK` 或 `CANCELLED + CONFIRMED` 等不匹配返回 `CONFLICT`。`PENDING`、`UNKNOWN` 或 `CONFLICT` 不得被支付服务当作已结清。

## CM-20260924-04：扣款、订单和库存

### 状态模型

`pay_record.status` 增加 `ORDER_SYNC_UNKNOWN`、`REFUND_PENDING`、`REFUNDED`。`SUCCESS` 的进入条件是：用户余额账本已存在唯一 `DEBIT` 且订单查询明确返回 `PAID`；扣款成功但订单调用超时进入 `ORDER_SYNC_UNKNOWN`。该状态不代表支付失败，也不能直接退款。

支付恢复任务按以下顺序处理：

1. 用原 `orderNo` 查询订单支付状态。
2. 返回 `PAID`：以同一 `payNo` 重试支付同步，推进 `SUCCESS`，再由订单按 CAS 规则确认库存。
3. 返回 `CANCELLED` 或明确的未支付终态：写入唯一 `REFUND_PENDING` 操作并调用用户退款接口。
4. 结果超时、连接断开或状态仍为 `PENDING_PAYMENT`：保持扣款和 `ORDER_SYNC_UNKNOWN`，按退避重试并告警；达到单次重试上限只转人工/持续对账，不自动退款。

### 持久化边界

支付服务在调用 user 前，先在独立已提交事务中写入 `pay_debit_intent(INIT)`。这一步覆盖“user 的 DEBIT 已提交、pay 进程在更新 `pay_record` 前崩溃”的窗口；恢复任务扫描 `pay_debit_intent`，不能只扫描 `pay_record.status=ORDER_SYNC_UNKNOWN`。恢复任务先用 `payNo` 查询用户账本：没有账本记录则用同一操作键重试扣款；已应用扣款则直接进入订单状态查询；明确拒绝则将意图和支付记录置为 `FAILED`。账本查询超时保持意图可恢复，不得猜测未扣款。

用户服务新增 `balance_ledger`，余额条件更新和账本插入为一个本地事务。`DEBIT` 的 `operation_key=payNo` 唯一；`REFUND` 使用独立唯一键并引用原扣款键。Redis 的 `balance:debit:*` 只能加速重复请求，丢失或过期后必须以账本恢复，不能再次扣款。退款金额不得超过原扣款金额，重复退款返回第一次结果。

`pay_debit_intent` 保存 `attempts`、`next_retry_at`、`lease_owner`、`lease_until` 和 `last_error`。恢复任务用数据库条件租约认领并按退避时间重试；达到阈值后进入可观测告警，但记录保持可重入。支付记录可以暂时仍为 `PENDING`，恢复依据以意图和用户账本查询为准。

支付服务保留 `pay_record` 作为支付状态事实，并通过 `payNo/orderNo` 关联账本操作。订单服务提供只读 `GET /api/internal/orders/{orderNo}/payment-state`。用户服务提供扣款、账本查询和退款三个内部接口，完整路径与字段见 `api_spec.md`。

## CM-20260924-06：普通库存事实源与缓存修复

普通库存路径改为 MySQL 先行：第一阶段在独立事务中提交 `stock_reservation_request(order_no,items_hash)=INIT`，固定按 `skuId` 升序规范化后的快照和 SHA-256；第二阶段在库存事务中锁定该请求行，按 `sku_id + version` 条件更新全部 `available_quantity/reserved_quantity`，写入唯一 `stock_flow`，递增 `stock_sku.version`，同时写入 `stock_cache_repair(PENDING, target_available, source_version)`，最后标记 `RESERVED`；提交成功后才异步更新 Redis。库存不足时第二阶段整体回滚，再由独立事务锁定原 `INIT` 行标记 `REJECTED`，不可释放订单号。相同订单号相同哈希返回原结果，不同哈希返回 `ORDER_RESERVATION_KEY_REUSED`。Redis 不再承担普通库存交易裁决，Redis Lua 只用于带版本的投影更新。秒杀 Redis 原子预扣仍按原异步链路执行，并由其持久化受理台账收敛。

`INIT` 请求带数据库租约和退避时间。第二阶段崩溃后，恢复任务必须等待 `lease_until` 到期，再锁定请求行检查状态：已是 `RESERVED` 则不得重扣；仍为 `INIT` 且没有对应已提交流水才可重试；只有租约到期、确认没有预扣流水时才可标记 `REJECTED`。活跃租约中的 INIT 不能被超时任务直接拒绝。

Redis Key `stock:available:{skuId}` 为 hash：`quantity`、`version`。修复 Lua 的原子规则为：当前 Redis 版本小于等于来源版本时写入数量和来源版本；当前版本高于来源版本时拒绝写入并把旧修复记录标记 `STALE`。修复器通过 `stock_cache_repair.lease_owner/lease_until` 在 MySQL 中按条件认领记录，按 SKU 串行处理；该租约只防止多个实例重复处理，Redis 锁不承担一致性保证。提交后崩溃、Redis 断连、Lua 返回未知均由同一 `PENDING` 记录重试。启动校准读取 MySQL 当前 `version`，只生成/应用当前版本投影。

迁移期间若仍存在旧的“Redis 先扣、MySQL 后写”请求，不能运行全量 `MSET/SET` 覆盖。必须先将相关 SKU 标记 `REPAIR_BLOCKED`，根据 `stock_flow` 查明预扣、补偿或未知状态；完成裁决后以 MySQL 当前版本创建修复记录。这样避免修复任务把并发中的 Redis 回补或旧值覆盖掉新的库存事实。

## CM-20260924-13：远程写结果未知

`POST /api/stock/reservations` 以 `orderNo` 作为稳定幂等键，响应明确区分 `RESERVED`、`ALREADY_RESERVED`、`REJECTED`。新增 `GET /api/stock/reservations/{orderNo}`，从 `stock_flow` 和库存状态返回最终结果。Feign 对该 POST 不配置自动重试；读取超时、连接断开和响应解析失败都进入订单 `order_reservation_intent=RESERVE_UNKNOWN`。恢复任务只能以原 `orderNo` 查询或重试，先读取订单状态再决定确认/回滚。

商品详情和结算校验只允许对幂等 GET 做有限重试。商品服务最终不可用时返回 `GATEWAY_SERVICE_UNAVAILABLE`/明确的服务不可用结果；订单不能使用购物车缓存价格、缓存库存或前一次查询结果创建有效订单。

### 订单状态与库存动作结果未知

- `paid`：本地事务提交 `PENDING_PAYMENT → PAID` 与 `CONFIRM/PENDING` 后，事务外调用 `stock.confirm`。响应超时或连接断开只将动作置为 `UNKNOWN`；恢复任务查询到 `CONFIRMED` 即成功，查询到 `RESERVED` 则以同一 `operationKey` 重试确认。不得把订单回滚为 `PENDING_PAYMENT`。
- `cancel/timeout`：本地事务只有在 `PENDING_PAYMENT` CAS 成功时，才提交 `CANCELLED` 与 `ROLLBACK/PENDING`。响应未知时查询 stock；`ROLLED_BACK` 视为成功，`RESERVED` 以同一键重试回滚。CAS 失败不得创建回滚动作。
- 若任一动作查询到相反终态（确认动作看到 `ROLLED_BACK`，或回滚动作看到 `CONFIRMED`），标记 `CONFLICT` 并告警，禁止继续发起相反动作或伪造订单状态。

## 验收与故障注入

### 支付

- 在用户余额本地事务提交后注入 order 连接断开：余额只扣一次，`pay_record=ORDER_SYNC_UNKNOWN`，恢复任务查询到 `PAID` 后不退款且库存只确认一次。
- 在用户余额本地事务提交后、pay 写回 `pay_record` 前终止进程：重启后由 `pay_debit_intent` 扫描发现已应用账本，继续查询同一订单，不遗漏扣款，也不重复扣款。
- 在 order 已 CAS 成 `CANCELLED` 后注入支付恢复重试：只产生一笔退款，余额恢复原值，重复恢复无副作用。
- 在订单查询连续超时期间检查：不得调用退款；恢复记录可重启后继续处理。
- 清空余额扣款 Redis 幂等 Key 后重放同一 `payNo`：账本返回原结果，余额不再减少。
- 在 order CAS 为 `PAID` 后让 `stock.confirm` 提交成功但丢弃响应，再终止 order：重启后订单仍为 `PAID`，`order_stock_action=UNKNOWN` 可查询并收敛为 `SUCCEEDED`，库存最终为 `CONFIRMED`，不得允许 cancel 回滚。
- 支付恢复读取 `PAID + PENDING/UNKNOWN/CONFLICT`：不得将扣款标记为 `SUCCESS`；读取 `PAID + SUCCEEDED` 后才结清。读取 `CANCELLED + PENDING/UNKNOWN/CONFLICT`：不得退款；读取 `CANCELLED + SUCCEEDED` 后才退款。
- 对无 `order_stock_action` 的历史 `PAID` 订单，只有 stock reservation 持久状态为 `CONFIRMED` 才兼容返回 `stockActionStatus=SUCCEEDED`；对历史 `CANCELLED` 订单，只有库存为 `ROLLED_BACK` 才兼容返回 `SUCCEEDED`。库存查询失败/无记录返回 `UNKNOWN`，状态不匹配返回 `CONFLICT`，不得盲信订单终态或退款。
- 让 timeout/cancel 在 `PENDING_PAYMENT` CAS 成功后丢失 `stock.rollback` 响应：订单保持 `CANCELLED`，恢复任务查询/重试同一操作键，库存最终为 `ROLLED_BACK`。
- 并发 paid 与 cancel/timeout：最多一个订单状态 CAS 成功；胜者只创建对应动作，不能出现订单 `CANCELLED` 且库存 `CONFIRMED`（`SOLD`）。

### 普通库存

- 并发提交同一 `orderNo` 的两个不同 SKU 集合：只有一个请求能建立 `stock_reservation_request`，另一个返回 `ORDER_RESERVATION_KEY_REUSED`，不得分别占用两组库存。
- 同一 `orderNo`、同一规范化快照并发重试：所有响应一致，只产生一组 `stock_flow`，确认/回滚各最多一次。
- 让某个 SKU 不足：第二阶段库存事务整体回滚，所有 SKU 数量不变；随后 `stock_reservation_request=REJECTED` 持久存在，原哈希不变，后续 GET 返回 `REJECTED`，不能返回 `NOT_FOUND`。
- 在 MySQL 提交后、Redis Lua 前终止进程：重启修复器最终写入正确数量和同一 `source_version`。
- 在 Redis Lua 执行结果未知后重试：同版本只得到同一投影，不发生二次扣减或回补。
- 并发新库存事务产生更高版本时，旧修复记录不能覆盖新数量，必须转 `STALE` 并由新版本记录收敛。
- 在旧 Redis 先扣路径的迁移窗口注入数据库失败：SKU 进入 `REPAIR_BLOCKED`，裁决前禁止全量覆盖；裁决后 MySQL、`stock_flow`、Redis 版本和数量一致。

### Feign 结果未知

- 让 stock 已提交预扣后丢弃 HTTP 响应：order 保持同一 `orderNo`，查询到 `RESERVED` 后只回滚一次。
- 让 stock 未执行但响应同样丢失：若请求认领已提交但库存事务尚未处理，查询返回 `INIT` 并由恢复任务继续处理；若库存不足且拒绝事务已完成，查询返回持久化 `REJECTED`；若认领事务尚未提交，查询可返回 `NOT_FOUND`，订单按保护等待后重试原请求，不生成新订单号、不产生回滚流水。
- GET 返回 `NOT_FOUND` 时，只能说明尚未观察到请求认领；恢复任务在保护等待后以相同订单号和相同快照重试原 POST，不能直接按“未执行”发起回滚。
- 对同一超时请求重启 order 恢复任务：不会重复预扣；恢复任务最终进入 `RELEASED` 或 `COMMITTED`，未知状态进入可观测告警。
- 让 product GET 超时：结算拒绝创建订单，不读取购物车金额作为替代价格。

## 开发顺序与审查门槛

1. 先新增并评审 `balance_ledger`、`stock_cache_repair` 和状态枚举/索引。
2. 再实现 user/pay 的扣款账本与支付恢复，最后接入 order 查询和 stock 确认。
3. 再切换普通库存为 MySQL 先行，补版本投影修复器和迁移保护。
4. 最后补 stock 查询接口、Feign 结果分类和故障注入测试。

每一步都必须通过对应模块测试、`git diff --check` 和上述故障注入验收后再进入下一步；本规格评审完成前不得修改业务实现。
