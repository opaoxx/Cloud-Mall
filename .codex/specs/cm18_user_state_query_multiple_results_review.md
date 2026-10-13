# CM-18 16T126：User TCC state query multiple-result classification

## 1. 结论与范围

GO。UserTccStateQuery 的 receive operationKey、attempt payNo、freeze payNo、ledger operationKey、Fence branch point reads 依赖单结果；历史唯一约束缺失时可能 500，不能稳定返回 TCC conflict。

本批只统一该类五类 point read 的精确多结果分类，不改 SQL/RR/HTTP/写/RM/global handler/历史数据。

## 2. Narrow state reader

抽取本类 private `readStateCandidate(Supplier<Candidate>)`，每个 point 保留原 SQL、一次读取、锁/非锁性质与参数：

- direct `TooManyResultsException`，或 MyBatisSystemException direct cause TooMany → 原 `USER_TCC_STATE_CONFLICT/409` “TCC 持久事实不一致”；
- null/unique 原样；
- 其它 wrapper/deep TooMany、Runtime、DAO/DataAccess/SQL 原对象传播；不遍历 cause、不按 message 匹配。

初始 receive null 仍 COMMON_NOT_FOUND/404；receive duplicate 不伪造 missing/UNKNOWN。attempt/debit/ledger/Fence duplicate 统一 conflict，不猜成功状态。

## 3. 保持项与验收

不改 read-only RR、权限/metadata/状态顺序、T90/T92/T93 scope、API/VO、Fence/Seata/全局异常；不修历史唯一键、不宣称完整 fencing。

- Unit：五 point direct/wrapped/direct-cause/其它 fault、receive 404、旧 priorities、healthy modes；
- Native：五 source exact/CI duplicate，owned 临时移除相应 UNIQUE；Fence 仅 owned prepare/drop primary/shadow/delete/recover，六表 no-write、RR same connection、finally 清理；
- TCC/HTTP/资源回归保持，不启动 RM/TC/App。

## 4. 交付边界

实现仅允许增加 StateQuery narrow reader 并替换五类 point reads、必要 JavaDoc/测试；不得修改 Mapper SQL、全局异常或业务写路径。
