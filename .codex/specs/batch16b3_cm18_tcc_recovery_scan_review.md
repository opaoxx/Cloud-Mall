# CM-20261006-18：16B3 TCC Coordinator 有界恢复扫描评审

## 1. 结论和边界

结论：**GO，增加 coordinator 有界自动恢复扫描；只调用既有 `recover`，不调用 execute/begin/Try。**

16B3 解决多实例下 COMMIT/ROLLBACK 请求响应丢失和 User/Pay 状态延迟的恢复调度问题。扫描器只读取已存在的 `pay_tcc_execution`，不创建 execution、不生成 XID、不注册分支、不改变 `decision=NONE` 的决议。已有 COMMIT/ROLLBACK decision 只重试同一 XID、同一方向的官方 TM RPC，并通过原 `recover` 核对两端事实。

本轮不接公网、不接 Order/Stock、不接退款、不修改 Pay/User 资金状态机。`BEGIN_UNKNOWN` 或 xid 为空的执行跳过并告警，不能猜测或重新 begin。

## 2. 调度字段

在 `pay_tcc_execution` 增加：

```sql
ALTER TABLE pay_tcc_execution
  ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN next_recovery_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN recovery_owner VARCHAR(128),
  ADD COLUMN recovery_lease_until DATETIME(3),
  ADD COLUMN recovery_last_error VARCHAR(512);
```

实际迁移必须逐列检查、可重复执行；已有业务 state/decision/xid/载荷不得被迁移脚本改写。`next_recovery_at` 数据库默认 `CURRENT_TIMESTAMP(3)`；新 claim 必须显式写入 `createdAt + 30s`，历史行保持迁移时默认值到期，不 UPDATE 原业务字段。

恢复 lease 与 16B2 的一次性 begin/两分支 Try 执行权完全分离：lease 只能让一个实例调用已有 recover，不能让它接管 claim、begin 或 Try。`recovery_owner` 每次使用随机实例/批次 token，不能使用固定进程 owner；迟到完成只能凭 token CAS 释放。

## 3. 精确扫描 SQL

扫描候选必须在 SQL 层完成过滤，再应用 `LIMIT 20`：

```sql
SELECT execution.*
FROM pay_tcc_execution execution
JOIN pay_record payment ON payment.pay_no = execution.pay_no
WHERE BINARY payment.debit_mode = 'TCC'
  AND BINARY payment.pay_no = BINARY execution.pay_no
  AND payment.user_id = execution.user_id
  AND payment.order_no = execution.order_no
  AND payment.amount = execution.amount
  AND (
    (execution.state IN ('RUNNING','UNKNOWN') AND execution.decision='NONE')
    OR (execution.state='COMMIT_REQUESTED' AND execution.decision='COMMIT')
    OR (execution.state='ROLLBACK_REQUESTED' AND execution.decision='ROLLBACK')
  )
  AND execution.xid IS NOT NULL
  AND execution.next_recovery_at <= :now
  AND (execution.recovery_lease_until IS NULL OR execution.recovery_lease_until <= :now)
ORDER BY execution.next_recovery_at, execution.id
LIMIT :limit
```

`COMMITTED/ROLLEDBACK/CONFLICT/CLAIMED/BEGIN_UNKNOWN` 不扫描；xid NULL 不发任何 RPC，保留人工核验。`decision=NONE` 的 RUNNING/UNKNOWN 只能进入低频只读 recover，recover 不新增决议。SQL 必须在 LIMIT 前完成状态、decision、XID 和 BINARY 键过滤。

必须保证坏记录、长期 UNKNOWN 和永远失败的记录不会占满固定窗口；扫描每轮最多 20 条，每个实例单线程。若使用索引，先以 EXPLAIN 和代表性数据验证，不凭小表计划强加索引。

## 4. Lease 和退避

### 4.1 认领

短本地事务内执行：

1. 按 payNo 锁定候选行。
2. 条件校验 state、nextRecoveryAt、lease 过期和原 tuple。
3. 生成随机 recovery token，写 `recovery_owner/recovery_lease_until`、`recovery_attempts+1`。
4. 提交事务后调用已有 `coordinator.recover(payNo)`；绝不在事务内调用 TC/Feign。

lease 默认 60 秒；旧 RPC 过期后仍可能运行，允许与新实例重叠执行同一 decision 的幂等 RPC。lease 不改变 decision，不允许过期接管 XID。调用超时或进程崩溃后，下一实例仅可继续同向 recover。

