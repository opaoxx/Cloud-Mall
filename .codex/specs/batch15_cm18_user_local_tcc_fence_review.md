# CM-20261006-18：User LocalTCC 显式 Fence 适配评审

## 1. 结论

结论：**15A GO，采用 `useTCCFence=false` + 适配器显式 Fence 的小切片；不引入 PREPARED、未决恢复器或新的业务状态。15B 故障批后置。**

Seata 1.5.2 的官方 `TCCFenceHandler` 提供公开 Fence 操作。适配器关闭框架自动 Fence，在同一个 User 本地 `REQUIRED` 事务中显式调用 Fence，再调用已经验收的 `BalanceTccDebitService` 业务方法。空回滚时，TC 仍会调用适配器 Cancel；适配器执行 `rollbackFence` 后继续调用业务 Cancel，业务 Cancel 写永久 `CANCELLED` tombstone。Fence 的 `SUSPENDED` 和业务 tombstone 一起提交，Fence 后续清理不影响迟到 Try 的阻断。

本轮不扩展 `PREPARED` 状态，不加恢复扫描器，不修改 Pay，Pay 继续 LEGACY。Seata 1.5.2 JAR 和官方源码已确认公开 `prepareFence/commitFence/rollbackFence`，内部 `TransactionTemplate` 为 REQUIRED，可加入同一外层 User 事务；因此本切片可实施。进程中断各窗口、TC 暂失和重启恢复移到 15B，不与 15A 混验。

源码依据：<https://raw.githubusercontent.com/apache/incubator-seata/v1.5.2/tcc/src/main/java/io/seata/rm/tcc/TCCFenceHandler.java>。

## 2. 范围

### 纳入

- User 侧一个真实 `@LocalTCC` RM 资源适配器。
- `@TwoPhaseBusinessAction(useTCCFence=false)`，由适配器显式组合官方 Fence 和业务 callback。
- 同一 User DataSource、同一 `DataSourceTransactionManager`、同一 `REQUIRED` 外层事务。
- `prepareFence`、Try 业务、`commitFence`/Confirm 业务、`rollbackFence`/Cancel tombstone 的真实 TC 验收。
- 重复、乱序、空回滚和 Fence 清理后的永久业务归属校验。

### 排除

- Pay TCC、支付入口、Order/Stock 和全链路 Seata。
- PREPARED 状态、未决锚点、Fence 恢复扫描器和猜测式 Unknown 决议。
- 新 Confirm/Cancel HTTP/Feign；RM 回调只由 Seata TC 调用。
- 第二套自定义 Fence 表或自定义 Fence 状态机；唯一 Fence 来源是官方 `tcc_fence_log`。

15A 只覆盖真实 TC 正常提交/取消、重复/空回滚/乱序、业务失败原子回滚、Fence 清理后迟到 Try 和被拒分支安全 ACK。15B 再覆盖 User 进程中断各窗口、TC 暂失、重启和长期恢复。

## 3. 事务组合契约

适配器外层必须使用 User 的本地 `REQUIRED` 事务；官方 Fence handler 的调用也必须加入该事务，业务表和 `tcc_fence_log` 使用同一 DataSource。不能在独立连接、异步线程或另一个事务中调用 Fence 后再写 tombstone。

### 3.1 Try

适配器 Try 在一个外层事务内依次执行：

1. 从 Seata 上下文取得 XID、branchId 和业务参数；请求体不能覆盖上下文标识。
2. 调用官方 `prepareFence`。具体参数和返回值以 1.5.2 源码为准。
3. 仅当 Fence 允许当前分支时调用 `BalanceTccDebitService.tryDebit`；业务方法继续执行用户行、attempt、TCC 行和冻结额的既有锁序。
4. 业务结果和 Fence 写入同一事务提交；业务拒绝或异常整体回滚。

若 Fence 判断该分支已被回滚/悬挂，Try 返回失败且不得改变余额。不得绕过 Fence 直接调用业务 Try。LocalTCC proxy 的同 XID 重放可能注册第二 branch；业务归属的不可变 branchId 必须拒绝该分支，Fence Prepare 回滚，不能改动原分支。

### 3.2 Confirm

适配器 Confirm 在同一个外层事务内调用官方 Fence commit 操作和独立业务 callback：

- callback 只调用 `BalanceTccDebitService.confirmDebit`，写一次 `balance_ledger(DEBIT,APPLIED)` 并将业务状态置 `CONFIRMED`。
- callback 返回 boolean；载荷冲突、缺失账本或未知状态返回 false，让 TC 重试，不能吞掉异常后返回成功。
- Fence 已经是 committed 时，callback 仍必须按业务唯一键幂等核验原账本；不能仅凭 Fence 成功返回资金成功。
- Fence 或业务 callback 任一失败，外层事务回滚，TC 可重试。

### 3.3 Cancel

适配器 Cancel 在同一个外层事务内执行：

1. 调用官方 `rollbackFence`，保留 Fence 的 `SUSPENDED`/回滚事实。
2. 无论业务 Try 是否被执行，只要业务载荷和归属允许，都调用 `BalanceTccDebitService.cancelDebit`。
3. 已 TRYING 时释放冻结并变为 CANCELLED；空回滚时写完整载荷的永久 CANCELLED attempt tombstone，不回加余额。
4. Cancel callback 返回 boolean；未知、冲突或 Fence 操作失败返回 false，让 TC 重试。

这样空回滚不依赖 Seata 自动调用业务 Cancel，也不依赖 Fence 历史永久保留。业务 tombstone 清理前，迟到 Try 必须被唯一 payNo/ledgerKey/XID/branchId 归属阻断。

