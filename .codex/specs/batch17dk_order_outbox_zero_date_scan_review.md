# CM-20261009-17DK：订单 Outbox 扫描零日期下界过滤评审

## 1. 结论与范围

结论：**GO，仅在 `findPendingOutbox` 的 LIMIT 前增加 DATETIME 下界 eligibility，避免 MySQL 零日期在全 row 映射阶段阻断整批扫描。**

历史 `PENDING` 行若 `next_retry_at`、`created_at`、`updated_at` 或非空 `published_at` 为 MySQL 零日期，当前 `getTimestamp(...).toLocalDateTime()` 可能在 driver 默认 zero-date 行为下抛错；扫描级异常会在 CS 候选隔离前阻断后续健康行。本批只把明显早于合法 DATETIME 年份下界的行排除在候选窗口之外，不读取、不修复、不删除历史事实。

## 2. Scan eligibility 契约

`findPendingOutbox` 保留原 SELECT 全 row 投影、参数、`Math.max(1, limit)`、排序、due/status/type/attempts/identity 条件和映射逻辑。WHERE 在 LIMIT 前追加四个字面量谓词，且不增加 prepared 参数：

```sql
AND next_retry_at >= '1000-01-01 00:00:00.000'
AND created_at >= '1000-01-01 00:00:00.000'
AND updated_at >= '1000-01-01 00:00:00.000'
AND (published_at IS NULL OR published_at >= '1000-01-01 00:00:00.000')
```

四列中任一非空值早于合法下界的行不进入候选窗口；`published_at IS NULL` 继续合法。年份 1000 与 9999 的可读值保留，合法非零时间继续原排序、LIMIT 和 dispatcher 路径。该过滤不改变 DDL、索引、driver zero-date 行为、SQL mode、DI/DJ 时间写入、Service 或错误传播；扫描级 SQL/driver 异常仍不被全局 catch 吞掉。

本批只处理下界之前、包括典型零日期的行，不宣称完整日历校验，也不处理月份/日期非法但仍不小于下界的值、`ALLOW_INVALID_DATES` 语义或历史修复。

## 3. 兼容与不变量

- 不 UPDATE、DELETE、回填、替换或标记坏行；坏行不会发送、不会写 PUBLISHED/failure，仍保留原文本事实供人工核验。
- 不改变 due `next_retry_at<=now`、PENDING/type/counter/identity eligibility，消息 envelope、Rabbit confirm、17DD proof、DF/DE/DG/DH/DI/DJ 语义和 API/message 保持。
- 不新增参数、列、索引、时间转换、全局 zero-date 配置或 partial projection；只改变 LIMIT 前候选资格。

## 4. 验收矩阵

- 真实 MySQL 隔离库：empty mode 构造四列分别为零日期的行，strict/empty 两种读取模式覆盖四列各自坏值组合；批量 `LIMIT 1` 扫描只返回健康行，dispatcher 使用真实 SQL + Rabbit 发送确认替身仅发布健康事件，坏行不发送、不写入，第三行保持 PENDING。
- 核对坏行完整文本事实在 scan/dispatch 后不变；`published_at=NULL`、年份 1000/9999、原 due 顺序、LIMIT 和投影可正常读取。
- 保留现有 SQL/dispatcher/Rabbit 回归；不通过扩大 catch、修改 driver 或改变 SQL mode 规避问题。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
