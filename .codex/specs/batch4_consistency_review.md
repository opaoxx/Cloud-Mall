# 第 4 批秒杀一致性缺陷评审规格

## 范围

本规格冻结 CM-20260924-07（秒杀受理、消息投递与死信恢复）、CM-20260924-08（重复消费和幂等键载荷冲突）和 CM-20260924-09（Redis 对账锁误释放）的最小实现契约。只使用现有 MySQL、Redis、RabbitMQ、OpenFeign 和定时任务，不新增中间件。

## 跨服务请求指纹

`requestHash` 必须由 common 中的同一 `SeckillRequestFingerprint` 算法生成。输入字段固定为 `activityId`、`skuId`、`userId`、固定数量 `1`、`addressSnapshot`；地址快照递归按 JSON 键的字典序排序，字符串使用 UTF-8，数字使用无多余零的规范表示，去除空白。规范字符串精确为 `v1\\n{activityId}\\n{skuId}\\n{userId}\\n1\\n{canonicalAddressJson}`，其中 `\\n` 是单个换行符；计算 SHA-256 小写十六进制。stock 在受理入口生成并持久化，order 消费消息前用同一算法重算验证；任一字段或哈希不一致进入 `SECKILL_IDEMPOTENCY_CONFLICT`/`CONFLICT`，不得创建订单、改写 orderNo 或重试扣库存。旧版 `SECKILL_ORDER_ACCEPTED` 消息若缺少 `addressSnapshot`，先按旧契约补 `{}` 再计算 legacy 指纹；只有核心维度缺失/非法或无法形成稳定不可变输入才人工裁决。

## CM-20260924-07：持久受理与可靠投递

### 入口状态

秒杀请求先按 `(userId,idempotencyKey)` 跨活动全局认领不可变 `seckill_reservation`，在执行 Redis Lua 前提交完整规范化 `request_payload`（必须包含 `addressSnapshot`）和 `requestHash`；订单服务另用独立 `seckill_order_dedup` 表，不修改普通订单共享幂等表。Redis Lua 成功后，在库存库持久化 `ACCEPTED` 与 `seckill_order_outbox(PENDING)`；两者缺一不可。Redis 预扣成功但数据库写入失败时，按原请求键和已保存载荷执行补偿 Lua，保留可观测错误记录；不能直接返回 `accepted=true`。进程在 Lua 前后任一窗口退出时，恢复任务都能使用 `request_payload` 重建同一事件，不依赖请求线程内存或 Redis 临时键。

入口只有在 Redis 预扣和 outbox 已提交后返回 `accepted=true`。这表示请求可恢复，不表示订单已经写入。响应包含 `orderNo`、`eventId`、`reservationStatus`。库存对账直接读取 stock 自身的受理台账和 outbox Mapper；订单 DLQ recovery 直接使用持久化原消息和订单库幂等记录，不新增跨服务受理查询接口。前端继续使用秒杀入口响应和已有订单查询观察结果。

### Outbox 与 RabbitMQ

`seckill_order_outbox` 是库存服务事实台账。多实例 dispatcher 按 `status`、`next_retry_at` 和数据库租约 CAS 认领 `PENDING/UNKNOWN/DEAD` 记录：只有租约为空/已过期且时间到达的记录可写入当前 `lease_owner/lease_until`；发布前后检查租约仍归当前实例，失去租约立即停止并等待下次扫描。发布固定 JSON envelope，检查 returned message 并等待 publisher confirm：

```json
{
  "eventId": "...",
  "eventType": "SECKILL_ORDER_REQUEST",
  "occurredAt": "2026-09-25T12:00:00+08:00",
  "activityId": 1,
  "skuId": 2,
  "userId": 3,
  "orderNo": "202609...",
  "idempotencyKey": "client-key",
  "requestHash": "sha256",
  "addressSnapshot": {},
  "payloadVersion": 1,
  "traceId": "..."
}
```

confirm 成功标记 `PUBLISHED` 并清空租约；confirm 超时、连接断开或 returned message 未路由保持 `UNKNOWN/PENDING`，更新退避时间并释放租约后使用同一 `eventId` 持续重试。达到告警阈值可标记 `DEAD`，但 `DEAD` 仍可被 dispatcher 扫描和自动恢复；RabbitMQ 或商品服务短时停机不得使 accepted 请求永久无订单。只有不可解析载荷或不可变哈希冲突进入 `CONFLICT`，停止自动恢复，不能删除 Redis 预扣或把 `accepted` 改成订单成功。

