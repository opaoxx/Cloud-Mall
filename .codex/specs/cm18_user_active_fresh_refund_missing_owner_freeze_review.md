# CM-18 16T100：User active fresh refund missing owner freeze

## 1. 结论与范围

GO。T98/T99 active fresh refund 对非 null attempt 已证明 TCC/LEGACY owner，但 attempt=null 仍按历史 Legacy 兼容；同 pay 的 balance_tcc_debit freeze 仍存在时可能 credit/insert。

本批只补 active fresh missing-owner 的本地 freeze 存在性门，不证明幽灵 receive/Fence、nonnull Legacy 的额外 freeze、全资金因果或 source fencing。

## 2. Null attempt freeze gate

在 T70 原 DEBIT proof、T97 bounded DEBIT scope 通过后、T75 aggregate 前，active 用户读取一次现有 `BalanceTccDebitMapper.selectByPayNo(request.payNo)`：

- 返回任何 non-null row（CONFIRMED/TRYING/CANCELLED/UNKNOWN、CI alias、任意 metadata）→ active `BALANCE_REFUND_INVALID`/409 原 message；不按 state/amount 过滤，不 credit/insert/cache；
- 仅可靠 null 才保留历史 active Legacy 兼容，继续 T75/wallet/ledger 链；
- DAO/DataAccess 异常原样传播。

## 3. 保持项

该 gate 仅在 active fresh attempt=null 生效；nonnull LEGACY/TCC 继续 T99/T98 predicate，disabled 404 与 existing replay 不增加查询。T70/T97 source、T75 aggregate、T72 wallet atomicity、SQL/API/锁/TCC/Redis 保持；不修历史 attempt/freeze，不改写或删除 freeze。

## 4. 验收

- Unit：attempt null + freeze null/row/DAO fault、priority/read-count/no-write；nonnull attempt、existing replay、disabled 与 source failure 不调用该门。
- Native：真实 TCC confirm 后删除 attempt，保留五种 owned freeze 变体，active fresh HTTP/service 均 409、四表 facts/wallet 不变；健康无 attempt Legacy、active TCC/Legacy、disabled/replay 保持。
- 只验证 User owned local read/transaction，不启动 RM/TC/App，不宣称幽灵 receive/Fence 或完整资金 fencing。

## 5. 交付边界

实现仅允许在 active fresh attempt=null 分支增加现有 freeze point read、必要 JavaDoc/测试；不得改变 T98/T99 predicate、T75/wallet/写入或其它入口。
