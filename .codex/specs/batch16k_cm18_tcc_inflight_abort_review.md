# CM-20261007-18：16K TCC 已开始未决议主动回滚评审

## 1. 结论与范围

结论：**有限 GO；复用 `cloudmall.pay.tcc-cancel.enabled`，仅对已绑定 XID、`decision=NONE` 且两端 TRYING/no-ledger 证据完整的原 execution 允许主动选择 ROLLBACK。**

本轮解决“用户 failure 到达时 TCC 已开始但尚未决议”的竞态：不创建新表，不新建 XID、branch、execution、Try、退款或 Order/Stock paid/cancel。最终 Pay FAILED 仍只能由 16F 公开入口或 16G 后台在双端永久 `CANCELLED`/no-ledger 证据下投影。

COMMITTED、COMMIT 已选、无 XID/BEGIN_UNKNOWN、分支缺失、载荷冲突和 User live 未知均不执行主动回滚；沿原 coordinator/recovery 事实链返回 202/既有冲突。

## 2. 开关与入口

- 复用 `cloudmall.pay.tcc-cancel.enabled=false`；关闭时 TCC failure 保持既有 202，不能绕过原 coordinator。
- 新增独立 `PayTccAbortService`（受 cancel flag 条件控制），由公开 `mock-fail`/failure callback 的 TCC 分支调用；它不依赖或调用 `PayTccPaymentService.pay`、`coordinator.recover`，防止 failure 意外发送 COMMIT。
- `PayServiceImpl.fail` 的 Legacy 路由继续由 16J `PayLegacyFailureService` 处理；TCC 入口保持 `NOT_SUPPORTED`，callback 仍无外层事务。
- 新增内部 `PayTccAbortProofVO` 保存原 execution、Pay receipt/Fence、User dispatch 的完整 tuple 及 Pay/User branchId；不对公网返回，不新增持久表。

## 3. 方法契约

### 3.1 `PayTccAbortService.requestRollback(PayVO)`

使用 `Propagation.NOT_SUPPORTED`，执行顺序：

1. 调用 Store 的独立短事务读取并锁定原 Pay/execution，先核对 Pay 持久模式 TCC、payNo/userId/orderNo/amount；不使用公开请求旧 snapshot 授权。
2. 若 execution 已 `COMMIT_REQUESTED/COMMITTED` 或 decision 已为 COMMIT：不改向、不调用 TC，返回既有支付处理中/冲突 202/409。
3. 若 `BEGIN_UNKNOWN`/XID 为空、`CLAIMED` 未绑定 XID、decision 非 NONE、state 非允许未决状态、receipt/dispatch/Fence 缺失或本地 tuple 不一致：不 begin、不 Try、不补事实，返回 202/既有冲突。
4. 对 decision NONE 且 XID 已绑定的候选，Store 提交完整本地 TRYING 快照；事务外调用 User live GET，仅接受同一 XID/branch、原 pay/order/user/amount/operation/ledger tuple，User `TRYING` 且 no-ledger。User 不可达、状态未知或证据不足返回 202。
5. 证据完整后调用 Store `requestRollback(payNo,xid,proof)` 的短 `REQUIRES_NEW` 事务：锁 Pay 行，再锁 execution 行，重新校验 tokenless 当前 execution 仍 decision NONE、XID/载荷/receipt/dispatch/Fence 与 proof 一致、无 cancel/Legacy intent，然后 CAS `decision NONE→ROLLBACK`、state `ROLLBACK_REQUESTED`。CAS 同时把 `next_recovery_at` 更新为 `min(原值, 当前业务时间)`，以便原 recovery 尽快重试；不得清空或重置 `recovery_owner`、`recovery_lease_until`、`recovery_attempts`。CAS 失败必须重新读取，不覆盖 COMMIT。
6. 决议 CAS 成功后，由既有 recovery scheduler 按原 `ROLLBACK_REQUESTED + decision=ROLLBACK` 候选重试同一 XID rollback；abort service 不直接调用 TC rollback，也不声称已回滚。

### 3.2 并发决策规则

- coordinator `decide(COMMIT)` 与 abort `requestRollback` 共用 execution 行锁和单向 CAS；先 COMMIT 者胜出，abort 返回 409/202 且不改向；先 ROLLBACK 者胜出，迟到 coordinator 的 COMMIT 选择冲突，不能覆盖 decision。
- abort 在事务外 User GET 期间不持有 execution 锁；最终 CAS 是唯一决议写入点，proof 过时只能导致 202/冲突。
- 已 `ROLLBACK_REQUESTED`/`ROLLEDBACK` 不重复发起主动决议；交由原 recovery/16F/16G 事实链。
- TC 自然超时、迟到 COMMIT/ROLLBACK RPC 或执行器并发不在本服务判定 Pay FAILED；仍由 16F/16G 在后续双端永久取消证明后投影。

