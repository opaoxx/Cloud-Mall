# CM-20261007-18：16G TCC 后台 ROLLEDBACK 失败投影评审

## 1. 结论与边界

结论：**有限 GO；在既有 TCC recovery scheduler 中增加第二阶段 ROLLEDBACK projection，复用 recovery 元数据、lease、退避和开关。**

16G 处理公开请求未到达的已回滚 execution：execution 已为 `ROLLEDBACK`，Pay 仍为 `PENDING`/`ORDER_SYNC_UNKNOWN`，后台核对同一 User live TCC 永久 `CANCELLED`/no-ledger 后，将 Pay CAS 为 `FAILED`。本轮不创建新表、不新增 flag、不自动完成 callback 日志、不调用 Order/TC/User 写接口、不改变 execution decision，不处理未决取消台账。

自动投影不依赖 `PayTccPaymentService` 或公开 entry。新增独立 `PayTccRollbackProjectionService`，由已有 `PayTccRecoveryScheduler` 在同一 `cloudmall.pay.tcc-recovery.enabled` 开关和每轮预算内调用，避免 RecoveryScheduler→entry→PaymentService 的依赖循环。

## 2. 开关、批量和阶段顺序

- 复用 `cloudmall.pay.tcc-recovery.enabled=false`；关闭时不扫描、不 claim、不投影。16G 不引入 `tcc-rollback-projection.enabled`。
- 每轮保持既有 recovery scheduler 单实例单线程，整轮最多 `batchSize` 条（默认 20）且从轮次开始起共享 10 秒启动预算；在原 decision recovery 阶段之外增加独立 rollback projection 阶段。两阶段轮换首选优先级：首轮旧 decision recovery 优先，下一轮 rollback projection 优先，之后交替；某阶段耗尽预算后，下一轮必须让另一阶段先运行，避免长期旧 recovery 饥饿 projection 或反之。每阶段候选 SQL 的 `LIMIT` 使用整轮剩余额度，不得各自取满 20。
- 阶段查询或单条 claim/RPC 异常只记录并按该记录退避，不阻塞另一阶段消耗本轮剩余预算；阶段异常不能把整轮预算视为已耗尽。认领失败/未知本身消耗该条工作配额和实际耗时，但不阻塞后续候选。
- 两阶段使用独立 SQL 候选和随机 token lease。投影 service 只负责 ROLLEDBACK，不能调用 coordinator/recover/execute，也不依赖 Pay public entry；不新增独立 scheduler，仍由同一 scheduler 按轮次优先级驱动。
- 候选顺序固定 `next_recovery_at, id`；每条 RPC/DB 工作受总预算约束，不强杀已发出的 User GET。

## 3. ROLLEDBACK 候选 SQL 契约

在 SQL 层完成全部事实过滤后再 `LIMIT 20`，至少满足：

```sql
SELECT execution.*
FROM pay_tcc_execution execution
JOIN pay_record payment ON payment.pay_no = execution.pay_no
LEFT JOIN pay_tcc_cancel cancellation ON cancellation.pay_no = execution.pay_no
LEFT JOIN pay_debit_intent intent ON intent.pay_no = execution.pay_no
LEFT JOIN pay_tcc_dispatch dispatch ON dispatch.pay_no = execution.pay_no
LEFT JOIN pay_tcc_receipt receipt ON receipt.pay_no = execution.pay_no
WHERE BINARY execution.state = 'ROLLEDBACK'
  AND BINARY execution.decision IN ('NONE','COMMIT','ROLLBACK')
  AND execution.xid IS NOT NULL
  AND execution.xid <> ''
  AND BINARY payment.debit_mode = 'TCC'
  AND payment.user_id = execution.user_id
  AND payment.order_no = execution.order_no
  AND payment.amount = execution.amount
  AND payment.status IN ('PENDING','ORDER_SYNC_UNKNOWN')
  AND BINARY execution.operation_key = BINARY CONCAT('tcc-debit:', execution.pay_no)
  AND BINARY execution.ledger_key = BINARY execution.pay_no
  AND cancellation.pay_no IS NULL
  AND intent.pay_no IS NULL
  AND dispatch.pay_no IS NOT NULL
  AND receipt.pay_no IS NOT NULL
  AND execution.next_recovery_at <= :now
  AND (execution.recovery_lease_until IS NULL OR execution.recovery_lease_until <= :now)
ORDER BY execution.next_recovery_at, execution.id
LIMIT :limit
```

Dispatch/receipt 必须在候选中存在并由投影阶段完整核对其原载荷和 branch；缺失事实不补建、不猜测，不能因缺失行反复占满窗口。`pay_tcc_cancel` 或 `pay_debit_intent` 任一存在均排除；cancel 表即使功能关闭也必须作为 gate。

## 4. Lease 与旧 recovery 隔离

