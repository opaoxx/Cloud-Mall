# CM-18 16T103：User operation payment membership

## 1. 结论与范围

GO。T78 `balanceOperation` 目前只证明 original_key linked 集合，遗漏同 pay 的 orphan/wrong-origin/unknown refund 或其它账本行；T102 的双集合 proof 仅覆盖 fresh refund。

本批只把既有 operation GET 扩为同一 RR 快照下的 payment membership proof，不改写入、owner、账户、receive/Fence 或全历史因果。

## 2. GET proof 与读取

保持 caller/primary 403/404、主行 metadata、REFUND 原 DEBIT source proof 与 linked kernel 优先。随后在同一 read-only RR 内读取一次 `findRefundPaymentLedgerCandidates(payNo)` LIMIT102；不重复读取 linked list。

- payment list null/size > 101/坏行/错误 source → 映射现有 `COMMON_CONFLICT/409`“余额账本事实待核对”；
- DAO/DataAccess 异常原样传播；
- payment list 必须包含唯一复制 original 的 DEBIT。source matcher 的 state 精确等 original.state；DEBIT/APPLIED、REFUND 主行仍按既有 GET source 规则，REFUND source 必须 APPLIED；不把 state 写死为 APPLIED 以误拒健康 REJECTED 主行。

其余 payment 行必须复用 T102 REFUND/APPLIED kernel。linked 与 payment refund 子集的 operationKey、金额、before/after、membership、sum 必须完全一致；错误沿上述 409。

## 3. marker 与保持项

DEBIT marker selector 仍在集合 proof 后按原 SQL/排序返回，并必须属于已证集合；REFUND marker 仍 null。主行/selector/partial/REJECTED 语义保持。新 payment SELECT 只增加一次，T102 fresh、T78 原 linked helper 与其它写路径不改。

不改 API/VO、SQL 写、锁、TCC/owner、current wallet、退款集合之外的全账户/全历史因果；不宣称 read→Pay finish 或 source fencing。

## 4. 验收

- Unit/HTTP：DEBIT/REFUND/REJECTED healthy、100 边界、orphan/wrong-origin/unknown/CI/DAO/priority；坏 membership 409、no-write、marker 语义保持。
- Owned RR/真实 Mapper：same-pay 全行 membership 与 linked 集合一致时通过；任意 mismatch/extra row facts 不变；同连接/read-only 及查询计数增加一次如实记录。
- 缺表/数据库错误保持 503/异常链，资源 finally 清理；fresh T102、T78/T90–T102 回归。

## 5. 交付边界

实现仅允许扩展 `balanceOperation` 的预读 payment list、复用 T102 membership kernel 与 source-state matcher、必要 JavaDoc/测试；不得修改写入或其他证据入口。
