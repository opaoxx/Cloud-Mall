# CM-18 16T14：User TCC 余额快照证据门评审

## 1. 结论与范围

结论：**GO，限定修复 `UserTccStateQuery.read` 的余额快照证明；不改变 User 写状态机、RM/Fence、HTTP/Feign、DDL、flags 或其他查询语义。**

writer 已冻结：Try 的 `balanceBefore=user.balance`、`balanceAfterTry=balanceBefore-amount`；Confirm ledger 复制该 pair；Cancel 保留原冻结快照，空 Cancel 无 debit。当前只读 Query 未核验这些字段，坏历史可误报资金终态。

## 2. 只读金额门

在 receive 身份/金额门后、任何金额输出或 compare 前，`debit` 存在时必须要求：

- `balanceBefore`、`balanceAfterTry` 非 null、非负、符合 DECIMAL(18,2) 无损精度且不超过 `9999999999999999.99`；
- `balanceBefore-balanceAfterTry == debit.amount`；
- CONFIRMED ledger 存在时，`ledger.balanceBefore`、`ledger.balanceAfter` 同样通过非负/容量/精度门，并分别数值等于 debit 的 before/afterTry。

任一 NULL、负数、超容量、非无损 scale 或 delta/快照不等统一原 `USER_TCC_STATE_CONFLICT`/409；不比较当前账户余额，不验证历史 before/after 与账户因果或冻结总额。

## 3. 保持边界

- receive 缺失仍 404，DB 异常沿原 503；unknown state、Fence、过期语义和空取消无 debit 保持。
- 只修改原 Query 的只读验证；不新增 SELECT、锁、写、RM/TC、API/Feign 字段、DDL、历史修复或资金动作。
- TRYING/CONFIRMED/CANCELLED 的原状态只有在新快照门通过后才可继续原输出；合法 writer 快照不改变响应。

## 4. 验收矩阵

- Unit/HTTP：debit/ledger 四来源 NULL、negative、excess scale/capacity、错误 delta、两组各自 delta 合法但 snapshot 不一致均 409，不 500、不输出终态；余额为 zero 本身合法，`before=amount、after=0` 时通过，只有 delta/tuple 不成立才冲突。
- 合法 `0.01`、`1.000`、最大 DECIMAL(18,2) 及对应 zero-after 快照通过；旧 state/http、16T9–16T13 取证回归保持。
- Owned MySQL 四来源/六表事实、原库 checksum、并发读无写和资源清理；不改变 writer、RM/Fence、DDL 或 API。

## 5. 交付边界

本评审只冻结 User TCC 余额快照证据门，不扩展账户余额因果、退款、Pay/Order/Stock/TC/MQ 或 CM18 M1–M4。
