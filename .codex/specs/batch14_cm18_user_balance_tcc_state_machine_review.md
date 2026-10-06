# CM-20261006-18：User 余额 TCC 就绪状态机评审

## 1. 评审结论

结论：**GO，实施 User 侧完整本地资金状态机和不可变尝试归属；本轮不接真实 Seata TCC 回调。**

本轮把余额冻结、确认、取消、互斥和幂等事实做成可独立测试的 User 模块。`Try/Confirm/Cancel` 是 User Service 内部事务契约，由单元和真实 MySQL 状态机测试直接验证；不新增 HTTP 编排入口，不接受客户端伪造 Confirm/Cancel，不宣称 TC 已接通。下一轮再把同一契约绑定到 Seata 1.5.2 `@LocalTCC(useTCCFence=true)` RM。

Pay 继续只使用 `LEGACY`，支付路由不改。`cloudmall.user.tcc-debit.enabled=false` 默认关闭：关闭时拒绝新的 TCC Try，但对已经存在的冻结记录，Confirm/Cancel 清算方法必须按持久状态继续工作，尤其 Cancel 必须能够释放冻结，不能因为开关关闭造成冻结泄漏。

## 2. 本轮边界

### 2.1 纳入

- `mall_user.frozen_balance` 兼容迁移。
- 共享 `balance_debit_attempt` 归属表，保证同一 `payNo` 在 LEGACY/TCC 间全局互斥。
- `balance_tcc_debit` 冻结状态表。
- User 侧 PO、DTO、Mapper、Service 方法和状态机测试。
- 用户行锁、尝试归属锁、TCC 行/Legacy 账本锁的固定顺序。
- 空回滚/迟到 Try/重复确认/重复取消/确认后取消的持久状态契约，为下一轮 Seata Fence 接入提供准确边界。

### 2.2 不纳入

- Pay `PayConsistencyService`、Pay receipt、真实支付入口或回调路由。
- Seata TC、`@LocalTCC` 注解和 Fence 回调接入；本轮不伪造 TC 验收。
- 新增 Confirm/Cancel HTTP/Feign 端点；本轮没有客户端可调用的 TCC API。
- Order/Stock、退款和现有 Legacy 账本业务规则的重写。
- Redis 余额缓存、前端余额字段和公共用户响应契约。

## 3. 数据库契约

### 3.1 `mall_user.frozen_balance`

```sql
ALTER TABLE mall_user
  ADD COLUMN frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00;
```

迁移先检查列是否存在，重复初始化不得失败。历史用户冻结额回填 `0.00`。Legacy 扣款仍减少 `balance`；冻结额只由 TCC 状态机改变。

### 3.2 共享 `balance_debit_attempt`

```sql
CREATE TABLE IF NOT EXISTS balance_debit_attempt (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  operation_key VARCHAR(128) NOT NULL,
  mode VARCHAR(16) NOT NULL,
  xid VARCHAR(128),
  branch_id BIGINT,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_balance_attempt_pay_no(pay_no),
  UNIQUE KEY uk_balance_attempt_operation(operation_key),
  UNIQUE KEY uk_balance_attempt_branch(xid,branch_id),
  KEY idx_balance_attempt_user_state(user_id,state,updated_at)
);
```

`mode` 为 `LEGACY` 或 `TCC`；`state` 为 `TRYING`、`CONFIRMED`、`CANCELLED`。余额不足的 Try 整笔本地事务回滚，不写失败归属。新 Legacy 扣款也必须在用户行锁后认领该表，避免同一 payNo 与 TCC 并发。Legacy 的 `operationKey` 不保证等于 `payNo`，因此互斥检查必须按 `pay_no` 查询 `balance_ledger` 的 DEBIT 事实，不能只查 `operation_key=payNo`。历史 `balance_ledger` 没有归属行时，读取阶段按 `pay_no` 查账本并视为既有 Legacy 事实；新请求必须先插入归属行，唯一键冲突时完整校验用户、订单、金额、模式和 operationKey。

### 3.3 `balance_tcc_debit`

```sql
CREATE TABLE IF NOT EXISTS balance_tcc_debit (
  id BIGINT PRIMARY KEY,
  operation_key VARCHAR(128) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  branch_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  pay_no VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  state VARCHAR(16) NOT NULL,
  balance_before DECIMAL(18,2) NOT NULL,
  balance_after_try DECIMAL(18,2) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_balance_tcc_operation(operation_key),
  UNIQUE KEY uk_balance_tcc_xid(xid),
  UNIQUE KEY uk_balance_tcc_branch(xid,branch_id),
  UNIQUE KEY uk_balance_tcc_pay_no(pay_no),
  KEY idx_balance_tcc_user_state(user_id,state,updated_at)
);
```

