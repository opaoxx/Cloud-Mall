# CM-18 16T165：Pay Legacy mapper time NULL

## 1. 结论与范围

GO。ROW_MAPPER 的 next_retry_at/created_at/updated_at 直接调用 `getTimestamp(...).toLocalDateTime()`；SQL NULL 会 NPE，使整次 lookup/list 映射失败。PO LocalDateTime 可表达 NULL，lease_until 已保留同语义。

本批只修三项时间字段的 NULL-safe 映射，不新增业务授权或历史修复。

## 2. Exact mapping

按原 Timestamp 读取到语义化局部变量，再分别赋值：

`timestamp == null ? null : timestamp.toLocalDateTime()`

仅适用于 next_retry_at、created_at、updated_at；nonnull 继续原 `Timestamp.toLocalDateTime()` 转换，不改 JDBC/session timezone 语义，不使用 typed LocalDateTime、不填当前时间/默认值。lease_until 原 NULL-safe 表达保持。

## 3. 保持项与边界

不改 3 boxed mapping、SQL/candidate、Service/API/DDL、due predicate、时区、attempt/lease/资金资格。next_retry_at NULL 在 due SQL 中仍不匹配；created/updated NULL 仅可被读取，不代表合法业务资金事实或恢复资格，不新增 service reject/授权门。

## 4. 验收

- Actual native test-only projection：public findByPayNo/ForUpdate 单字段/all NULL 不 NPE，保持 FOR UPDATE/参数，其他字段完整；
- 正常毫秒时间、lease NULL/non-null、非 NULL 数值与 stored facts 保持；缺行 null 仍保持；资源 finally 清理，不 ALTER schema。

## 5. 交付边界

实现仅允许三项 ROW_MAPPER 时间赋值 NULL-safe 化及必要 JavaDoc/测试；不得新增业务时间域校验、migration 或其它源码路径。
