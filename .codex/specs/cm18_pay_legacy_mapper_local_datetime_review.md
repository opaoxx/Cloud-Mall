# CM-18 16T168：Pay Legacy mapper LocalDateTime read

## 1. 结论与范围

GO。T165 的 Timestamp→LocalDateTime conversion 仍受 Java/Gregorian cutover 影响，合法 MySQL DATETIME(3) 1582-10-05/10/14 等值可能读异常，阻断 lookup/ForUpdate/candidate/recoverDue。

本批只修 ROW_MAPPER 合法日期组件读取，不筛历史、不改 SQL/claim/资格/Service。

## 2. Exact typed mapping

将四字段直接改为 JDBC typed read：

- `next_retry_at = resultSet.getObject("next_retry_at", LocalDateTime.class)`；
- `lease_until = resultSet.getObject("lease_until", LocalDateTime.class)`；
- `created_at = resultSet.getObject("created_at", LocalDateTime.class)`；
- `updated_at = resultSet.getObject("updated_at", LocalDateTime.class)`。

保真实 NULL、合法日期组件、毫秒精度；移除仅无用 `Timestamp` import。不得筛掉合法历史日期，不改 JDBC driver/global timezone/SQL/参数绑定/SET/DDL/API。

## 3. 验收与边界

- Owned native red/fix：1582-10-05/10/14 三样本 × 四字段，public find/ForUpdate/candidate 实际 typed 读取；100 合法历史头 + healthy tail recover；1000、1582-10-04/05/10/14/15、modern、9999、NULL projection 保原值/资格语义。
- 独立 UTC test fork 仅改变测试 JVM timezone；受控 Feign/原 DB read-only/owned clone finally 清理，不改 OS/生产 JVM。
- 已复现旧 `Timestamp` 基线测试在 UTC 下按时区换算得到 `0999-12-31T16:00`，而 typed/raw stored component 为 `1000-01-01T00:00`；该旧基线不再作为本批合同。测试应改名为 `nonNullTimeMappingPreservesStoredDateComponents`，直接按 raw stored 日期组件断言 1000/现代 789ms/9999 与 nullable lease，不保留旧 Timestamp 查询换算期望。默认 Asia focused 与独立 UTC 新增/更新方法均须分别记录，失败日志/XML、owned schema cleanup、原 fingerprint/pool evidence 保留。
- 不承诺所有 calendar/driver 语义、时间先后、1582 前规则、主项 fencing；T165 NULL/业务域边界按本批 typed read 重新验证。

## 4. 交付边界

实现仅允许替换四个 ROW_MAPPER 时间字段及 import/JavaDoc、测试 harness；不得修改 SQL、claim、资格、Service、DDL、driver 配置或历史数据。
