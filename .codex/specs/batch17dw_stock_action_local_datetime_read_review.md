# CM-20261009-17DW：库存动作恢复 LocalDateTime 直接读取评审

## 1. 结论与范围

结论：**GO，仅替换 `OrderStockActionMapper.findDue` RowMapper 对 `next_retry_at` 的 JDBC 读取方式。**

该 Mapper 当前显式 `getTimestamp("next_retry_at").toLocalDateTime()`，在 Connector/J/Java GregorianCalendar cutover 下会使合法历史 1582 日期抛错；扫描级异常发生在 Recovery 的候选隔离前，两个健康动作因此无法继续。该问题与订单 `queryForList` 自动映射不同，本批只保护这一列。

## 2. Mapper 契约

`findDue` SQL 文本、列投影、三个原参数（normalizedNow 双绑定与 limit）、状态/负 attempts/lease WHERE、排序和 limit 归一保持。RowMapper 仅改为：

```java
action.nextRetryAt = resultSet.getObject("next_retry_at", LocalDateTime.class);
```

不改其他列读取、`normalizedNow` 毫秒归一、claim/finish/retry、Recovery、Stock 调用、时区/配置、Calendar、字符串 fallback、DDL、索引、历史事实或零/部分非法日期策略。扫描数据库/driver 异常仍沿原传播，不增加全局 catch；未来租约、shadow、状态和 lease fencing 不纳入本批。

## 3. 验收矩阵

- 真实 MySQL 隔离库 strict/empty 两 mode 覆盖 7 个日期：1000、1582-10-04/05/10/14/15、现代毫秒值，共 14 组合；四时间/动作事实、排序和原日期组件保持。
- 第二个真实 IT 在两个动作中放置古老候选与健康后项：Mapper/Recovery 能读取并继续，Stock 替身返回 CONFIRMED，两个 action 以原 operationKey 完成；不重复发写 RPC，状态/lease/attempts/日期保持原契约。
- 新测试使用独立 Surefire fork `user.timezone=UTC`，仅测试 JVM 设置 marker，不改业务全局时区；保留既有 6 类 action SQL/lease/status/attempts/time 回归与源库/owned 库清理。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
