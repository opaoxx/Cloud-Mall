# CM-18 16T90：User TCC state no-funds ledger scope

## 1. 结论与范围

GO。`UserTccStateQuery` TRYING/CANCELLED 分支当前只确认固定 DEBIT key 缺失；同 pay 的孤立 REFUND、未知 type/state 或 CI 候选仍可能被报告为安全无账本状态。

本批只补 state GET 的无资金账本存在性门，不改 CONFIRMED/T88、写服务、当前钱包或其它 User 证据范围。

## 2. 保持旧 proof 与新增 scope

在 TRYING/CANCELLED 分支既有 `selectDebitByPayNo(payNo)==null` 固定 DEBIT 门通过后，复用已有 `findPaymentLedgerCandidates(payNo)` 一次读取全 type/state、CI pay、LIMIT101 候选：

- null list 表示证据不可用，沿原 `USER_TCC_STATE_CONFLICT`/409 与固定消息；
- 非空任意行（null row、REFUND、未知 type/state、任何金额、任何 user 或 CI alias）表示存在资金账本事实，同样沿原 conflict/409；
- 只有非 null 且 empty 才保留 TRYING/CANCELLED 的无账本报告。

保留旧 fixed DEBIT 与 fixed ledgerKey 读取优先级；旧 DEBIT 命中仍在新增全 type scope 前失败，不用新错误替代旧错误。DAO 异常原样传播，不转换为空或冲突报告。

## 3. 调用边界

- attempt/receive 不完整或 null 的既有 UNKNOWN 分支保持，不把“没有已知 source”提升为无账本证明；
- TRYING、CANCELLED（含 freeze 与仅 attempt 空取消）使用新增 scope；CONFIRMED/T68/T88 不改、不调用该门；
- 共享 private helper 可复用旧 DEBIT read 后的全 type scope，但不复制/移动原分支读取优先级。

不改 API/VO、SQL 写、RR 事务、Seata/Fence、资金服务、attempt/debit/freeze、退款集合、当前 wallet 或其它 User reconciliation reader 的 proof 语义；其它 reader 因复用 Query 自然继承冲突，不新增 endpoint。

## 4. 验收

- Unit：TRYING/CANCELLED × freeze/attempt-only，orphan REFUND/UNKNOWN/type、null row、CI alias、null list、DAO fault；旧 DEBIT/fixed-key 优先与 attempt-null UNKNOWN 保持。
- Native RR/HTTP：真实 User owned ledger 覆盖三种未确认形态（TRYING with freeze、CANCELLED with freeze、CANCELLED attempt-only）及 CONFIRMED healthy control；任意 same-pay 非 DEBIT 行在前三种形态阻止状态报告，CONFIRMED 带 REFUND 仍合法。other-pay REFUND、invalid wallet/status 优先保持；只修改 owned ledger，source checksum 与 no-write tracking 不变。
- 如新增全 type SELECT，准确更新查询计数；不访问 funds worker/Pay/Order，不启动 TC/RM，不宣称全历史或完整账户因果。
- 保留 T67–T89、User state/diagnostic 及 TCC 回归。

## 5. 交付边界

实现仅允许扩展 `UserTccStateQuery` TRYING/CANCELLED 的既有 no-DEBIT 分支、复用已有 Mapper 查询、必要 JavaDoc/测试；不得改 CONFIRMED 或其它资金状态。
