# CM-18 16T128：User receive store multiple-result classification

## 1. 结论与范围

GO。`UserTccReceiveStore.claim`/`bindBranch` 在 REQUIRES_NEW 内 insert/update 后以 `lockByPayNo` 单结果读取；历史 pay 唯一约束缺失时多 receipt 会抛 MyBatis 异常，无法沿原 payload conflict 回滚。

本批只分类两处 lock read 多结果，不改 Mapper SQL、HTTP/RM/GET、分支派发规则或数据库历史。

## 2. Shared locked reader

抽取本类 private `readLockedReceipt(payNo)`，保留一次原 `lockByPayNo` FOR UPDATE：

- direct `TooManyResultsException`，或 MyBatisSystemException direct cause TooMany → 原 `IDEMPOTENCY_CONFLICT/409` “TCC 接收载荷不一致”；
- null/unique 原值；
- 其它 wrapper/deep TooMany、Runtime、DAO/DataAccess/SQL 原对象传播，不遍历 cause、不按 message 匹配。

## 3. Transaction/ordering boundaries

claim 继续 insert/IGNORE → lock read → payload proof；多结果必须沿 BizException 回滚本次 REQUIRES_NEW insert。bindBranch 继续参数 guard → update → lock read → proof；多结果必须回滚 metadata update。branchId null 仍早 noop，非法 branch 仍不 Mapper；合法 replay/派发权、insert/update 影响行语义保持。

不改 transaction propagation、SQL/HTTP/RM/GET、API/DTO、历史 receive；不自动修复/合并 rows。

## 4. 验收

- Unit：claim/bind direct/wrapped/direct-cause/other/deep faults、null/unique、branch null/bad priority；每处一次 lock read，合法 replay/派发结果保持。
- Native：fresh insert1、replay IGNORE0、bind update1 × exact/CI duplicate，owned 临时移除 pay UNIQUE；真实 REQUIRES_NEW 前后六表事实证明 rollback/commit，finally 删除自有两行并恢复 index。
- 不启动 RM/TC/App，不宣称全局 receive/fencing；资源 checksum/cleanup 保持。

## 5. 交付边界

实现仅允许增加 ReceiveStore locked reader wrapper 并替换两处调用、必要 JavaDoc/测试；不得修改 Mapper SQL、异常全局处理或业务派发路径。
