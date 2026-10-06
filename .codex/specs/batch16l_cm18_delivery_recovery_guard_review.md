# CM-20261007-18：16L TCC delivery 种子与过期租约保护评审

## 1. 评审结论

结论：**GO，限定修复 delivery worker 的两个本地恢复缺口；不实现 COMMITTED 后主动取消。**

本轮只保护已 COMMITTED TCC execution 派生 delivery 台账时的候选筛选和本地租约写入。修复必须保持原 `payNo`、XID、退款键、delivery 状态机和 User/Order 远程调用契约，不新增表、字段、配置开关或消息主题。

## 2. 已确认缺陷

### 2.1 无 XID execution 会造成种子饥饿

`PayTccDeliveryMapper.findMissing` 当前按 `LIMIT` 返回 COMMITTED execution，但没有过滤空白 XID。`PayTccDeliveryStore.ensure` 的 `requirePayment` 会拒绝空白 XID，导致最早的坏记录反复占据扫描窗口，后续合法 execution 无法及时生成 READY delivery。

`findMissing` 必须在 LIMIT 前增加：

```sql
execution.xid IS NOT NULL
AND LENGTH(TRIM(execution.xid)) > 0
```

该条件与 `findDue`、`claim` 的已知 XID约束一致。空 XID、空白 XID 记录仍由其他事实核验或人工处理，不创建 delivery job。

### 2.2 过期 delivery worker 仍可写本地状态

`updateState` 和 `finishAttempt` 目前只按 `lease_owner` 判断，未要求 `lease_until` 仍有效。`PayTccDeliveryStore.stage` 也只比较 token，不在 Pay 行锁和 delivery 行锁之后检查当前时间。旧 worker 在租约过期后、尚未被新 worker 接管的窗口内，仍可能推进状态或覆盖退避时间。

## 3. 修复契约

### 3.1 Store 层

`PayTccDeliveryStore.stage` 在锁定 Pay 记录和 delivery 记录后，使用同一个 `now` 判断：

```text
leaseOwner == token
leaseUntil != null
leaseUntil > now
```

任一条件不满足立即返回 `false`，不得调用 Pay 状态更新，也不得继续执行 `updateState`。当前 delivery 已为 `SUCCESS` 或 `REFUNDED` 时继续返回 `false`。

`finish` 不得让过期 worker 修改本地重试元数据；由 Mapper 的同一 `now` 条件作最终保护。RPC 已经发出后不承诺撤回，租约只保护本地状态和重试字段。

### 3.2 Mapper 层

`updateState` 和 `finishAttempt` 的 SQL 必须同时满足：

- `BINARY lease_owner = BINARY #{token}`，避免 token 比较受 MySQL 大小写排序规则影响；
- `lease_until IS NOT NULL AND lease_until > #{now}`；
- 原有 payNo、期望状态和终态保护条件不变。

`updateState` 仍与 PayRecord 状态更新处于同一个本地事务；若 delivery CAS 因租约过期失败，整个事务回滚，不能留下 Pay 状态已更新而 delivery 未更新的半状态。

## 4. 不变边界

- 不新增 schema、flag、scheduler、RPC、退款键或 delivery 状态。
- 不改变 `findDue`/`claim` 的有效租约筛选和 60 秒租约配置。
- 不把 lease 变成 TCC execution、User ledger 或 Order 状态的所有权；只保护 Pay 本地 delivery 台账和 PayRecord 投影。
- 不处理 COMMITTED 后主动取消、Order cancel、User refund 或 delivery paid/refund 优先级；该能力仍待独立评审。
- 已发出的 Order/User RPC 无法撤回；迟到 RPC 的本地结果只能按当前 lease 和状态 CAS 丢弃或记录重试。

## 5. 验收矩阵

1. 真实 MySQL 插入超过 20 条最早的 COMMITTED 空/空白 XID execution，再插入第 21 条合法 execution；`findMissing(20)` 不返回坏记录，合法 execution 最终生成 READY delivery。
2. 空 XID execution 不因 `ensure` 异常反复占据种子批次。
3. 当前 owner 且 `lease_until > now` 时，`stage` 成功推进目标状态；PayRecord 与 delivery 更新原子提交。
4. `lease_until = now`、`lease_until < now`、`lease_until IS NULL` 时，`stage` 返回 false，不修改 PayRecord、delivery state 或重试字段。
5. 旧 token、大小写不同 token、租约已被新 owner 接管时，`stage` 和 `finish` 均失败；新 owner 的 lease、状态和 `next_retry_at` 不被覆盖。
6. 过期 worker 在 RPC 返回后执行 `finish`，不能清除新 owner 的 lease 或延后重试时间。
7. `SUCCESS`/`REFUNDED` 终态及既有 delivery、Legacy 支付、16E cancel、16F/G rollback、16K inflight abort 回归不受影响。

## 6. 实施与评审门

实现仅涉及 `PayTccDeliveryMapper` 和 `PayTccDeliveryStore` 及对应回归测试。完成后必须执行真实 MySQL 集成验收、相关 Pay 单测/集成测试、全仓构建和 `git diff --check`。规格评审通过后再委派 backend 实现；本规格不授权修改前端或其他业务模块。
