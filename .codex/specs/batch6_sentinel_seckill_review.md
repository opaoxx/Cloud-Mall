# CM-20260924-17：Sentinel 秒杀限流与故障语义评审

## 1. 评审范围与结论

本规格只处理秒杀入口的 Sentinel 保护、热点参数限流、规则来源、429/503 响应和幂等重试语义。它不改变秒杀 API 字段、Redis Lua 库存扣减、`seckill_reservation`、`seckill_order_outbox`、RabbitMQ 事件或订单状态机，也不引入复杂风控组件。

评审结论：现有工程虽然编排了 Sentinel Dashboard，但 Gateway、order、stock 三个模块都没有 Sentinel 客户端依赖、资源标记或规则配置；当前流量只能依赖 Redis 的库存/限购脚本，不能提供请求速率保护。CM-17 需要在 Gateway 和秒杀业务服务建立两层保护，并保留 Redis、MySQL、RabbitMQ 原有事实链路。

## 2. 当前实现证据与边界

- `cloud-mall-gateway` 的 `order` 路由同时匹配普通订单和 `/api/seckill/orders`，没有独立的秒杀路由资源。
- `AuthenticationFilter` 位于 Gateway 全局最高优先级；秒杀订单不属于公开 GET，必须先完成 Bearer Token Redis 校验，再进入限流和路由。Token Redis 故障必须继续返回 `503 REDIS_SERVICE_UNAVAILABLE`，不能被 Sentinel 降级成 429。
- Gateway 将 `/api/seckill/orders` 转发到 `cloud-mall-order`。order 的 `OrderServiceImpl.seckill` 校验地址后通过 Feign 调用 stock 的 `/api/stock/seckill/reservations`；该内部调用带 `X-User-Id/X-User-Role`，不经过 Gateway。
- stock 入口当前依赖 `AuthContext` 和请求归属校验。Gateway 的 `/api/stock/**` 路径已禁止外部转发，因此 stock 的 Sentinel 资源只能保护内部 Feign 路径和防止服务被绕过调用，不能替代 Gateway 入口保护。
- order 当前把所有下游 HTTP 429 都解释为 `SECKILL_LIMIT_REACHED`。接入 Sentinel 后必须按响应业务码区分“网关/系统流控”与“用户限购”，否则 Sentinel 拒绝会被错误展示为业务限购。

## 3. 固定依赖与规则来源

### 3.1 客户端依赖

只允许使用与现有版本线兼容的 Sentinel 1.8.6 组件：

| 模块 | 必需能力 | 依赖方向 |
| --- | --- | --- |
| Gateway | Spring Cloud Gateway 资源适配、Gateway 流控、Sentinel transport | `com.alibaba.cloud:spring-cloud-starter-alibaba-sentinel`、`com.alibaba.cloud:spring-cloud-alibaba-sentinel-gateway` |
| order | Web/MVC 资源保护、热点参数、降级 | `com.alibaba.cloud:spring-cloud-starter-alibaba-sentinel` |
| stock | 内部秒杀资源保护、降级 | `com.alibaba.cloud:spring-cloud-starter-alibaba-sentinel` |

具体 artifact 版本由现有 Spring Cloud Alibaba BOM `2021.0.5.0` 管理；该 BOM 对应 Sentinel 客户端 `1.8.6`。Nacos 动态数据源由 starter 的传递依赖提供；若需要显式声明，只能使用 `com.alibaba.cloud:spring-cloud-alibaba-sentinel-datasource:2021.0.5.0`，不得单独升级 Sentinel 或引入另一套限流框架。Dashboard 继续使用 Compose 中的 `bladex/sentinel-dashboard:1.8.6`，端口和认证约定保持不变。实现前必须执行 Maven 依赖解析，确认没有被本地缓存的旧版 `2021.0.4.0` 或 Sentinel `1.8.5` 覆盖。

### 3.2 规则来源与默认值

