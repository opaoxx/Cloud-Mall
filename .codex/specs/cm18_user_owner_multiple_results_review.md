# CM-18 16T123：User owner multiple-result classification

## 1. 结论与范围

GO。四类 owner point read（Legacy debit、APPLIED GET/active refund、REJECTED GET、disabled refund TCC）在遗留 pay 唯一约束缺失时多归属会 500，而各 caller 已有不同业务错误契约。

本批只统一精确多结果分类，不改 Mapper SQL、锁、COUNT、查询预算、历史数据或后置资金门。

## 2. Shared narrow reader

抽取 `readDebitOwnershipCandidate(payNo, duplicateConflictSupplier)`，每个 caller 保持一次原 `selectByPayNo`：

- direct `TooManyResultsException`，或 `MyBatisSystemException` direct cause 为 TooMany → 调用 caller factory 抛业务异常；
- null/unique 原样返回；
- 其它 wrapper/deep TooMany、Runtime、DAO/DataAccess/SQL 原对象传播，不遍历 cause、不按 message 匹配。

Caller factories：

- Legacy debit → `IDEMPOTENCY_CONFLICT/409` “支付号存在重复扣款归属记录”；
- APPLIED source/active refund → `BALANCE_REFUND_INVALID/409` “原扣款不存在或未应用”（GET 外层仍映射 COMMON_CONFLICT）；
- REJECTED GET → COMMON_CONFLICT/409 “余额账本事实待核对”；
- disabled refund → COMMON_NOT_FOUND/404 “用户不存在”。

## 3. 保持项与优先级

caller 的 source/caller/User/T95/row/owner 优先级、后置 freeze/receive/COUNT/wallet/write 保持；多结果分类发生在该 point read 处，不能 mask 前置错误。BalanceTccDebitService、RM、API/DTO、Redis/状态机不改。

## 4. 验收

- Unit：direct/wrapped direct-cause/other wrapper/deep/message-only、null/unique 与四 caller 映射及前置 gate。
- Native：13 路由 × exact/CI duplicate，owned 临时移除 attempt pay 唯一索引（其它 unique 保持），各 caller 返回对应业务错误，五表/cache no-write；finally 删除自有行并恢复 index。
- 保留 T120–T122、TCC/RM/API/资源 checksum 回归，不启动完整 App/TC。

## 5. 交付边界

实现仅允许增加 shared owner point-read wrapper、四 caller factory 接入及必要 JavaDoc/测试；不得修改 Mapper SQL、全局异常处理器或后置资金逻辑。