被拒第二 branch 的 rollback 只允许在以下条件同时满足时无资金副作用 ACK：Fence 明确为 `SUSPENDED`；持久归属证明 payNo/ledgerKey/operationKey/XID 已永久属于另一 branch；当前 `(xid,branchId)` 没有 attempt 行和 TCC 资金行。该 ACK 不得取消或释放原 branch。若 Fence 不存在且当前 branch 没有明确终态，不得猜测成功，返回 false 让 TC 重试。

## 4. Fence 调用限制

- 只使用本地 Seata 1.5.2 `TCCFenceHandler` 的公开 API；不得反射私有方法、复制 Fence SQL 或创建第二张 Fence 表。
- `useTCCFence=false` 只表示关闭框架外层自动 Fence，不能省略官方 Fence 调用。
- 每个 TCC 回调最多调用一次对应 Fence 方法；重复回调依据官方返回值和业务事实幂等处理。
- 不依赖 TC `getStatus=Unknown` 推断提交或回滚；Fence/业务事实不明确时返回 false 并等待重试。
- 所有 Fence 方法和业务 callback 必须在同一个 User 本地事务中执行；不得异步调用 callback。
- 适配器不得暴露 Spring MVC 映射；没有客户端 Confirm/Cancel HTTP 入口。
- Fence 行已被清理后，若当前 branch 的业务终态和完整载荷/账本核验一致，可返回同一终态幂等成功；不得推进 TRYING、创建新账本或生成未知事实。

## 5. 既有业务状态和互斥约束

继续使用第 14 轮已验收状态机：

- Legacy attempt：`APPLIED/REJECTED`，不能留下 `TRYING`。
- TCC attempt：`TRYING/CONFIRMED/CANCELLED`。
- TCC `operationKey=tcc-debit:<payNo>`，最终 `ledgerKey=payNo`；Legacy `ledgerKey=请求 operationKey`。
- `payNo`、`ledgerKey`、XID、`(XID,branchId)` 唯一约束继续阻止跨协议、跨用户和跨分支重复资金。
- 锁顺序继续为 User 行 → attempt 行 → Legacy ledger/TCC 行。
- Confirm/Cancel 不读取 `cloudmall.user.tcc-debit.enabled`；服务禁用后仍可清算已有事实。新 Try 是否开放仍由开关保护。
- 资源配置 `cloudmall.user.tcc-resource.enabled=false`、`seata.enabled=false`、`enable-auto-data-source-proxy=false` 默认关闭；开启资源必须同时启用 Seata，并校验 Fence 表、同 DataSource 和本地事务管理器。

## 6. 适配器和 callback 结构

- `service.BalanceTccDebitLocalResource`：唯一 `@LocalTCC` 适配器，固定签名为 `prepare(BusinessActionContext, @BusinessActionContextParameter(paramName="request") BalanceTccBranchDTO) boolean`、`commit(BusinessActionContext) boolean`、`rollback(BusinessActionContext) boolean`；XID/branchId 只能从框架上下文读取。
- `BalanceTccDebitBusinessCallback`：本地 bean，提供 `tryBusiness`、`confirmBusiness`、`cancelBusiness`；三者只调用既有 Service，不复制 SQL/余额逻辑，返回 boolean/业务结果。
- `BalanceTccBranchDTO`：只包含 `userId、payNo、orderNo、amount`；调用者不能覆盖 XID、branchId、operationKey、ledgerKey，后两者由冻结规则派生。
- 适配器和 callback 不调用 Pay、Order、Stock、Redis、RabbitMQ。

## 7. 真实验收矩阵

15A 必须使用真实 Seata 1.5.2 TC 和真实 User MySQL：

1. Try → Confirm：Fence、TCC 行、attempt、余额、冻结和一条 APPLIED ledger 一致。
2. Try → Cancel：冻结只释放一次，重复 Cancel 无变化。
3. Try 尚未执行即 TC 回滚：真实观察 Fence `SUSPENDED`，适配器仍调用业务 Cancel，生成永久 tombstone，无资金变化。
4. Cancel 后迟到 Try：官方 Fence 和业务 tombstone 都阻断 Try。
5. 重复 Confirm/Cancel、Confirm 后迟到 Cancel：TC 重试收敛，无双扣、双释放或账本删除。
6. 被拒第二 branch rollback 安全 ACK：Fence `SUSPENDED`、另一 branch 永久归属、incoming branch 无资金行时 ACK，不释放原 branch。
7. Fence 行清理后再次发送迟到 Try：业务 tombstone 仍阻断，不能依赖 Fence 行存在。
8. 同 payNo、不同 XID/branchId/ledgerKey 和跨用户并发：唯一约束与业务载荷校验阻断第二笔资金。
9. Pay LEGACY 支付、退款、余额不足和恢复回归保持不变；Pay 不注册 TCC branch。

每个场景必须核对 `mall_user`、`balance_debit_attempt`、`balance_tcc_debit`、`balance_ledger` 和 `tcc_fence_log`。仅 HTTP 响应、TC 返回值或 Fence 单表状态不足以宣称资金成功。

15B（后续批）再验证 User 进程在 Try/Confirm/Cancel 各窗口中断、TC 暂失、重启和长期恢复；15A 不以这些场景未完成为阻断。

## 8. 最终边界

- **GO**：真实 User LocalTCC RM + 官方显式 Fence + 同事务业务 callback/tombstone + TC 故障矩阵。
- **NO-GO**：Pay TCC 路由、Order/Stock 联动、HTTP Confirm/Cancel、第二套 Fence、Unknown 猜决议。
- 12B1 完成后只能宣称 User TCC RM/Fence 适配通过，CM-18 真实支付闭环仍未完成。
