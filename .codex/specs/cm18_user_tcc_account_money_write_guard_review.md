# CM-18 16T19：User TCC 账户金额写入保护评审

## 1. 结论与范围

结论：**GO，限定在 User TCC Try/Confirm/Cancel 三个资金写入口对当前 UserPO `balance/frozenBalance` 的 fail-closed guard；不改变写 SQL、锁序、状态机、RM、flags、API 或历史事实。**

当前 fresh Try 的 NULL balance 会在 compare 前 NPE；frozenBalance NULL/负数可能让冻结 SQL提交坏投影；Cancel balance NULL 可能提交 CANCELLED 但形成无效可用额。Malformed wallet 必须在资金 SQL 前返回既有 `BALANCE_TCC_UNKNOWN`/409。

## 2. 三个写点 guard

使用 private account money helper：non-null、非负、<= `MAX_AMOUNT`、`stripTrailingZeros().scale()<=2`；余额为 zero 合法，且不比较当前余额与历史快照/聚合。

- fresh Try：完成既有 existing attempt/replay/NoLedger 门后、余额 compare、claim 和 freeze SQL 前，要求 `user.balance` 与 `user.frozenBalance` 均合法；坏账户返回 `BALANCE_TCC_UNKNOWN`/409，不补 0、不修复历史。
- Confirm：`lockUser` 后，仅在已有 TRYING 清算分支、`requireNoLedger` 门通过且 `clearFrozenBalance` 前，要求当前 balance/frozenBalance 合法；Confirmed replay/空或其他 metadata 分支不新增财务因果门。
- Cancel：`lockUser` 后，仅在已有 TRYING 释放分支、`requireNoLedger` 门通过且 `releaseFrozenBalance` 前，要求当前 balance/frozenBalance 合法；空取消无 debit 仍只写原 metadata，不要求坏账户提供新财务结论。

## 3. 不变边界

- 不增加 SELECT，沿原用户行锁、条件 UPDATE、事务回滚、attempt/debit/ledger/Fence/RM 顺序和错误码链。
- 不改变 request amount validation、余额不足 `PAYMENT_FAILED`/409、disabled 用户已有清算、zero/无损尾零、replay/NoLedger/空取消、DDL/API/flags/TTL。
- 不做账户余额与历史快照/aggregate 比较，不做算术 overflow proof，不修历史账户或自动资金动作。

## 4. 验收矩阵

- Unit：fresh Try、Confirm、Cancel 当前 wallet balance/frozenBalance 分别 NULL、negative、scale/capacity 非法返回 `BALANCE_TCC_UNKNOWN`/409；合法 zero (`before=amount, after=0`)、0.01、1.000、最大金额、disabled 清算、replay、空取消保持。
- Owned `BalanceTccStateMySqlIT`：原 23 项资金回归（21 金融/幂等/并发/回滚 + 2 初始化/迁移）及 NULL wallet 演练；错误路径四表、余额、冻结、ledger、时间完整不变，source checksum/finally 清理通过。
- 不启动 RM/TC/App/固定端口，不改 UserMapper SQL/DDL、资金写步骤或其他服务。

## 5. 交付边界

本评审只冻结当前账户金额 malformed 的资金写保护，不扩展只读 Query 金额门、余额因果、历史 repair 或 CM18 M1–M4。