`state` 只允许 `TRYING`、`CONFIRMED`、`CANCELLED`。`operation_key` 固定为 `tcc-debit:<payNo>`，重试不能生成新键。TCC 行和共享尝试行必须在同一 User 数据库事务内写入。

## 4. 锁顺序和互斥规则

所有 Legacy/TCC 余额写方法固定使用以下顺序：

1. `SELECT mall_user ... FOR UPDATE` 锁用户行。
2. 按 `pay_no` 锁 `balance_debit_attempt`；不存在时检查 Legacy `balance_ledger.pay_no` 和 TCC `operation_key/pay_no`，再插入唯一归属。
3. 按 `pay_no` 查询并锁定 Legacy DEBIT 事实；再按 `operation_key` 锁 TCC 行。Legacy 的操作键可能不是 payNo，不能用单一 operationKey 查询替代 payNo 互斥检查。
4. 在同一事务中更新余额、冻结额和状态。

禁止先锁 TCC 行再锁用户行，也禁止 Confirm/Cancel 与 Legacy refund 使用相反顺序。不同用户使用相同 payNo 时，唯一键冲突必须转为明确冲突；不能因为用户行不同而产生两笔资金事实。

同一 payNo 的 Legacy 归属会阻止 TCC Try；同一 payNo 的 TCC 归属会阻止 Legacy debit。载荷中 userId、orderNo、amount、operationKey、mode、xid 任一不一致返回 409，不能复用已有扣款。

## 5. User 内部状态机契约

### 5.1 `tryDebit`

Java 内部方法建议为 `tryDebit(BalanceTccDebitTryDTO request)`，不暴露 HTTP：

1. 校验正金额、用户状态、payNo、operationKey、XID 和 branchId 非空；本轮本地可信方法接受并持久化 XID/branchId，下一轮再绑定 Seata 上下文。
2. 若 `tcc-debit.enabled=false`，对新请求返回 `PAY_DEBIT_MODE_UNAVAILABLE`，不写任何资金数据。
3. 锁用户 → 共享归属 → Legacy DEBIT 事实 → TCC 行。已有同键 `TRYING` 返回幂等成功；`CONFIRMED/CANCELLED` 返回当前终态；不同载荷/XID/branchId 返回冲突。
4. 发现 Legacy 账本或 Legacy 尝试归属时拒绝 TCC Try；发现同 payNo 不同 XID 时拒绝。
5. 只有 `status=1` 且 `balance >= amount` 时，执行 `balance -= amount`、`frozen_balance += amount`，插入 `balance_debit_attempt(mode=TCC,state=TRYING)` 和 `balance_tcc_debit(state=TRYING)`；XID/branchId 在两份归属事实中不可变。
6. 余额不足返回 409，并使整笔本地事务回滚；不写归属、不改变余额/冻结额、不写 APPLIED ledger，余额变化后可用相同 operationKey 重试。

Try 成功只表示冻结成功；不能写 `balance_ledger(DEBIT,APPLIED)`，不能写 Redis，也不能推进 Pay/Order。

### 5.2 `confirmDebit`

本轮只定义 Service 方法，不对外暴露：

1. 不受 `tcc-debit.enabled` 开关阻断，允许清算已存在的 TCC 冻结。
2. 使用用户行 → 归属行 → TCC 行锁序。
3. 仅 `TRYING` 可确认：减少 `frozen_balance`，不再次减少 `balance`，写一条 `balance_ledger(DEBIT,APPLIED)`，再将 TCC 和归属状态都设为 `CONFIRMED`；`APPLIED` 只属于 `balance_ledger` 的 state，不属于 TCC 归属表。
4. 已 `CONFIRMED` 的重复确认只返回原事实；账本已存在时完整核验载荷，不能重复插入或减少余额。
5. TCC 行缺失、归属缺失或状态不明时返回未知/冲突，不能凭请求体创建 APPLIED 账本。

### 5.3 `cancelDebit`

本轮只定义 Service 方法，不对外暴露：

1. 不受 `tcc-debit.enabled` 开关阻断，确保服务禁用后仍能释放已存在冻结。
2. 使用用户行 → 归属行 → TCC 行锁序。
3. `TRYING` 转 `CANCELLED`，执行 `balance += amount`、`frozen_balance -= amount`，更新条件必须带 `frozen_balance >= amount`。
4. 已 `CANCELLED` 重复取消不再加回余额；已 `CONFIRMED` 的迟到取消不得释放余额或删除 APPLIED ledger，返回冲突。
5. 缺少业务 TCC 行时不能直接判断未冻结。若不存在归属行，Cancel 必须以完整 user/pay/order/amount/operationKey/xid/branchId 载荷创建 `balance_debit_attempt(mode=TCC,state=CANCELLED)` tombstone，且不改变余额；若已有归属但无业务行，仍返回 UNKNOWN/409，不能盲目加回。下一轮接 Fence 后，Fence `SUSPENDED` 与该 tombstone 共同证明空回滚。

