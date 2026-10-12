# CM-18 16T89：User no-funds payment ledger scope

## 1. 结论与范围

GO。`requireNoLedger` 当前只查同 pay 的 DEBIT 与固定 ledgerKey；同 pay 的孤立 REFUND、未知 type/state 或 CI alias 账本行可能被忽略，导致 fresh Try、首次 Confirm、Cancel 或空取消重放错误获得“无资金事实”证明。

本批只补本地 no-ledger 存在性门，不总结金额、不证明全历史 source、不改 CONFIRMED T88、退款集合或当前 wallet。

## 2. 保持旧优先级并补全存在性

`requireNoLedger(request)` 保留两个既有读取及其优先级：

1. `selectDebitByPayNo(request.payNo)`；
2. `selectByOperationKey(request.ledgerKey)`。

两者命中仍沿原冲突码/message。两旧读取通过后，复用已有 `findPaymentLedgerCandidates(request.payNo)` 一次只读 LIMIT101 候选（全 type/state、CI pay、按 id），不新增 Mapper/SQL/锁。

- 返回 null 表示证据不可用，沿原 `BALANCE_TCC_UNKNOWN`/409，不把 null 当空列表；
- 返回非空任意行（不论 id/type/state/money/user）表示该 pay 已有资金账本事实，沿 `IDEMPOTENCY_CONFLICT`/409 固定“支付号已有资金账本事实”；
- 只有非 null 且 empty 才证明当前查询范围内无账本。

不对候选金额、operationKey、状态或 user 做进一步归因；旧 DEBIT/固定 key 门仍负责其更具体的优先错误。

## 3. 调用范围与保持项

所有既有 `requireNoLedger` 调用复用该 guard：fresh Try、首次 Confirm、TRYING/CANCELLED replay、Cancel 及 attempt-only 空取消；CONFIRMED replay 继续走 T88 `requireLedger`，不调用本门。enabled/status/user lock、空取消与无冻结分支的原优先级保持。

不改 SQL 写、锁、事务、状态、DTO/API、TCC/Fence、Redis、attempt/freeze、User GET 其它来源；不修历史账本、不自动清理、不证明全局唯一或完整资金因果。

## 4. 验收

- Unit：null list、DAO exception、任意 null/nonempty row、empty list；旧 DEBIT/固定 key 命中仍保持原错误优先，非 DEBIT 行固定 IDEMPOTENCY_CONFLICT。
- Owned MySQL：真实 same-pay REFUND/UNKNOWN/其它 type/state、CI pay alias、other-user operation 均阻止适用入口且不产生资金/attempt 写；无账本健康、TRYING/CANCELLED、空取消与 invalid wallet/status 优先保持。
- 覆盖 Try/Confirm/Cancel 适用入口及 attempt-only 空取消；确认四表 facts/checksum、资源清理和真实 User transaction。Redis 可 mock，不宣称 TC/Fence。
- 不覆盖 CONFIRMED T88、退款集合、User operation GET 全历史或账户因果；保留 T70–T88 回归。

## 5. 交付边界

实现仅允许扩展 `requireNoLedger` 的已有纯读取调用与错误分支、必要 JavaDoc/测试；不得新增 Mapper SQL、API、表或其它资金路径。
