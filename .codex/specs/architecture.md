# CloudMall 云购微商城系统架构规格

## 1. 架构目标与边界

CloudMall 采用 Spring Boot + Spring Cloud Alibaba 微服务架构，按单一职责拆分服务。网关作为统一入口，服务通过 Nacos 注册发现和配置管理；业务服务独立部署、独立迭代。本文只定义设计边界，不提供业务实现代码；Docker Compose 作为本地开发部署编排入口，具体脚本属于后续交付物。

## 2. 固定技术栈

| 层次 | 技术与版本 | 落地场景 |
| --- | --- | --- |
| 服务框架 | Spring Boot 2.7.18；Spring Cloud 2021.0.5；Spring Cloud Alibaba 2021.0.5.0 | 微服务基础运行时 |
| 入口 | Spring Cloud Gateway 3.1.5 | 路由、鉴权、跨域、过滤、限流预处理 |
| 注册配置 | Nacos 2.2.0 | 注册发现、配置管理、开发/测试隔离 |
| 稳定性 | Sentinel 1.8.6 | 秒杀限流、热点参数、熔断降级 |
| 事务 | Seata 1.5.2 | 订单/库存及支付/订单一致性协作 |
| 异步 | RabbitMQ 3.11-management | 超时关单、支付通知、库存处理、浏览统计 |
| 缓存 | Redis 7.0 | 登录态、购物车、热点数据、秒杀库存、限流计数 |
| 数据 | MySQL 8.0；Sharding-JDBC 4.1.1 | 业务持久化、订单按时间分表 |
| 检索日志 | Elasticsearch 7.17.0 + Kibana 7.17.0 | 商品检索、日志分析 |
| 观测 | Micrometer Tracing 1.9.5 + Zipkin 2.23.19；Prometheus 2.47.0 + Grafana 9.5.5 | 链路与指标监控 |
| 前端/代理 | React + Vite；Nginx | 页面构建、静态资源代理、请求分发 |
| 环境 | Docker Compose | 本地开发编排基础设施与 Nginx；业务服务前期保持 IDEA 本地运行，后期再镜像化 |

## 3. 服务划分与依赖

| 服务 | 职责 | 主要依赖/边界 |
| --- | --- | --- |
| `cloud-mall-gateway` | 统一入口、路由、Token 鉴权、过滤、跨域、限流预处理 | Nacos、Sentinel；不承载业务数据 |
| `cloud-mall-user` | 注册、登录、权限、个人信息、登录态 | MySQL、Redis；用户事实数据归属本服务 |
| `cloud-mall-product` | 商品/分类/参数 CRUD、上下架、ES 检索、热度统计 | MySQL、ES、Redis、RabbitMQ；商品事实数据归属本服务 |
| `cloud-mall-cart` | Redis 购物车、勾选结算、跨设备同步 | Redis、商品服务查询；不负责库存扣减 |
| `cloud-mall-order` | 订单创建、快照、状态流转、关单、分表 | MySQL、Sharding-JDBC、Seata、RabbitMQ；订单事实数据归属本服务 |
| `cloud-mall-stock` | 预扣、确认、回滚、防超卖、秒杀库存 | MySQL、Redis、Seata、RabbitMQ；库存事实数据归属本服务 |
| `cloud-mall-pay` | 模拟支付、回调、支付状态同步 | MySQL、RabbitMQ、Seata；不接真实支付渠道 |
| `cloud-mall-common` | 统一返回、异常、工具、常量、分页封装 | 仅公共能力，不放业务规则或业务表 |

跨服务调用统一使用 Spring Cloud OpenFeign，并经 Nacos 服务发现；外部前端请求统一经 Gateway。服务间不得直接读写其他服务数据库，订单中的商品名称、价格等使用不可变快照。连接超时为 3 秒，读取超时为 5 秒，仅对幂等 GET 允许有限重试。

## 4. 核心调用与一致性

### 4.1 普通下单

`React → Nginx → Gateway → order → product/cart/地址校验 → [已提交本地事务] INIT → stock 预扣 → [已提交本地事务] RESERVED → [本地事务] order 保存 + timeout outbox 记录 + COMMITTED → 返回待支付`。地址归属、商品快照和数量校验必须在调用 stock 之前完成。INIT 必须先提交，不能把远程预扣放在未提交的本地事务之后再依赖回滚。库存预扣与订单本地写入不假定跨服务本地事务可以自动回滚：预扣结果必须按 `orderNo` 幂等，订单创建失败时由同步补偿和 `order_reservation_intent` 扫描任务继续回滚孤立预扣。

若进程在库存预扣成功后、意图更新为 RESERVED 前崩溃，恢复任务在宽限期后使用原 `orderNo` 和完整快照重试同一预扣请求；库存端必须返回原预扣结果而不是再次扣减。恢复任务随后将意图推进为 RESERVED 并回滚，或在订单已成功提交时识别为 COMMITTED。恢复任务与创建请求通过状态条件更新/租约避免同时处理同一意图。

