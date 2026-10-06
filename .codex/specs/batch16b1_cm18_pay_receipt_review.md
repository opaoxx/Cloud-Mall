# CM-20261006-18：16B1 Pay LocalTCC Receipt 评审

## 1. 结论

结论：**GO，实施 Pay 自身 LocalTCC receipt/Fence 状态机，并与 16A User bridge 做真实双资源 TC 提交/取消验收；不切对外支付入口，不接 Order/Stock。**

16B1 的 receipt 是 Pay 资源事实，由 TC 驱动 `TRYING/CONFIRMED/CANCELLED`，不是 User 状态镜像。`PayTccReceiptService.prepare(PayTccDispatchDTO)` 只读取当前 RootContext XID，调用 16A dispatch 的仅持久化原 XID/载荷锚点方法，再独立提交 receipt `CLAIMED`，最后由唯一 winner 注册 Pay LocalTCC Try；它不自动调用 User、不 begin/commit 全局事务、不运行 scheduler。16A User dispatch 作为独立调用供两资源测试组合使用。

Pay 对外支付入口、Legacy 余额主路由、Order/Stock、Pay receipt 主编排和退款均不在本轮切换。

## 2. 固定顺序和边界

真实测试顺序固定为：

`PayTccReceiptService.prepare` → Pay receipt LocalTCC Try 注册 → 独立调用 User bridge dispatch/唯一 POST → TC 对 Pay/User 两个 RM 分支统一 commit 或 rollback。

`PayTccReceiptService.prepare` 先调用 16A bridge 的 anchor prepare/Store.claim，仅独立持久化 `pay_tcc_dispatch`，不调用 User、不注册 branch；随后 receipt CLAIMED 的唯一 winner 才调用 Pay LocalTCC `prepare` 注册 branch。并发或重放 receipt prepare 只返回既有事实，永不注册第二 Pay branch。Pay Try 注册前崩溃会永久保留 CLAIMED，后续不能换 XID 或新 receipt 猜测恢复。

新 receipt 只能绑定原 Pay `pay_record` 的精确 `payNo/userId/orderNo/amount`、`debit_mode=TCC`、`status=PENDING`，并且必须绑定同 XID 的 `pay_tcc_dispatch(state=PREPARED)`；已有 receipt 重放只核对这些事实，不重新派发或创建新 XID。

本轮不新增 User Confirm/Cancel HTTP，不新增第二张 dispatch 表，不修改 Pay `/api/payments`。16A 的 User POST/GET、内部认证和 Gateway 隔离继续适用。

## 3. `pay_tcc_receipt` 契约

```sql
CREATE TABLE IF NOT EXISTS pay_tcc_receipt (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  operation_key VARCHAR(128) NOT NULL,
  ledger_key VARCHAR(128) NOT NULL,
  branch_id BIGINT,
  state VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pay_receipt_pay(pay_no),
  UNIQUE KEY uk_pay_receipt_xid(xid),
  UNIQUE KEY uk_pay_receipt_operation(operation_key),
  UNIQUE KEY uk_pay_receipt_ledger(ledger_key)
);
```

状态只允许：

- `CLAIMED`：Pay façade 已绑定原 dispatch，但 Pay branch 尚未注册。
- `TRYING`：Pay LocalTCC Try 已绑定 branch 并提交业务事实。
- `CONFIRMED`：Pay LocalTCC Confirm 已提交。
- `CANCELLED`：Pay LocalTCC Cancel 已提交；空回滚也必须留下取消事实。

状态推进必须带原 payNo/XID/载荷和当前状态条件。`CONFIRMED/CANCELLED` 不回退、不互相覆盖；TC/网络 Unknown 不创建新状态、不生成新 XID，等待原 branch 重试。

## 4. Java 资源和事务契约

- `PayTccReceiptService.prepare(PayTccDispatchDTO)`：从 RootContext 取原 XID，调用 dispatch anchor prepare，独立提交唯一 `CLAIMED`，然后唯一 winner 调用 Pay LocalTCC `prepare(null,request)`；不 begin/commit 全局事务、不调用 User、不启动 scheduler。
- `PayTccReceiptLocalResource`：Pay 侧 `@LocalTCC` 资源，固定 action name `cloudmallPayDebitReceipt`、`useTCCFence=false`，在同一 Pay DataSource、本地 `REQUIRED` 事务中显式调用官方 Seata 1.5.2 Fence API。
- `PayTccReceiptBusinessCallback.tryBusiness`：校验当前 Seata XID/branch 与 CLAIMED 一致，CAS `CLAIMED → TRYING`；不调用 User。16A User dispatch 由测试/后续编排以同一 XID 独立注册。
- `confirmBusiness`：CAS `TRYING → CONFIRMED`，不扣 User 余额、不写 User ledger、不调用 Order。
- `cancelBusiness`：CAS `CLAIMED/TRYING → CANCELLED`，无 Pay 资金副作用；重复和空回滚幂等。
- `PayTccReceiptVO`：返回 payNo、原 XID、branchId、state、operationKey、ledgerKey；不对前端开放。

