# CM-20261006-18：16B2 Pay 内部 TCC Coordinator 评审

## 1. 评审结论

结论：**GO，实施 Pay 内部一次性 TCC coordinator；不开放公网、不接 Order/Stock、不接退款。**

16B2 只负责在同一 XID 下协调已验收的 Pay receipt RM 和 User bridge RM。Coordinator 先持久化一次性 execution claim，再 begin 一个全局事务、持久化原 XID、注册两个 Try 分支并记录不可变载荷。首次 begin 响应丢失或 XID 尚未持久化时，execution 永久进入 UNKNOWN，禁止创建新 XID。决议只能单向选择 COMMIT 或 ROLLBACK；已有决议、原 XID 和原分支不能被相反决议覆盖。

Pay 对外支付入口、Legacy 余额主路由、Order/Stock、退款和 Pay receipt 主业务状态不在本轮切换。该 coordinator 只允许显式内部测试/迁移入口调用。

## 2. Execution 表

```sql
CREATE TABLE IF NOT EXISTS pay_tcc_execution (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  xid VARCHAR(128),
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  operation_key VARCHAR(128) NOT NULL,
  ledger_key VARCHAR(128) NOT NULL,
  state VARCHAR(32) NOT NULL,
  decision VARCHAR(16) NOT NULL,
  last_error VARCHAR(512),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pay_tcc_execution_pay(pay_no),
  UNIQUE KEY uk_pay_tcc_execution_xid(xid),
  UNIQUE KEY uk_pay_tcc_execution_operation(operation_key),
  UNIQUE KEY uk_pay_tcc_execution_ledger(ledger_key)
);
```

`decision` 只允许 `NONE/COMMIT/ROLLBACK`；只能由 NONE 单向写入 COMMIT 或 ROLLBACK。`state` 固定为 `CLAIMED、BEGIN_UNKNOWN、RUNNING、COMMIT_REQUESTED、ROLLBACK_REQUESTED、UNKNOWN、COMMITTED、ROLLEDBACK、CONFLICT`。不使用可过期 lease 接管 Try；INSERT winner 的一次性执行权不可转移。并发 recover 在已有 decision 时只能重试同一 decision RPC。

XID 可为空只表示 begin 响应未确认或持久化前进程崩溃。XID 为空时禁止恢复 begin、禁止创建新 execution、禁止创建新 payNo；只能保留 UNKNOWN 并告警/人工核验。

## 3. Java/API 边界

内部 Java 契约：

- `PayTccCoordinatorService.execute(PayTccDispatchDTO)`：一次性 claim、begin、持久化 XID、注册 Pay receipt/User 两个 Try 分支和记录决议意图，复用 16A 的 dispatch DTO。
- `PayTccCoordinatorService.recover(payNo)`：只恢复既有 execution/XID/branch，绝不 begin 新全局事务、绝不重新 Try；本轮不增加定时扫描器。
- `PayTccDispatchDTO`：只含 payNo、userId、orderNo、amount；Pay record 必须精确 `TCC + PENDING`。
- `PayTccExecutionVO`：返回原 payNo、XID（内部）、decision、state、错误摘要；不对前端开放。

不新增公网 Controller，不新增 User Confirm/Cancel HTTP，不让 Gateway 路由 coordinator。Coordinator 只提供上述内部 Service，不增加新的 HTTP 入口或 scheduler。现有 `PayTccReceiptService.prepare` 只能在 coordinator 持有原 XID 时注册 Pay receipt branch；User bridge 也必须复用原 XID。

配置 `cloudmall.pay.tcc-coordinator.enabled=false` 默认关闭；开启时必须同时满足 receipt、bridge、Seata 已启用，Pay DataSource 未被 AT 代理且本地事务管理器匹配。全局事务 timeout 默认 30000ms，可配置为正整数且上限为 120000ms。execute/recover 都拒绝已有 RootContext 或本地事务，自己绑定并在 finally 清理 XID。Coordinator 入口拒绝已有本 payNo 的 dispatch/receipt/execution 新建；恢复已有 execution 时只核对原 tuple，不要求当前 `pay_record` 仍为 PENDING。

## 4. 一次性执行顺序

1. 以 `payNo` 锁定原 `pay_record`，核对 user/order/amount、`debit_mode=TCC`、`status=PENDING`；若已有 dispatch、receipt 或 execution，首次 claim 必须拒绝，不能为旧尝试创建新 XID。16A/16B1 已有 dispatch/receipt 但没有 execution 也必须拒绝新 begin。
2. 独立本地事务插入 `pay_tcc_execution(state=CLAIMED,decision=NONE,xid=NULL)`；唯一冲突只核对既有 execution 原 tuple，不重新认领。
3. 只有 claim winner 可以 begin 全局事务。begin 成功取得 XID 后，立即以独立本地事务写入 execution；若响应丢失或写入前崩溃，写不出 XID 的 execution 永久 BEGIN_UNKNOWN。若测试/控制层稍后能提供同一真实 XID，只允许原 INSERT winner 将该 XID 绑定为 RUNNING；观察者、recover 和新调用者不能绑定或新 begin。
4. XID 持久化后，调用 Pay receipt Try 和 User bridge Try；两者必须携带同一 XID，所有 branchId 由对应 RM/Seata 上下文产生。
5. Pay receipt 与 User 都返回明确 TRYING 后，独立本地事务 CAS `decision NONE→COMMIT`，再通过已核验的 Seata `TransactionManagerHolder.get().commit(xid)` 对原 XID 发起 commit。若首次 live owner 观察到 User 明确失败、余额不足或两端无法同时证明 TRYING，则只能 CAS `NONE→ROLLBACK`，通过同一官方 TM API 对原 XID 发起 rollback；不能直接把 rollback 请求当成已取消终态。
6. TC commit/rollback 的 HTTP/RPC 响应丢失时保留 COMMIT_REQUESTED/ROLLBACK_REQUESTED，恢复只重试同一 XID、同一决议。

