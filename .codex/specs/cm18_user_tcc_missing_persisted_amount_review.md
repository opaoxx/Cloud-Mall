# CM-18 16T18：User TCC 缺失持久金额保护评审

## 1. 结论与范围

结论：**GO，限定修复 User TCC 写入口对已有 attempt/debit/confirmed ledger 的 NULL amount 防护；不改变写状态机、SQL/锁、RM、flags、API、DDL、历史修复或自动资金动作。**

当前 `validateAttempt`、`requireConsistent`、`requireLedger` 直接对持久 amount 调用 `compareTo`，NULL 会 500；本批把缺失原金额转为既有安全冲突/未知响应。

## 2. 三处最小 guards

- `validateAttempt(attempt, request)`：在原不可变载荷比较前要求 `attempt.amount != null`；缺失沿原 `conflict(...)`，返回 `IDEMPOTENCY_CONFLICT`/409。
- `requireConsistent(attempt, debit, request)`：在 debit.amount compare 前要求 `debit.amount != null`；缺失沿原 `unknown()`，返回 `BALANCE_TCC_UNKNOWN`/409。
- `requireLedger(attempt, request)`：在 confirmed ledger.amount compare 前要求 `ledger.amount != null`；缺失沿原 `unknown()`，返回 `BALANCE_TCC_UNKNOWN`/409。

guards 必须位于对应 `compareTo` 之前；其他 non-null amount 比较、原 request 校验、锁序、状态 CAS、事务回滚、RM/Fence 和资金写步骤保持。只阻塞缺失金额，不新增全量金额容量/scale/余额 snapshot 门；这些属于其他批次边界。

## 3. 不变语义与边界

- 不修改 DDL、Mapper SQL、状态机、operation/ledger key、API/Feign、flags、RM/TC 或历史数据；不修复 NULL 行、不自动资金动作。
- 正常 TRY/CONFIRM/CANCEL 与 replay 行为保持；原四表事实、余额/冻结/ledger/time 不因 guard 改写。
- 仅受影响的写入口返回既有 409；DB 异常、锁序和其他 Runtime/Error 沿原渠道。

## 4. 验收矩阵

- Unit：attempt/debit/confirmed ledger 分别 NULL，确认不再 NPE/500；既有 conflict/unknown code=409；正常金额回归。
- Owned `BalanceTccStateMySqlIT` 随机库：仅 owned 演练三表 nullable amount，核对资金四表、余额、冻结、ledger、时间和 source checksum 完整不变，finally DROP；不启动 RM/TC/App/固定端口。
- 保留原 23 项金融回归（21 项金融/幂等/并发/回滚 + 2 项初始化/迁移）、旧 User state/http/16T9–16T17 观察回归；旧 flags 关闭和读边界不变。

## 5. 交付边界

本评审只冻结缺失持久金额的 fail-closed 保护，不扩展金额全规则、快照增量、账户因果、历史 repair 或 CM18 M1–M4。
