# CloudMall 云购微商城系统架构规格

## 1. 架构目标与边界

CloudMall 采用 Spring Boot + Spring Cloud Alibaba 微服务架构，按单一职责拆分服务。网关作为统一入口，服务通过 Nacos 注册发现和配置管理；业务服务独立部署、独立迭代。本文只定义设计边界，不提供业务实现或 Docker Compose 脚本。

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
| 环境 | Docker | 所有中间件容器化，业务服务后期镜像化 |

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

`React → Nginx → Gateway → order → product/cart 校验 → stock 预扣 → order 保存 → 返回待支付`。订单与库存的关键写操作由 Seata file 模式协作；RabbitMQ 负责后续超时关单、重试和异步通知。每一步必须以业务幂等键约束重复请求。

### 4.2 支付成功

`pay` 校验模拟支付请求并记录支付结果，向 order 发起回调/消息；order 幂等更新为已支付，并协作 stock 确认库存。回调重复、消息重复或服务重试不得重复确认库存。

### 4.3 超时关单

order 创建待支付订单后发布带 TTL 的超时处理消息；消息经 DLX 到期转发给关单消费者。消费者检查订单仍为待支付时关闭订单并通知 stock 回滚。消息投递、消费、回滚均需可重试且幂等。

### 4.4 秒杀

Gateway/Sentinel 保护入口，Redis 原子预扣热点库存，成功后 RabbitMQ 异步削峰到 order/stock；持久化订单和库存最终通过既定事务与补偿规则收敛。不得把 Redis 预扣结果直接当作最终支付成功。

## 5. 数据与缓存边界

- MySQL 是用户、商品、订单、库存、支付等事实数据的持久化来源。
- Redis 登录态 TTL 为 2 小时，购物车 value 使用 JSON 且 TTL 为 30 天；必须区分用户登录态、购物车、热点商品、秒杀库存、限流计数。
- Elasticsearch 是商品检索索引，不替代 MySQL 事实数据；商品变更通过 RabbitMQ 异步同步并记录失败结果。
- 订单表由 Sharding-JDBC 按 `created_at` 路由到月度逻辑表，开发阶段预建当前月份及测试需要的物理表。
- 日志通过 Kibana 分析，Micrometer Tracing 将 Trace ID 透传至 Zipkin，Prometheus 抓取 Spring Actuator 指标端点并在 Grafana 展示。

## 6. Docker 中间件部署说明

所有中间件使用 Docker Desktop 容器运行，业务服务前期本地启动，后期再统一打包镜像。中间件清单如下：

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

中间件容器名、网络名、数据卷路径和健康检查方式由本地 Docker 环境决定；本轮不编写 docker-compose。业务服务前期由 IntelliJ IDEA 以标准 Maven 多模块工程本地运行，端口固定为 Gateway 8080、user 8081、product 8082、cart 8083、order 8084、stock 8085、pay 8086；React/Vite 前端端口为 5173。所有容器应处于同一可达的本地开发网络，服务连接使用容器服务名或明确的本地映射。

## 7. 配置、安全与可观测性约束

- Nacos 配置按开发/测试环境隔离；敏感配置仅按本地练习约定使用，不得宣称生产安全。
- Gateway 统一处理跨域、鉴权和入口级过滤；服务仍需校验用户身份和资源归属，不能只信任网关头信息。
- Sentinel 只用于计划书范围内的限流、熔断、降级和热点参数保护，不引入复杂风控。
- 统一返回结构和错误码由 common 提供；API 契约以 `.codex/specs/api_spec.md` 为准。
- 通过 Micrometer Tracing 透传 Trace ID 串联 Gateway 到各服务，Prometheus 从 Spring Actuator 指标端点采集至少覆盖 JVM、QPS、响应时间和中间件状态的指标。
- 禁止引入 Zuul、Eureka、Kubernetes、多级缓存及计划外中间件。

## 8. 部署阶段

1. 环境阶段：启动 Docker 中间件，验证认证、网络、数据卷和健康状态。
2. 基础阶段：构建 common、Gateway、Nacos 配置和观测链路。
3. 业务阶段：按 user/product/cart/order/stock/pay 服务边界开发和联调。
4. 稳定性阶段：落地 Sentinel、RabbitMQ、秒杀和链路/指标监控。
5. 交付阶段：业务服务打包 Docker 镜像，Nginx 代理 React/Vite 静态资源，完成全流程联调。

## 9. 设计冻结说明

本轮已冻结 OpenFeign + Nacos 服务通信、3 秒连接超时、5 秒读取超时、仅幂等 GET 有限重试、RabbitMQ TTL + DLX 延迟关单、Seata file 模式、RabbitMQ 商品索引异步同步、Micrometer Tracing Trace ID 透传、Spring Actuator 指标端点以及 IDEA/Vite 本地端口。Docker 具体容器名、网络/卷名称和生产化安全不属于本地代码实现契约。