TM API 的具体调用必须以本地 Seata 1.5.2 JAR/源码为准；已核验 `TransactionManagerHolder.get().commit(xid)/rollback(xid)` 可对原 XID 决议。不得通过新 `GlobalTransaction.begin()`、新 XID 或客户端 body 猜决议。

## 5. 决议状态机和并发

### 5.1 初次 owner

初次 claim winner 独占 begin 和两分支 Try；该执行权不可转移，不使用 lease 过期接管。其他请求发现 CLAIMED/RUNNING/决议中状态只读取，不重新 begin 或 Try。

### 5.2 决议前

- Pay receipt 和 User 持久事实均为明确 TRYING：允许 `NONE→COMMIT`。
- 只有首次 live owner 在两端无法明确证明 TRYING，或观察到明确失败，才允许持久化 `NONE→ROLLBACK` 请求；这不是已取消结论，必须等待原 TC/两端事实收敛。
- recover 在 `decision=NONE` 时绝不新增决议，只观察原 XID、分支和 TC timeout；不能因“两端看起来 TRYING”自动 COMMIT，也不能仅因 RPC Unknown 自动 ROLLBACK。
- 没有 decision 记录的崩溃恢复保持 NONE/UNKNOWN，禁止接管一次性 Try 权限。

### 5.3 决议后

- COMMIT 只能重试原 XID 的 commit；任何 ROLLBACK 请求都冲突。
- ROLLBACK 只能重试原 XID 的 rollback；任何 COMMIT 请求都冲突。
- `COMMITTED/ROLLEDBACK` 是 TC 和两端业务事实都核对后的终态；TC Unknown 不能写终态。
- 终态不能被迟到 TRY、相反决议或新 branch 覆盖；持久错误和终态都必须锁内 CAS 防倒退。
- 终态必须同时核对 Pay receipt（本库同一快照和 Fence）以及 `bridge.recover` 返回的 User 完整 tuple/业务事实；未知或缺少任一端只能保持 UNKNOWN，不能声称 rollback 无分支成功。TC/Fence 记录已清理时，两个永久业务终态仍是必要证据。

## 6. 故障和 Unknown 语义

- begin 响应丢失且 XID 未落库：`BEGIN_UNKNOWN`，不重 begin；保留 execution 和测试现场。测试注入器若掌握真实 XID，只供清理/核验，不能把业务 execution 伪造为已恢复。
- XID 已落库、Pay receipt/User Try 前崩溃：恢复只读取原 XID 和原分支事实；不得新 Try。若无 decision，不自动 COMMIT。
- 一端 Try 成功、另一端响应未知：若已明确失败可请求原 XID rollback；否则保持 UNKNOWN/等待 TC。
- 决议请求响应丢失：保留 REQUESTED 状态，恢复重试同一决议。
- TC `getStatus=Unknown`：只记录观察结果，不推断资金或订单结果。
- 任一载荷、XID、branch、payNo 或金额冲突：返回 coordinator conflict，不修改原 execution。

## 7. 真实验收矩阵

使用真实 Seata 1.5.2 TC、Pay/User 独立进程、Nacos/Feign、独立 Pay/User MySQL：

1. 一次 execution claim、原 XID 持久化、Pay/User 两个 Try 分支和 COMMIT。
2. User 余额不足或 User Try 明确失败：原 XID ROLLBACK，receipt/attempt 无资金泄漏；Pay 不标记已取消直到 TC/事实核对。
3. 并发 execute/recover：只有一个 begin、一个 Pay receipt branch、一个 User branch。
4. begin 响应丢失/XID 未持久化：保持 BEGIN_UNKNOWN，禁止新 XID。
5. XID 已持久化但两分支 Try 未完整确认后进程退出：恢复不自动 COMMIT，只观察/按明确失败请求原 XID rollback。
6. COMMIT/ROLLBACK 请求响应丢失：原 XID、同一 decision 重试并最终核对 TC/两端事实。
7. TC Unknown、载荷冲突、相反决议、迟到 Try：不覆盖原 execution、不产生第二 branch。
8. 原有 16A bridge、16B1 receipt、Legacy 支付和 User 资金回归保持通过。

每个场景核对 execution、dispatch、Pay receipt、User receive/attempt/TCC、余额、ledger、Fence 和 TC 终态。未决 XID/TC Unknown 必须保留现场，禁止删除或猜决议。

## 8. 边界

- **GO**：Pay 内部 coordinator 一次性 begin、原 XID 锚定、两资源 Try、单向决议和原 XID 恢复。
- **NO-GO**：公网支付入口、Order/Stock、退款、Pay receipt 驱动订单成功和完整支付闭环。
