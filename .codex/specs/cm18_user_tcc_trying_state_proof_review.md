# CM-18 16T23：User TCC TRYING 状态账本证明评审

## 1. 结论与范围

结论：**GO，限定保护 User TCC 已有 TRYING 重放及状态一致性；不改变 Try/Confirm/Cancel 写状态机、RM/Fence、SQL/锁/CAS、DDL、API 或 flags。**

当前 Try 在 `requireConsistent` 后可直接返回 debit 摘要，未像 Cancel/Confirm 一样核账本；同 payNo 任意 DEBIT 或固定 ledgerKey 占用可能被忽略。另有历史/owned nullable state 两份都为 NULL 时 `Set.of(...).contains(null)` 可 NPE。

## 2. 最小修复门

- Try 的 `requireConsistent` 后，若 debit.state=`TRYING`，必须先调用既有 `requireNoLedger(request)`，再返回 debit；CANCELLED 分支的既有门保持，CONFIRMED 仍走 `requireLedger`。不扩展 Confirm/Cancel/首次 claim 其他路径。
- `requireConsistent` 在 state 白名单判断前，若 debit.state==null，直接走既有 `unknown()`/`BALANCE_TCC_UNKNOWN`/409；不对其他非 NULL 状态新增校验或改变原错误码。
- 原 `requireNoLedger` 内部查询/短路/错误码不改；正常 TRYING 无账本 replay 保持，坏 current wallet、开关/user.status 优先与空取消 NoOp 保持。

## 3. 验收边界

- Unit：TRYING 两种账本来源（payNo DEBIT、固定 ledgerKey）均 409；attempt/debit state 双 NULL 及三入口旧 NPE 场景统一 unknown 409；合法 TRYING 无账本、CANCELLED/CONFIRMED、NoOp/replay 保持。
- Owned MySQL：扩展取消矩阵中的 TRYING replay，覆盖任意 DEBIT state、不同 key、CI alias/case/accent/trailing、NULL state；四表完整不变、source checksum/owned 清理保持。NULL 仅 owned nullable 列演练，生产 DDL 不改。
- 保留 16T19–16T22 wallet、账本快照、CANCELLED conflict、旧资金回归；不验证全历史财务因果、Pay/Order/Stock/TC/MQ。

## 4. 交付边界

本评审只冻结 TRYING 状态下的原账本存在门与 NULL state fail-closed，不扩展其他状态字段、历史修复或 CM18 M1–M4。
