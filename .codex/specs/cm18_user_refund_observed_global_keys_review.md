# CM-18 16T115：User refund observed global keys

## 1. 结论与范围

GO。fresh/replay 双集合 proof 仍只按当前 original/linked keys 观察；同一 operationKey 的 source shadow、wrong-origin clone 或 other-pay row 可因历史 UNIQUE 缺失而被 point/局部集合隐藏。

本批只为 fresh/replay 已证明集合补 bounded global key count，不改 point SELECT、锁、首行多结果分类或写路径。

## 2. Observed key count

在既有 source/owner/scope、linked kernel、payment membership、cap/amount/current membership 全部通过后，新 helper 对观察到的 keys 复用 `countOperationCandidates(key)`：

- original/source operationKey 必须 count=1；
- 每个已证明 linked refund operationKey 必须 count=1；
- count null/负数/0/多行沿 `BALANCE_REFUND_INVALID`/409 固定累计证据 message，DAO 异常原样。

fresh 最大预算为 source 1 + 99 linked = 100（100 linked 已被 T111 capacity 拒绝）；existing replay 最大 source 1 + 100 linked = 101。只 count 当前观察 keys，不枚举全域，不把 count 替代原 field/kernel proof。

## 3. 调用边界与保持项

- fresh：`requireFreshRefundCollection` 在双集合/金额/capacity成功后执行 global keys，wallet 前；
- replay：`requireRefundReplayCollection` 在双集合/current membership/total成功后执行；
- source/key/DAO/owner/freeze/amount/collection 错误优先不被 count mask；active/disabled/fresh/replay/API/SQL/写/GET 保持。

不修历史 UNIQUE、不改数据、不扩其它 pay/全账户/source fencing；既有 point SELECT 多候选异常分类仍保留。

## 4. 验收

- Unit：count 0/1/multiple/null/DAO、source/linked priority、fresh 99→100 与 replay 100→101 budgets、wallet NULL/no-write。
- Native：owned 临时移除 UNIQUE，source shadow、same-key wrong-origin/other-pay、case alias 在 fresh/replay 均 409，四表 facts 不变；finally 删除额外 id 并恢复 UNIQUE/index。
- 健康 partial/full/100、active Legacy/TCC/disabled、GET/operation 及旧 T102–T114 回归；不启动 RM/TC/App。

## 5. 交付边界

实现仅允许增加 fresh/replay observed-key count helper 与必要 JavaDoc/测试；不得修改 point query、集合 kernel、容量、写入或 API。
