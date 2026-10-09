# CM-20261009-17DH：订单 Outbox 失败退避时间可表示性评审

## 1. 结论与范围

结论：**GO，仅在 failure writer 前保护 `retry-delay-seconds` 计算结果的 Java/MySQL 时间可表示性。**

17DG 保留所有正数配置且不设上限；当前 failure catch 直接执行 `now(Asia/Shanghai).plusSeconds(retryDelaySeconds)`，极大正数可能先抛 `ArithmeticException/DateTimeException`，或得到超过 MySQL `DATETIME` 年份范围的值后才进入 SQL，导致整批异常或错误写入。本批只增加 private retry-time helper，在既有一次 failure UPDATE 前完成可表示性校验。

## 2. Retry time helper 契约

failure catch 继续只采样一次 `LocalDateTime.now(BUSINESS_ZONE)`，并以原 `retryDelaySeconds` 调用 private helper：

- 使用 `now.plusSeconds(retryDelaySeconds)`，保留当前时间的原始纳秒；不 floor、clip、替换为其他时钟或补造时间。
- `ArithmeticException`、`DateTimeException` 或等价时间计算异常统一转换为 `InvalidDataAccessApiUsageException`（`DataAccessException`）。
- 计算成功但结果年份不在 `1000..9999` 时，同样转换为 `InvalidDataAccessApiUsageException`；不尝试让非法年份进入 MySQL `DATETIME(3)`，不调整到边界年份。
- 新异常以原始发送/解析异常作为 cause，并将 retry-time 计算异常作为 suppressed 证据；异常消息包含 eventId 和 retry delay，便于区分原发送失败与退避计算失败。

该 DataAccessException 在原单候选 CS 边界隔离，告警并继续后续健康候选；计算失败前不得调用 `recordOutboxFailure`，不得读取 proof、重发消息、修改 metadata、补写 retry 时间或修复原行。计算后的原 failure writer 前仍保留 CU 当前中断检查；明确中断沿原渠道恢复信号并停止后续动作，`Error` 不捕获。writer SQL 异常、返回值分类及 17DF 日志语义不变。

## 3. 兼容与非目标

- 17DG 的正数配置读取、默认值和“无任意 positive cap”保持；可表示在 `1000..9999` 的合法大正数继续原退避流程。
- 不扩大 batch/confirm 配置校验，不改变 Mapper limit 归一，不增加 SQL、Schema、API、消息、路由、publication proof 或 Rabbit 拓扑。
- 不声明所有正值都不会发生算术/性能问题，不扩展 DATETIME(3) 的舍入/截断边界，不自动纠正配置。
- 项目冻结的数据库时间采用 Asia/Shanghai 语义 `DATETIME(3)`；本批仅落实其可存储年份 `1000..9999` 的应用前置边界。

## 4. 验收矩阵

- 真实 MySQL 隔离库 + Rabbit 发送替身：用 `Long.MAX_VALUE` 触发原生时间计算溢出，用使结果跳至年份 `10000` 的正 delay 验证 failure writer 未调用；首行保持完整原事实，后续健康行正常 `PUBLISHED`。不通过 SQL 伪造或补写非法时间。
- unit：覆盖原发送/解析异常 cause、retry-time 计算异常 suppressed、无 writer 调用、无 proof 查询、后健康候选继续，以及中断/Error边界。
- 配置测试继续以实际 scan/confirm/retry 行为验证合法大正数，不镜像 private 字段；保留原 33 条 SQL IT、17DE 两条真实 Rabbit case 和既有 dispatcher 回归。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