- 规则的权威持久来源为 Nacos 配置数据源，使用开发环境隔离的 Sentinel 规则 Data ID/Group。推荐固定为以下四组，便于启动、变更和回滚：`cloudmall-gateway-flow-rules`/`CLOUDMALL_DEV`（Gateway API 流控）、`cloudmall-order-flow-rules`/`CLOUDMALL_DEV`（order 流控）、`cloudmall-order-param-flow-rules`/`CLOUDMALL_DEV`（order 热点参数）、`cloudmall-stock-flow-rules`/`CLOUDMALL_DEV`（stock 内部保护）。熔断降级使用 `cloudmall-order-degrade-rules` 和 `cloudmall-stock-degrade-rules`，仅在本轮实现确实装配 `DegradeRule` 数据源后启用；否则不能声称已完成熔断验收。
- 服务启动时必须先装载可解析的 Nacos 规则；Nacos 不可用或规则格式错误时，服务使用代码内置的安全默认规则继续提供有限保护，并记录明确告警。不得因为 Dashboard 不可用而关闭本地保护。
- Dashboard 是运行时观察和人工调参入口。Dashboard 的临时推送允许立即作用于客户端；若修改没有写回 Nacos，重启后以 Nacos 规则或代码默认规则恢复，不能声称 Dashboard 内存修改已持久化。
- 每个 Nacos Data ID 的内容是对应规则类型的 JSON 数组，不能把不同类型的规则混放在同一个 Data ID。示例默认值如下（阈值只是本地验收基线，正式值可由 Nacos 覆盖）：

  ```json
  [{"resource":"seckill-order","grade":1,"count":20,"limitApp":"default","strategy":0,"controlBehavior":0,"warmUpPeriodSec":0,"maxQueueingTimeMs":0,"clusterMode":false}]
  ```

  ```json
  [{"resource":"seckill-order-submit","grade":1,"count":20,"paramIdx":0,"durationInSec":1,"controlBehavior":0,"maxQueueingTimeMs":0,"paramFlowItemList":[]}]
  ```

  Gateway API 流控使用 Gateway 规则字段（`resource`、`resourceMode`、`grade`、`count`、`intervalSec`、`controlBehavior`、`burst`）；order/stock 普通流控使用 `FlowRule` 字段；热点参数使用 `ParamFlowRule` 字段。字段必须与 Sentinel 1.8.6 的 JSON 序列化契约一致，并通过反序列化测试拒绝未知或缺失关键字段。
- Nacos 动态数据源的配置键按 Spring Cloud Alibaba 2021.0.5.0 约定使用 `spring.cloud.sentinel.datasource.<name>.nacos.server-addr`、`username`、`password`、`data-id`、`group-id`、`rule-type`。客户端只订阅与自身服务相关的 Data ID，不能把 Gateway 规则误装到 order/stock。
- 不把 Token、密码或用户请求内容写入规则。规则中只允许资源名、阈值、控制行为、时间窗口和有限的参数索引。
- 默认阈值必须可配置并可在本地压测中验证，不能硬编码到 API 逻辑。默认值以小规模本地环境为基线：Gateway 秒杀路由按实例限流，order 按用户热点参数限流，stock 使用高于 Gateway 的内部保护阈值；实际阈值由 Nacos 开发配置覆盖。

### 3.3 规则加载失败与 Dashboard 冲突

启动时先注册代码内置规则，再创建 Nacos `ReadableDataSource`。因此 Nacos 首次连接失败、Data ID 不存在或 JSON 校验失败时，服务仍有最小保护；成功读取后由数据源发布完整规则。刷新失败保留最近一次有效规则，不清空规则表。

Dashboard 直接向 Sentinel transport 推送的是客户端内存规则，优先级高于当前内存值，但不会自动写入 Nacos。Nacos 下一次变更或服务重启会覆盖该临时值。CM17 不实现自定义 Dashboard→Nacos 双写适配，运维验收必须把长期配置写入 Nacos；需要双写审计、规则版本和控制台持久化时另立运维问题。

## 3.4 本轮最小可验收范围

为避免把 Sentinel 接入、规则中心、熔断和运维控制台一次性混成不可回溯改动，本轮 CM17 的闭环门槛是：

1. Gateway 独立 `seckill-order` 路由及 429 BlockHandler；
2. order `seckill-order-submit` 用户热点参数和活动参数的明确入口保护；
3. Gateway/order 的 Nacos flow/param-flow 数据源、代码默认规则和加载失败兜底；
4. order 对 Sentinel 429 与 Redis 用户限购 429 的业务码区分，以及原幂等键重试语义；
5. stock 先保留为内部入口的固定保护资源和默认规则，只有完成对应测试后才把 stock 动态规则列入本轮完成；
6. Sentinel 熔断降级、Dashboard 规则双写和跨实例全局配额不作为 CM17 的完成条件，分别记录为后续稳定性任务，避免用未实现的 503/持久化能力宣称闭环。

## 4. 资源、规则和调用顺序

### 4.1 Gateway 路由级保护

将当前 Gateway `order` 路由拆出一个更具体的 `seckill-order` 路由，匹配 `/api/seckill/orders` 和 `/api/seckill/orders/**`，并保持原普通订单路由匹配范围。秒杀路由必须排在普通 order 路由之前，目标仍为 `lb://cloud-mall-order`。

Gateway Sentinel 资源名固定为路由 ID `seckill-order`。规则至少包括：

