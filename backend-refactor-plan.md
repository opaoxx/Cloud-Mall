# CloudMall 后端全模块规范化重构方案

## 1. 目标与执行边界

本方案针对当前工作树中的 `cloud-mall-common`、`cloud-mall-gateway`、`cloud-mall-user`、`cloud-mall-product`、`cloud-mall-cart`、`cloud-mall-order`、`cloud-mall-stock`、`cloud-mall-pay` 八个后端 Maven 模块。

重构只改变代码组织、格式、注释和持久层实现，不改变：

- HTTP 方法、路径、请求头、请求 JSON 字段、响应 JSON 字段、HTTP 状态码和业务错误码；
- Feign 接口的接口路径、方法、请求参数、返回结构和服务名；
- 数据库表名、列名、字段语义、金额/时间精度和订单分片规则；
- Redis Key、购物车 JSON 结构、秒杀 Key、RabbitMQ 交换机/队列/消息字段、Seata 协作语义；
- 既有业务状态机、幂等行为、资源归属校验、库存防超卖逻辑和 ES 异步索引逻辑。

不修改两份只读参考文档，不改前端、Docker/部署文件或与本次后端重构无关的本地资料。

## 2. 当前盘点结果

### 2.1 工作树基线

- 当前分支为 `main`，工作树已有未提交修改：约 54 个已修改文件，并有既有未跟踪 `docs/` 目录；这些改动不是本次重构产生的，实施时保留并避免将 `.codex/config.toml`、`docs/` 等无关内容加入重构提交。
- 后端当前没有 Maven Wrapper，当前环境 `mvn` 命令不可用；阶段验证优先使用可获得的 Maven/JDK 环境，若环境未补齐，最终报告明确列出未执行项，不以静态检查替代编译结论。
- 后端主源码约 40 个 Java 文件，绝大多数业务类直接位于 `com.cloudmall.<service>` 根包；公共模块已有 `api/auth/error/web` 初步分包，但仍需要统一命名、注释和公共类型边界。

### 2.2 现有类与改动点总表

