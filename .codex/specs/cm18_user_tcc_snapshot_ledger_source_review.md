# CM-18 16T20：User TCC 原冻结快照账本来源评审

## 1. 结论与范围

结论：**GO，限定补 User TCC 写服务对 debit/ledger balance snapshot 的来源证明；不改变 16T14 Query、16T19 wallet guard、写 SQL、锁序、状态机、RM/Fence、API、DDL 或 flags。**

当前 `requireConsistent` 未核 debit `balanceBefore/balanceAfterTry`，Confirm 会把坏快照复制到 ledger 并推进 CONFIRMED；confirmed replay 的 `requireLedger` 也未核 ledger snapshot pair 或 operationKey 精确归属。

## 2. 最小写路径门

- `requireConsistent` 在 debit 非 null/amount 门后，要求 debit before/afterTry 使用既有 `validAccountMoney`（非负、<=MAX、无损 scale）并满足 `before-afterTry=debit.amount`；失败原 `unknown()`/`BALANCE_TCC_UNKNOWN`/409。
- `requireLedger` 私有签名增加 debit 参数，Confirm/replay 两个调用者都传入原 debit；除现有 type/state/user/pay/order/amount 外，要求 ledger before/after 通过同一 snapshot/delta 门、分别数值等于 debit before/afterTry，并 `Objects.equals(ledger.operationKey, attempt.ledgerKey)`；失败原 unknown/409。
- 只阻塞坏原冻结/账本来源；不重新核当前 wallet、不比较账户余额、不修历史、不自动资金动作。

## 3. 不变边界

- Try/Confirm/Cancel 正常写步骤、NoLedger、T19 wallet guard、空取消、replay、零余额和无损尾零保持。
- 不新增 SELECT/锁、Mapper SQL、DDL、状态/操作键、RM/Fence、API/flags；不改变 amount/request 的既有规则。
- 空取消无 debit 保持原 metadata 语义；坏 debit/ledger 即便 replay 也不得报告成功。

## 4. 验收矩阵

- Unit：bad original frozen NULL/negative/scale/capacity/delta、ledger NULL/negative/scale/capacity/delta、ledger pair 不等 debit、ledger operationKey alias 均 409；合法 pair、zero-after、旧 T19 wallet guard 和正常状态保持。
- Owned MySQL 资金事务：invalid frozen/ledger 首次 Confirm 错误回滚，四表、余额、冻结、ledger、时间完整不变；source checksum/owned cleanup 保持。
- 保留原 16T13/16T14/16T19 查询与写回归，不启动 RM/TC/App/固定端口，不改变生产 schema。

## 5. 交付边界

本评审只冻结写路径原冻结快照→ledger 来源证明，不扩展账户因果、退款、历史 repair 或 CM18 M1–M4。