### 死信自动恢复

订单服务的 DLQ consumer 必须先在 `seckill_message_recovery` 写入唯一 `eventId`、原始 payload 和请求哈希，再确认 RabbitMQ 死信消息。replayer 使用数据库租约认领 `REPLAY_PENDING`，以原 `eventId` 和原 payload 投递回主交换机；重放成功后由正常消费者将记录标记 `RESOLVED`。重放次数、下次时间和最后错误持久化，采用有上限间隔的持续退避；超过告警阈值仅增加告警，不停止自动重放。事件载荷无法解析或同一幂等键出现不可变哈希冲突时进入 `CONFLICT`，人工处理并保留原消息证据。

现有 8 条无 `eventId` 的 DLQ 消息必须先导入 `seckill_message_recovery` 再重放。已知历史版本 `eventType=SECKILL_ORDER_ACCEPTED` 的消息若包含 `orderNo`、`activityId`、`skuId`、`userId`、`idempotencyKey`，但缺少 `addressSnapshot`，按旧 OrderService 契约补为 `{}`，生成 `legacy-seckill:{orderNo}`，并用补全后的规范化载荷计算 `requestHash`，标记 `payloadVersion=0` 后自动重放。一般旧消息生成规则固定为：有 `orderNo` 使用 `legacy-seckill:{orderNo}`；没有订单号但有 `userId + idempotencyKey` 使用 `legacy-seckill:key:{userId}:{idempotencyKey}`；两者都没有则对完整规范化原始载荷使用 `legacy-seckill:hash:{sha256}`。禁止使用 RabbitMQ delivery tag、消费时间或随机 UUID。只有 `orderNo/activityId/skuId/userId/idempotencyKey` 缺失、格式非法或无法形成稳定不可变载荷时标记 `CONFLICT`；已知旧版缺失地址不属于冲突，可自动兼容重放。

## CM-20260924-08：并发消费和载荷冲突

订单消费者必须在创建订单的同一本地事务中插入/锁定独立的 `seckill_order_dedup`，再写订单和明细，最后提交 `COMMITTED`。唯一键冲突不得统一抛异常：

- 同一 `(userId,idempotencyKey)`、同一 `requestHash`：读取已提交订单并确认消息；若首个事务仍持锁，等待其提交后重新读取，不创建第二个订单。
- 同一用户跨活动同键不同 `requestHash`：记录冲突事件，返回 `SECKILL_IDEMPOTENCY_CONFLICT` 并确认消息，不进入 RabbitMQ 重试/死信，不改原订单号。库存受理台账同样以全局 `(userId,idempotencyKey)` 拒绝不同哈希，不能重复扣 Redis。
- 首次消费事务失败：事务回滚，消息进入 RabbitMQ 重试；达到死信后走 `seckill_message_recovery`，仍使用原事件和哈希，并持续退避重放，告警阈值不等于停止恢复。

同一事件重复投递与同一幂等键重复投递都必须满足：最多一条有效订单、最多一笔秒杀预扣、同一订单号保持不变。订单状态与库存受理状态查询以 MySQL 为准，Redis 只作为秒杀热点预扣执行介质。

秒杀消费者成功落单的本地事务必须同时写入订单主表、订单明细、`seckill_order_dedup=COMMITTED` 和既有 `order_outbox(ORDER_TIMEOUT)`；提交后由订单 outbox dispatcher 投递待支付超时消息。订单已落库但 timeout outbox 缺失属于 CM-07 的恢复失败，必须由恢复任务按原 `orderNo` 补建同一 `eventId`，不能依赖消费者再次创建订单。

本批 CM-07 的边界是“秒杀受理 → 消息可恢复 → 订单最终落库”。秒杀 Redis 配额在订单支付成功时确认、订单取消/超时时释放的最终结算，若当前 `stock.confirm/rollback` 只处理普通 `stock_flow`，则属于独立的秒杀库存结算缺陷，需要另立 bug 条目和后续批次；不能把订单落库成功当作 Redis 配额已结算。

## CM-20260924-09：对账锁安全协议

