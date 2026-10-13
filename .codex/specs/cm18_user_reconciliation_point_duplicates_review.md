# CM-18 16T131：User reconciliation point duplicates

## 1. 结论与范围

GO。`UserTccReconciliationReadService.inspect` 的 receive、account projection、fixed refund point reads 依赖单结果；遗留 duplicate 会被包装成 503 或裸 MyBatis 500，不能伪装成暂时不可用。

本批只分类这三个 point read 的精确多结果，不改 UserTccStateQuery、global handler、API/flag、DDL 或写路径。

## 2. Narrow helper

在同一 read-only RR 事务内抽取本类 generic Supplier reader，保持原 SQL、顺序、次数与连接：

- direct `TooManyResultsException`，或 MyBatisSystemException direct cause TooMany → 既有 `USER_TCC_RECONCILIATION_CONFLICT/409` 固定“本地User资金证据不唯一”；
- null/unique 原行为（receive null 404，account/refund 缺失进入原 missing/classification）；
- 其它 wrapper/deep TooMany、DataAccess/SQL/Runtime 原对象传播，不遍历 cause、不按 message 匹配。

多结果必须在后续 state/account/aggregate/refund proof 前分类，不能伪装 503 或继续生成报告；不读取其它 pay，不写资源。

## 3. 保持项与验收

不改 RR、权限/脱敏/错误处理器、六来源/字段/分类、HTTP no-store、API/flag、DDL/TC/RM。

- Unit：三 point direct/wrapped/deep/other faults、null/unique、原读取预算与分类；
- Owned MySQL：临时移除 receive pay UNIQUE、account user PK/唯一约束、refund operation UNIQUE（保留必要普通索引），插 exact/CI duplicate；HTTP 409、六表/read-only facts/source fingerprint 不变，finally 恢复索引并清理自有行。
- 不启动完整服务/TC/RM，不宣称全局数据修复；资源 finally 清理。

## 4. 交付边界

实现仅允许增加 ReconciliationReadService point-reader helper 并替换三处 SELECT、必要 JavaDoc/测试；不得修改 StateQuery、global exception、Mapper SQL 或业务写路径。