## 6. 空回滚与防悬挂边界

本轮不伪造 Fence 行为，只冻结下一轮接入所需的业务语义：

- Cancel 先到：没有业务行时写入完整载荷 tombstone，不能产生余额回加；下一轮 Fence 会以 `SUSPENDED` 记录空回滚，后续 Try 必须看到两份事实并拒绝冻结。
- Cancel 后迟到 Try：持久化的 `CANCELLED` 归属或 Fence `SUSPENDED` 阻止 Try；不能创建新的 operationKey。
- 同 XID 重入：唯一 `(xid,branch_id)` 和 `(pay_no)` 归属阻止第二个分支；下一轮必须把 Seata branch 回调幂等映射到同一 operationKey。
- 同 payNo 换 XID：返回冲突，不能以新 XID 重新 Try。
- Fence 未来回收：Fence 的 `SUSPENDED` 是空回滚证据，但不是永久业务状态；业务归属行仍必须持久化 Cancelled/拒绝状态，防止 Fence 历史清理后旧 Try 复活。

## 7. PO、DTO、Mapper 和 Service 范围

- `BalanceDebitAttemptPO`：映射共享归属表，包含 payNo、operationKey、mode、xid、branchId、userId、orderNo、amount、state 和时间字段。
- `BalanceTccDebitPO`：映射冻结状态表，包含 operationKey、xid、branchId、业务载荷、冻结前/冻结后余额和状态。
- `BalanceTccDebitTryDTO`：只承载 userId、payNo、orderNo、operationKey、xid、branchId、amount；Confirm/Cancel DTO 只供本地资源契约使用，不能作为 HTTP 请求 DTO。
- `BalanceTccDebitFinalizeDTO`：承载同一完整载荷和目标回调类型；只能由本地资源适配器构造，不能从 HTTP body 绑定。
- `BalanceTccDebitVO`：只返回 state、operationKey、payNo、xid 脱敏摘要和 branchId，不返回 PO 或余额内部快照。
- `BalanceTccDebitService`：固定提供 `tryDebit(BalanceTccDebitTryDTO)`, `confirmDebit(BalanceTccDebitFinalizeDTO)`, `cancelDebit(BalanceTccDebitFinalizeDTO)`；三方法均使用 `REQUIRED` 本地事务，Confirm/Cancel 不受 enabled 开关阻断。
- Mapper 提供锁用户、锁归属、锁 TCC 行、按 Legacy payNo 查询、插入唯一归属、条件 Try/Confirm/Cancel、插入/核验 APPLIED ledger 等语义方法；SQL 不进入 Service。
- Service 方法只编排上述锁和状态迁移；不得调用 Pay、Order、Stock、Redis 或消息系统。

## 8. 配置和验证范围

- `cloudmall.user.tcc-debit.enabled=false` 默认关闭；关闭时新 Try 必须无资金副作用，Confirm/Cancel 仍可清算已存在冻结。
- Pay `cloudmall.pay.debit-mode` 保持 `LEGACY`，本轮不改 Pay 代码或接口。
- 迁移必须在已有 User 数据库上重复执行通过；`frozen_balance`、归属表和 TCC 表建立失败时启动/初始化明确失败，不能静默跳过。

必须通过本地状态机和真实 MySQL 测试：

1. Try 成功、余额不足、用户禁用、重复 Try。
2. 同 payNo Legacy/TCC 互斥、同 payNo 不同 XID、同键不同金额/订单/用户冲突。
3. Confirm 成功和重复 Confirm：只一条 APPLIED ledger，余额只扣一次，冻结归零。
4. Cancel 成功和重复 Cancel：余额只释放一次，冻结归零；服务开关关闭仍可释放。
5. Confirm 后 Cancel、Cancel 后迟到 Try、缺少业务行的未知保护。
6. 并发 Try/Legacy debit/退款，验证用户行 → 归属 → 账本统一锁序、无死锁和无负冻结。
7. Legacy 原有余额扣款、退款、Redis 加速缓存和 Pay 回归继续通过。

本轮不做 TC 进程中断、Seata callback、Fence 回收或 Pay 全链路验收；这些属于 12B2/12C。

## 9. 最终边界

- **GO**：User 本地资金状态机、共享尝试归属、冻结/确认/取消、Legacy/TCC 互斥和真实 MySQL 验收。
- **NO-GO**：真实 Seata TCC RM 回调、Fence 接入、Pay TCC 编排、Order/Stock 联动和支付入口切换。
- 本轮完成后只能宣称“User TCC 就绪状态机已验证”，不能宣称 CM-18 真实支付闭环完成。
