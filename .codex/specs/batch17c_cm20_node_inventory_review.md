# CM-20261007-20：Sharding 节点清单与规则工厂（阶段 C 前置）评审

## 1. 评审结论与范围

结论：**GO，限定为生成可审计的 Sharding-JDBC 4.1.1 规则配置对象；不装配、不接管业务 DataSource，不启用 shadow，不修改默认 dynamic-monthly 链路。**

17B 已修复 dormant precise/range 算法，但当前 YAML 的静态 `actual-data-nodes=202608..202612` 会在跨年后失效，也与三类表的真实节点窗口缺少统一来源。继续维护该静态声明会产生第二套路由真相。本轮移除失效的静态 `spring.shardingsphere` 节点声明，保留 sharding profile 标记和 17A 的启动拒绝；未来 shadow 只通过显式工厂和隔离测试使用。

## 2. 非 Spring 规则工厂

新增设计规格 `OrderShardingRuleFactory`，必须是普通 Java 工厂，不注册为 Spring `@Bean`，不创建 DataSource、JdbcTemplate、事务管理器、连接或表。工厂 API 固定为：

```java
public static ShardingRuleConfiguration create(
    YearMonth currentMonth,
    Map<String, ? extends Collection<String>> physicalTables)
```

`physicalTables` 必须严格包含且只包含三个 key：`mall_order`、`mall_order_item`、`order_status_log`；每个 key 的清单是该逻辑表已观测的物理月表名。数据源名固定为 `ds0`，不开放调用方传入其他数据源名。

工厂输出仅为真实 Sharding-JDBC 4.1.1 支持的 `ShardingRuleConfiguration` 及其三张表规则配置；实现必须以项目锁定的 4.1.1 API 编译验证，不发明兼容类或隐式启动 Spring 路由。三张逻辑表使用相同 `created_at` standard precise/range 算法配置，算法 FQCN 为 `com.cloudmall.order.config.MonthlyOrderShardingAlgorithm`。

## 3. 节点清单强校验

工厂在生成规则前拒绝以下输入：

1. 任一清单为空、含 null/重复/非 `<logical_table>_yyyyMM` 名称、错误逻辑表前缀或非法月份；
2. 三类清单的 YearMonth 集合不完全相同；
3. 历史月份集合存在内部缺口。清单必须覆盖从最早观测月份到最晚观测月份的连续月份；工厂不得凭当前时间猜测或补齐缺失历史；
4. 共享滚动窗口不完整。使用 `OrderTableInitializer.rollingMonths(currentMonth)` 的同一 helper，要求上月、本月及未来两个月四个月均出现在三类已观测集合中；currentMonth 和该四个月必须落在 `1000..9999` 年范围内，滚动窗口越界时直接拒绝；
5. 输入的 currentMonth 与清单窗口、规则 target 不一致；
6. 数据源前缀、逻辑表名或 target 名称被混入算法回调所用的不带 `ds0.` 物理表名。

规则节点由已观测清单逐项生成 `ds0.<physical_table>` actual-data-node；历史月份必须保留，不能只生成滚动四个月。缺表、缺伴表或窗口不完整直接 fail closed，不自动建表、不读取数据库补清单、不生成部分规则。

## 4. 生命周期与跨年边界

规则工厂是快照式构建：输入清单变化后必须显式重新观测、校验并重新创建隔离规则/DataSource；不得运行期热改现有 DataSource 的 actual nodes。旧规则快照不能读取新增加的月份，直到显式重建工厂和隔离 DataSource；历史物理表继续保留并纳入新快照。

以 `currentMonth=2026-12` 为例，已有滚动窗口是 `202611/202612/202701/202702`；进入 `currentMonth=2027-01` 后，新增目标月份是 `202703`，新窗口为 `202612/202701/202702/202703`。旧的 2026-12 快照不能自动路由 202703；只有在 January 重新观测三表并把 202703 追加到清单后显式重建工厂/隔离 DataSource，才可读取该月，同时保留历史月份。工厂不得把“跨年”理解为在 December 快照中提前加入 202703。

## 5. YAML 与默认链路边界

`application.yml` 只保留 `spring.config.activate.on-profile: sharding` 的 profile 标记和必要说明，不再保留失效的静态 `spring.shardingsphere.datasource`、`actual-data-nodes` 或算法 FQCN 声明。17A 继续在该 profile 被激活时 fail-fast；默认启动不加载或接管 Sharding 配置。

该删除不代表启用 Sharding，也不迁移 `OrderSqlMapper`、MyBatis Mapper、订单恢复、状态日志、Seata 代理或事务管理器。默认 dynamic-monthly、普通 Hikari/同源合法 Seata proxy 和现有物理月表链路保持不变。

## 6. 隔离 MySQL/真实 4.1.1 验收

在隔离数据库和真实 Sharding-JDBC 4.1.1 依赖下验证：

1. 读取三类实际物理表清单，验证固定 `create(YearMonth, Map)` API、严格三个 key 和固定 `ds0`，跨年构造规则，precise/range 单月及跨月只读路由成功；
2. 删除任一伴表或制造历史月份缺口，工厂在建规则前失败，不生成部分规则；
3. 2026-12 快照窗口为 202611..202702；旧快照不能读取新增 202703，2027-01 清单追加 202703 并显式重建后才可读取；历史表仍可读取；
4. range 跨越缺失月份整体失败，无界 range、空 range marker、缺 `created_at` 的框架广播边界按 17B 规则验证；
5. 三类逻辑表 target 集合、精确/范围算法和 `ds0.` 前缀转换与 4.1.1 实际 API 一致；
6. 默认 dynamic-monthly 服务、订单表初始化、Seata 独立演练及 Pay/Order/Stock 既有回归不接入该工厂。

测试只读或使用隔离数据库，禁止连接当前真实订单库执行迁移、建表、写订单或替换默认 DataSource。

## 7. 实施门与范围声明

本轮只允许实现工厂、节点清单校验、静态 YAML 清理和隔离测试所需契约；不装配工厂为 Spring Bean，不创建 shadow 入口，不修改默认业务路由，不自动建表或热更新 DataSource。17C 完成只表示节点真相可显式审计、跨年规则可隔离验证，不表示 CM-20 分片主链路已切换。