| 模块 | 当前类/问题 | 目标处理 |
| --- | --- | --- |
| common | `api/ApiResponse`、`api/PageResult` | 保留 `api`，补字段注释、构造器/工厂方法 JavaDoc，统一命名和格式 |
| common | `auth/AuthContext` | 保留 `auth`，补线程上下文生命周期说明和方法 JavaDoc |
| common | `error/BizException`、`error/ErrorCodes` | 归入统一异常/错误码包（保留对外错误码值），全量更新 import；不把业务规则放入 common |
| common | `web/AuthInterceptor`、`CommonWebConfiguration`、`GlobalExceptionHandler` | 保留 `web`/`config` 的公共职责边界；配置类统一归入 `config`，异常处理只负责协议映射 |
| gateway | `AuthenticationFilter` | 移至 `filter`；保留 Gateway 鉴权、公开路径、Bearer Token 和空响应语义 |
| gateway | `GatewayApplication` | 保留根包；补启动类注释 |
| user | `UserController` 同时承载 HTTP、JDBC、Redis、密码和余额业务 | 新建 `controller`、`service/UserService`、`service/impl/UserServiceImpl`、`mapper/UserMapper`，拆出 `domain/po`、`domain/dto`、`domain/vo`；Controller 只做绑定/校验/委托 |
| user | `Credentials`、`Profile`、`DebitRequest`、`AddressRequest`、内部 `User` | 按请求/返回/持久化职责迁移到 `domain/dto`、`domain/vo`、`domain/po`，保持 JSON 字段和 Feign 返回兼容 |
| user | 多处 `JdbcTemplate` SQL | 使用 MyBatis-Plus Mapper API；用户、地址写操作及余额原子扣减全部下沉 Mapper |
| product | `ProductController` 同时承载分类、商品、SKU、参数、活动、热度的 JDBC 和业务流程 | 新建 `controller`、`service/ProductService`、`service/impl/ProductServiceImpl`、对应 Mapper 与 domain 类型；HTTP 入口保留原契约 |
| product | 内部 `Product`、`Sku`、`ProductParameter`、`Category`、`Activity`、响应类 | 拆到 `domain/po`、`domain/dto`、`domain/vo`，解决数据库 snake_case 到 API lowerCamelCase 的映射 |
| product | `ElasticsearchConfiguration`、`ProductMessagingConfiguration` | 移至 `config`；配置常量、Bean 名和队列契约不变 |
| product | `ProductIndexConsumer`、`ProductIndexDocumentLoader`、`ProductIndexWriter`、`ElasticsearchProductIndexWriter`、`ProductIndexDocument` | 消费/索引编排归 `service`/`service.impl`，ES 文档归 `domain/po`；事实数据读取必须经过 Mapper/Service，不在索引代码散落 SQL |
| product | 多处商品/分类/参数/活动/热度 `JdbcTemplate` SQL | 接入 MyBatis-Plus；基础 CRUD 使用 `BaseMapper`/Wrapper，复杂查询只保留在 Mapper 层/XML，服务层不出现 SQL 字符串 |
| cart | `CartController` 同时承载 Redis 读写、商品校验、结算预览 | 新建 `controller`、`service/CartService`、`service/impl/CartServiceImpl`、`domain/dto`、`domain/vo`；购物车仍以 Redis 为唯一权威，因无 MySQL 购物车事实表不创建虚假 Mapper |
| cart | `ProductClient` 根包，内部 `Item` | Feign 移至 `feign`；请求/缓存/返回对象按职责迁移，Redis Key、TTL 30 天和 JSON 字段不变 |
| order | `OrderController` 同时承载订单创建、查询、状态流转、秒杀消费、JDBC、RabbitMQ | 新建 `controller`、`service/OrderService`、`service/impl/OrderServiceImpl`，另拆消息消费者到 service 实现/消息处理类；Controller 不保留业务流程 |
| order | `CartClient`、`ProductClient`、`StockClient`、`UserClient` | 全部移至 `feign`；保持现有 Feign 注解、路径、参数/返回记录和认证配置 |
| order | `Order`、`OrderItem`、创建/秒杀请求、内部行对象 | 按 `domain/po/dto/vo` 拆分；订单详情仍返回不可变商品/地址快照 |
| order | `OrderMessagingConfiguration`、`FeignAuthConfiguration`、`MonthlyOrderShardingAlgorithm`、`OrderTableInitializer` | 全部移至 `config`；保留 TTL + DLX、Seata、月度分片、物理表初始化语义 |
| order | 订单、明细、幂等表和秒杀相关 JDBC SQL | 新建 `OrderMapper`、`OrderItemMapper`、`OrderStatusLogMapper`、`OrderIdempotencyMapper` 及需要的领域 Mapper；接入 MyBatis-Plus，动态分片查询/原子状态更新仅在 Mapper 层实现 |
| stock | `StockController` 同时承载普通库存、秒杀 Redis Lua、JDBC 流水和补偿 | 新建 `controller`、`service/StockService`、`service/impl/StockServiceImpl`、`mapper`、`domain/po/dto/vo`；保留 Redis 原子预扣、数据库条件更新、流水幂等、补偿语义 |
| stock | `StockCacheReconciler`、`StockConsistencyReconciler` | 移至 `service.impl`（或同等运行时服务子包），数据库读取改由 Mapper 提供；保留启动恢复、定时对账和分布式锁 |
| stock | `StockMessagingConfiguration` | 移至 `config`，消息常量和 Bean 名不变 |
| pay | `PayController` 同时承载支付创建、余额扣减、回调、JDBC、RabbitMQ | 新建 `controller`、`service/PayService`、`service/impl/PayServiceImpl`、`mapper`、`domain/po/dto/vo`；保留支付幂等和金额校验 |
| pay | `OrderClient`、`UserClient`、`FeignAuthConfiguration` | Feign 移至 `feign`，认证配置移至 `config`；接口契约不变 |
| pay | 支付记录、回调日志 JDBC SQL | 接入 MyBatis-Plus Mapper；支付状态条件更新、余额扣减调用 Mapper，不在 Service 拼 SQL |

### 2.3 目标包结构

每个业务服务统一采用以下结构，启动类仍在根包：

