# CM-20261009-17EA：库存动作恢复重试日历上限过滤评审

## 1. 结论与范围

结论：**GO，仅在 17DZ 零月/零日门之后排除 `next_retry_at` 超出当月最后一天的日期。**

DY/DZ 已排除年份下界与零月/零日，但 `2026-02-29`、`2026-02-30`、`2026-04-31`、`2026-09-31`、`1900-02-29`、`1000-02-29` 仍可进入 typed read；在 `ALLOW_INVALID_DATES` 造数后会抛 `DateTimeException` 并阻断健康恢复。本批只保护 `next_retry_at` 的当月日历上限。

## 2. Scan 契约

在原 DY/DZ 条件后、LIMIT 前增加唯一无参数谓词：

```sql
AND DAYOFMONTH(next_retry_at)<=DAYOFMONTH(LAST_DAY(next_retry_at))
```

其余 `findDue` SQL、三个原参数、投影、typed RowMapper、status/attempts/lease 条件、排序、limit、claim/finish/retry、Recovery/Stock、DDL、时区、driver 和 SQL mode 保持。对非法/NULL `LAST_DAY` 结果的非空行不放行；不修复、不删除、不写状态。

## 3. 非目标与验收

- 只校验 `next_retry_at` 的月长；不扩展其他时间列、完整 Gregorian/1582、零/部分日期门、历史 repair、全局配置或真实 Stock HTTP。
- 真实隔离 MySQL strict/empty 两 mode 覆盖 6 类坏日 × 两健康日期共 24 场景，坏行 full snapshot 不变，健康行可选并继续恢复；60 混合坏日 + 健康 61 验证 scan limit 不被占满，健康动作完成且 Stock 仅替身 GET。
- 合法 1000/1900 Feb28、2000/2024 Feb29、2026 Apr30、9999 Dec31 和 UTC fork 三方法回归保持；函数 warning/语义不符时验收失败，不放宽断言。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