- 按实例 QPS 的流控，限制突发请求进入 order；
- 必要的预热/直接拒绝行为由规则配置控制；
- 规则命中后返回 429 JSON `{code:"GATEWAY_RATE_LIMITED",message,data:null,requestId}`，设置 `Cache-Control: no-store`，不转发到 order；
- Gateway 的流控统计不读取请求体、不解析地址快照，不以 Bearer Token 原文作为参数；鉴权完成后可使用已注入的 `X-User-Id` 作为有限参数，但用户级核心限购仍由 order/Redis 业务事实裁决。

过滤顺序冻结为：Gateway 访问日志/Trace 上下文 → Bearer 鉴权及 Redis 可用性校验 → Sentinel 路由流控 → 路由转发。鉴权失败返回 401，鉴权 Redis 失败返回 503，只有已通过鉴权的超额请求返回 Sentinel 429。

### 4.2 order 业务资源与热点参数

order 秒杀入口在完成 `AuthContext.requireUserId()` 和基础参数校验后、读取地址及调用 stock 前进入 Sentinel 业务资源 `seckill-order-submit`。业务资源使用用户 ID 作为热点参数，避免同一用户在多个实例上无限重复占用下游资源；Sentinel 拒绝时不执行地址查询、Feign 调用、Redis 预扣或持久化。

活动参数 `activityId` 作为第二个热点参数只用于活动维度的保护或观测，不从请求体传给 Gateway Sentinel。由于当前 `@PostMapping` 入口已经绑定 `SeckillOrderDTO`，order 内部可以在参数校验后使用 `activityId` 进入同一次 Sentinel 业务资源；若采用 `@SentinelResource`，必须保证参数位置和 BlockHandler 契约稳定，禁止通过反射或重新解析 JSON 获取参数。

用户限购仍由 stock Redis Lua 和持久化受理事实决定，返回 `SECKILL_LIMIT_REACHED`/429。Sentinel 的用户热点限流也返回 429，但业务码必须是 `GATEWAY_RATE_LIMITED`，以便调用方区分短时流控与永久/活动维度限购。

### 4.3 stock 内部资源

stock 的 `/api/stock/seckill/reservations` 进入资源 `seckill-stock-reservation`，仅作为内部 Feign 的第二道保护。该资源使用较宽的 QPS/并发默认阈值和异常降级，避免 order 的正常流量在两层相同阈值下被重复削减；它不能绕过 `AuthContext`、用户归属校验、请求认领、Redis Lua 或持久化受理事务。

stock 被 Sentinel 拒绝或降级时返回固定 429/503 业务响应。order 解析 Feign 错误体：

- `GATEWAY_RATE_LIMITED` → 原样映射 429，保留“稍后重试”语义；
- `SECKILL_LIMIT_REACHED` → 原有用户限购 429；
- Sentinel 降级 503 → `GATEWAY_SERVICE_UNAVAILABLE`/503；
- 其他 409/503 继续按既有秒杀活动、Redis 和库存错误语义处理。

## 5. 幂等、重试与 Sentinel 拒绝语义

- Sentinel 429 是“本次请求未进入秒杀业务”的明确拒绝。它不得创建 `seckill_request_claim`、Redis marker、库存扣减、受理台账或 outbox。
- 客户端可携带同一个 `Idempotency-Key` 稍后重试。由于 429 没有创建受理事实，重试仍按既有请求哈希和用户/活动/SKU 认领流程执行；不得因为前一次 Sentinel 429 生成新的订单号或改变原请求载荷。
- order 内部 Feign 对 POST 秒杀调用不启用自动重试。若 stock 返回 Sentinel 429/503 或调用结果未知，order 不重复调用 stock，不生成新订单号；现有持久认领/恢复链路继续按原订单号核对事实。只有明确的 429 且响应体可解析时，才向前端返回对应业务码。
- Gateway/客户端不得对 Sentinel 429 做透明自动重试；重试由用户或上层业务按原幂等键发起。已有 Feign 规则“仅幂等 GET 有限重试”保持不变。
- 若 Sentinel 在 order 已进入资源后才拒绝，BlockHandler 必须在业务副作用前返回；不能在异常处理里回补 Redis，因为此时不应存在本次请求的扣减事实。

## 6. 熔断、降级和故障语义（后续扩展边界）

本节定义目标语义，只有在第 3.4 节列出的最小范围之外继续实施熔断时才适用；它不扩大 CM17 的首轮闭环门槛。

