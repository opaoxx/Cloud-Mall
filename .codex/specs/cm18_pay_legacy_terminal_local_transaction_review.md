# CM-18 16T82：Pay Legacy terminal local transaction

## 1. 结论与范围

GO。16T80 的 PayRecord owned update 与 intent owned finish 是两个 autocommit 操作；若前者已提交、后者返回 0 或异常，原 catch 无法回滚 PayRecord，且新 lease 可能被卡在无法完成的终态。

本批只把 Legacy 三个 terminal 的两表本地写包入一个短事务，不扩大事务到 claim、stage、retry、User/Order/RPC 或 TCC。

## 2. TransactionTemplate 配置

在 `PayConsistencyService` 注入同一 Pay 数据源的 `PlatformTransactionManager`，构造私有 `TransactionTemplate`：

- `PROPAGATION_REQUIRES_NEW`，使用本地 Pay `DataSourceTransactionManager`；
- 进入 TransactionTemplate 前检查 `RootContext.getXID()`；非 null 时直接 Runtime 拒绝，不进入 terminal 两表写、不解绑或修改调用者已有 XID。无 XID 时使用本地事务，不绑定 Seata、不加入全局事务、不依赖外部 caller 事务；
- manager 为 null 时构造器 fail fast，不提供五参数兼容 fallback；
- 不新增配置 Bean、模块、API、DDL 或独立事务服务。

## 3. 三个 terminal 原子块

`finishSuccess`、`finishRefunded`、`failWithoutDebit` 在当前 token/lease 前置条件已满足后，使用同一 TransactionTemplate 执行且仅执行：

1. 对应 PayRecord owned terminal UPDATE；
2. `finishOwnedLegacy` intent UPDATE。

两次结果均必须为 1；任一为 0 或抛异常，抛 Runtime 触发该本地事务回滚，不做同终态 readback/fallback，不在事务内调用 RPC、claim、stage、retry 或 User/Order。外层现有 catch 可按 token 执行后续 retry，但不伪称终态成功。

`finishRefunded` 前置的 owned `REFUND_PENDING` stage 保留在该短事务之外；User refund proof 与远程调用不放入短事务。三条终态的原状态集合、SET、paid_at/updated_at、token/deadline 条件保持。

## 4. 保持项与边界

不改 T79 token、T80/T81 owned SQL、claim/lease、非终态 stage、RPC 顺序、API/Feign/DTO、Redis、消息、TCC/RM。短事务只保证本地 Pay 两表终态写的 commit/rollback；不保证 read→RPC、RPC→stage、远程 source、跨数据源、lease fencing 或全局 Seata 原子性。

## 5. 验收

- Unit：fake `PlatformTransactionManager` 验证 REQUIRED 外部上下文不混入、REQUIRES_NEW 定义、两次 update 任一 0/异常回滚语义及 manager 缺失 fail-fast；不以 mock rollback 冒称 SQL rollback。
- Unit 另验证虚构 RootContext XID 时在进入 template 前拒绝且不 terminal write；测试 finally 清理自身 ThreadLocal，不解绑或声称启动真实 TC。
- Owned MySQL：真实 terminal 三路径在 PayRecord 已执行后注入 intent 0/异常，确认两表均保持非终态/原 lease，另行 owned retry 可提交；healthy 三路径两表终态一致，RPC 探针证明短事务不包远程调用。
- 验收真实同一 Pay DataSource、连接/事务状态及资源 finally 清理；不启动完整 App/RM/TC，不扩大到全局事务。
- 保留 T79–T81、Legacy/TCC 回归和 source checksum。

## 6. 交付边界

实现仅允许新增第 6 个构造器依赖、私有 TransactionTemplate 与三处终态短事务封装，更新现有 unit/owned IT 构造器和必要 JavaDoc；不得提供旧五参数无事务 fallback。