订单状态迁移使用条件更新（`where order_no=? and status=?`）并检查影响行数。只有从 `PENDING_PAYMENT` 成功迁移到 `PAID` 的调用方可以确认库存，只有从 `PENDING_PAYMENT` 成功迁移到 `CANCELLED` 的调用方可以回滚库存；影响行数为 0 时重新读取状态并按已完成的迁移返回幂等结果，禁止继续执行相反库存动作。

### 4.2 支付成功

`pay` 校验模拟支付请求并记录支付结果，以 `payNo` 作为唯一操作键调用 user 的余额扣款；扣款和 `balance_ledger` 在 user 本地事务内完成。扣款成功后向 order 发起幂等支付同步。同步返回明确 `PAID` 时支付进入 `SUCCESS` 并确认库存；同步超时/断连时进入 `ORDER_SYNC_UNKNOWN`，由恢复任务用同一 `orderNo` 查询订单状态并重试。只有查询明确得到 `CANCELLED`/未支付终态才创建唯一退款操作；未知期间不得退款，避免订单其实已 `PAID` 时发生钱和货双重结算。回调、扣款、退款、库存确认均按操作键幂等。

### 4.3 超时关单

order 创建待支付订单时，在保存订单的同一本地事务中写入 timeout outbox 记录；事务提交后由 dispatcher 以 publisher confirm 投递到现有 TTL + DLX 链路，并以同一 `eventId` 标记发送成功。dispatcher 对未发送或确认超时记录持续重试，不能在事务内直接依赖 `convertAndSend` 的返回值。若 RabbitMQ 已 confirm 但 outbox 更新失败，记录保持 `PENDING` 并重发同一 `eventId`；消费者按 `eventId`/订单 CAS 幂等处理。兼容存量 String 订单号消息：消费者将其转换为 `legacy-timeout:{orderNo}`，从订单读取 `expireAt` 后按相同 CAS 关单路径处理；新消息使用包含 `eventId`、`eventType`、`orderNo`、`expireAt`、`traceId` 的 JSON envelope。关单消费者检查订单号、事件号和过期时间，只有 CAS 成功取消待支付订单后通知 stock 回滚。

### 4.4 秒杀

Gateway/Sentinel 保护入口，Redis 原子预扣热点库存，成功后 RabbitMQ 异步削峰到 order/stock；持久化订单和库存最终通过既定事务与补偿规则收敛。不得把 Redis 预扣结果直接当作最终支付成功。

### 4.5 普通库存缓存一致性

普通库存写路径以 MySQL 为唯一事实源：先以 `stock_reservation_request.order_no` 唯一认领规范化 SKU 快照，再在一个本地事务内完成所有 `stock_sku` 条件更新、`stock_flow` 幂等流水和 `stock_cache_repair` 记录，并递增 `stock_sku.version`；事务提交后才异步更新 Redis。库存不足时整笔库存更新回滚，但必须持久化 `REJECTED` 结果。Redis 更新失败、进程在提交后退出或结果未知时，修复器扫描持久化记录重试。Redis 数量必须携带数据库版本，Lua 以版本条件原子覆盖，旧修复任务不能覆盖新库存。预扣/确认/回滚请求都先按订单号、快照哈希和流水幂等，再执行数据库条件更新；Redis 不可用只影响投影，不允许用购物车库存或旧缓存作交易结论。

这项调整明确替代普通库存的“先 Lua 扣 Redis、再写 MySQL、失败时盲目回补”路径。若保留兼容旧 Redis 预扣数据，必须先把该 SKU 标记为 `REPAIR_BLOCKED`，等待对应 `stock_flow` 完成或超时裁决后再以 MySQL 版本重建，禁止直接 `MSET/SET` 覆盖。

### 4.6 Feign 写请求结果未知

库存预扣通过 `orderNo` 固定成幂等操作。Feign `POST /reservations` 读取超时、连接断开或返回解析失败时，订单服务必须保留 `order_reservation_intent=RESERVE_UNKNOWN`，不得生成新订单号或把未知当作失败。恢复任务先调用 `GET /reservations/{orderNo}`；状态为 `RESERVED` 才能依据订单状态确认或回滚，状态为 `REJECTED` 才能释放意图，持续未知则重试并告警。商品读取超时只能有限重试幂等 GET；最终仍不可用时返回服务暂不可用，订单不能使用购物车缓存价格或库存继续创建。

## 5. 数据与缓存边界

