# CM-20261009-17DZ：库存动作恢复重试零月日过滤评审

## 1. 结论与范围

结论：**GO，仅在 17DY 下界之后排除 `next_retry_at` 的零月份或零日期。**

17DY 已排除年份下界前值，但 `2026-00-15`、`2026-10-00`、`2026-00-00` 仍可进入 `findDue`；typed LocalDateTime 读取这些值可能抛 `DateTimeException`，扫描在恢复候选隔离前阻断健康动作。本批只保护 `next_retry_at` 组件资格。

## 2. Scan 契约

在原 DY 下界条件后、LIMIT 前增加两个不带参数的谓词：

```sql
AND MONTH(next_retry_at)>0
AND DAYOFMONTH(next_retry_at)>0
```

其余 `findDue` SQL、3 个原参数、列投影、typed RowMapper、status/attempts/lease 条件、排序、limit、claim/finish/retry、Recovery/Stock、时区配置、driver 策略和历史事实保持。零月/零日行只排除候选，不读取、不修复、不删除、不写状态。

## 3. 非目标与验收

- 不增加合法月份上限、超月末校验、其他时间列过滤、全局 JDBC/SQL mode 设置或历史 repair；不承诺其他非法日期路径。
- 真实隔离 MySQL strict/empty 两 mode 覆盖三种零组件 × 两个健康日期，共 12 场景；只选健康 id=2，坏行 full snapshot 保持。
- 60 条混合零月/零日 + 健康 61 场景，scan limit 50 恢复 61；Stock 仅健康 GET 替身，健康动作完成，坏行不查询/不写 RPC。保留 DW 合法日期/UTC 与 Action 原回归。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
