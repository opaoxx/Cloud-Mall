# CM-20261007-18：16M TCC cancel 恢复租约 CAS 保护评审

## 1. 评审结论

结论：**GO，限定修复 16E cancel recovery 的过期 worker 本地写入边界；不扩展 COMMITTED 主动取消。**

本轮沿用 16L delivery 的租约保护口径，只约束 `pay_tcc_cancel` 台账和 Pay FAILED 投影的本地 CAS。不得新增表、字段、配置开关、RPC 或退款键，不改变 16E 的资金互斥门和事务外 Order.cancel 约束。

## 2. 已确认缺陷

`PayTccCancelMapper.updateState`、`finish` 目前只按 `pay_no` 与 `lease_owner` 判断，未校验 lease 是否仍有效，且 owner 比较可能受 MySQL 大小写排序规则影响。过期 worker 在尚未被新 worker 接管的窗口仍可推进取消状态或覆盖退避字段。

`PayTccCancelStore.stage` 当前通过 `Objects.equals` 比较 owner；当传入 token 与数据库 owner 同为 null 时可能通过 owner 判断，随后SQL的NULL比较导致CAS失败、抛异常并回滚，并非成功提交了未经授权的Pay状态。空白token则未被显式拒绝。它多次调用 `now()`，Java 检查通过后到 Pay FAILED 或台账 CAS 之间可能跨过 lease 边界。`finish` 没有本地 guard，必须由 Mapper 的同一时间条件兜底。

## 3. 修复契约

### 3.1 Store.stage

锁定 Pay 记录和 cancel 台账后只采样一次 `now`，并要求：

```text
token != null && !token.isBlank()
current.leaseOwner == token（精确大小写）
current.leaseUntil != null && current.leaseUntil > now
```

任一条件不满足立即返回 `false`，不得读取远程资源、调用 Pay 状态更新或写 cancel 台账。`COMPLETED`、`CONFLICT` 仍不可推进。

同一次本地事务内，`COMPLETED` 必须以该时间语义将 Pay `PENDING→FAILED` 与 cancel 台账状态 CAS 一起提交；台账 CAS 失败必须回滚 Pay FAILED，不能形成半完成。

### 3.2 Store.finish

`finish` 只采样一次 `now`，用它计算 `nextRetryAt` 与本次更新时间；不承诺撤回已经发出的 Order RPC。最终是否能释放 lease 或更新退避由 Mapper 的有效租约条件裁决。

### 3.3 Mapper SQL

`updateState` 与 `finish` 的 WHERE 必须同时满足：

- `BINARY pay_no = BINARY #{payNo}`；
- `BINARY lease_owner = BINARY #{token}`；
- `lease_until IS NOT NULL AND lease_until > #{now}`；
- 原有状态、token 和终态条件保持不变。

`updateState` 的失败必须让调用方事务回滚；`finish` 失败不能清除新 owner 的 lease，也不能修改 `next_retry_at`。

## 4. 不变边界

- 继承 16E 的 pay_record 行锁、cancel gate、完整 tuple 校验和 `REQUESTED/ORDER_CANCELLED/CANCEL_UNKNOWN` 状态机。
- Order.cancel 仍只能在本地抢占事务提交后调用；本轮不增加任何远程调用。
- 不改 execution/dispatch/receipt/Legacy intent 的资金互斥规则，不把 cancel lease 变成 TCC decision 或 User ledger 所有权。
- 不处理 COMMITTED 后取消、delivery refund、TC 决议或新的退款意图。
- RPC 已发出不能撤回；本轮只保护 Pay 本地状态和恢复重试元数据。

## 5. 验收矩阵

1. 真实 MySQL 中 owner 正确且 `lease_until > now` 时，`stage` 可推进状态；`COMPLETED` 时 Pay FAILED 与 cancel COMPLETED 同事务提交。
2. `lease_until = now`、过去、NULL 时，`stage` 返回 false，Pay 和 cancel 均不改变。
3. null token、空白 token、大小写不同 token、旧 owner 和已被新 owner 接管的 token 均不能推进或释放租约。
4. `finish` 在有效 owner/lease 下可释放并写入退避；过期 owner 不能清理 lease、改变 `next_retry_at` 或错误信息。
5. 台账 CAS 触发数据库失败时，Pay FAILED 回滚并保持 PENDING；有效 lease 下非终态状态更新仍保持原子性。
6. 多次 recovery、重复 Order.cancel、16L delivery、16F/G rollback、16K inflight abort 和 Legacy 支付回归不受影响。

## 6. 实施门

实现限定在 `PayTccCancelMapper`、`PayTccCancelStore` 及对应回归测试；完成后执行真实 MySQL 租约边界、单测、相关集成测试、全仓构建和 `git diff --check`。规格评审通过后再委派 backend 实现。