- MySQL 是用户、商品、订单、库存、支付等事实数据的持久化来源。
- Redis 登录态 TTL 为 2 小时，购物车 value 使用 JSON 且 TTL 为 30 天；必须区分用户登录态、购物车、热点商品、秒杀库存、限流计数。
- Elasticsearch 是商品检索索引，不替代 MySQL 事实数据；商品变更通过 RabbitMQ 异步同步并记录失败结果。
- 订单表由 Sharding-JDBC 按 `created_at` 路由到月度逻辑表，开发阶段预建当前月份及测试需要的物理表。
- 日志通过 Kibana 分析，Micrometer Tracing 将 Trace ID 透传至 Zipkin，Prometheus 抓取 Spring Actuator 指标端点并在 Grafana 展示。

## 6. Docker Compose 本地开发部署说明

Docker Compose 是本地开发部署的统一编排入口，使用 Docker Desktop 管理基础设施容器、网络、数据卷、依赖启动顺序和健康检查。必须编排以下组件：Nacos、MySQL、Redis、RabbitMQ、Seata、Elasticsearch、Kibana、Zipkin、Prometheus、Grafana、Sentinel-Dashboard 和 Nginx。各组件版本与认证约定如下：

| 容器组件 | 版本 | 认证 |
| --- | --- | --- |
| MySQL | 8.0 | `root/root` |
| Redis | 7.0 | 密码 `root` |
| RabbitMQ | 3.11-management | `root/root`，不用 `guest` |
| Nacos | 2.2.0 | `root/root` |
| Sentinel-Dashboard | 1.8.6 对应面板 | 默认无认证；显式开启时 `root/root` |
| Seata-Server | 1.5.2 | 默认无认证 |
| Elasticsearch/Kibana | 7.17.0 | 开发环境关闭安全认证；ES 如开启认证密码 `root` |
| Zipkin | 2.23.19 | 默认无认证 |
| Prometheus | 2.47.0 | 默认无认证 |
| Grafana | 9.5.5 | `root/root` |
| Nginx | 以计划书预置镜像为准 | 默认无认证 |

Compose 至少应提供统一的本地开发网络、持久化数据卷、健康检查和必要的启动依赖；容器间连接使用 Compose 服务名及容器端口，宿主机映射仅为 IDEA、浏览器或运维控制台访问所需。不得因 Compose 映射改变既定端口：Gateway/user/product/cart/order/stock/pay 仍分别使用 8080/8081/8082/8083/8084/8085/8086，React/Vite 开发服务器仍使用 5173。

本轮不将七个业务服务纳入 Compose 启动集合。现有业务 Dockerfile 均以已构建的 `target/*.jar` 为输入，且当前开发约束要求业务服务前期由 IntelliJ IDEA 以标准 Maven 多模块工程本地运行；强行纳入会引入镜像构建前置条件、与 IDEA 进程的端口冲突及两套运行时配置。业务服务后期完成镜像化后，另行评审 Compose 扩展，不改变服务边界和固定端口。Nginx 虽由 Compose 编排，但只承担本地静态资源代理和请求分发；前端开发阶段仍可由 Vite 在 5173 提供资源。

## 7. 配置、安全与可观测性约束

- Nacos 配置按开发/测试环境隔离；敏感配置仅按本地练习约定使用，不得宣称生产安全。
- Gateway 统一处理跨域、鉴权和入口级过滤；服务仍需校验用户身份和资源归属，不能只信任网关头信息。
- Sentinel 只用于计划书范围内的限流、熔断、降级和热点参数保护，不引入复杂风控。
- 统一返回结构和错误码由 common 提供；API 契约以 `.codex/specs/api_spec.md` 为准。
- 通过 Micrometer Tracing 透传 Trace ID 串联 Gateway 到各服务，Prometheus 从 Spring Actuator 指标端点采集至少覆盖 JVM、QPS、响应时间和中间件状态的指标。
- 禁止引入 Zuul、Eureka、Kubernetes、多级缓存及计划外中间件。

## 8. 部署阶段

1. 环境阶段：通过 Docker Compose 启动基础设施与 Nginx，验证认证、网络、数据卷、健康状态和宿主机访问入口。
2. 基础阶段：构建 common、Gateway、Nacos 配置和观测链路。
3. 业务阶段：按 user/product/cart/order/stock/pay 服务边界开发和联调。
4. 稳定性阶段：落地 Sentinel、RabbitMQ、秒杀和链路/指标监控。
5. 交付阶段：业务服务打包 Docker 镜像，Nginx 代理 React/Vite 静态资源，完成全流程联调。

## 9. 设计冻结说明

本轮已冻结 OpenFeign + Nacos 服务通信、3 秒连接超时、5 秒读取超时、仅幂等 GET 有限重试、RabbitMQ TTL + DLX 延迟关单、Seata file 模式、RabbitMQ 商品索引异步同步、Micrometer Tracing Trace ID 透传、Spring Actuator 指标端点、Docker Compose 本地编排范围以及 IDEA/Vite 本地端口。Docker Compose 的具体容器名、网络/卷名称、镜像构建细节和生产化安全不属于本地代码实现契约。
