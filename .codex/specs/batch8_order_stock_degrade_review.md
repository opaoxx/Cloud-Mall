# CM-20261005-27：Order→Stock Sentinel 异常与慢调用熔断规格评审

## 1. 评审结论

**GO，范围限定为 Order 服务发出的 Stock Feign 调用。**本轮可以安全落地 Sentinel `DegradeRule`、Nacos 规则快照、本地兜底和故障分类，但不能把熔断当成库存结果判定，也不能在熔断后自动换订单号、自动重试写请求或释放库存。

本轮覆盖四类调用资源：

| 资源 | 调用位置 | 熔断后的语义 |
| --- | --- | --- |
| `order-stock-reserve` | 普通订单创建及 `OrderReservationRecovery` | 普通创建返回 `ORDER_RESERVATION_UNKNOWN`/202 并保留原意图；恢复任务保持 UNKNOWN、退避重试原订单号 |
| `order-stock-seckill` | 秒杀同步受理 | 返回 503 `GATEWAY_SERVICE_UNAVAILABLE`；客户端使用原 `Idempotency-Key` 查询/重试，不创建第二个请求事实 |
| `order-stock-status` | 普通预扣状态查询、支付状态兼容查询、动作恢复前置查询 | 只返回或记录 UNKNOWN，恢复租约保留，不能推断 NOT_FOUND、RESERVED 或终态 |
| `order-stock-action` | 订单已提交后的 confirm/rollback 恢复 | 当前动作保持 `PENDING/UNKNOWN` 并退避；订单已提交状态不可回滚，不能执行相反动作 |

Stock 服务内部 Redis/MySQL 调用、Stock 自身秒杀 flow 限流和跨实例集群熔断不在本轮；它们另立规格，避免把调用方熔断误当作库存端事务保护。

## 2. 已有状态机和不可破坏的不变量

1. 普通下单先独立提交 `OrderReservationIntent(status=INIT)`，再调用 `stock.reserve`。远程超时可能代表未执行、已执行但响应丢失或服务端正在处理，必须只用原 `orderNo` 查询和幂等恢复。
2. `OrderReservationRecovery` 已按原订单号查询 `reservation`，必要时使用原快照重放同一 `reserve`，随后只对已确认 `RESERVED` 调用幂等 `rollback`。熔断阻断任何一步都只能记录失败、延迟下一轮，不能标成 `REJECTED` 或 `ROLLED_BACK`。
3. 秒杀 Stock 端先 claim 幂等请求，再进行 Redis/MySQL/outbox 受理。Order 端遇到下游 429/409 业务结果必须保留现有映射；传输超时和 5xx 的结果未知，必须返回 503 并保留相同幂等键。
4. 支付成功、取消和超时关单先在 Order 本地 CAS 后插入 `order_stock_action`。`OrderStockActionRecovery` 以唯一 `operationKey` 查询、重试、再查最终事实；confirm/rollback 的熔断只延迟动作，不能修改已提交的订单终态。
5. `409` 库存不足、幂等键冲突、状态冲突、秒杀未开始/已结束/售罄和用户限购是明确业务结果，不得计入 Sentinel exception ratio，也不得触发熔断。Stock 429 中明确的 `GATEWAY_RATE_LIMITED`、`SECKILL_LIMIT_REACHED` 同样不是异常。

## 3. Sentinel 规则和来源契约

新增 Order 专用 Data ID：`cloudmall-order-degrade-rules`，Group：`CLOUDMALL_DEV`。内容是严格 JSON 数组，必须恰好包含上述四个资源各一条 `DegradeRule`，禁止夹带其它资源或未知字段：

```json
[
  {"resource":"order-stock-reserve","grade":0,"count":3000,"slowRatioThreshold":0.5,"minRequestAmount":20,"statIntervalMs":60000,"timeWindow":10},
  {"resource":"order-stock-seckill","grade":1,"count":0.2,"minRequestAmount":20,"statIntervalMs":60000,"timeWindow":10},
  {"resource":"order-stock-status","grade":1,"count":0.2,"minRequestAmount":20,"statIntervalMs":60000,"timeWindow":10},
  {"resource":"order-stock-action","grade":1,"count":0.2,"minRequestAmount":20,"statIntervalMs":60000,"timeWindow":10}
]
```

字段语义按 Sentinel 1.8.6 固定：`grade=0` 为 `SLOW_REQUEST_RATIO`，`grade=1` 为 `EXCEPTION_RATIO`；`grade=0` 时 `count` 是触发慢调用统计的最大响应时间，单位毫秒，`slowRatioThreshold` 才是慢调用比例；`grade=1` 时 `count` 是异常比例，取值范围为 `0 < count <= 1`。`timeWindow` 单位秒。实现必须按规则类型校验：慢调用规则校验有限正数的 `count`（本项目上限 60000 毫秒）和 `0 < slowRatioThreshold <= 1`；异常比例规则校验 `0 < count <= 1`，并忽略或要求 `slowRatioThreshold=0`；两类规则都校验 `minRequestAmount >= 1`、`statIntervalMs >= 1000`、`timeWindow >= 1`，并限制时间窗口在合理配置范围内。规则不得使用 `EXCEPTION_COUNT`，不得开启 cluster mode。