```text
com.cloudmall.<service>
├── controller
├── service
│   └── impl
├── mapper
├── domain
│   ├── po
│   ├── dto
│   └── vo
├── config
├── feign
├── util
└── <Service>Application
```

Gateway 采用 `config`、`filter`、`util` 和根包启动类；common 采用 `api`、`auth`、`config`、`constant`、`domain/{po,dto,vo}`、`error/exception`、`enum`、`util`、`web`，仅放真正跨服务公共类型。

## 3. 契约保护与重构策略

1. 先建立迁移前后契约基线：读取 `.codex/specs/api_spec.md`、`.codex/specs/architecture.md`、`.codex/specs/db_schema.md`，记录所有 Controller 映射、Feign 声明、配置常量、SQL 表列和测试断言。
2. 包迁移采用按职责拆分，而不是仅改 `package`：HTTP Controller 只做参数绑定、校验、鉴权上下文读取和 Service 调用；消息消费者、定时任务、ES 写入和持久化分别归到 Service/Config/Mapper。
3. DTO/VO/PO 只改变 Java 内部类型位置和映射方式，不改变 Jackson 序列化字段。必要时使用明确的字段映射、构造器或 `@JsonProperty` 保持当前 JSON。
4. Feign 接口保留原有 public 方法签名、路径、Header、record 字段和 `ApiResponse` 泛型形状；只移动包并修正 import。
5. 订单月度分片不改逻辑表、物理表后缀、`created_at` 路由或订单号年月前缀；MyBatis-Plus 与 Sharding-JDBC 的组合通过 Mapper 实现。
6. 变量命名以语义为准：除循环条件中的 `i`/`j` 等计数变量外，禁止 `b`、`c`、`d`、`r`、`n`、`q`、`x`、`st`、`no`、`id` 等无上下文短名；统一改为 `request`、`callback`、`resultSet`、`rowNumber`、`quantity`、`line`、`status`、`orderNo`、`identifier` 等顾名思义的名称。

## 4. 分阶段实施与验收

### 阶段 1：分层包结构与类迁移

- 创建所有目标目录；移动现有类；为职责缺失的 Controller/Service/Mapper/domain 建立最小骨架并完成必要的业务委托拆分。
- 修复全项目 import、Spring 扫描路径、Feign 扫描、Mapper 扫描、配置类引用、测试资源路径和包名。
- 保证启动类仍在 `com.cloudmall.<service>` 根包，保证对外路径和 Feign 声明未变。
- 检查根包下不得残留非启动类业务实现；检查每个服务的目录结构与本方案一致。
- 验收：源码包路径扫描、API/Feign 静态契约检查、可执行的 Maven 编译/测试（若环境仍无 Maven，记录为环境阻塞）。
- 提交：`refactor: 全模块新建标准分层包结构，迁移类文件，修复导包`

### 阶段 2：全 Java 文件格式化与命名

- 覆盖 `src/main/java` 和 `src/test/java` 的全部 Java 文件，不只处理本次新增文件。
- 统一 import 顺序、缩进、换行、空行、括号、泛型、Lambda、链式调用、注解位置、常量声明和长参数列表；遵守阿里 Java 规范及现有 Spring Boot 2.7/JDK 17 语法。
- 全量审查形参、局部变量、字段和方法名；除循环计数外移除一两个字母的非语义变量名。
- 验收：格式化工具/IDEA 格式检查、全量搜索禁用短变量模式、编译和既有测试。
- 提交：`refactor: 全项目代码格式化，统一编码排版`

### 阶段 3：注释体系

- 每个类成员变量（包括 `static final` 常量、Logger、依赖、DTO/PO 字段和 record 组件）补充准确含义说明，禁止“保存 xxx 对应的内部状态或配置”这类无信息注释。
- 所有 public 方法、构造器、Feign 方法、配置 Bean、Controller 接口、定时任务和消息消费者补充标准 JavaDoc；参数、返回值、异常按需要写明。
- 每个业务实现方法按真实流程添加连续步骤注释：`// 1. ...`、`// 2. ...`、`// 3. ...`；注释描述业务动作而不是重复代码。
- 复杂私有方法补充算法、幂等、分片、Redis Lua、补偿和异常语义注释；测试辅助方法也保持基本可读性。
- 验收：检查所有 public 声明前有 JavaDoc，成员变量无遗漏，业务方法步骤完整；编译和测试。
- 提交：`refactor: 补全全项目注释，增加业务分步流程注释`