Pay receipt 的 Fence、receipt 表和 Pay dispatch 使用同一个 Pay DataSource；不得使用 AT 自动代理。Fence 行为必须沿用 15A 已核验的官方 handler 组合规则。配置 `cloudmall.pay.tcc-receipt.enabled=false` 默认关闭；开启时必须同时启用 Pay bridge、Seata，并关闭 AT 自动数据源代理。

## 5. 与 User 16A 的关系

16B1 不自动调用 User 16A dispatch。两资源真实组合测试按以下顺序分别调用：

1. 复用同一 XID，不创建新全局事务。
2. User dispatch 仍通过 `user_tcc_receive(CLAIMED)` 唯一认领，User LocalTCC 注册唯一 User branch。
3. Pay receipt Confirm/Cancel 和 User Confirm/Cancel 都由同一 TC 决议，任何一方 callback false 或异常都回滚各自本地事务并等待 TC 重试。
4. 已有 CLAIMED receipt 但 branch 仍为空时，TC 空 Cancel 可以绑定当前原分支并永久写 `CANCELLED`；receipt 行完全不存在时返回 false，不得凭空创建 receipt 或支付事实。CLAIMED 本身不证明 branch 尚未注册，必须读取 branch/资金事实。

被拒第二 Pay branch 只有在 Fence 明确为 `SUSPENDED`、当前 branch 没有 receipt 行、且存在另一 branch 的确定永久 receipt owner 时才允许无资金副作用 ACK；不得取消或覆盖原 branch。Fence 清理后没有本 branch 终态证据时返回 false，交 TC 重试。

## 6. 状态和异常规则

- Pay receipt `TRYING` 只表示 Pay 侧资源已受理；不表示支付成功。
- User `PAYMENT_FAILED`、`BALANCE_TCC_UNKNOWN` 或普通 HTTP 4xx 没有持久拒绝事实时，Pay dispatch 保持 UNKNOWN；不能写 receipt `CANCELLED` 猜测回滚。
- 已有 `CONFIRMED/CANCELLED` 的 receipt 收到迟到 Unknown/TRY 不回退。
- 原 XID、payNo、金额、订单、用户、operationKey、ledgerKey 或 branch 不一致返回 `PAY_TCC_RECEIPT_CONFLICT`，不自动修复、不创建新 receipt。
- TC `getStatus=Unknown` 不作为资金决议；仅 Fence、Pay receipt、User attempt/TCC/ledger 的持久事实可用于幂等核验。

## 7. 16B1 验收矩阵

必须使用真实 Seata 1.5.2 TC、Pay/User 独立进程、Nacos/Feign、独立 Pay/User MySQL：

1. `prepare → Pay Try → User Try → TC commit`：Pay receipt `CONFIRMED`，User 资金事实一条 APPLIED，两个 branch 同一 XID。
2. `prepare → Pay Try → User Try → TC rollback`：Pay receipt `CANCELLED`，User 冻结释放或空回滚 tombstone，均无双释放。
3. 并发 prepare/重复 Pay Try：只一条 CLAIMED、一条 Pay branch，重放不注册第二 branch。
4. Pay Try/User POST 响应未知：原 dispatch/XID 只 GET，不能重发 User POST，TC 重试后两端收敛。
5. Pay receipt Try/Confirm/Cancel 本地事务失败：Fence 与 receipt 状态一起回滚，TC 重试后只收敛一次。
6. 空回滚、乱序和 Fence 清理后迟到回调：永久 receipt/dispatch 事实阻断复活。
7. XID/载荷/branch 冲突、User 余额不足：原事实保留，不能创建新 XID 或错误终态。
8. 现有 Pay Legacy、User 26 项资金测试和 16A bridge 回归保持通过；对外支付入口仍不进入 TCC。

## 8. 边界

- **GO**：Pay LocalTCC receipt/Fence、CLAIMED 唯一认领、16A User bridge 双资源 TC 验收。
- **NO-GO**：Pay receipt 驱动对外支付成功、Order/Stock、退款、主恢复器和完整支付闭环。
- 本轮完成后只能宣称 Pay/User 两端 TCC 资源事实可由同一 TC 收敛，CM-18 真实支付闭环仍未完成。