锁 Key 固定为现有 `stock:consistency:lock`，值为每次运行生成的随机 token。获取使用 `SET key token NX PX 20000`；续期脚本和释放脚本都执行“读取当前值并与 token 比较”的 Lua 原子操作。释放脚本只有匹配时才删除，token 不匹配返回 0。

对账任务每 5 秒续期一次，并在每批数据库/Redis写操作前检查租约。续期失败、token 丢失或 Key 已被新实例接管时，旧实例立即停止写入并退出；`finally` 只能执行 compare-and-delete，不能无条件 `DEL`。任务执行超过 TTL 仍可继续的前提是续期成功；无法续期时不延长旧任务的写权限。锁过期后新实例可以接管，旧实例不能覆盖新实例的修复结果。

## 对账与恢复边界

- 秒杀 Redis 预扣成功、outbox 缺失：以受理台账为依据补建 outbox 或执行补偿，不能只看 Redis Key。
- outbox 已发布但订单未落库：按事件号进入订单幂等消费/死信恢复，不重复扣 Redis。
- 订单已落库但受理台账仍为 `ACCEPTED`：对账任务按 `orderNo` 查询订单并推进 `ORDER_CREATED`，不能生成新订单。
- 受理记录 `CONFLICT` 或事件哈希冲突：停止自动重放并告警，保留 Redis/DB 数量等待裁决。
- Redis Key 丢失：不得按零库存或新请求重建；由库存事实台账和活动库存配置恢复，期间入口返回可观测不可用/恢复中。

## 可执行验收

### 入口和投递

1. Redis Lua 成功后在写 outbox 前终止进程：重启扫描受理台账，补建同一 `eventId` 的 outbox，或按原记录执行一次补偿；不得返回无记录的成功。
2. RabbitMQ publisher confirm 丢失：outbox 保持待恢复状态，重试使用同一 `eventId`；订单最终只生成一条有效订单。
3. RabbitMQ 消费连续失败进入 DLQ：DLQ consumer 先持久化 recovery 记录；修复后自动重放原事件，订单落库后记录 `RESOLVED`，不产生第二次 Redis 预扣。
4. 重放进程在发布后崩溃：租约到期可再次重放，按事件号和幂等键收敛，无新订单号。
5. RabbitMQ 或商品服务停止超过告警阈值后恢复：所有可解析且无哈希冲突的 accepted 请求最终自动恢复为订单；`DEAD` 只表示告警，不得成为永久停重试状态。
6. 导入现存 8 条无 `eventId` DLQ 消息：每条生成稳定 legacy eventId，重复导入不新增记录；可解析记录自动重放，无法形成稳定载荷的记录进入 `CONFLICT`。
7. 秒杀消息成功消费后模拟订单事务提交：同一事务可见订单、`seckill_order_dedup=COMMITTED` 和 `order_outbox(ORDER_TIMEOUT)`；故障注入删除/遗漏 outbox 时，恢复任务按原订单号补建超时事件，不创建第二个订单。

### 幂等和冲突

1. 两个消费者并发处理同一事件：最多一个事务创建订单，另一个读取同一 `COMMITTED` 记录并确认消息。
2. 同一用户/幂等键相同载荷重复：返回原订单号，只有一个订单。
3. 同一用户/幂等键不同活动、SKU、地址或数量：库存和订单两侧都返回 `SECKILL_IDEMPOTENCY_CONFLICT`，不重试、不创建订单、不二次扣 Redis，并保留冲突记录；原订单号保持不变。
4. 首个消费事务回滚后重新投递：允许重试并最终只创建一条订单。

### 对账锁

1. 实例 A 持有 `stock:consistency:lock` 运行超过 20 秒，实例 B 接管；A 的无条件释放操作不得删除 B 的锁，A 检测续期失败后停止写入。
2. A 持锁期间正常续期：B 不能进入；A 完成后 compare-and-delete 成功释放。
3. A 进程崩溃：TTL 到期后 B 可接管，B 完成后锁值正确清理。
4. 对账批次写入前模拟 token 丢失：该批停止，不得覆盖新实例已经写入的库存投影。

## 开发门槛

先落数据库表和事件 envelope，再实现 outbox dispatcher、DLQ recovery、原子幂等消费，最后实现 token 续期/释放和故障注入。每个阶段必须通过对应模块测试、消息重复/断连演练、`git diff --check` 后再进入下一阶段。
