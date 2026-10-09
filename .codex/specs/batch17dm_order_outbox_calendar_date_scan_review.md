# CM-20261009-17DM：订单 Outbox 扫描日历日期下界过滤评审

## 1. 结论与范围

结论：**GO，仅在 17DK/17DL 的时间 eligibility 之后排除非零但超出当月日历范围的日期。**

17DK/17DL 已拦截年份下界、零月和零日，但 `2026-02-29`、`2026-02-30`、`2026-04-31`、`2026-09-31`、`1900-02-29`、`1000-02-29` 仍可进入 scan；在 `ALLOW_INVALID_DATES` 造数后，JDBC 时间读取可能抛异常，scan/dispatchPending 在 CS 候选隔离前阻断健康行。MySQL `LAST_DAY()` 返回月份末日，非法或 NULL 参数返回 NULL（[MySQL Date and Time Functions](https://dev.mysql.com/doc/refman/8.0/en/date-and-time-functions.html)），本批用它做当月日上限比较。

## 2. Scan eligibility 契约

保留 17DK 下界、17DL 零月/零日条件原样，保留原 due/status/type/attempts/identity、全 row 投影、四个 prepared 参数、排序、`Math.max(1, limit)` 和 JDBC 映射。WHERE 在 LIMIT 前追加：

```sql
AND DAYOFMONTH(next_retry_at)<=DAYOFMONTH(LAST_DAY(next_retry_at))
AND DAYOFMONTH(created_at)<=DAYOFMONTH(LAST_DAY(created_at))
AND DAYOFMONTH(updated_at)<=DAYOFMONTH(LAST_DAY(updated_at))
AND (
  published_at IS NULL
  OR DAYOFMONTH(published_at)<=DAYOFMONTH(LAST_DAY(published_at))
)
```

对非 NULL 日期，`LAST_DAY` 为 NULL 时比较结果不满足 eligibility，行不进入候选；`published_at IS NULL` 继续合法。有效 Gregorian 日期继续原扫描与 dispatch 路径。

若实际 MySQL 版本/模式对这些函数产生新 warning 或返回语义与上述契约不符，应保留验收失败并请求重新评审，不放宽断言或改用完整日期转换。

## 3. 兼容与非目标

- 不 UPDATE、DELETE、修复、替换或标记历史非法日期；坏行不发送、不写 PUBLISHED/failure，保留原全 row facts。
- 不改变 17DK/17DL 下界和零月日门、driver、SQL mode、Service、writer、消息、proof、DDL、索引、API、参数、投影、排序或 LIMIT。
- 不宣称所有日期 driver 组合闭环，不处理 1582 日历切换、历史 timezone、编码或其他非本批日期异常，也不修复 `ALLOW_INVALID_DATES` 历史事实。

## 4. 验收矩阵

- 真实 MySQL 隔离库在 `ALLOW_INVALID_DATES` 造数：四列分别覆盖 `2026-02-29`、`2026-02-30`、`2026-04-31`、`2026-09-31`、`1900-02-29`、`1000-02-29`，strict/empty 两 mode 共 48 组合；scan/dispatchPending 只处理健康行，编号 2 真实 SQL+Rabbit 确认替身发布为 PUBLISHED，编号 3 保持 PENDING，坏行不发送不写入。
- 合法日期覆盖 2000/2024 闰年 2 月 29 日、1900/1000 年 2 月 28 日、2026 年 4 月 30 日与 1 月 31 日；四列均可精确读取并保持。
- 保留 17DK/17DL 零日期、NULL published_at、1000/9999 端点、原 Order 指纹和 owned 隔离库清理；函数产生 warning 或语义不符时验收保持失败，不放宽测试。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
