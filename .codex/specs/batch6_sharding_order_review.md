# CM-20261004-20：订单 Sharding-JDBC 默认链路评审

## 1. 评审范围与结论

本轮核查订单服务是否已经把默认订单主链路切换为 Sharding-JDBC，以及分片算法、物理表发现、订单明细/状态日志、恢复任务、Seata 与数据源代理之间的兼容性。

评审结论：**当前不能安全地把默认 Order 主链路切换为 Sharding-JDBC；本轮对真实订单写入和查询执行 no-go。** 当前实现是“普通 Spring `spring.datasource` + `JdbcTemplate` + 服务端生成月表名”的动态物理表方案，`sharding` profile 只留下了一份未接入默认启动链路的配置。将 profile 直接设为默认或替换 `OrderSqlMapper` 的数据源，会同时改变事务管理器、表路由、恢复任务和 Seata 代理边界，现有测试并不能证明切换安全。

本轮允许的独立范围是：补充启动时的分片配置审计/明确运行模式、为分片切换建立只读验证和影子兼容测试；不得直接把 `OrderServiceImpl`、恢复任务或状态迁移写路径切换到 Sharding-JDBC。

## 2. 代码证据

### 2.1 默认启动没有启用 Sharding-JDBC

`backend/cloud-mall-order/src/main/resources/application.yml` 第一段只有：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:13307/cloudmall_order
```

Sharding 配置位于 `---` 后的 `on-profile: sharding` 文档段。全仓未发现 `spring.profiles.active=sharding`、`@Profile("sharding")` 或通过代码显式创建 `ShardingDataSource` 的实现。因此默认启动使用普通 Hikari 数据源，订单服务不会依据 `spring.shardingsphere.*` 这段 YAML 自动切换。

订单主链路 `OrderServiceImpl` 直接调用 `OrderSqlMapper`。`OrderSqlMapper` 持有 Spring `JdbcTemplate`，并在插入、读取、状态 CAS、分页和恢复查询前自行拼接 `mall_order_yyyyMM`、`mall_order_item_yyyyMM` 表名；它没有调用 `OrderMapper`、`OrderItemMapper` 或 `OrderStatusLogMapper` 的逻辑表 API。

### 2.2 仅声明 `sharding-jdbc-core` 不会提供当前 YAML 的 Spring Boot 接入

订单模块 POM 只有：

```xml
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>sharding-jdbc-core</artifactId>
  <version>${sharding.version}</version>