- 新增语义化 mapper 方法，例如 `findDueRollbackProjection`、`claimRollbackProjection`，不能复用只允许 RUNNING/UNKNOWN/REQUESTED 的旧候选 SQL。
- claim 使用独立随机 token，按原 execution `recovery_owner/recovery_lease_until` CAS，lease 默认 60 秒；claim 不改 state/decision/XID/branch。
- 最终投影短事务必须锁 `pay_record` 再 `pay_tcc_execution FOR UPDATE`，校验 `recovery_owner=token`、lease 未过期、state/decision/XID/tuple/keys/cancel/intent 仍满足；旧 token、过期 token、抢到新 owner 的迟到执行者不得写 Pay FAILED。
- 复用既有 `finishRecovery(payNo, token, nextAt, error)` 释放 lease 和写退避；释放条件必须带 token。旧 recovery 阶段的迟到 finish 不能清理新投影 owner，反之亦然。
- 投影证据未知或 User GET 异常：不写 Pay，不改 execution，按原 attempts 退避；证据冲突记录错误并继续人工核验，不把 execution 改为 CONFLICT。

## 5. 投影调用链与本地事务

每条投影按以下顺序执行：

1. claim rollback projection lease，事务提交后调用 User live TCC GET；不调用 TC、coordinator、Order、User refund/写接口。
2. 用原 execution/dispatch/receipt 快照核对 payNo、userId、orderNo、amount、XID、operationKey、ledgerKey、原 User branchId；User 必须返回 `CANCELLED`、同 XID/branch、完整 tuple 和 no-ledger。
3. User 证明完整后，进入独立 `REQUIRES_NEW` 短事务：锁 Pay 行和 execution 行，重新核对 token/lease/state/decision/mode/tuple/cancel/intent；Pay 仅允许 `PENDING` 或 `ORDER_SYNC_UNKNOWN`，CAS 为 `FAILED`。
4. 投影成功后释放 lease；不更新 callback log、不修改 execution decision/state、不生成退款、不调用订单取消。Pay 已 FAILED 可幂等结束；SUCCESS/REFUNDED/其他冲突终态保持既有 409/冲突，不覆盖。

`ROLLEDBACK` 的 decision 可为 `NONE/COMMIT/ROLLBACK`，投影绝不改写方向。TC Unknown 不能作为 ROLLEDBACK 候选或失败证明；候选 state 已是持久 ROLLEDBACK 才允许进入本阶段。

## 6. 与公开入口和 16E 的关系

- 公开 `PayTccPaymentService.failure` 与 16F 继续复用同一 snapshot + User live proof + short CAS 语义；后台 service 应共享证明/投影能力，但不能反向依赖公开 PaymentService。
- 16E 无 execution 主动取消仍由 `pay_tcc_cancel` 自身调度；16G 候选排除任何 cancel 台账，不推进取消状态，不把取消台账当作 ROLLEDBACK 证据。
- callback `RECEIVED_FAILURE` 不由后台自动完成；客户端或后续明确入口按 16F 规则核验并完成日志。

## 7. 最小验收矩阵

1. ROLLEDBACK + TCC PENDING + 原 dispatch/receipt + User live CANCELLED/no-ledger：后台最终 Pay FAILED，execution decision/state 不变。
2. decision NONE/COMMIT/ROLLBACK 三种均可投影；XID、branch、operation/ledger tuple 不变。
3. User GET 超时、UNKNOWN、载荷/branch/ledger 不完整：Pay 保持原状态，按 token lease 退避，不写 FAILED。
4. Pay SUCCESS/REFUNDED、execution 有效 lease、旧 token、lease 过期、并发新 owner：不写 FAILED，旧执行不能覆盖新 owner。
5. cancel 台账或 Legacy intent 存在、dispatch/receipt 缺失或载荷不一致：SQL 候选排除或投影拒绝，不补建事实。
6. 整轮最多 20 条、共享启动后 10 秒预算；首轮旧 recovery 优先、后续轮次交替优先，阶段 LIMIT 使用剩余额度。一个阶段查询异常或单条失败不阻塞另一阶段；失败/未知按独立退避，旧 recovery finish 与 projection finish 以 token 隔离，长期运行两阶段均不会饥饿。
7. recovery 开关关闭时表可存在但不扫描；打开时不要求 PayTccPaymentService/entry Bean，不形成依赖循环。

## 8. 明确 NO-GO

- 为后台 projection 新增表、flag，或依赖公开 PaymentService/entry 才能启动。
- 从 UNKNOWN/TC timeout/缺失 execution 推断 ROLLEDBACK。
- 用旧 recovery 候选 SQL 覆盖新阶段，或在 LIMIT 后才过滤 mode、tuple、cancel/intent。
- User live GET 未证明 CANCELLED/no-ledger 就写 FAILED。
- 旧 lease/旧 token 完成后覆盖新 owner，或 projection 修改 decision、调用 TC/Order/User 写接口、自动完成 callback。
