# CloudMall 项目全局规则

本文档是 CloudMall 云购微商城项目的持久化项目规则。当前项目已完成规格设计，后续按 `.codex/specs/` 中的已评审规格进入业务开发。

## 1. 工作边界

- 两份参考文档 `微服务电商实战项目开发计划书.md`、`多Agent协作开发梳理笔记.md` 只读，禁止修改。
- 业务开发必须以 `.codex/specs/` 中已经评审并落地的规格为前提；任何设计变更必须先由 `pm_arch` 评审并同步规格文档。
- 只做当前任务需要的最小改动，不引入计划书之外的技术，不进行无关重构。
- 本项目是个人技术实战项目，重点是理解和复盘，不承诺生产级高可用与生产安全。
- 后端使用标准 Maven 工程布局，前端使用标准 React + Vite 工程布局，所有代码必须可由 IntelliJ IDEA 导入、编译和运行；不得依赖仅对 Agent 环境可用的脚本或路径。
- 设计冻结基线：Gateway/user/product/cart/order/stock/pay 本地端口依次为 8080/8081/8082/8083/8084/8085/8086，前端 Vite 使用 5173；API 使用 Redis 不透明 Bearer Token（TTL 2 小时）、JSON 金额字符串/Java `BigDecimal`、ISO-8601 `+08:00` 时间、`Idempotency-Key`；数据库使用 Asia/Shanghai 语义的 `DATETIME(3)`。

## 2. 固定技术栈与版本

### 后端

- Spring Boot 2.7.18
- Spring Cloud 2021.0.5（Gazelle）
- Spring Cloud Alibaba 2021.0.5.0
- Spring Cloud Gateway 3.1.5
- Nacos 2.2.0：注册发现、配置管理、开发/测试环境隔离
- Sentinel 1.8.6：限流、熔断、降级、热点参数限流
- Seata 1.5.2：跨服务事务一致性
- RabbitMQ 3.11-management：异步解耦、超时关单、支付通知、库存处理
- Redis 7.0：购物车、登录态、热点数据、秒杀库存、限流计数
- MySQL 8.0：业务数据存储
- Sharding-JDBC 4.1.1：订单表按时间分表
- Elasticsearch 7.17.0 + Kibana 7.17.0：商品检索与日志分析
- Micrometer Tracing 1.9.5 + Zipkin 2.23.19：调用链路追踪
- Prometheus 2.47.0 + Grafana 9.5.5：JVM、QPS、响应时间和中间件监控

### 前端与部署

- React + Vite
- Docker：所有中间件容器化；业务服务前期本地调试，后期打包镜像
- Nginx：前端静态资源代理、请求分发和负载均衡

## 3. 服务边界

服务遵循单一职责、高内聚低耦合，每个服务独立部署、独立迭代：

- `cloud-mall-gateway`：统一入口、路由、鉴权、限流预处理、过滤、跨域
- `cloud-mall-user`：注册、登录、权限、个人信息
- `cloud-mall-product`：商品、分类、参数、CRUD、ES 检索、热度统计
- `cloud-mall-cart`：Redis 购物车、增删改查、勾选结算、同步
- `cloud-mall-order`：订单创建、状态管理、Seata 协作
- `cloud-mall-stock`：扣减、回滚、防超卖、RabbitMQ 异步处理
- `cloud-mall-pay`：模拟支付、回调、支付状态同步
- `cloud-mall-common`：统一返回、全局异常、工具类、常量、分页封装

跨服务注册发现和配置统一使用 Nacos；网关是统一入口；公共模块只承载通用能力，业务规则留在所属服务。

## 4. 本地中间件认证约定

中间件全部通过 Docker 部署，无需本机单独安装。开发环境统一使用账号 `root`、密码 `root`，不得在配置文件、Docker 命令或连接参数中随意分叉。

| 组件 | 认证约定 |
| --- | --- |
| MySQL 8.0 | 用户 `root` / 密码 `root` |
| Redis 7.0 | 密码 `root`；按实际 Redis 认证模型连接 |
| RabbitMQ 3.11-management | 用户 `root` / 密码 `root`，不使用默认 `guest` |
| Nacos 2.2.0 | 用户 `root` / 密码 `root` |
| Grafana 9.5.5 | 用户 `root` / 密码 `root` |
| Seata-Server、Zipkin、Prometheus、Nginx | 默认无认证 |
| Sentinel-Dashboard | 默认无认证；显式开启登录时使用 `root/root` |
| Elasticsearch 7.17.0 | 开发环境关闭安全认证；开启认证时统一密码 `root` |
| Kibana 7.17.0 | 跟随 Elasticsearch，不单独设置认证 |

以上约定仅用于本地练习，不是生产安全方案。新增需要认证的中间件，必须先经过项目规则评审。

## 5. 编码、架构与验证约束

- 保持计划书中的成套版本；遇到兼容问题先定位依赖、配置和运行时链路，不随意换栈或降级。
- API、架构、数据库设计以 `.codex/specs/` 中的已评审文件为准。核心接口字段、核心表结构和跨服务架构变更必须先交 `pm_arch` 评审。
- 订单、库存、支付、购物车、秒杀必须落实计划书指定的 Redis、Seata、RabbitMQ、Sentinel、Sharding-JDBC 等真实业务场景，禁止只添加表面依赖。
- 服务间固定使用 Spring Cloud OpenFeign + Nacos，连接超时 3 秒、读取超时 5 秒，仅幂等 GET 有限重试；RabbitMQ 延迟关单使用 TTL + DLX，Seata 使用 file 模式，商品索引通过 RabbitMQ 异步同步，Trace ID 使用 Micrometer Tracing 透传，Prometheus 使用 Spring Actuator 指标端点。
- 修改代码后执行项目实际存在且适用的构建、lint、单元测试或接口校验；没有脚本时先检查 `pom.xml`、`package.json`、README 和模块结构，不臆造命令。
- 测试未通过不得宣告闭环；缺陷修复后必须回归。功能完成后逐行阅读核心代码并记录关键调用链。
- 禁止引入 Zuul、Eureka、Kubernetes、多级缓存、复杂风控等计划外技术。

## 6. Agent 权限边界

- `pm_arch`：只负责需求、架构、数据库、API 规格和评审，规格写入 `.codex/specs/`，不写业务实现。
- `backend`：只修改后端业务源码、后端测试和必要的后端配置，不修改前端或未经评审的契约。
- `frontend`：只修改 React/Vite 前端源码、前端测试和必要的前端配置，不修改后端或未经评审的契约。
- `qa`：使用只读沙箱，只能读取文件和执行不产生项目改动的验证，反馈复现步骤、证据、影响和归属角色，不直接修复。
- 角色协作通过 Codex 的多 Agent 委派完成；子 Agent 不自行扩展角色范围或创建未授权的并行任务。
- 仓库级可发现 Skill 放在 `.agents/skills/`；当前仅保留占位目录，未启用任何项目专属工作流。