### 阶段 4：MyBatis-Plus 持久层解耦

- 在父 POM 统一管理 MyBatis-Plus 版本，在 user/product/order/stock/pay 接入 starter，并按服务增加 Mapper 扫描和必要配置；cart 继续使用 Redis，不伪造 MySQL Mapper。
- 为数据库实体建立 PO：`@TableName`、`@TableId`、`@TableField`、必要的 `@Version` 等注解准确对应现有表列；金额使用 `BigDecimal`，时间使用与现有 `DATETIME(3)` 兼容的类型。
- 为每张事实表建立职责清晰的 Mapper，基础 CRUD 使用 `BaseMapper` 和 Wrapper；动态分片、关联读取、条件更新、库存原子扣减、幂等插入等无法由通用 API 表达的 SQL 只放在 Mapper 注解/XML/自定义 Mapper 方法中。
- 将 Controller 中的 SQL 和 JDBC 依赖全部移除；Service 只组织事务、状态机、Feign、Redis、RabbitMQ 和 Mapper 调用，不出现 SQL 字符串、ResultSet 映射或数据库列名拼接。
- order 特别处理月度物理表路由、订单/明细/状态日志同月写入、按时间范围聚合和订单号定位；stock 特别保留条件扣减和流水唯一幂等；pay 特别保留金额、余额和回调幂等；product 特别保留参数替换、活动、热度和 ES 异步同步；user 特别保留默认地址和余额原子更新。
- 验收：`rg` 检查业务 Controller/Service 无 `JdbcTemplate`、SQL 字符串和 ResultSet；Mapper/PO 表列覆盖核对；契约测试、持久层测试和可执行 Maven 测试。
- 提交：`refactor: 业务模块适配MyBatis-Plus，解耦业务与原生SQL`

### 阶段 5：全项目编译、告警和兼容性闭环

- 使用项目实际可用的 JDK 17/Maven 环境执行根工程全量编译和测试；按模块定位并修复包迁移、泛型、Mapper、Spring Bean、Sharding-JDBC、Feign、RabbitMQ、Jackson 和测试编译问题。
- 检查并修复编译警告：未使用 import、原始类型、未检查转换、无效注释、过时 API、字段隐藏、可疑 null 和异常吞噬；不为消除警告改变业务契约。
- 逐接口核对 Controller 路径/方法/请求/响应；逐接口核对 Feign；核对 Redis/RabbitMQ/Seata/ES 配置和消息字段；执行现有合同测试并补充必要的重构回归测试。
- 逐行复核核心调用链：登录、商品查询/写入、购物车结算、订单创建/取消/支付/关单、库存预扣/确认/回滚、模拟支付回调、秒杀异步链路和 ES 索引同步。
- 验收：根工程 `mvn test`（或项目环境提供的等价命令）通过、接口/Feign 静态契约通过、无业务层原生 SQL、工作树只剩明确的既有无关修改。
- 提交：`refactor: 重构完成，全项目编译校验修复告警`

## 5. 风险、回滚与提交纪律

- 每阶段只暂存该阶段相关的后端文件和本方案文件；`.codex/config.toml`、既有 `docs/` 和其他无关修改不加入提交。
- 每个阶段提交前记录 `git diff --check`、测试/编译结果和变更文件；提交后用 `git status` 和 `git show --stat` 核对版本回溯点。
- 若阶段验证失败，不进入下一阶段；先修复当前阶段问题并重新验证，再创建该阶段提交。
- 如果环境持续缺少 Maven 或中间件，代码结构与静态契约仍可检查，但不能宣称运行时闭环；最终报告明确区分“已验证”和“未验证”。
- 本方案是实施清单，不修改既定 API/架构规格；任何发现必须改变契约或业务规则的问题，暂停该点并先更新评审规格，不在重构中擅自扩展。
