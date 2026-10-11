# CM-18 16T21：User TCC 账户派生余额边界评审

## 1. 结论与范围

结论：**GO，限定在 User TCC Try/Confirm/Cancel 三个资金写点增加派生余额边界门；不改变 T19 wallet 合法性门、SQL、锁、CAS、状态机、RM、API、DDL、flags 或 Legacy/refund 路径。**

T19 只证明当前 balance/frozenBalance 单值合法；派生 `frozen+amount` 或 `balance+amount` 仍可能越过 DECIMAL 容量导致 strict SQL 22003/500，或 underflow 条件更新返回 0 后被包装成 500。本批在资金 SQL 前 fail closed。

## 2. 三个派生边界点

- fresh Try：T19 valid wallet、NoLedger、原余额不足 `PAYMENT_FAILED`/409 门保持原顺序；在 compare/claim 前要求 `frozenBalance.add(request.amount)<=MAX_AMOUNT`。不通过返回 `BALANCE_TCC_UNKNOWN`/409；余额不足优先返回原 PAYMENT_FAILED。
- Confirm：T19 valid wallet、TRYING、NoLedger 后、`clearFrozenBalance` 前要求 `frozenBalance>=request.amount`；不通过 `BALANCE_TCC_UNKNOWN`/409。
- Cancel：T19 valid wallet、TRYING、NoLedger 后、`releaseFrozenBalance` 前要求 `frozenBalance>=request.amount` 且 `balance.add(request.amount)<=MAX_AMOUNT`；不通过 `BALANCE_TCC_UNKNOWN`/409。

使用 BigDecimal 派生比较，不 double、不截断、不补 0；等于 MAX、frozen 等于 amount、zero/尾零合法。

## 3. 不变边界

- T19 当前字段合法性/scale 门保持；NoOp/replay/空取消和 disabled 清算不进入新的财务派生门。
- 原 conditional UPDATE、`requireOne`、事务回滚、锁序、状态/CAS、RM/Fence、API/flags/DDL 不变；SQL 错误仍沿原数据库异常，不统一映射为冲突。
- 不比较历史快照、账户 aggregate、Legacy/refund，不修历史、不自动资金动作。

## 4. 验收矩阵

- Unit：fresh overflow、Confirm underflow、Cancel underflow/overflow 各覆盖；合法等 MAX、frozen=amount、zero/尾零、余额不足优先、replay/NoOp/空取消保持。
- Owned strict MySQL：beforefix 22003/condition-0 红样本，修复后四资金表/余额/frozen/ledger/time 全不变；正常边界可确认/取消，source checksum/finally 清理通过。
- 保留旧资金 28 项及其他专项回归，不启动 RM/TC/App/固定端口；本批仅本地 TCC 派生金额边界。

## 5. 交付边界

本评审只冻结当前 TCC 写路径派生余额边界，不扩展 Legacy、refund、只读 Query、历史 repair 或 CM18 M1–M4。