### 4.2 成功和失败

- recover 已核对终态：清空 lease，保留 COMMITTED/ROLLEDBACK。
- recover 得到同向 REQUESTED/UNKNOWN：清空 lease，`next_recovery_at` 按退避后重试。
- recover 抛出冲突：只记录 `recovery_last_error` 并按退避重试；扫描器不得把 execution 改为 CONFLICT，CONFLICT 只由 coordinator 两端事实矛盾语义写入。
- 退避为 `5 * 2^(attempt-1)` 秒，最大 300 秒；attempt 最大 31，避免指数溢出。成功但未终态也使用退避，不能忙循环。
- 释放 lease 必须 `WHERE pay_no=? AND recovery_owner=?`，迟到实例不能清理新实例的 lease。
- 每轮最多 20 条并设置 10 秒总时间预算；不强杀已经发出的 RPC。单实例使用 `AtomicBoolean` 防止 scheduler/手动调用重入。

## 5. 状态和决议边界

- `decision=NONE`：扫描器只读原 XID、Pay receipt、User GET 和 TC 可观察状态；绝不写 COMMIT/ROLLBACK。
- `decision=COMMIT`：只调用原 XID commit，直到两端永久 CONFIRMED 并完成完整 tuple 核验。
- `decision=ROLLBACK`：只调用原 XID rollback，直到两端永久 CANCELLED/无冻结事实核验完成。
- TC Unknown、网络超时、Fence 缺失或任一端缺少完整事实都保持 UNKNOWN，不能写终态。
- 已有终态不回退，迟到 TRY/Unknown 不覆盖；保留原 decision/XID/branch。

## 6. Java 契约

- `PayTccRecoveryScheduler`：`@Scheduled` 或等价内部调度 Bean，默认 `cloudmall.pay.tcc-recovery.enabled=false`；批量上限 20，单线程，AtomicBoolean 防重入；UNKNOWN 只保留人工核验，不做热循环告警。
- `PayTccExecutionRecoveryStore.findDue(now, limit)`：只实现上述 SQL 候选筛选。
- `claimRecovery(payNo, token, leaseUntil, now)`、`finishRecovery(payNo, token, nextAt, error)`：全部 token CAS。
- scheduler 只调用 `PayTccCoordinatorService.recover(payNo)`；禁止依赖或调用 `execute`。
- 默认配置关闭；开启时必须校验 coordinator、receipt、bridge、Seata、未代理 DataSource 和有界 timeout 已满足。

## 7. 验收矩阵

1. 多实例同一到期 execution：只有一个随机 token 获得 lease，另一个不发 RPC。
2. COMMIT_REQUESTED：扫描只重试原 XID commit；重复响应不产生新 XID/branch。
3. ROLLBACK_REQUESTED：扫描只重试原 XID rollback；User/Pay 资金最终无泄漏。
4. decision NONE/RUNNING：扫描只读观察，绝不新增 rollback/commit。
5. BEGIN_UNKNOWN/xid NULL：不发任何 RPC、不 begin、不占用其他合法记录窗口。
6. 20 条批量中混入长期失败/冲突/终态：合法执行不会饥饿，失败记录按退避跳过。
7. 用确定性 SQL/两实例边界验证 claim、过期 lease、旧 token 完成 CAS 和逐 payNo 认领；B2 已有决议 RPC 窗口进程退出/重启证据。本轮不宣称 recovery claim、RPC、release 各窗口独立进程退出均已验收，后续可补专门进程矩阵。
8. 终态证据不足、TC Unknown、Fence 已清理且缺少永久业务终态：保留 UNKNOWN，测试数据库和 TC 事实不清理；若 Pay/User 两端已有完整永久终态证据，则按 B2 规则完成核对，不因 Fence 清理本身强制保持 UNKNOWN。
9. 自动 scheduler 从已有 COMMIT_REQUESTED/ROLLBACK_REQUESTED 恢复；NONE 不新增 decision；历史 nextRecoveryAt、60 秒过期 lease、10 秒预算和退避上限行为正确。

## 8. 边界

- **GO**：有界 SQL 扫描、随机 lease、同向原 XID recover、有限退避和多实例竞争验收。
- **NO-GO**：scheduler 调用 execute/begin/Try、自动新增 decision、补建 XID、Order/Stock、退款和公网入口。
- 16B3 完成后只能宣称 TCC 恢复调度通过，CM-18 真实支付闭环仍未完成。