规则快照启动顺序：先加载可配置本地默认规则，再订阅 Nacos；连接失败、超时、空内容、未知字段、资源缺失、重复资源或非法阈值时保留最近一次有效快照并每 5 秒重试。替换时只替换四个 Order→Stock 资源，保留进程内其它 Sentinel flow/param/degrade 规则。停止时移除 listener 并关闭本服务创建的 Nacos 客户端。Dashboard 临时修改不视为持久来源。

本地默认值必须可通过配置覆盖，默认值应与仓库 JSON 一致；默认规则仅是每个 Order 实例的保护阈值，不是全局配额。第一次部署必须先发布 Nacos 配置，再滚动重启 Order；Nacos 不可用时仍以本地默认规则启动。

## 4. 调用包装和结果分类契约

在 Order 统一封装 `SphU.entry(resource)` 与 Feign 调用，资源名固定，所有 `Entry` 必须在 `finally` 退出。不得在业务方法中复制规则加载或直接调用 `DegradeRuleManager`。

调用前被 Sentinel `BlockException` 阻断时：

- `reserve`：构造 `ORDER_RESERVATION_UNKNOWN`、HTTP 202，意图保持 INIT/UNKNOWN，禁止盲目调用 rollback。
- `seckill`：返回 503 `GATEWAY_SERVICE_UNAVAILABLE`，不新增 Order/Stock 事实；客户端仍使用原幂等键查询或重试。
- `status` 和 `action`：抛出内部可识别的临时异常，由现有恢复器写 UNKNOWN/退避，不能把阻断解释为 NOT_FOUND。

远程调用分类必须先按响应业务码分类，再决定是否向 Sentinel 记录异常：

- 明确 2xx 且 `code=0`：成功，不计异常。
- 明确的 4xx 业务结果，包括库存不足、冲突、限购、活动状态和业务 429：返回既有业务语义，不计异常。
- Feign connect/read timeout、连接拒绝、DNS/服务发现失败、响应无法解析、HTTP 5xx，以及下游返回非业务格式：计为一次异常；对写操作结果仍标记 UNKNOWN。
- `BlockException` 是本地熔断结果，不作为远程异常再次记录；指标单独记录 `sentinel.degrade.block`。

慢调用统计必须让 Feign 调用正常返回后由 Sentinel 计算，不得用本地手工计时重复调用 `SphU`。超时导致的异常同时会被异常分类；未超时但超过 `count` 的调用进入慢比例统计。

## 5. 对现有恢复器的最小改动契约

- `OrderReservationRecovery`：熔断、超时和 5xx 走现有 `recordFailure(nextRetryAt)`；不改变原订单号、原 SKU 快照和状态查询顺序。只有读取到明确 `RESERVED/REJECTED/ROLLED_BACK` 等持久事实时才推进状态。
- `OrderStockActionRecovery`：熔断、超时和 5xx 走现有 `retry`；保持 operationKey 不变。仅明确目标终态才 `SUCCEEDED`，明确相反终态才 `CONFLICT`。
- `legacyStockActionStatus`：熔断视为 UNKNOWN，不能因熔断把支付恢复误判为库存动作成功。
- 不新增 Feign 自动重试；保持项目约定的写调用无重试。只有恢复任务已有的受控、同 operationKey/订单号退避重放可继续运行。

## 6. 验收契约

1. 单元测试覆盖四资源规则严格解析、空/未知字段/缺失/重复/非法比例拒绝、Nacos 不可达本地兜底、非法刷新保留旧快照、停止释放 listener，以及不污染其它规则。
2. 调用分类测试覆盖明确业务 409/429 不增加异常计数，Feign timeout/503 增加异常计数；BlockException 映射分别得到 202、503 或恢复退避。
3. 普通下单故障注入：Stock 在 reserve 调用前延迟/拒绝，接口得到 `ORDER_RESERVATION_UNKNOWN`/202，只有一条 INIT 意图，无订单、无新增库存回滚；恢复器恢复后仍使用原 orderNo，并最终依据 Stock 事实收敛。
4. 秒杀故障注入：Stock 返回 429/409 时现有业务码不变；传输超时或 503 时返回 503，`seckill_request_claim`、库存流水、Redis 扣减和 outbox 不因 Order 熔断新增重复事实；相同幂等键恢复后最多一条受理事实。
5. confirm/rollback 恢复故障注入：熔断和远程 UNKNOWN 保留 `order_stock_action` 为 `UNKNOWN`，不改变已提交的 `PAID/CANCELLED`，恢复后使用相同 operationKey；目标终态和相反终态分别验证 SUCCEEDED/CONFLICT。
6. 两个 Order 实例连接同一 Nacos，规则修改最终在两实例生效；Nacos 断开时两实例保持最近有效规则。执行 Order 模块测试、全仓适用 Maven 构建和 JMeter 1/5/10/20 并发；记录 Sentinel 阻断数、业务 409/429、503、恢复台账和库存事实，确认无重复扣减。

## 7. GO/NO-GO 边界

本规格对 Order→Stock 调用为 **GO**，因为现有持久意图、幂等订单号、库存状态查询和 `order_stock_action` 已提供未知结果的承载位置。对 Stock 内部 Redis/MySQL 熔断为 **NO-GO（本轮）**：需要另行定义 Redis Lua、MySQL 事务、缓存修复意图和库存流水的异常分类与降级状态，直接套用本规格会把未执行、执行成功但响应丢失和事务回滚混为一谈。
