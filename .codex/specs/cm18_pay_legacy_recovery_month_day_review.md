# CM-18 16T167：Pay Legacy recovery month-day qualification

## 1. 结论与范围

GO。T166 过滤年月日为零后，非零但超出当月天数的历史 DATETIME（如 Feb29 非闰年、Feb30、Apr31、Sep31）仍可能进入 candidate/claim，JDBC 映射或归一化会阻断批次或改 lease/attempt。

本批只补日期月末日资格，不扩完整 Gregorian/时间先后/时区/fencing。

## 2. Exact SQL predicate

在 `findDue`、`findDueAfter` candidate WHERE 与 `claim` outer WHERE，对 next_retry_at、created_at、updated_at 及非 NULL lease_until 各追加：

`DAYOFMONTH(field) <= DAYOFMONTH(LAST_DAY(field))`

保 T166 `YEAR>=1000`、MONTH/DAY>0 条件先行；lease NULL 仍合法。候选先过滤再 LIMIT/afterId，claim 先过滤再写 lease/attempt。绑定、排序、SET、其它 T158–T166 条件保持。

MySQL 日期函数语义依据官方文档：[date and time functions](https://dev.mysql.com/doc/refman/8.0/en/date-and-time-functions.html)、[date and time types](https://dev.mysql.com/doc/refman/8.0/en/date-and-time-types.html)。

## 3. 非目标与验收

不改 `zeroDateTimeBehavior`、sql_mode/global mode、DDL、数据、ROW_MAPPER、时序/未来时间、1582 cutover、时区或完整 calendar validity。

- Owned SESSION 仅允许借用插入 zero/partial/bad-calendar 历史值，finally 恢复 mode；CAST CHAR 保留原 facts；坏日期不 candidate/claim，100 bad heads 不阻塞 healthy tail。
- 覆盖 2000/2024 leap、1900/2100 non-leap、1000/9999 boundary、30/31 month-end、NULL/未来 lease 与 due controls；资源 cleanup，不启动 App/RM/TC。

## 4. 交付边界

实现仅允许修改两个 candidate SQL 与 claim outer WHERE 的 month-day predicate、JavaDoc/测试；不得新增时间修复、全局配置或 Service 门。
