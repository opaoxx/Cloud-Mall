# CM-18 16T83：Pay Legacy recovery context isolation

## 1. 结论与范围

GO。T82 只隔离 terminal 本地短事务；若 `process`、`initiate` 或 `recoverDue` 从外部 local transaction 或 Seata XID 调用，claim、读取、stage 与 RPC 仍可能继承 caller 上下文，terminal 后还可能等待 caller 持锁。

本批只在三个 public Legacy recovery 入口做前置上下文拒绝，不解绑、不结束、不提交 caller 事务。

## 2. 前置门

新增私有 `requireStandaloneLegacyRecovery()`，作为 `initiate`、`recoverDue`、`process` 的第一步，在任何 Mapper、intent 保存、扫描、claim、读取或 RPC 前执行：

- `TransactionSynchronizationManager.isActualTransactionActive()` 为 true，或
- `RootContext.getXID()` 非 null

任一成立即抛固定 Runtime `Legacy支付恢复不能继承事务上下文`；不调用 mapper、不 claim、不保存/更新 intent、不发 User/Order RPC，也不进入 catch retry。不得 unbind XID、暂停/提交/回滚 caller 事务或修改 caller ThreadLocal。

无上下文时保留原 mode gate、claim/token、lease、流程和返回协议。T82 terminal 内部 RootContext 防御门仍保留，作为后置保护，不替代本入口门。

## 3. 事务与保持项

正常 HTTP/API success、scheduler recovery 与内部 Java 调用必须在无 local transaction/Seata XID 下进入；已有 create `@Transactional` 不调用 `initiate` 的边界保持。无新增 SQL、DDL、依赖、API/DTO、状态字段、锁、RPC header、TCC/RM 或模块。

本批只拒绝明确继承上下文，不承诺 remote/source 读后写窗口、lease deadline、跨表 fencing、完整原子性或外部 caller 数据的提交/回滚语义。

## 4. 验收

- Unit：三个 public 入口分别覆盖 local active、global XID、两者同时存在；断言在第一步抛出、无 Mapper/intent/claim/retry 调用。测试 finally 清理自建 ThreadLocal，不解绑外部上下文。
- Owned MySQL：实际 local DataSource transaction caller 内调用 process，首个 User GET fault 场景改前会 claim/retry，改后入口直接拒绝且 owned PayRecord/intent 两表不变；caller 事务仍可由测试自行写入并提交，证明方法未结束它。global-only case 断言 zero mapper effects。
- 正常无上下文的 terminal、scan、mode isolation 与 T80–T82 回归保持；不启动完整 App/RM/TC，不伪称真实 Seata 全链路。

## 5. 交付边界

实现仅允许在 `PayConsistencyService` 三个 public 入口第一步增加共享上下文门、必要 JavaDoc/步骤注释及测试；不得修改 T82 terminal、claim、retry 或外部业务调用。
