# CM-18 16T69：User DEBIT originalOperationKey 合同

## 1. 结论与范围

本切片 GO，修正 User TCC CONFIRMED/重放与只读状态查询忽略 `balance_ledger.DEBIT.originalOperationKey` 的问题。原 TCC Confirm 写入该字段为 null；T25/Pay T29 已把 null 作为 DEBIT 合同，非 null origin 可能是退款或篡改来源，不应被当作原扣款证明。

本批只增加三个纯字段门：UserTccStateQuery fixed ledger、T68 full-pay candidate、BalanceTccDebitServiceImpl requireLedger；不新增 SELECT/锁/SQL/CAS/DTO/API/状态机/资金写。

## 2. 三处 origin 门

- Query CONFIRMED：原 fixed ledger 的身份、金额、snapshot、type/state 门通过后，追加 `ledger.originalOperationKey == null`，再进入 T68 full-pay scope；非 null（empty、literal `null`、self、other 均拒）沿原 `USER_TCC_STATE_CONFLICT` 409，固定 key/金额等更早门保持优先。
- T68 `matchesDebitScope`：candidate 必须同样 `originalOperationKey == null`；其它原 exact user/pay/order/key/type/state/money/snapshot 门不改。非 null candidate 不输出 CONFIRMED。
- Write `requireLedger`：原 confirmed ledger 门中追加 `ledger.originalOperationKey == null`；非 null 沿既有 `BALANCE_TCC_UNKNOWN` 409，覆盖 Try/Confirm confirmed replay，不新增资金写或查询。

null 是合法原 DEBIT 合同；empty/literal null/self/other 均是非 null 值，必须拒绝。不新增 id/createdAt/完整退款溯源门，不改变 fresh confirm 插入 null、Try flag/user status 优先、CANCELLED 冲突或其它状态。

## 3. 验收与边界

- Unit：Query fixed/scope 与 write Try/Confirm replay 覆盖 empty/literal null/self/other origin、healthy null、金额尾零；坏 origin 返回原 409、无新 money write。
- Owned SQL/HTTP：实际固定 DEBIT 修改 origin 后旧 state GET 保持既有缓存契约且 409；诊断入口 409/no-store、无写。T68 scope 与 User state/proxy 保原 wrapper 分类，existing confirmed replay 409。source facts/checksum/资源 finally 不变，不启 RM/TC/App。
- 保留 T67/T68、T25/T29、退款与全 pay scope 的其它异常优先级；不改 Mapper SQL、DDL、API、状态机、fence/TTL、full refund/account causality。

本切片只冻结 DEBIT 原扣款 origin null 合同，不宣称完整退款溯源或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