- Sentinel 熔断只覆盖可识别的下游慢/异常窗口，至少观察 order→stock Feign、stock→Redis/MySQL 的异常或慢调用；不把业务 409（未开始、已结束、售罄、限购）计为系统异常。
- 降级响应固定为 503 `GATEWAY_SERVICE_UNAVAILABLE`，包含 `Cache-Control: no-store`，不得返回异常堆栈、账号、规则内容或请求体。
- Redis 连接失败仍由既有逻辑返回 503 `REDIS_SERVICE_UNAVAILABLE`，不可被 Sentinel fallback 伪装成 `accepted=false`、售罄或用户限购。MySQL/RabbitMQ 的持久化事实和 outbox 恢复规则不由 Sentinel 替代。
- Dashboard、Nacos 规则源或 Sentinel transport 不可用时，不影响已经加载的本地规则和业务启动；规则刷新失败记录监控指标和告警。

## 7. 可观测性

至少提供以下 Prometheus 指标或等价 Micrometer 计数：

- `sentinel_block_total`：按服务、资源、规则类型和响应码统计拒绝；不得包含 Token 原文、地址快照或完整幂等键。
- `sentinel_degrade_total`：按资源和异常类型统计降级。
- 秒杀入口现有受理、库存、outbox、订单和 Redis 计数继续作为事实指标，且能够区分 `409` 业务拒绝、`429` Sentinel/限购拒绝和 `5xx` 系统失败。
- 日志包含 requestId/traceId、资源名、规则类型、活动 ID（如已有安全日志上下文）和 HTTP 状态；用户 ID、幂等键只按项目现有日志脱敏约定记录。

## 8. 测试与验收门槛

实现前后必须先运行现有模块测试，再执行以下聚焦验收：

1. Gateway 路由断言：秒杀路径命中 `seckill-order`，普通订单仍命中 `order`；秒杀路由优先级和鉴权顺序正确。
2. Gateway 鉴权矩阵：无 Token=401，Token Redis 故障=503，合法 Token 超阈值=429 `GATEWAY_RATE_LIMITED`。
3. order 资源：Sentinel 429 不产生数据库认领、Redis 扣减、受理或 outbox；规则恢复后同一个幂等键可成功继续。stock 内部资源只有在对应实现纳入本轮时才执行相同验收。
4. 热点参数：同一用户连续请求受到用户级规则限制；不同用户不因单个用户的热点参数被全部阻断；活动参数规则按活动维度生效。
5. 业务区分：Redis Lua 用户限购仍返回 `SECKILL_LIMIT_REACHED`，不能被映射为 `GATEWAY_RATE_LIMITED`；stock 的 Sentinel 429 由 order 原样区分。
6. 幂等重放：Sentinel 429 后使用原 `Idempotency-Key` 重试只产生一次请求认领、一次 Redis 扣减、一个 outbox 和一条订单；Sentinel 503/Feign 未知结果不自动重发 POST。
7. 规则故障：Nacos 暂时不可用、Dashboard 不可用、规则 JSON 无法解析时，Gateway/order 代码默认规则仍有效；服务重启不会无保护启动。stock 的动态数据源属于后续扩展，未实现时必须在日志和台账中明确。
8. 阶梯与短突发：至少复用 `backend/loadtest/seckill-ladder.jmx` 的 1/2/5/10/20 并发档位和短突发，分别统计业务 409、用户限购 429、Sentinel 429、503、受理/outbox/订单/Redis 最终事实；不得用单一 HTTP 200 比例宣称限流正确。
9. 全仓门槛：完整 Maven `clean test package`、适用的接口/契约检查、`git diff --check` 通过；验证完成后复核 Gateway→order→stock→Redis/MySQL/RabbitMQ 调用链和日志语义。

## 9. 实施与提交边界

实现阶段只允许修改 Gateway、order、stock 的 Sentinel 依赖/配置/资源保护及必要的 common 错误映射测试；不修改前端、不修改 API 字段、不替换 Redis Lua 或 RabbitMQ/Seata 技术链路。Nacos 数据源的 Data ID/Group、默认规则和 Dashboard 运维说明必须随配置规格一并评审。每轮以可回溯提交交付，`bugfix_log.md` 在修复前记录现象，修复后补充根因、方案和验证结果。

## 10. 残余风险

- Gateway 的路由级 QPS 是实例级保护；多实例全局配额不在本轮承诺范围内。
- Sentinel 规则只保护请求进入和服务依赖，不能证明秒杀最终支付/取消配额结算，后者属于 CM-20260925-24。
- Dashboard 的运行时修改若未配置持久化写回，重启后会丢失；验收必须检查 Nacos 规则源和服务重启行为。
- 秒杀路径的请求体热点参数无法在 Gateway 不读取 body 的前提下直接提取；活动级热点保护放在 order，Gateway 只做路由级和可用 Header 级保护。
