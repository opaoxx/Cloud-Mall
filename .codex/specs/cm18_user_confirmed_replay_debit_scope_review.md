# CM-18 16T88：User CONFIRMED replay DEBIT scope

## 1. 结论与范围

GO。`BalanceTccDebitServiceImpl.requireLedger` 当前只证明固定 ledgerKey 的 APPLIED DEBIT；同 payNo 的其它 DEBIT 行仍可能存在，导致 CONFIRMED Try/Confirm replay 把双扣、错误状态或 CI type alias 当作唯一资金事实。

本批只收紧已 CONFIRMED replay 的同 payNo DEBIT scope，不改 fresh Try、首次 Confirm、Cancel、写入或其它历史因果。

## 2. Scope 查询

在固定 ledger proof、金额与 snapshot/origin 门通过后，复用 `BalanceLedgerMapper` 新增纯 SELECT：

`SELECT * FROM balance_ledger WHERE pay_no=#{payNo} AND operation_type='DEBIT' ORDER BY id LIMIT 2`

保留 CI 候选以观察别名/坏行，不按 state 过滤，不写入、不锁定、不新增 DDL/API。列表必须恰好一行；null/空、多于一行或 DAO 异常分别沿现有 `BALANCE_TCC_UNKNOWN`/409 或 DAO 原样异常传播。

## 3. 唯一 scope proof

唯一候选必须与原固定 ledger/attempt/request 精确匹配：

- id 非 null 且等于原固定 ledger id；
- userId、payNo、orderNo、operationKey 精确相等；
- operationType 精确 DEBIT，state 精确 APPLIED，originalOperationKey 为 null；
- amount 与 request/original 固定金额数值相等且非 null；
- balanceBefore/balanceAfter 与原固定 ledger 逐值相等，且原既有合法 snapshot/delta 门仍成立。

候选 amount/snapshot 不额外扩大资金域；固定 ledger 已证明的合法金额域和复制值继续作为基础。scope 缺失、重复、CI alias、错误 state/type、错误 identity 或坏快照统一 `BALANCE_TCC_UNKNOWN`/409 固定原 message。

## 4. 调用与保持边界

仅在 `tryDebit`/`confirmDebit` 已存在 `CONFIRMED` 分支调用一次；fixed ledger proof 失败时不查询 scope。active/user lock、TCC enabled、原 operation/Fence/attempt 校验、当前 wallet 不参与 replay，REFUND 多行与其他状态不改变。

不改 fresh Try/首次 Confirm/Cancel 的 `requireNoLedger`，不改 SQL write、状态、Seata/Fence、TCC callback、DTO/API 或历史修复；不证明全历史、账户因果、当前钱包或退款集合。

## 5. 验收

- Unit：missing/single bad/multiple/CI type alias/额外 APPLIED、REJECTED、UNKNOWN DEBIT、错 tuple、坏 snapshot；仅 CONFIRMED replay 新 scope 门触发，失败 409 且无写。
- Owned MySQL：实际 Try+Confirm 后插入/变更同 pay 额外 DEBIT，各状态与 alias 均使两种 replay 返回 UNKNOWN/409，wallet/four-table facts 不变；健康无 extra DEBIT、合法部分 REFUND、current wallet 变化及 disabled confirm 保持。
- 补齐健康 fixtures 的 fixed ledger id 与 scope list，不削弱 gate；DAO fault 原样传播，资源与 source checksum 清理。
- 保留 T69–T87、User state/HTTP、TCC 与资金回归；不启动 RM/TC/App。

## 6. 交付边界

实现仅允许新增 BalanceLedgerMapper 的 scope SELECT、`requireLedger` CONFIRMED replay 纯字段门、必要 JavaDoc/测试；不扩展其它资金状态或 API。
