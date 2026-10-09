# CM-20261009-17DL：订单 Outbox 扫描部分零日期过滤评审

## 1. 结论与范围

结论：**GO，仅在 17DK 下界 eligibility 之上排除月份或日期为零的部分零日期，避免 JDBC 时间映射再次阻断整批扫描。**

17DK 只排除了年份下界之前的典型零日期；MySQL 允许部分零日期，`2026-00-15`、`2026-02-00`、`2026-00-00` 仍可进入候选，driver 在 `getTimestamp(...).toLocalDateTime()` 时可能抛 `MONTH` 或 `DAY_OF_MONTH` 错误。MySQL 官方日期函数定义 `MONTH()`、`DAYOFMONTH()` 可直接提取月份和月内日期，并允许返回 0（[MySQL Date and Time Functions](https://dev.mysql.com/doc/refman/8.0/en/date-and-time-functions.html)）。本批只用这两个提取结果排除零月/零日，不做完整日期转换。

## 2. Scan eligibility 契约

保留 17DK 的四个下界谓词原样、原 due/status/type/attempts/identity 条件、全 row SELECT 投影、四个原 prepared 参数、排序、`Math.max(1, limit)` 和 JDBC 映射。WHERE 在 LIMIT 前追加：

```sql
AND MONTH(next_retry_at)>0 AND DAYOFMONTH(next_retry_at)>0
AND MONTH(created_at)>0 AND DAYOFMONTH(created_at)>0
AND MONTH(updated_at)>0 AND DAYOFMONTH(updated_at)>0
AND (
  published_at IS NULL
  OR (MONTH(published_at)>0 AND DAYOFMONTH(published_at)>0)
)
```

四列任一非空值的 MONTH 或 DAYOFMONTH 为 0 时不进入候选窗口；nullable `published_at` 继续合法。月份/日期为正且年份满足 17DK 下界的值继续原扫描、排序和 dispatch 路径。

## 3. 兼容与非目标

- 不 UPDATE、DELETE、修复、替换或标记历史部分零日期；坏行不发送、不写 PUBLISHED/failure，保留原全 row 事实。
- 不修改 driver zero-date 行为、SQL mode、Service、writer、Dispatcher、消息、路由、proof、DDL、索引或 API；不增加参数。
- 不宣称完整日历校验，不处理非零非法月份/日期、`ALLOW_INVALID_DATES` 或时间转换 warning；函数只承担零月/零日候选资格过滤。

## 4. 验收矩阵

- 真实 MySQL 隔离库：对 `next_retry_at/created_at/updated_at/published_at` 的 `2026-00-15`、`2026-02-00`、`2026-00-00` 组合，在 strict/empty 两 mode 覆盖 24 组合；`LIMIT 1` scan 与 `dispatchPending` 均只处理健康事件，编号为 2 的健康行真实 SQL+Rabbit 确认替身发布为 PUBLISHED，编号 3 行保持 PENDING，坏行不发送不写入。
- 核对坏行全 row 文本 facts、原 Order 数据库指纹、owned 隔离库清理；17DK 的零日期、NULL published_at、年份 1000/9999 和既有 due/limit 回归保持。
- 保留既有 SQL/dispatcher/Rabbit 回归；不通过扩大异常 catch 或修改 SQL mode 规避 driver 错误。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
