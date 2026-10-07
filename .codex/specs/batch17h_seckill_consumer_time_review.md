# CM-20261007-20：秒杀消费者创建时间与原订单月份校验评审

## 1. 评审结论与范围

结论：**GO，限定保护 Order 秒杀消费者首次落单的显式事件时间和原 orderNo 月份归属；不修改 Stock、orderNo、历史消息载荷、API 或状态机。**

17G 修复了 Stock 新投递时间；Order 已进入队列的旧/异常消息仍可能在 `occurredAt` 缺失时使用当前 now，或在显式时间月份与原 orderNo 前缀不一致时写入 orderNo 指定的错误月表。

## 2. 首次创建时间门

在 `consumeSeckill` 已有 dedup 认领之后、ProductClient 调用和任何订单业务表写入之前，首次创建必须：

1. 要求事件带有显式 `occurredAt` 字段，且类型为受支持的明确字符串；缺失、null、空白、非字符串或无法解析均抛稳定 `ORDER_SECKILL_TIME_UNVERIFIED` 错误；
2. 复用 17F `OrderCreationTime` 的 same-instant → `+08:00`、DATETIME(3) 向下截断和 `1000..9999` 年校验；
3. 复用既有 `tableFromNo(orderNo)` 的严格 orderNo 格式/月份解析，要求规范化 occurredAt 的 Shanghai `YearMonth` 与原 orderNo 前六位完全一致；错月抛 `ORDER_SECKILL_TIME_UNVERIFIED`，不得重建 orderNo、猜测当前时间或改选表；
4. 通过校验后才读取 Product、写订单/明细/timeout outbox；createdAt、expireAt、明细和 outbox 继续使用该规范化时间。

必须提供纯工具 `OrderCreationTime.forSeckill(String orderNo,Object occurredAt)` 或等价 helper，统一严格读取字符串、解析/normalize、校验合法 orderNo 和月份并返回 normalized OffsetDateTime；不使用 `String.valueOf` 把任意对象猜成时间，不访问数据库或时钟。Order Service 的 `tableFromNo` 原有格式校验继续保留，helper 不替代订单表选择逻辑。

## 3. 幂等、事务和旧消息边界

时间校验必须位于现有 dedup `COMMITTED` 重放分支之后：

- 已有同 user/key、同 hash/orderNo 且 dedup 已 `COMMITTED` 的重复消息，继续按现有幂等结果标记 recovery resolved/返回，不因旧消息缺时间阻断 ACK；
- 新认领或未提交 dedup 的消息，时间缺失/非法/错月会使当前 `@Transactional` 回滚，原 Rabbit listener 的异常、重试、死信和 recovery 台账语义保持不变；
- 不能在失败前调用 Product、插入订单/明细/outbox 或 markCommitted；
- legacy 消息兼容仍保留既有核心字段和 address 默认规则，但首次创建同样不能用当前 now 补缺失 occurredAt；已提交重复消息仍按上一条幂等边界处理。

本轮不修改已发往 Order 的历史缺时间消息，不为错月旧消息重建原 orderNo 或补写历史载荷；无法核验的消息沿现有异常/重试/DLQ 处理。

`SeckillMessageRecovery.replayDue` 也必须在调用 Stock 验真 RPC 或重发原 payload 前复用同一时间/月 helper。缺失、null、blank、非字符串、解析失败或错月时，不调用 Stock、不发布 Rabbit 重试消息；以稳定原因 `ORDER_SECKILL_TIME_UNVERIFIED` 将 recovery 记录置为 `QUARANTINED`，沿既有 5 分钟退避/释放租约语义记录，保留原始 payload、eventId、orderNo 和 requestHash，不自动修复或改键，避免无效消息循环。Recovery 没有 consumer dedup 的已提交证据时不强行扩展跨服务依赖；正常 consumer 已有的 COMMITTED 重复消息继续按原幂等直接返回。

## 4. 边界与异常

- 事件 offset 必须按 same instant 转上海后再比较月份，不直接 `toLocalDateTime()` 丢 offset；
- DATETIME(3) 亚毫秒向下截断与 17F 一致；
- 原 orderNo 仍是 Stock 生成的固定幂等事实，消费者不得根据 occurredAt 改号或改表；
- `expireAt=created+30m` 的极端年份溢出不在本轮扩展为新错误码；若现有算术抛错，必须在事务内失败并保留原重试语义，后续再单独冻结清晰错误契约。

## 5. 验收矩阵

1. 首次消息显式合法 occurredAt 且月份匹配 orderNo：正常写订单、明细、outbox，月份和 DATETIME(3) 一致。
2. 缺失/null/blank/非字符串/非法 occurredAt：稳定 `ORDER_SECKILL_TIME_UNVERIFIED`，Product 不调用，订单事务回滚，Rabbit 原重试/DLQ 仍生效。
3. 显式时间与 orderNo 月份不一致：同样在 Product/DDL 前失败，不猜 now、不改 orderNo、不写错月事实。
4. 已 COMMITTED 同 hash/orderNo 重复消息缺时间：保持原幂等 ACK/resolved，不被新时间门阻断。
5. 非 `+08:00` offset、月末亚毫秒和跨年边界：按 17F normalize 后比较 orderNo 月份。
6. 真实 MySQL/Order Service 结合现有 Rabbit retry/DLQ 夹具验证首次创建、回滚、重复消息、Recovery 无效事件隔离和原库 checksum；不新增 RPC/API。

## 6. 实施门与范围声明

实现限定在纯时间/月校验 helper、`consumeSeckill` 首次创建门和 `SeckillMessageRecovery.replayDue` 的投递前时间门；不改 Stock candidate、历史消息 payload、orderNo、状态机、Product/Stock API、Rabbit 拓扑或默认 dynamic-monthly。17H 完成只保护消费者首次落单归属并避免无效 recovery 重放，不表示旧历史消息已全部重建。