## 4. 本地 TRYING 证明

本地 snapshot 必须完整核对并封装为 `PayTccAbortProofVO`：

- `pay_record.debit_mode=TCC`、Pay 状态仍为 PENDING/ORDER_SYNC_UNKNOWN，且无 `pay_tcc_cancel`、Legacy `pay_debit_intent`；
- execution `xid` 非空、decision NONE、原 operationKey=`tcc-debit:<payNo>`、ledgerKey=`payNo`；
- Pay dispatch 与 receipt 均存在，user/pay branch、XID、payNo、orderNo、userId、amount、operationKey、ledgerKey 完整一致；
- Pay receipt/Fence 仍为 TRYING/有效未决事实，不能以 PREPARED、CLAIMED、CONFIRMED、CANCELLED 或镜像缺失替代；
- User live GET 同 tuple 为 TRYING 且 no-ledger。余额不足、REJECTED、CANCELLED、CONFIRMED/APPLIED、未知或冲突均不得选择 ROLLBACK。

初次 proof 事务和最终 `requestRollback` 短事务都必须核验同一 proof 的本地 TRYING 快照；User live GET 严格位于两个事务之间。初次/最终本地读取复用 `REPEATABLE_READ` 下已有 Evidence 一致读，不能在 Pay→execution 锁之外以 `receipt→Fence` 顺序追加锁；不得形成与 RM `Fence→receipt` 相反的锁序。Pay/execution 行锁只负责决议互斥，最终事务不重新锁 receipt/Fence。

## 5. 与 16E/16F/16G 的边界

- 16E 无 execution cancel 仍使用 `pay_tcc_cancel`；16K 不创建或推进 cancel 台账，不把 inflight execution 写入该表。
- 16F/16G 仍是 Pay FAILED 的唯一投影路径：ROLLBACK_REQUESTED 经原 recovery 成为 ROLLEDBACK 后，只有双端永久 CANCELLED/no-ledger 证明才 CAS FAILED。
- 不处理 COMMITTED 后订单取消；不调用 delivery refund，不创建新退款键，不调用 Order cancel/paid。
- callback failure 只有 abort 决议 CAS 成功才可返回“回滚请求已持久化”的 202；不能 complete `RECEIVED_FAILURE` 为 FAILED。最终 callback FAILED 仍等待 16F/G 或后续明确入口。

## 6. 最小验收矩阵

1. XID 已绑定、decision NONE、Pay/receipt/User 两端 TRYING/no-ledger：abort CAS `NONE→ROLLBACK_REQUESTED`，无新 XID/branch/Try，`next_recovery_at` 提前到当前时间且 owner/lease/attempts 保持不变，返回 202。
2. abort 与 coordinator COMMIT 竞态：同一 execution 行锁，先 COMMIT 则 abort 不改向，先 ROLLBACK 则迟到 COMMIT 冲突。
3. User live 未知、分支缺失、Fence/receipt 非 TRYING、载荷冲突、XID 缺失：不决议、不补事实，返回 202/409。
4. 已 COMMIT/COMMITTED、ROLLBACK_REQUESTED/ROLLEDBACK：不重复决议、不调用 delivery/refund，沿既有恢复/投影。
5. 初次/最终本地 TRYING proof 使用同一 `PayTccAbortProofVO` 和 Evidence 一致快照；最终不锁 receipt/Fence，不与 RM 形成反向锁序；TC 超时并发不在 16K 判 FAILED。
6. cancel flag 关闭：TCC failure 仍原 202；Legacy failure、16E cancel、16F/G rollback 不受影响。
7. callback failure 预绑定与 202/RECEIVED 状态保持正确；未写 Pay FAILED 前不 complete。

## 7. 明确 NO-GO

- failure 通过 `coordinator.recover` 间接发送 COMMIT，或用新 begin/Try 代替原 execution。
- 未完成两端 TRYING/no-ledger 证明就 CAS ROLLBACK。
- 改写已 COMMIT decision、调用 Order cancel/paid、User refund、delivery refund 或创建新退款键。
- 将 ROLLBACK_REQUESTED/RPC 返回当作 Pay FAILED；FAILED 仍必须由 16F/G 永久双端证据投影。
