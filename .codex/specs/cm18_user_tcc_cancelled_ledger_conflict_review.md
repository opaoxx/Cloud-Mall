# CM-18 16T22：User TCC CANCELLED 账本冲突评审

## 1. 结论与范围

结论：**GO，限定在 BalanceTccDebitServiceImpl 四个 CANCELLED 摘要返回分支前复用既有 `requireNoLedger(request)`；不改变 requireNoLedger 内部查询/错误码、正常 NoOp、wallet guard、写状态机、SQL/锁/CAS、DDL、RM/Fence、API 或 flags。**

当前 Try/CANCELLED 与 Cancel 空取消/已释放重放分支可直接返回 CANCELLED，跳过同 payNo 的任意 DEBIT 或固定 ledger key 占用核验，导致原事实矛盾被误报为已取消。

## 2. 四个调用点

在以下四个尚未覆盖的 CANCELLED 摘要返回前调用既有 `requireNoLedger(request)`：

1. `tryDebit`：`attempt != null`、`debit == null` 且 `attempt.state=CANCELLED` 的 `view(attempt)` 返回前；
2. `tryDebit`：`requireConsistent` 后 `debit.state=CANCELLED` 的 `BalanceTccDebitVO.from(debit)` 返回前；
3. `cancelDebit`：`debit == null` 且 `attempt.state=CANCELLED` 的 `view(attempt)` 返回前；
4. `cancelDebit`：`debit.state=CANCELLED` 的既有摘要返回前。

`cancelDebit` 的首次 `attempt == null && debit == null` 空取消分支已有 `requireNoLedger(request)`，本批保持原样，不重复纳入新增缺口；CONFIRMED/TRYING 其他路径不扩展。

`requireNoLedger` 内部仍按原 `pay_no` DEBIT 与固定 `ledgerKey` 查询，冲突继续 `IDEMPOTENCY_CONFLICT`/409；不增加预查询、BINARY WHERE、状态校验或金额规则。

## 3. 不变边界

- 正常无账本 NoOp、当前 wallet T19/T21 门、disabled 用户 Cancel、Try 开关、CONFIRMED/反向冲突优先顺序保持。
- 不校验所有历史退款/账户因果，不修历史，不新增资金动作；SQL、锁、CAS、DDL、RM/Fence、API/flags 不变。
- 合法无 DEBIT 的四个 CANCELLED 分支仍按原摘要/API 返回；存在任意状态 DEBIT 或固定 key 占用才阻塞。

## 4. 验收矩阵

- Unit：四个返回分支 × 同 payNo 任意状态 DEBIT/固定 key 占用，均 409；合法 no-op/disabled/已有取消/正常 replay 保持；wallet malformed 与原 guard 对照保持。
- Owned MySQL：同 payNo 不同 ledgerKey DEBIT 任意 state、固定 key 不同 pay/type/state、CI alias 情形，四表完整不变，source checksum/finally 清理；不启动 RM/TC/App/固定端口。
- 保留原资金、T19–T21 和 User state/HTTP/取证回归；本批不改 requireNoLedger 查询语义。

## 5. 交付边界

本评审只冻结 CANCELLED 摘要返回前的原账本冲突门，不扩展退款、账户因果、历史 repair 或 CM18 M1–M4。
