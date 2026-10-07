# CM-20261007-20：订单月表模板引导修复评审

## 1. 评审结论与范围

结论：**GO，限定修复订单明细/状态日志模板的来源选择和月表 DDL 封装；不改变 schema、API、月份窗口、默认 dynamic-monthly 或 Sharding 接入。**

当前 `OrderTableInitializer.prepareForMonth` 每次执行 `CREATE TABLE IF NOT EXISTS <template> LIKE <legacy_202608>`。MySQL 即使目标 template 已存在，也会先解析 LIKE 源表；当历史 `202608` 被移除而正式 template 保留时仍报 1146，导致启动和定时月表维护失败。

## 2. 模板来源契约

只对现有 companion template 处理：

- `mall_order_item_template` 优先使用已存在的正式模板；
- `order_status_log_template` 优先使用已存在的正式模板；
- 正式模板缺失时，分别兼容从 `mall_order_item_202608`、`order_status_log_202608` 创建；
- 正式模板和对应 legacy 202608 源表都缺失时，抛出稳定的模板引导错误并停止本次 DDL；
- 正式模板存在时不得访问对应 legacy 源表，因此 legacy 缺失不影响启动；
- 不猜测其他月份、不从业务表复制结构、不自动重造缺失的业务模板结构。

现有 `mall_order_template` 继续作为订单主表正式模板使用；本轮不改变其 schema 或引导来源。若其缺失，沿现有 DDL 错误路径明确失败。

## 3. Mapper 语义 API

所有 SQL 和元数据判断归 `OrderSqlMapper`，Initializer 不再拼接模板/物理月表 SQL。建议冻结两个语义方法：

```text
ensureMonthlyCompanionTemplates()
createMonthlyOrderTables(String yyyyMM)
```

`ensureMonthlyCompanionTemplates()` 先通过安全的表存在性元数据判断选择正式模板或对应 202608 legacy fallback，再执行必要的 `CREATE TABLE ... LIKE ...`；不对已存在正式模板执行带缺失源表的 CREATE LIKE。

`createMonthlyOrderTables(yyyyMM)` 校验月份严格为四位年份 `1000..9999` 与 `01..12`，然后固定按三个模板执行幂等建表：

```text
mall_order_<yyyyMM>       LIKE mall_order_template
mall_order_item_<yyyyMM>  LIKE mall_order_item_template
order_status_log_<yyyyMM> LIKE order_status_log_template
```

月份字符串不得来自用户输入或任意表名；非法 suffix 直接失败。DDL 仍使用当前 Mapper/JdbcTemplate/事务边界，不新增表字段、不改已有月表内容。

## 4. Initializer 与启动边界

`OrderTableInitializer` 继续 `@DependsOn("orderRoutingAudit")`，保持 Shanghai rollingMonths 四个月窗口和现有启动/定时生命周期；只调用上述语义 Mapper 方法。`OrderReliabilityTableInitializer`、17A 路由审计和 Sharding profile 拒绝逻辑不变。

模板引导失败必须阻止本轮窗口 DDL 继续推进并记录明确错误；不得吞异常或声称月表准备成功。重复启动、重复定时执行和跨年窗口必须保持幂等。

## 5. 验收矩阵

1. 正式 item/status template 存在、legacy 202608 缺失：启动/维护成功，能够创建滚动窗口月表。
2. 正式 template 缺失、对应 legacy 202608 存在：兼容创建 template 后成功建月表。
3. 正式 template 与对应 legacy 均缺失：稳定错误、窗口准备失败，不猜其他月份、不自动复制业务表。
4. 已有正式 template 时确认不会执行或依赖缺失 legacy LIKE 源表。
5. 现有 `OrderTableInitializerTest` 更新为语义 Mapper 调用与 fallback 分支；真实 MySQL 隔离测试覆盖模板存在无 legacy、模板缺失有 legacy、双缺失败、重复/跨年滚动和三表结构/数据不变。
6. 无 legacy 但有正式模板的真实 Order 进程可启动；模板引导不改变 17A audit、默认 dynamic-monthly、路由窗口或 Sharding 拒绝。
7. 原库表清单/checksum、订单 API、恢复任务、Pay/Order/Stock 回归保持通过。

## 6. 明确排除

本轮不启用 Sharding/shadow、不替换 DataSource、不迁移订单 SQL/事务、不扩展实际月份窗口、不新增 schema/API、不修改历史月表内容、不自动创建丢失的业务结构。模板缺失只允许规定的正式模板/202608 兼容来源；两者都缺失必须 fail closed。
