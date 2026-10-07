# CM-20261007-20：Order 路由模式启动审计（阶段 A）评审

## 1. 评审结论

结论：**GO，限定为启动 fail-fast 与只读运行时信息审计；不接入 ShardingDataSource，不迁移真实订单 SQL/事务，不改变原库数据。**

当前 Order 默认链路使用普通 `spring.datasource`、Spring `JdbcTemplate`、动态月表名和本地事务；`application.yml` 的 `sharding` profile 只声明静态 `spring.shardingsphere` 配置，Order 模块仅有 `sharding-jdbc-core`，没有对应 Spring Boot starter、ShardingDataSource Bean 或路由接管代码。若激活该 profile，当前应用可能继续使用普通 Hikari 并静默忽略分片配置，形成配置与实际运行模式不一致。

阶段 A 只让运行事实诚实可见，并在明确不支持的配置下尽早失败。默认 `dynamic-monthly` 行为保持不变；CM-20 整体切换仍 no-go。

## 2. 运行模式与合法配置

冻结配置概念 `cloudmall.order.routing.mode`：缺省值 `dynamic-monthly`，沿用当前 OrderSqlMapper 动态物理月表、普通 Spring JDBC 事务和滚动建表窗口。`sharding`、`sharding-shadow` 或其他未知值当前均不支持，启动早期返回明确配置错误；未来 shadow 另行评审。

默认模式允许普通 Hikari，也允许 Seata `DataSourceProxy` 包装同一 Hikari，前提是审计确认 JdbcTemplate 与事务管理器最终引用同一个有效 DataSource。不得仅凭 Proxy 类名拒绝现有合法配置。

默认模式必须拒绝激活 `sharding` profile、实际创建 ShardingDataSource/ShardingSphereDataSource、JdbcTemplate 与事务管理器引用不同 DataSource、多个无法判定主数据源，或 routing mode 为空白/未知。普通 Hikari 本身不是错误；不强行消费静态 actual-data-nodes。

## 3. 启动审计契约

审计必须在订单业务写入、表初始化和恢复调度前完成；失败时不创建订单、不执行 DDL、不发起 Order/Stock/Pay RPC。核对 effective mode、profile、实际 DataSource 类型、事务管理器及其 DataSource 身份、Shanghai 上月/本月/未来两个月窗口。日志只记录模式、简单类名、对象身份摘要和月份集合，不记录 URL、用户名、密码或连接属性。

## 4. Actuator Info 只读信息

只扩展现有 `/actuator/info`，不新增业务 API，继续遵循现有 exposure/访问规则。白名单字段为 routingMode、清洗后的 activeProfile、DataSource/transactionManager 简单类型和 orderTableWindow；不得输出 URL、账号、密码、JDBC properties、连接池明细或订单数据。

## 5. 静态窗口与实施边界

激活 `sharding` profile 或实际 ShardingDataSource 时必须 fail-fast，提示当前 Sharding 路由未接入、静态窗口与滚动建表窗口未冻结。阶段 A 不扩展 actual-data-nodes、不广播查询、不改逻辑表、不补状态日志、不改变跨月分页、恢复任务或 Seata 代理顺序。

## 6. 验收与范围声明

验收覆盖默认 dynamic-monthly、合法 Hikari、合法同源 Seata Proxy、激活 sharding/未知 mode/不同 DataSource 的早期失败、月份窗口和 Actuator 脱敏信息。完成阶段 A 只表示运行模式不再静默误导，不表示 CM-20 分片切换、跨月逻辑路由或生产容量已闭环。

## 7. 实现级冻结

- profile/mode 的静态拒绝使用 `BeanFactoryPostProcessor` 或等价的 BeanFactory 早期校验；校验不得实例化 DataSource、打开连接或执行 DDL。激活未支持 profile/mode 时在业务 Bean 创建前失败。
- 实际 DataSource 绑定由独立 runtime audit Bean 校验；`OrderTableInitializer`、`OrderReliabilityTableInitializer` 显式 `DependsOn` 该 audit Bean，确保任何订单表/可靠性表 DDL 前已完成审计。`@Scheduled` 任务和 Feign/RPC 业务调用只在 Spring refresh 完成后按现有生命周期启动。
- 运行时选定一个 primary DataSource：必须是 Spring 选中的同一 Bean 对象，且与 `JdbcTemplate.getDataSource()`、`DataSourceTransactionManager.getDataSource()` 完全相同。不能只比较 unwrap 后的底层 Hikari，因为 Seata proxy 与原始 Hikari 可能使用不同 Spring 事务资源键。
- 多 DataSource 不一概拒绝；若 Spring 已明确选出 primary 且三者引用一致则允许，否则让 Spring 的明确多 DataSource/primary 配置错误或审计错误尽早失败。
- 类型审计只允许安全识别 Spring AOP/Delegating/Seata 已知 wrapper，并报告 observed bean、underlying 和事务管理器的简单类名；不得通过 `getConnection()` 探测。
- Info 的月份字段复用 `OrderTableInitializer` 的同一静态窗口计算 helper，避免日志/Info 与 DDL 目标窗口漂移。`expectedMonths` 只表示计算出的目标窗口，不查询数据库，不声称物理表真实存在。
