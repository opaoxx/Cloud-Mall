# CM-18 16T97：User fresh refund bounded DEBIT source

## 1. 结论与范围

GO。fresh refund 当前只证明 request 指定 operationKey 的 DEBIT；同 pay 的额外 DEBIT、CI pay alias 或坏状态行可被忽略后继续 credit/insert。

本批只补 fresh refund 原 DEBIT 的同 pay bounded source scope；不扩展退款集合、全账户因果、TCC 完整归属或 source fencing。

## 2. Fresh source scope

在原 disabled 404/source proof 与 `isProvenRefundOriginalDebit` 通过后、T75 linked refund aggregate 之前，复用已有 `findDebitCandidatesByPayNo(request.payNo)` LIMIT2：保 CI/全 state 候选，不锁不写。

- list null/empty/size != 1/唯一 null row → active `BALANCE_REFUND_INVALID`/409，固定“原扣款不存在或未应用”；disabled 保持原 source proof 已通过后的同一 fresh 失败分类约定；
- DAO/DataAccess 异常原样传播；
- 唯一 candidate 必须 id 非 null 且等 original.id，并通过同一原 DEBIT proof：operationKey、user/pay/order、DEBIT/APPLIED、origin NULL、正合法 amount、合法 before/after 与 delta exact/value copy；否则同上 409。

不以 state/type/metadata 过滤候选，不把重复 DEBIT 隐藏为空；候选通过后才进入 T75 `findLinkedRefundCandidates`/wallet/ledger 写链。

## 3. Replay 与保持项

existing refund replay 必须在 fresh source scope 前早返回 T73 proof，不新增该查询；disabled existing 仍先 T70 original/TCC 404。合法退款集合、T75、T72 wallet/ledger atomicity、SQL/锁/事务/API/TCC/Redis 保持。

## 4. 验收

- Unit：null/empty/multiple/nullrow、wrong id/copy、CI/other-key、APPLIED/REJECTED/UNKNOWN、DAO fault、source/disabled 优先；无 credit/insert/cache。
- Native HTTP/service：active Legacy/active TCC/disabled TCC fresh × duplicate state/CI/three-row，均 409 且四表 facts 不变；healthy real refund/replay、other-pay 与 valid candidate 保持。
- 查询计数 fresh 仅额外一次；existing replay 不查 scope；资源 finally 清理，不启动 RM/TC/App。
- 不覆盖全账户、其它退款来源、全历史或跨服务 fencing；保留 T70–T96 回归。

## 5. 交付边界

实现仅允许在 fresh refund 原 DEBIT proof 后增加现有 bounded candidate read 与纯 copy predicate、必要 JavaDoc/测试；不得修改 replay、T75 aggregate、wallet 或其它资金路径。
