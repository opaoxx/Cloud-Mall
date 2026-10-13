# CM-18 16T166：Pay Legacy recovery zero-calendar qualification

## 1. 结论与范围

GO。T165 只处理 SQL NULL；MySQL zero/partial-zero DATETIME 在 SELECT `intent.*` 映射时仍可能 JDBC 失败，阻断 recoverDue 批次/游标，并占候选窗口。

本批只过滤年月日含 0 的 recovery datetime，不做完整 Gregorian/calendar 校验。

## 2. Candidate/claim predicates

在 `findDue`、`findDueAfter` candidate WHERE 与 `claim` outer WHERE 增加：

- `YEAR(next_retry_at) >= 1000 AND MONTH(next_retry_at) > 0 AND DAYOFMONTH(next_retry_at) > 0`；
- `YEAR(created_at) >= 1000 AND MONTH(created_at) > 0 AND DAYOFMONTH(created_at) > 0`；
- `YEAR(updated_at) >= 1000 AND MONTH(updated_at) > 0 AND DAYOFMONTH(updated_at) > 0`；
- `lease_until IS NULL OR (YEAR(lease_until)>=1000 AND MONTH(lease_until)>0 AND DAYOFMONTH(lease_until)>0)`。

候选先资格过滤再 LIMIT；claim 同样先资格过滤，禁止先写 lease/attempt 或自动修 updated。保 T158–T165 pair/key/numeric/status/time/order/SET/参数/Service/ROW_MAPPER/JDBC 配置。

## 3. 非目标与验收

不修改 `zeroDateTimeBehavior`、global sql_mode、DDL、数据、不校验 Feb31/月份天数/时序/未来时钟，不宣称完整 calendar/fencing。

- Owned SQL：仅 SESSION 允许插入 zero/partial-zero 日期，证明坏头 scan/claim 旧 driver 失败；修复后零候选/零 claim、full facts 不变；100 bad 头 + healthy 尾正常。
- NULL lease、1000/9999 边界、非零毫秒日期保持；资源 finally 清理，不启动 App/RM/TC。

## 4. 交付边界

实现仅允许修改两 candidate SQL 与 claim outer WHERE 的 datetime qualification、JavaDoc/测试；不得改 mapper mapping、driver/global config、Service 或历史数据。
