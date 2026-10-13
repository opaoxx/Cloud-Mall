# CM-18 16T122：User Legacy ledger multiple-result classification

## 1. 结论与范围

GO。Legacy `debitFromLedger` T95 historical pay scope 通过后，fixed operationKey point read 仍使用单结果 Mapper；缺历史 UNIQUE 时同 key 多行可 500，影响 fresh/replay 两入口。

本批只分类该 Legacy fixed-key point read 的精确多结果，不改 T95、owner/freeze/receive、wallet、写链或其它 GET/refund point reads。

## 2. Narrow fixed-key reader

抽取 `readLegacyDebitLedgerCandidate(operationKey)`，保持一次原 `selectByOperationKey` 调用：

- direct `TooManyResultsException`，或 `MyBatisSystemException` direct cause 为 TooMany → `IDEMPOTENCY_CONFLICT/409` 固定“扣款操作键存在重复账本记录”；
- null/unique row 原样继续 T95 后置 state/snapshot/freeze/receive/cache/写语义；
- 其它 wrapper cause、deep/nested TooMany、Runtime、DAO/DataAccess/SQL 原对象传播；不按 message 匹配、不遍历 cause、不 COUNT/遍历历史。

T95 attempt/pay historical、caller/user/owner/freeze/receive 优先级与查询次数保持；不修历史 unique，不保证读后 source 稳定窗口。

## 3. 验收

- Unit：direct/wrapped direct-cause/other wrapper/deep/message-only/DAO，fresh/APPLIED replay/REJECTED replay 与 old priority；非目标异常原对象。
- Native：两入口 × fresh/APPLIED/REJECTED × exact/CI same-key other-pay duplicate，owned 临时移除 ledger operation UNIQUE，fixed point 409、五表/no-write/cache facts 保持；finally 删除自有行并恢复 index。
- 保留 T95/T104/T117/T121 回归，不启动 RM/TC/App。

## 4. 交付边界

实现仅允许包裹 `debitFromLedger` fixed operationKey point read、必要 JavaDoc/测试；不得修改 Mapper SQL、全局异常处理器或其它资金路径。
