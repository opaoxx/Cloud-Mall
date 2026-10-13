# CM-18 16T125：User local TCC multiple-result classification

## 1. 结论与范围

GO。BalanceTccDebitServiceImpl 的 attempt、freeze 与 fixed ledger point reads 在历史唯一约束缺失时可能 500，影响 Try/Confirm/Cancel 与 confirmed replay。

本批只统一 7 处 local TCC point read 的精确多结果分类，不改 SQL、锁、COUNT、状态机、RM、HTTP、receive、GET 或历史数据。

## 2. Shared reader

抽取 generic `readTccCandidate(Supplier<Candidate>, Supplier<BizException> duplicateFailure)`，每个 caller 保持原 Mapper SQL、锁性质、读取次数与参数：

- direct `TooManyResultsException`，或 `MyBatisSystemException` direct cause TooMany → caller factory；
- null/unique 原样；
- 其它 wrapper/deep TooMany、Runtime、DAO/DataAccess/SQL 原对象传播，不遍历 cause、不按 message 匹配。

Caller factories：

- attempt（非锁/ForUpdate）与 freeze point → 原 `BALANCE_TCC_UNKNOWN/409` “资金事实待核对”；
- requireNoLedger fixed operationKey → 原 conflict “支付或账本键已有扣款事实”；
- requireLedger fixed ledgerKey → 原 unknown “资金事实待核对”。

## 3. 保持项与验收

旧 user lock、payload/state 优先、requireNoLedger 全 type 门、requireLedger scope、资金写/事务/Seata/TCC callback/API 保持；多结果分类发生在 point read，不伪造 candidate 或放宽状态。

- Unit：locked/nonlocked attempt、three freeze、fixed key/confirmed ledger direct/wrapped/other faults/null/unique/priority，均一读。
- Native：owner/pay duplicate、freeze/pay duplicate、ledger/key other-pay duplicate，Try/Confirm/Cancel/confirmed replay exact/CI，四表 no-write；owned UNIQUE 临时移除并 finally 恢复/清理。
- 保留 T88/T91/T124 与 User/RM/HTTP 回归，不启动额外 App/TC。

## 4. 交付边界

实现仅允许增加 local TCC point reader wrapper 并接入 7 处、必要 JavaDoc/测试；不得修改 Mapper SQL、全局异常或业务资金规则。
