# CM-20261009-17DE：订单 Outbox 已到期消息直达死信路由评审

## 1. 结论与范围

结论：**GO，仅为已到期的 timeout outbox 消息选择既有 DLX 直达路由，消除 TTL 队列的 head-of-line 阻挡。**

当前 dispatcher 将所有消息发送到既有 TTL 队列，并把已过去的 `expireAt` 也压成 `1ms`。当长 TTL 消息位于队头时，后面的已过期消息可能在队列中等待；publisher confirm 只确认消息进入交换机/路由链路，不能证明订单已及时关单。RabbitMQ 官方 TTL 语义说明，消息过期后通常在到达队头时才被清理并死信，过期消息可能排在未过期消息后面形成阻挡（[RabbitMQ TTL 文档](https://www.rabbitmq.com/docs/ttl)）。

本批只处理 dispatcher 已读取并解析出 `expireAt` 的 timeout 快照：过去或恰好当前的事件直达既有 `OrderMessagingConfiguration.DLX`，未来事件保持既有 `TIMEOUT_EXCHANGE` + per-message TTL。订单事实时间判断、消费者状态/CAS、future TTL 队头阻挡、DLQ 消费重试和其他消息类型不扩展到本批。

## 2. 到期判断与路由契约

`parseExpiration` 继续兼容带 offset 的 `OffsetDateTime` 和无 offset 的 `LocalDateTime`（按 `Asia/Shanghai` 解释）。在 `remainingTtlMillis` 中使用同一上海业务当前时刻计算 `Duration`：

- `Duration <= 0`：返回 `0`，必须在调用 `toMillis()` 前判定，避免已过去的超大负 Duration 转毫秒溢出；
- `Duration > 0`：保持既有未来时间策略，返回至少 `1ms` 的 per-message TTL；未来时间解析失败、溢出或非法格式继续沿原 payload/发送失败退避，不猜测为已到期。

dispatcher 根据 TTL 结果选择 exchange，routing key 始终为空字符串：

| 快照时间 | exchange | message expiration | 目的 |
| --- | --- | --- | --- |
| `expireAt <= now` | `OrderMessagingConfiguration.DLX` | 清除为 `null` | 通过既有 DLX→DLQ binding 直接交给 timeout consumer |
| `expireAt > now` | `OrderMessagingConfiguration.TIMEOUT_EXCHANGE` | `String.valueOf(max(1, ttlMillis))` | 保持既有 TTL 队列延迟死信 |

到期路由的 postprocessor 必须清除 message properties 的 `expiration`（`null`），避免直达 DLQ 的消息再次携带已过期 TTL；未来路由继续设置原毫秒 TTL。两条路由均保留原 envelope、`eventId`、`eventType`、`orderNo`、`expireAt`、`occurredAt`、`traceId`、payload、`CorrelationData` 和 mandatory 设置。

## 3. confirm、returned 与 outbox 完成边界

两条路由共用既有 publisher confirm/mandatory returned 检查：只有 confirm ACK 且 `returnedMessage == null` 才调用 `markOutboxPublished`，随后继续 17DD 的 0 行 publication facts proof、CU 中断和 CS 数据库异常隔离。confirm 成功但 direct DLX 没有对应 binding 时，Rabbit mandatory return 必须使本次发送进入原 failure/退避路径，保持 `PENDING`，不得把交换机 confirm 当作已进入 DLQ 或已处理订单的证明。

本批不新增 failure 写、不重发策略、不改变 `markOutboxPublished` SQL、17DD partial proof、eventId/orderNo/type/attempts 绑定、returned 语义或中断边界；消息发送成功后完成写异常仍保留原行供同一 eventId 重试。

## 4. 拓扑、Schema 与非目标

- 复用既有 `DLX`、`DLQ` 和 binding；不新增 exchange、queue、binding、consumer、插件或 Rabbit 配置拓扑，不修改 `OrderMessagingConfiguration` 的名称和声明。
- `order_outbox` 表、字段、索引、状态机、payload 和 SQL 均不变；API 无新增公开或内部字段。
- 不宣称所有 TTL 延迟或队头阻挡问题均被解决：未来消息仍使用原 TTL FIFO，只有 dispatcher 已采样且已到期的消息走直达 DLX；消费者读取订单事实的时间窗口仍由既有业务规则决定。
- 不改变 payload 中原时间、trace、身份字段，不用当前时间重建 envelope，不引入新的消息 ID 或 MQ/SQL 原子证明。

## 5. 验收矩阵

- unit：覆盖 offset 与无 offset/上海 LocalDateTime 解析；过去、恰好当前返回 `0` 并走 DLX 清 expiration；未来至少 `1ms` 并走原 TIMEOUT exchange；非法/溢出未来时间继续原失败语义；确认、returned、17DD proof、CU/CS 使用原边界。
- 真实 RabbitMQ 3.11 + 隔离 UUID vhost `cm17de_*` + 随机 MySQL 数据库：future 长 TTL head 与 past tail 在约 3 秒窗口内，past 能经既有 DLX 到达 timeout consumer/完成真实 SQL `PUBLISHED`，future 仍留在 TTL 队列；核对真实 publisher confirm、returned 和订单 outbox 状态。
- 在隔离 vhost 移除/不声明 direct DLX binding 时，past 发布必须收到 mandatory return，保持 `PENDING` 并执行原 failure 记录；不将 confirm ACK 当作完成，不污染原业务 vhost、服务或容器。
- 验收资源只清理名称匹配 `cm17de_*` 的 vhost、队列、消息和随机测试库；若证据未闭合则保留现场，不扩大清理范围。

## 6. 交付边界

本评审只冻结规格；backend 按本文件及 API/DB 相邻条款实现和验收。本轮不修改业务代码、测试或 Maven 配置。
