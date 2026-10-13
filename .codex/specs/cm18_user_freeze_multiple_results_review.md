# CM-18 16T124：User freeze multiple-result classification

## 1. 结论与范围

GO。五处 `balance_tcc_debit.selectByPayNo` 在历史 pay UNIQUE 缺失时可能多结果 500，影响四个 freeze existence caller 与 confirmed TCC proof。

本批只做精确多结果分类，不改 Mapper SQL、锁、COUNT、预算、TCC service/RM、API 或历史数据。

## 2. Existence callers

fresh Legacy、Legacy replay、REJECTED GET、APPLIED Legacy/null owner 四处复用 `hasTccFreezeConflict(payNo)`：

- non-null row → true，null → false；
- direct `TooManyResultsException` 或 MyBatisSystemException direct cause TooMany → true；
- 其它 wrapper/deep/runtime/DAO/SQL 原对象传播，不深遍历、不按 message 匹配。

caller 保持原业务错误：fresh/replay 原各 conflict/unknown、GET COMMON_CONFLICT、disabled/owner proof 原映射。多结果被视为“存在冻结事实”，不伪造 PO。

## 3. Confirmable TCC caller

`confirmedTccRefund(original, attempt)` 的 freeze point 改用 `readConfirmableTccFreeze(payNo)`：

- direct multi/direct-cause TooMany → null，使既有 proof false；
- null/唯一 row 原值；
- 其它异常原对象传播。

既有 caller 映射保持：active source 409、GET COMMON_CONFLICT、disabled 404。TCC identity/amount/snapshot/状态规则不改。

## 4. 保持项与验收

不改 Mapper SQL/锁/COUNT、BalanceTccDebitService、RM、API、全局异常、source window 或历史修复；不创建伪 PO。

- Unit：direct/wrapped/direct-cause/other/deep faults、null/unique、owner/source priority；
- Native：17 routes × exact/CI duplicate，owned 临时移除 freeze pay UNIQUE（其它 unique 保持），五表/cache no-write，finally 删除自有 rows 并恢复 index；
- 健康 TCC/Legacy/disabled/replay 与 T74/T98/T99/T100/T113/T123 回归，不启动 RM/TC/App。

## 5. 交付边界

实现仅允许增加 existence/confirmable freeze wrapper 并替换五处调用、必要 JavaDoc/测试；不得修改 Mapper、业务状态机或全局异常处理。
