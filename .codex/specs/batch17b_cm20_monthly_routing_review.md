# CM-20261007-20：月度路由算法与 dormant 分片配置契约（阶段 B 前置）

## 1. 评审结论与范围

结论：**GO，限定修复 dormant 月路由算法的时间类型、月边界、range 契约和配置类名；不启用 sharding/shadow profile，不替换默认数据源，不修改真实订单 SQL、事务或物理表。**

17A 已冻结默认 `dynamic-monthly` 并拒绝当前未接入的 Sharding profile。本规格只为未来 Sharding-JDBC 4.1.1 隔离影子验证冻结可被验证的算法契约，不能被解释为允许当前主链路切换。

## 2. 配置与类名

- `MonthlyOrderShardingAlgorithm` 的正式 FQCN 冻结为 `com.cloudmall.order.config.MonthlyOrderShardingAlgorithm`；当前 YAML 中缺少 `.config` 的引用属于 dormant 配置错误，必须在影子配置校验中 fail closed。
- Sharding-JDBC 4.1.1 算法回调收到的 `availableTargetNames` 是不带 `ds0.` 前缀的物理表名；`actual-data-nodes` 配置仍使用 `ds0.<physical_table>`。算法只能匹配回调 target 名称，不能要求 target 本身带数据源前缀。
- `mall_order`、`mall_order_item`、`order_status_log` 三个逻辑表必须使用同一组月份 target；三表一致性属于配置加载/隔离夹具校验，不假设一次算法回调同时收到三张表。每张逻辑表必须声明 `created_at` 分片列，物理目标严格匹配 `<logical_table>_yyyyMM`，月份后缀唯一、可解析，不允许混入其他逻辑表。
- actual-data-nodes 只代表可路由 target 集合，不代表物理表一定存在；查询命中 target 缺表时必须整体失败，不静默丢弃该月、不广播到其他月份、不隐式建表。
- 当前 `dynamic-monthly` 默认模式、17A fail-fast、默认 Hikari/同源 Seata proxy 绑定和滚动建表链路不改；本轮不激活 `sharding` 或 `sharding-shadow`。

## 3. 时间与类型契约

订单分片键语义固定为上海时区、本地 `DATETIME(3)`，不携带时区。算法支持的 precise/range 边界类型只包括：

- `java.time.LocalDateTime`；
- `java.sql.Timestamp`，按 JDBC/session 已物化的本地字段调用 `toLocalDateTime()` 后处理。

`OffsetDateTime`、`ZonedDateTime`、`Instant`、`java.util.Date`、任意字符串、数字和其他 Comparable 不得隐式猜测时区或截取字符；发现时返回明确的路由配置/参数错误并 fail closed。`Timestamp.toLocalDateTime()` 不把 Timestamp 当 Instant 再次转换；JDBC/session 必须先冻结为 Asia/Shanghai 的本地 DATETIME 解释，并在隔离测试中验证。若 Sharding-JDBC 4.1.1 实际回调类型不是上述类型，必须在隔离影子测试中先冻结显式适配器，不能靠 `String.valueOf(...).substring(...)` 兜底。

所有月份计算使用 `Asia/Shanghai` 的 LocalDateTime 语义；不得用 JVM 默认时区。只接受 MySQL DATETIME 合法年份 `1000..9999`，且纳秒必须满足 `nano % 1_000_000 == 0`，以符合 DATETIME(3) 毫秒精度。月份边界是左闭右开：

```text
[yyyy-MM-01 00:00:00.000, next-yyyy-MM-01 00:00:00.000)
```

月初毫秒归入新月，月末 `23:59:59.999` 归入当月；不做四舍五入、截断到日或跨时区转换。

## 4. Precise 路由

给定单值 `created_at`，先验证支持类型和毫秒精度，再计算 Shanghai `YearMonth`，生成对应 `_yyyyMM` target。target 不在 available target 集合时抛出确定性“月份 target 不可用”错误；不得返回任意第一个 target 或把订单写入邻月。

空值、无法解析或类型不支持均失败。算法不得直接访问数据库、创建表或读取当前月份来改变路由结果。

## 5. Range 路由

影子实现必须提供与 Sharding-JDBC 4.1.1 版本匹配的 range 算法契约；实现前先用隔离 MySQL/真实依赖确认接口签名。range 只接受支持类型的上下界，按毫秒离散值和每个月的左闭右开区间计算相交 target：

- 有界范围：返回所有与查询范围有非空交集的月份，按月份稳定排序；
- 开下界按下界时间加 1 毫秒，开上界按上界时间减 1 毫秒；下界/上界相等或调整后反转时为空 range，不得路由到任意表；
- 上界正好落在下月月初时，exclusive 上界排除该边界，inclusive 上界按该毫秒瞬时所属月份处理；月末最后一毫秒之后的时间不再包含前一月；
- 无下界或无上界的无界范围不允许广播全库，直接 fail closed；
- 范围跨越 available target 缺失的月份时整体 fail closed，不能返回部分月份结果；
- 空 range 的返回方式必须先通过 Sharding-JDBC 4.1.1 unit/IT 观察并冻结：若框架接受空 target 集合则返回空集合；若不接受则转换为明确无结果错误，禁止查询任意表。

Range 算法不负责 count、全局排序、跨月分页或物理表创建；这些仍属于 CM-20 后续逻辑路由评审。

## 6. 隔离影子验证前置

启用任何 Sharding profile 前，必须用实际 Sharding-JDBC 4.1.1 依赖和隔离 MySQL 数据库完成只读/影子验证：

1. 校验修正后的 FQCN 可加载，默认 dynamic-monthly 仍拒绝该 profile；
2. precise 覆盖上海月初、月末毫秒、跨年、target 缺失和不支持类型；
3. range 覆盖空区间、边界开闭、跨月、无界范围和缺失月份；
4. 三个逻辑表 target 集合一致性和实际影子表存在性分别验证；
5. 单月/跨月只读结果与现有动态月表路径逐项比对，默认 LEGACY/TCC/Seata 业务链路不接入；
6. 禁止连接当前真实订单库执行影子写入、DDL、迁移、状态 CAS 或恢复任务。

## 7. 不支持与范围声明

本轮不支持隐式字符串时间、跨时区时间自动转换、无界广播、缺月部分路由、自动建表、动态扩展 actual-data-nodes、ShardingDataSource 接入、Seata+Sharding 事务切换、跨月全局分页和真实订单主链路切换。17B 完成只表示 dormant 路由契约可测试、配置错误不再含糊，不表示 CM-20 整体闭环。
