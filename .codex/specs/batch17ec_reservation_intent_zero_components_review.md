# CM-20261009-17EC：预扣意图恢复扫描零月日资格评审

## 1. 结论与范围

结论：**GO，仅在 `OrderReservationIntentMapper.findRecoverable` 的 LIMIT 前排除三个时间列中月份或月内日期为零的扫描候选。**

EB 已排除年份下界以前的历史值，但 MySQL 存量仍可能包含 `2026-00-15`、`2026-10-00` 或 `2026-00-00`。这些值在 full-row typed `LocalDateTime` 映射时会抛 `DateTimeException`，或在可读模式下占据 LIMIT，阻断健康意图恢复。本批只增加扫描资格，不改历史事实。

`findByUserAndKey` 保持原按用户与幂等键读取候选，不增加资格过滤；坏意图仍沿原点查读取或错误传播，不能被伪装成缺行。

## 2. Scan 契约

在 EB 年份下界谓词之后、LIMIT 前增加以下无参数资格：

```sql
AND MONTH(created_at)>0 AND DAYOFMONTH(created_at)>0
AND MONTH(updated_at)>0 AND DAYOFMONTH(updated_at)>0
AND (next_retry_at IS NULL
     OR (MONTH(next_retry_at)>0 AND DAYOFMONTH(next_retry_at)>0))
```

三组谓词仅用于恢复扫描。保留 EB 下界、原 `staleBefore`、`now`、`limit` 三个绑定、全行投影、状态条件、原排序、LIMIT 归一、共享 typed RowMapper、nullable `next_retry_at`、`claimRecovery`、`markReleased`、`RELEASED` 状态写入、Recovery/Stock 调用和异常传播。

本批不设置 SQL mode、driver 或时区，不增加完整日历/月末校验，不处理其他字段或历史 repair；零月日行只被恢复扫描资格排除，不读取、不修复、不删除、不改状态。

## 3. 验收矩阵

- 真实 MySQL strict 与 empty 两种读取模式，覆盖 `created_at`、`updated_at`、`next_retry_at` 三列的 `2026-00-15`、`2026-10-00`、`2026-00-00`，共 18 个组合。坏行原始文本和 full-row snapshot 前后不变，`limit=1` 只返回健康 id=2，`next_retry_at` NULL 的健康行仍可选；对应坏行的 `findByUserAndKey` 继续保留原 `DateTimeException` 或原候选语义。
- 真实混合场景保存 60 条跨三列的 partial-zero 行及健康 id=61；`findRecoverable(limit=50)` 能恢复健康意图至 `RELEASED`，清理其 `next_retry_at`/`last_error`，仅对健康行执行 Stock GET `ROLLED_BACK` 与订单不存在判据替身，不执行 reserve/rollback；60 条坏行整行不变。
- 保留 EB 12 个年份下界、NULL、三状态正例，DX 合法日期六类回归；新增 UTC fork 只覆盖本批两个测试方法。不得扩展到完整 Gregorian 校验、月末上限、其他时间列、历史修复或真实 Stock HTTP。

## 4. 交付边界

本评审只冻结规格，backend 按本文件及 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
