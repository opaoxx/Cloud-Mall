# 第 5 轮：秒杀并发压测与受理恢复竞态评审

## 1. 评审范围

本评审对应 CM-20260924-22 的后续缺口，范围限定为秒杀受理链路在并发压测下的恢复日志分类。目标是消除请求线程与 `SeckillOrderOutboxDispatcher.recoverPendingMarkers` 并发竞争同一 `seckill_reservation` 记录时的误报，同时保持未知结果的安全恢复语义。

本评审不改变 HTTP API、数据库表结构、Redis Key、RabbitMQ exchange/queue、消息 envelope 或状态枚举；实现应由 backend 角色按本规格落地。

## 2. 已确认证据

2026-10-01 在隔离 RabbitMQ vhost 和短时秒杀活动上完成并发阶梯验证：

- 并发档位 1/2/5/10/20 的 HTTP 200 受理数为 1/1/3/5/10，其余请求返回业务 409；无 5xx。
- 20 线程 × 10 次短突发产生 100 次 200、100 次业务 429；活动配额、`seckill_reservation`、outbox、异步订单、消费幂等记录和 Redis 扣减事实一致。
- 秒杀主队列和死信队列最终均为 0；同一幂等键 10 线程重放只产生 1 次 Redis 扣减和 1 条订单。
- `SeckillOrderOutboxDispatcher.recoverPendingMarkers` 产生 6 条“秒杀 PENDING 受理恢复失败/受理记录状态已变化”警告。逐条核对原订单后，6 条记录最终均为 `seckill_reservation=ACCEPTED`、对应 outbox=`PUBLISHED`，没有业务数据丢失。

源码核对确认存在两个相邻误报点：

1. marker 与原订单号相等时，恢复器调用 `SeckillAcceptanceService.accept`。请求线程可能已先完成 `ACCEPTED + outbox` 事务，恢复器随后执行受理 CAS，影响行数为 0，被包装成“状态已变化”异常并告警。
2. marker 缺失超过 30 秒时，恢复器无条件调用 `markSeckillUnknown` 并记录“已转 UNKNOWN”。若请求线程已在并发窗口内完成 `ACCEPTED`，该 UPDATE 影响行数为 0，当前代码仍会产生与事实不符的错误日志。

## 3. 根因判断与排除项

根因是恢复器把“CAS 失败”当作单一异常处理，没有在写竞争失败后读取同一 `orderNo/eventId` 的持久事实。CAS 失败同时可能表示：

- 并发请求线程已经成功提交受理和 outbox，这是幂等成功；
- 其他恢复实例已经完成受理，这是幂等成功；
- 记录状态与当前 marker、请求哈希或 outbox 不一致，这是需要保留证据并告警的真实冲突；
- 数据库写入失败或状态仍未知，这是现有恢复重试语义应继续覆盖的情况。

本次证据不支持把问题归因于 Redis 扣减超卖、RabbitMQ 重复消费、异步订单幂等失效或数据库事实丢失；这些事实均已在压测后核对一致。

## 4. 冻结的最小恢复分类

### 4.1 marker 相等、受理 CAS 影响行数为 0

恢复器捕获受理状态变化后，必须按原 `orderNo` 查询 `seckill_reservation` 和 `seckill_order_outbox`，并使用本次事件的 `eventId=SeckillRequestFingerprint.eventId(orderNo)`、请求哈希和订单号进行核对：

| 持久事实 | 处理 | 日志 |
| --- | --- | --- |
| reservation=`ACCEPTED` 且对应 outbox 存在（`PENDING`/`PUBLISHED` 均可），eventId/orderNo/hash 一致 | 视为幂等成功，继续后续 outbox 投递扫描 | 不记录 WARN/ERROR；可用 DEBUG 或计数器表示“竞争后已完成” |
| reservation=`ACCEPTED` 但 outbox 缺失，或 eventId/orderNo/hash 不一致 | 保留恢复任务/人工核验语义，不创建第二条 outbox，不覆盖既有事实 | WARN，包含 orderNo、eventId、持久状态和冲突类别 |
| reservation 仍为 `PENDING`/`UNKNOWN`，或查询结果为空 | 保留现有下一轮重试语义 | WARN；数据库/查询异常需携异常原因 |
| 查询失败 | 不改变状态，不静默 | WARN，保留异常和下次重试 |

### 4.2 marker 缺失超过宽限期、`markSeckillUnknown` 影响行数为 0

恢复器必须先依据 UPDATE 影响行数分类：

- 影响行数为 1：本次确实把仍为 `PENDING` 的受理记录转为 `UNKNOWN`，保留现有错误原因和 ERROR 级别证据。
- 影响行数为 0：不得直接记录“已转 UNKNOWN”。按同一 `orderNo/eventId` 查询持久事实；若已为 `ACCEPTED` 且 outbox 存在并且标识一致，视为并发成功并静默；若为其他状态或事实矛盾，记录 WARN 冲突并进入原有核验/重试路径。

该分类不能通过把所有 0 行结果视为成功来实现，否则会吞掉订单号错误、哈希不一致、outbox 缺失和未知查询结果。

### 4.3 约束

- 不允许因为恢复器 CAS 失败而生成新的订单号、eventId、幂等键或 Redis 扣减。
- 不允许删除或覆盖 `ACCEPTED` 事实；不允许把已 `ACCEPTED` 的记录降级为 `UNKNOWN`。
- 事实核对必须使用原订单号和原事件标识，不能只按用户或 SKU 模糊查询。
- outbox 的 `PENDING` 与 `PUBLISHED` 都表示受理事实已经存在；恢复器不应因 outbox 尚未 publisher confirm 而重复写 outbox。
- 真实冲突和数据库不可用仍需保持告警、重试或人工核验，不能为了消除噪声而静默。

## 5. 测试与验收契约

backend 实现至少补齐以下聚焦测试：

1. 请求线程先提交 `ACCEPTED + outbox=PENDING`，恢复器随后 CAS 影响行数为 0：无 WARN/ERROR，最终 outbox 由既有 dispatcher 发布。
2. 请求线程先提交 `ACCEPTED + outbox=PUBLISHED`，恢复器随后 CAS 影响行数为 0：无 WARN/ERROR，不新增 reservation/outbox。
3. marker 缺失且超过宽限期，`markSeckillUnknown` 影响行数为 0，持久事实为 `ACCEPTED + outbox`：不记录“已转 UNKNOWN”，不降级状态。
4. CAS 影响行数为 0 但 outbox 缺失或哈希不一致：产生冲突告警，保留核验/重试事实，不创建重复 outbox。
5. marker 缺失且 UPDATE 影响行数为 1：仍转 `UNKNOWN`，保持现有后续恢复语义和错误证据。
6. 重跑 20 线程 × 10 请求短突发后，HTTP 结果、Redis 配额、reservation、outbox、订单、消费幂等和 Rabbit 主/DLQ 最终事实保持一致；恢复器不再为最终成功的竞争请求输出误导性的失败告警。

验收指标不要求固定吞吐数值；必须报告各并发档位的 2xx/业务 4xx/5xx、Redis 扣减量、受理记录数、outbox 状态、订单数、消费幂等数、主队列/DLQ 峰值和最终值，并将真实冲突与幂等竞争分类统计。

## 6. 责任与提交边界

- `pm_arch`：冻结本规格，不修改业务实现。
- `backend`：仅修改 `SeckillOrderOutboxDispatcher` 及其必要的恢复事实查询/测试；不得修改 API、表结构或消息契约。
- `qa`：执行并发阶梯和恢复竞态验收，保留原订单号、eventId、哈希及数据库/Rabbit/Redis 证据。

