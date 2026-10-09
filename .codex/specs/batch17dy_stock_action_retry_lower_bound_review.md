# CM-20261009-17DY：库存动作恢复重试时间下界过滤评审

## 1. 结论与范围

结论：**GO，仅在 `OrderStockActionMapper.findDue` 的 LIMIT 前排除早于合法 DATETIME 年份下界的 `next_retry_at`。**

DW 已将 `next_retry_at` 改为 typed LocalDateTime 读取，但零日期、0000/0999 旧重试时间仍可能在 driver 可读或读取阶段阻断扫描，也可能占据 LIMIT 使健康动作无法恢复。本批只增加一个扫描 eligibility，不改写历史日期。

## 2. Scan 契约

在原 `next_retry_at<=?` 条件后、状态/attempts/lease 后续条件和 LIMIT 前增加无参数字面量：

```sql
AND next_retry_at >= '1000-01-01 00:00:00.000'
```

其余 `findDue` SQL、列投影、三个参数（normalizedNow 双绑定与 limit）、status/attempts/lease 条件、排序、limit 归一、typed RowMapper 和异常传播全部保持。下界前行不进入候选窗口、不读取、不修复、不删除、不标记状态；年份 1000 及之后合法日期继续原恢复流程。

本批不增加零/部分日期、月份长度、其他时间列或全局 zero-date 过滤；不设置 SQL mode、时区或 driver 参数，不承诺其他非法历史日期行为。

## 3. 验收矩阵

- 真实 MySQL 隔离库 strict（`NO_ZERO_DATE/NO_ZERO_IN_DATE`）与 empty mode，覆盖下界前三类日期（zero、0000-01-01、0999-12-31）与健康年份 1000/现代毫秒，共 12 场景；只扫描健康第 2 条，原坏行文本 facts 前后不变。
- 60 条 zero-date 行 + 健康第 61 条，scan limit 50 能找到并恢复健康动作；Stock 返回 CONFIRMED 替身，健康动作 claim=1/finish SUCCEEDED，坏行不查询、不发送写 RPC、不改变。
- 保留 DW 合法日期/UTC fork、Action 6 类 SQL/lease/status/attempts/time 回归；不扩展真实 Stock HTTP、其他时间列、历史修复或全服务策略。

## 4. 交付边界

本评审只冻结规格，backend 按本文件及 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
