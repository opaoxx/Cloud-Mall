# CM-20261009-17EB：预扣意图恢复扫描时间下界评审

## 1. 结论与范围

结论：**GO，仅在 `OrderReservationIntentMapper.findRecoverable` 的 LIMIT 前增加三项无参数时间下界资格。**

DX 已将共享 RowMapper 的三个时间列改为 typed `LocalDateTime` 读取，但恢复扫描仍可能遇到零日期、0000 或 0999 的历史值：driver 可能在映射阶段抛错，也可能让坏行占据扫描窗口。本批只排除不具备 MySQL DATETIME 合法年份下界的扫描候选，不改写历史意图。

`findByUserAndKey` 必须保持原按用户与幂等键读取候选，绝不增加下界过滤；坏意图不能因本批规则被伪装成“不存在”。

## 2. Scan 契约

在 `findRecoverable` 原有状态、恢复时间资格之后、LIMIT 前增加以下无参数字面量谓词：

```sql
AND created_at >= '1000-01-01 00:00:00.000'
AND updated_at >= '1000-01-01 00:00:00.000'
AND (next_retry_at IS NULL OR next_retry_at >= '1000-01-01 00:00:00.000')
```

原扫描仍只绑定 `staleBefore`、`now`、`limit` 三个参数；全行投影、status 条件、原排序、LIMIT 归一、共享 typed RowMapper、nullable `next_retry_at`、`claimRecovery`、`markReleased`、状态写入、Recovery/Stock 调用和异常传播保持。其他 SQL 保持原语义；本条只增加上述扫描资格。

下界以前的历史行不进入恢复候选，不被恢复扫描读取、修复、删除或改变状态；按用户与幂等键的原候选读取仍沿原行为处理。年份 1000 及以后且 `next_retry_at` 为 NULL 的行继续沿原流程处理。

本批不增加零月/零日、超月末、其他字段或完整日历校验，不设置 SQL mode、时区或 driver 参数，不提供历史 repair，也不承诺真实 Stock HTTP 全链路。

## 3. 验收矩阵

- 真实 MySQL 隔离库 strict 与 empty 两种读取模式，覆盖 `next_retry_at`、`created_at`、`updated_at` 三列分别写入 zero、`0000-01-01`、`0999-12-31` 的 18 个组合；健康行固定为较晚的 `2026-10-08 11:00:00`，避免依赖墙上时钟。`limit=1` 只返回健康 id=2，坏行的原始文本 facts 前后不变。
- 真实混合场景保存 60 条三列分布的坏日期行及健康 id=61；`findRecoverable(limit=50)` 能处理健康行并完成原恢复状态到 `RELEASED`，只对健康行执行 Stock GET ROLLED_BACK 替身与订单不存在判据替身，不调用 reserve/rollback；60 条坏行整行不变。
- 12 个合法正例覆盖 strict/empty × 三种原恢复状态 × `next_retry_at` NULL/最小合法时间组合；created/updated 使用精确年份 1000，键查询与扫描仍保持原结果。独立 UTC fork 只覆盖本批新增的三个测试方法；保留原 Intent 六类回归。

## 4. 交付边界

本评审只冻结规格，backend 按本文件及 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