</dependency>
```

没有与当前版本匹配的 Sharding-JDBC Spring Boot starter，也没有 `ShardingDataSourceFactory.createDataSource(...)`、`DataSource` Bean、`SqlSessionFactory` 或事务管理器装配代码。因而不能仅凭把 profile 改成 `sharding` 就断言 `spring.shardingsphere.datasource` 会被消费。必须先冻结版本对应的 Spring 接入方式，并验证最终 `DataSource` Bean 类型和 `JdbcTemplate` 实际使用的数据源。

### 2.3 配置的 actual-data-nodes 是静态窗口，与运行期建表窗口不是同一契约

当前配置固定为：

```yaml
mall_order:       ds0.mall_order_$->{202608..202612}
mall_order_item:  ds0.mall_order_item_$->{202608..202612}
order_status_log: ds0.order_status_log_$->{202608..202612}
```

`OrderTableInitializer` 按上海时间创建“上月、本月、未来两个月”物理表。例如当前月份为 2026-10 时创建 202609 至 202612，刚好与配置窗口重合；进入 2027-01 后初始化器会创建 202612 至 202703，而 Sharding-JDBC 的 actual-data-nodes 仍只声明到 202612。新月份写入会在初始化器已建表的情况下仍无法被 Sharding 路由，或者因目标不在 actual-data-nodes 而失败。

历史月表也没有由 Sharding 配置动态发现。按规格，缺表读取必须整体返回 `ORDER_QUERY_SHARD_UNAVAILABLE`，不能读路径隐式建表；因此不能用“让 Sharding 扫描一个宽泛表名范围”掩盖缺表和窗口治理问题。

### 2.4 分片算法只实现 precise 路由，无法覆盖现有列表的跨月查询语义

`MonthlyOrderShardingAlgorithm` 实现 `PreciseShardingAlgorithm`，只从一个 `created_at` 值解析年月并从 targets 中找后缀。它没有实现 range 路由。

现有 `GET /api/orders` 先按时间范围枚举月份，逐月执行 `COUNT`，再按月跳过 offset 并读取目标页；该行为正是 batch5 规格冻结的跨分片分页算法。若直接改为逻辑表查询，Sharding-JDBC 需要经过 range 路由、跨表聚合、全局排序和分页，且必须重新证明 total 与 items 使用同一 REPEATABLE READ 快照，不能用目前的 precise 算法替代。

### 2.5 动态表名与逻辑 Mapper 并存，切换会产生双重路由和事务边界风险

`OrderMapper`、`OrderItemMapper`、`OrderStatusLogMapper` 是 MyBatis-Plus 逻辑表接口，但生产调用点使用 `OrderSqlMapper` 的动态 SQL。`OrderSqlMapper` 还承载 `order_idempotency`、`order_outbox`、`order_reservation_intent`、`order_stock_action` 等非分片可靠性台账的 SQL。

如果只替换 `JdbcTemplate` 为 Sharding 数据源，动态物理表名 SQL 不会经过逻辑表路由，且 `mall_order_yyyyMM` 可能被 Sharding 再次解析为未配置的逻辑表；如果只把订单主表调用改成 MyBatis-Plus，明细、状态日志、恢复 SQL 和台账仍在另一套数据源/事务管理器中。两种方案都不是可接受的“一次切换”。

### 2.6 状态日志目前没有实际写入链路

仓库中只有 `OrderStatusLogPO`、`OrderStatusLogMapper` 和建表初始化逻辑；没有发现 `OrderStatusLogMapper.insert`、状态日志动态 SQL 或 `OrderStatusLogPO` 实例化调用。订单状态通过 `OrderSqlMapper.updateStatus` 直接 CAS 更新主表，支付、取消、超时和恢复也围绕 `order_stock_action` 等台账推进。

因此，不能把状态日志表纳入 Sharding 切换验收并宣称状态审计已完整；切换前必须先决定状态日志是本轮补齐为同事务事实，还是明确继续保持未使用的兼容表。两者都需要独立规格，不应在切换数据源时顺便改变。

### 2.7 恢复任务依赖订单号前缀和动态物理表，不兼容未验证的逻辑路由

`OrderReservationRecovery`、`OrderServiceImpl` 的查询和补偿路径通过订单号的 `yyyyMM` 前缀或 `created_at` 月份直接得到 `mall_order_yyyyMM`，并在事务外执行库存确认/回滚或恢复订单意图。该定位规则是当前补偿模型的恢复锚点。

切换到 Sharding-JDBC 后，恢复任务必须明确：精确订单号查询如何提供 `created_at` 分片键、无时间条件是否允许广播、缺少月份表如何返回不可用，以及恢复事务是否与订单逻辑表使用同一个 `DataSourceTransactionManager`。在这些问题未通过故障矩阵前，不能切换恢复任务。

### 2.8 Seata 只在受控演练中使用，不能把分片数据源直接视为已代理

订单配置声明 Seata 1.5.2 AT，但现有评审已经冻结：真实订单/库存/支付主链路仍由本地事务、intent、outbox、UNKNOWN 和恢复任务负责；CM-18 只允许独立演练表。当前仓库没有证明默认订单数据源已经由 Seata `DataSourceProxy` 包装，也没有证明 ShardingDataSource 与 Seata 1.5.2 AT 组合可用。

若将分片数据源直接交给 Seata，需要逐库验证每个实际物理表的 `undo_log`、分支注册、跨月写入和回滚；若只代理底层 Hikari 数据源而绕过 Sharding 路由，或只代理 Sharding 外层而遗漏动态 `JdbcTemplate`，都会出现回滚记录与实际写入表不一致。该问题超出本轮可安全修复范围。

## 3. 风险判定

| 风险 | 直接切换后的结果 | 证据/影响 |
| --- | --- | --- |
| profile 未激活且没有 Spring 接入 | 配置看似存在，运行时仍走普通数据源；或启动时缺少 Bean | 当前默认 YAML、POM 与源码 |
| actual-data-nodes 静态 | 2027-01 后初始化器建出的新月表不能被路由 | 配置只到 202612，初始化器按滚动窗口创建 |
| precise 算法不能表达列表 range | 跨月 total、排序、分页语义改变或查询失败 | 现有 API 逐月聚合已冻结 |
| 动态物理表 SQL 与逻辑表 Mapper 并存 | 表名二次路由、事务管理器分裂或部分写入 | OrderSqlMapper 是实际生产入口 |
| 状态日志没有写入 | 切换后不能证明审计事实与主表状态一致 | 只有 PO/Mapper，无 insert 调用 |
| 恢复任务依赖月前缀 | 结果未知和进程恢复可能查不到正确订单 | Reservation/stock-action 恢复路径 |
| Seata 代理边界未验证 | undo_log 与实际分片物理表不匹配，回滚破坏补偿事实 | CM-18 no-go 规格与当前配置 |

## 4. 分阶段可行方案

### 阶段 A：只读/启动审计（可单独修复）

1. 明确两个运行模式：`dynamic-monthly` 为当前默认兼容模式；`sharding-shadow` 仅用于验证，不接管写入。
2. 在不改变默认行为的前提下，启动时输出并暴露实际 `DataSource` 类型、当前 profile、Sharding 配置窗口、应用计算的可用月份和关键事务管理器；窗口不一致时在影子模式失败，而不是让生产写入半切换。
3. 增加静态配置检查：禁止 `actual-data-nodes` 窗口落后于 `OrderTableInitializer` 目标窗口；检查 `mall_order`、`mall_order_item`、`order_status_log` 三者月份集合一致。

### 阶段 B：冻结逻辑与物理表契约

1. 选择单一路由入口：要么所有订单、明细、状态日志和动态 SQL 统一使用经评审的 ShardingDataSource；要么继续动态表名并删除未接入的 Sharding 配置。不能两套入口同时宣称生效。
2. 冻结 Sharding 版本对应的 Spring Boot 接入、`DataSourceTransactionManager`、MyBatis/JdbcTemplate 绑定和 Seata 代理顺序；补充启动成功、单月读写、跨月列表、缺表失败、未来月滚动和回滚验证。
3. 将 `actual-data-nodes` 的生成/扩展与建表维护绑定，不能依赖静态 202608..202612。若框架版本无法安全动态扩展 actual nodes，应在切换前明确部署窗口和配置发布顺序。
4. 明确 order status log 是否成为真实状态机审计事实；若纳入，所有状态 CAS 和日志插入必须同一事务、同一分片月份，并补齐恢复与幂等语义。

### 阶段 C：影子验证后再评审单一纵向切片

用既有动态路径作为事实基线，在测试库对一批订单执行 Sharding 影子读：单月精确读取、跨月 count/page、订单明细、状态 CAS 结果、恢复查询和缺表错误码逐项比对。然后单独评审“订单创建 + 订单本地表”或其他单一纵向切片；不得同时迁移订单、库存、支付和用户，也不得在该切片内保留会提前提交的 intent/outbox 假设而期待 Seata AT 代替恢复模型。

## 5. 本轮验收门槛与状态

本评审没有修改业务代码、配置、数据库或 API，也没有改变默认运行行为。CM-20 当前应记录为：**订单动态月表方案仍是默认主链路；Sharding-JDBC 配置存在但未接入默认链路；直接切换 no-go。**

在阶段 A/B 完成并经 `pm_arch` 复审前，不得启用 `sharding` profile 作为默认生产/开发运行模式，不得将 `OrderMapper` 等逻辑表 Mapper 宣称为实际订单持久化入口。

验收至少包括：

- Maven 全量测试与 `git diff --check`；
- 2026-12 → 2027-01 滚动建表与路由一致性；
- 单月写入、跨月列表 total/page 排序、精确订单查询；
- 明细与订单同月、状态日志是否同事务；
- 缺表整体 503，不返回部分页；
- reservation/outbox/stock-action 恢复查询与订单主表同一快照/事务边界；
- Seata 演练表仍能完成 CM-18 已冻结的独立提交/回滚，真实订单表不参与 AT；
- 进程中断、Feign 结果未知、TC 不可达和重复操作键回归。
