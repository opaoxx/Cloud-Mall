# CM-20261009-17DN：订单 Outbox 扫描 LocalDateTime 直接读取评审

## 1. 结论与范围

结论：**GO，仅替换 `findPendingOutbox` RowMapper 对四个 DATETIME 列的读取方式，绕开 JDBC `Timestamp` 到 `LocalDateTime` 的 GregorianCalendar 混合历转换。**

当前 `getTimestamp(...).toLocalDateTime()` 在 Java 17 默认 GregorianCalendar 1582-10-15 cutover 下，对真实 MySQL 可读的 `1582-10-05/10/14` 等日期抛 “specified date doesn't exist”，使 scan/dispatchPending 在候选隔离前阻断健康行。生产 SQL、资格、参数和历史事实均已冻结，本批只改读取映射。

## 2. RowMapper 读取契约

`findPendingOutbox` 的 SQL 文本、四个原 prepared 参数、WHERE（含 DK/DL/DM 全部资格）、全 row 投影、排序、LIMIT 和 `Math.max(1, limit)` 不变。RowMapper 对以下列直接使用 `ResultSet.getObject(column, LocalDateTime.class)`：

- `next_retry_at`
- `published_at`
- `created_at`
- `updated_at`

`published_at` 的 SQL NULL 必须继续映射为 Java null；其他三列仍按原 NOT NULL 语义映射。删除不再需要的 `java.sql.Timestamp` import。不得引入字符串解析 fallback、`Calendar` 参数、timezone 转换、JVM 默认时区设置、零日期放宽或历史修复。

该读取方式不改变 DI/DJ 的毫秒写入边界，也不修改 Service、dispatcher raw 上海 now、proof、message、writer 或错误传播。driver 对仍不合法的日期抛错时，scan 级异常继续原传播，不新增全局 catch。

## 3. 兼容与非目标

- 不过滤合法的 Gregorian cutover 日期，不把 1582-10-04/05/10/14/15 转换或替换为其他日期。
- 不承诺所有 JDBC driver、全服务时间路径、历史 timezone、编码或旧 `Timestamp` 行为都被统一修复；本批只验证 MySQL Connector/J 8.0.33 的 typed `getObject` 路径。
- 不改变 SQL mode、zero-date/partial-date/calendar eligibility、DDL、索引、参数、投影、排序、LIMIT 或任何 writer。

## 4. 验收矩阵

- 真实 MySQL 隔离库覆盖四个时间列 × `1582-10-04/05/10/14/15 12:34:56.789` × strict/empty 共 40 组合；四列均可读、毫秒精度保留、nullable `published_at` 保持，原 row facts 与 Order 库指纹不变。
- 定向 IT 使用独立 Surefire fork `user.timezone=UTC`，日志 marker 明确为 UTC；仅该测试 fork 设置时区，不修改全局 JVM 或业务配置。验证合法切换日不被默认 JVM 时区或 Gregorian cutover 误拒。
- 保留既有 DK/DL/DM 过滤、zero/partial/calendar、writer、Dispatcher、Rabbit 与数据库回归；不新增字符串 fallback 或时区补偿断言。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
