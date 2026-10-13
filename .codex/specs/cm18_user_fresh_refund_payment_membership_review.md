# CM-18 16T102：User fresh refund payment membership

## 1. 结论与范围

GO。T75 只按 `original_operation_key` 汇总，可能漏掉同 pay 的 orphan REFUND、wrong-origin REFUND 或未知 type/state，导致 fresh refund 超额入账。

本批只把 fresh refund 的 linked 集合与同 pay 全账本集合交叉证明；不扩其它 pay、全账户、receive/Fence、source fencing 或完整主项闭环。

## 2. 第二集合读取

保留 T75 linked candidate query 与逐行 kernel。新增纯 SELECT `findRefundPaymentLedgerCandidates(payNo)`：全 type/state、CI pay、ORDER BY id、LIMIT102；101 行代表合法 DEBIT+100 refunds 上限，102 为 sentinel。不得改变既有 `findPaymentLedgerCandidates` LIMIT101 或 linked SQL。

- null 或 size > 101 → 原 `BALANCE_REFUND_INVALID`/409 “退款累计证据不完整或不一致”；
- DAO/DataAccess 异常原样传播；
- 列表中必须有且仅有一个与已证明 source 完全 copy 的 DEBIT（id/tuple/amount/snapshot），其它每行必须通过 T75 REFUND/APPLIED/original/user/pay/order/amount/snapshot kernel；unknown、null、wrong origin/type/state 均拒绝。

## 3. 双集合一致性

分别证明 linked 集合与 payment 集合中的 refund 子集：operationKey 集合、amount、balanceBefore、balanceAfter membership 与累计总额必须完全一致；任一集合缺行、多行、额外/错行或 sum 不一致沿原 `BALANCE_REFUND_INVALID`/409。只有两集合均完整且总额一致后，才进行 request refund amount 的超额判断及 T72 wallet/ledger 写链。

原 `provenRefundedAmount(original)` 及 static 逐行 kernel 可扩为候选 overload；fresh 只增加一次 payment pure query，existing replay、disabled/T98/T99/T100/T101 优先和查询边界保持。

## 4. 保持项与验收

不改 SQL 写、锁、事务、API、TCC、Redis、状态机或原 linked query；不按金额/状态过滤隐藏坏行。

- Unit：missing/DAO/membership/copy/priority/no-credit；DEBIT+0/1/100 refunds、101/102 sentinel、orphan/wronglink/unknown/CI/other-user。
- Native：Legacy/TCC/disabled 合法 source 与 same-pay orphan/wrong-origin/unknown/alias 均 409 且 facts 不变；健康分笔退款、100 refund 边界保持。
- 两集合查询在同一事务边界内，资源 finally 清理；不宣称全历史/全账户因果。

## 5. 交付边界

实现仅允许新增 payment membership pure SELECT、双集合 kernel/比较及必要 JavaDoc/测试；不得扩展其它退款来源或资金路径。
