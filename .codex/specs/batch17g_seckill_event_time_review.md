# CM-20261007-20：秒杀受理与 outbox 事件时间可靠性评审

## 1. 评审结论与范围

结论：**GO，限定修复 Stock 秒杀受理时间和 outbox envelope 时间漂移；不改变 API、orderNo、eventId、幂等键、状态机或 Rabbit 业务拓扑。**

当前缺口：秒杀认领用多次 `now()` 生成候选 orderNo、reservation 首次时间和 payload；`toSeckillPayload` 没有持久化 occurredAt；dispatcher 每次发布 envelope 都使用投递时 now，导致延迟跨月、重试和旧 payload 的事件创建时刻不稳定，Order 可能按错误月份路由。本轮修复新 claim 的候选 orderNo/首次时间不同次采样，以及新 outbox 的持久 occurredAt；已发往 Order 的历史缺时间消息和无法核验的错月旧记录留待后续，不猜测修改原号。

## 2. 新 claim 时间契约

`StockServiceImpl` 增加私有 `Clock seckillClaimClock = Clock.system(ZoneOffset.ofHours(8))` 或等价测试接缝，不注册 Bean、不外部配置、不改变 public 构造器/API；该时钟位于生成候选 orderNo 之前。一次 claim 只采样一次时间，并用既有创建时间规范化规则：same instant 转 `+08:00`、向下截断 DATETIME(3) 毫秒、年份 `1000..9999` 校验。

同一 `occurredAt` 必须用于：

- candidate orderNo 的 `yyyyMM` 前缀；
- `seckill_reservation.created_at/updated_at` 的首次认领时间；
- 持久 `request_payload.occurredAt`；
- `seckill_order_outbox.payload.occurredAt`。

outbox 表的 `created_at`、`updated_at`、`next_retry_at` 继续保持接受/调度时间的既有 Mapper 语义，不强行改成 claim 时间；它们不能替代订单创建时间或 payload.occurredAt。Mapper 原有 INSERT IGNORE 幂等语义保持不变。

现有 `INSERT IGNORE` 幂等语义保持：同一用户/幂等键已存在时不得用新时间、orderNo、payload 或 hash 覆盖原记录；返回/恢复继续使用原持久事实。

## 3. 持久 payload 与 dispatcher

`toSeckillPayload` 必须把规范化 occurredAt 写入原有 JSON payload 字段，不新增顶层 API 字段名；dispatcher 不再用投递时 now 重建 envelope 时间。每次发送必须：

1. 读取持久 outbox payload、eventId、orderNo、idempotencyKey、requestHash；
2. 读取同 orderNo 的 reservation 事实（`StockSqlMapper.findSeckillByOrderNo` 必须额外投影 `created_at`、`idempotency_key`，不改变其他状态字段）；
3. 校验 reservation/orderNo、用户、活动、SKU、幂等键、requestHash、payload 主体归属；可发布状态限定为 `ACCEPTED/PUBLISHED/ORDER_CREATED`；
4. payload 主体必须核对 orderNo、activityId、skuId、userId、idempotencyKey 和 quantity=1；requestHash 复用既有 `SeckillRequestFingerprint` 规则，缺少 address 等历史字段时不得猜测或改写 source payload，证据不足禁止发布；
5. payload 有显式 occurredAt 时，解析为合法 `OffsetDateTime`，规范化到 +08/DATETIME(3)，并同时核对 orderNo 月份、年份/毫秒和 reservation.created_at 同一瞬时；不匹配、非法或月份不一致时禁止发布；
6. payload 缺 occurredAt（历史 outbox）时，只能从唯一同 orderNo reservation.created_at 恢复，核对其合法年份/毫秒/月和全部不可变归属后，在**发送 envelope 内**补入 occurredAt；不得改写历史 payload、eventId、orderNo、hash 或幂等键；
7. 通过 Rabbit publisher confirm 后沿既有状态 CAS；重试必须复用同一 envelope 时间、eventId、orderNo、requestHash，不使用新的 now。

reservation 缺失、created_at 缺失/非法、orderNo 月份与持久创建时间不一致、payload 主体或 hash 冲突、状态不可发布、旧 payload 缺字段且无充分唯一证据时，记录既有错误/退避，禁止发送、禁止改键、禁止猜测时间。错误必须区分“证据不足/载荷冲突”和 Rabbit 传输失败。

## 4. 状态与兼容边界

- 新 claim 的 payload 结构继续使用已有 occurredAt 字段语义，不增加 API 请求字段或事件字段名。
- dispatcher 只兼容历史缺 occurredAt payload 的发送 envelope，不回写历史 JSON；其他历史 payload 缺失关键归属字段仍隔离。
- 既有 reservation/outbox 状态机、publisher confirm/returned、租约和退避保持不变。
- Order 消费端按 17F 既有 normalize 处理 occurredAt；本轮不修改 Order。
- 不修改已发往 Order 的历史缺时间消息，不为无法核验或错月旧记录猜测时间、重建原号或改写 source payload；不改库存状态时间辅助方法。
- 默认 dynamic-monthly、Sharding profile、Seata 和其他业务链路不变。

## 5. 验收矩阵

1. 固定 claim clock 在月末/跨年，candidate orderNo、reservation.created_at、payload/outbox occurredAt 和月份一致。
2. 同一幂等键重复 claim 保持原 orderNo、payload、hash、eventId 和 created_at 不变。
3. dispatcher 首次发送、confirm 丢失重试和延迟跨月重试生成完全稳定的 occurredAt/eventId/orderNo/hash envelope，不使用投递 now。
4. 新 payload 显式 occurredAt 为 UTC/非 +08 时按 same instant 核对月份；非法、错月、与 reservation.created_at 不一致均禁止投递。
5. 历史 payload 缺 occurredAt 时，唯一 reservation.created_at 完整匹配即可只在 envelope 补字段；缺 reservation、错 orderNo/key/hash/status 或 created_at 证据不足禁止投递。
6. 真实 MySQL 验收 claim/幂等/dispatcher，Rabbit 传输可 mock 但必须验证 publisher confirm 与重复 envelope；覆盖跨年月份、旧证据缺失/错月和隔离清理。
7. Reservation/outbox 原库结构与 checksum、默认 Stock/Order/Pay 交易回归保持通过。

## 6. 实施门与范围声明

实现限定在 Stock claim 时间采样、payload 持久化、reservation 查询投影和 dispatcher 发送前证据核验；不新增 API/表字段/消息字段名，不修改历史 payload，不改变状态机或 Rabbit 拓扑。本轮完成只表示秒杀事件创建时间和重试 envelope 稳定，不表示所有 Stock 时间/旧消息跨月问题已闭环。
