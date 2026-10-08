# CM-20260924-20：普通订单恢复中断边界评审

## 1. 评审结论与范围

结论：**GO，限定为普通 `OrderReservationRecovery` 的可观察中断检查和中断异常隔离。**

当前普通恢复任务没有检查线程中断：已设置中断标志时仍可能扫描、claim、查询库存、再次 reserve、rollback、释放 intent 或继续处理后续候选；阻塞调用抛出/包装 `InterruptedException` 后，通用异常路径还可能清除标志并写入 `recordFailure`。

本轮只增加安全检查点和中断异常分类，不修改 SQL、schema、50 条上限、2 分钟陈旧窗口、1 分钟 claim/退避、订单号快照、17AQ 身份门、状态机、RPC 字段或恢复线程拓扑。

## 2. 中断语义

### 2.1 可观察检查

使用 `Thread.currentThread().isInterrupted()` 检查，不主动清除中断标志。检查发现中断时立即从当前恢复任务返回：

- 不再扫描或 claim 后续候选；
- 不调用后续 Stock/RPC、Mapper 写入或 `recordFailure`；
- 不清理/延长已有 lease，不猜测数据库或远程动作结果；
- 保留当前行和已提交事实，交由 lease 到期后的后续扫描处理。

必须覆盖以下检查点：

1. 扫描 `findRecoverable` 前；
2. 每个候选 `claimRecovery` 前；
3. `claimRecovery` 返回后、进入 `recoverOne` 前；
4. `orderExists` 返回后、`forceCommitted` 前；
5. 每次 Stock 状态 GET 前和返回后；
6. JSON snapshot 解析完成后、reserve 前；
7. reserve 前和返回后；
8. rollback 前和返回后；
9. `markReleased`/`forceCommitted`/`recordFailure` 等完成写入前。

检查点只提供可观察的停止边界，不能撤回已经发出的 RPC、已经完成的数据库提交或确认结果未知的在途操作。

### 2.2 InterruptedException 与异常 cause

阻塞调用直接抛出 `InterruptedException`，或抛出异常的 cause 链中明确包含 `InterruptedException` 时：

- 恢复线程中断标志（`Thread.currentThread().interrupt()`）；
- 立即返回，不调用 `recordFailure`、不继续后续候选、不重复 RPC；
- cause 遍历必须安全处理 null 和循环引用，不能无限遍历。

候选层的 `DataAccessException` catch 和 `recoverOne` 的通用异常 catch 都必须先检查当前中断标志或明确的 `InterruptedException` cause，再决定是否记录数据库/业务失败。中断优先返回，不能被 17AR 的单条数据库隔离或普通业务失败记录吞掉。

`SocketTimeoutException`、一般 `InterruptedIOException`、Feign timeout 或其他普通超时不自动视为线程中断；没有当前中断标志且 cause 链没有 `InterruptedException` 时，保持原业务失败和一分钟退避语义。

## 3. 与既有恢复流程的关系

### 3.1 扫描与 claim

- 扫描前已中断：不调用 `findRecoverable`。
- 读取候选后、claim 前已中断：不 claim 当前或后续候选。
- claim 成功后检测到中断：不进入后续订单/Stock动作；已取得 lease 保留原事实，等待自然到期。
- claim/扫描数据库异常仍沿 17AR 分类处理；若同时检测到中断，优先恢复标志并停止，不追加失败写。

### 3.2 订单与库存操作

每次查询或写入前后都只检查中断，不改变原调用顺序。中断发生在 Stock GET 返回后、reserve 返回后或 rollback 返回后时，不根据“可能已执行”推断反向动作；不额外 rollback、不重复 reserve、不 `markReleased`。

内部 Feign 409/明确拒绝分支也必须先检查中断，避免吞掉中断后继续第二次状态 GET或释放流程。合法原订单号身份校验继续沿 17AQ 执行。

### 3.3 完成与失败记录

在 `markReleased`、`forceCommitted` 或 `recordFailure` 前发现中断时，不发起该写入。若写入已经开始后才发生中断或结果未知，不重试、不猜状态、不补写相反状态；沿现有 lease/恢复事实等待后续扫描。

非中断的业务、RPC、解析和身份错误仍按既有一次 `recordFailure` 与一分钟退避处理；17AR 的数据库异常隔离继续适用。中断返回路径不得记录失败，也不得改变原错误元数据。

## 4. 明确保留与排除

- 保留原 `findRecoverable` 扫描失败传播、单条 `DataAccessException` 隔离、非数据库 Runtime/Error 传播规则。
- 保留 `reserve`/`rollback`/`markReleased`/`forceCommitted` 的既有状态条件和订单号；不新建重试线程，不延长 lease。
- 不保证数据库、Redis、Feign、Rabbit 或事务链路的全程取消，不保证已发请求可撤回。
- 不把普通 Socket timeout 当作中断，不扩展到 Stock dispatcher、秒杀恢复或其他消费者；这些组件的中断策略另行评审。

## 5. 验收矩阵

1. 入口预中断：不执行扫描、claim、RPC、Mapper 写入，线程标志保持。
2. 各检查点设置中断：覆盖候选 claim 前后、订单查询前后、Stock GET 前后、解析后、reserve/rollback 返回后和完成写前；后续动作和后续候选均不执行。
3. claim 成功后中断：当前 intent 的身份、状态、错误字段和 lease 保持可核验，不强制清理；后续候选不 claim。
4. 已发 reserve/rollback 或已提交写入后才中断：不撤回、不重复、不猜测结果，验证下一轮仍按原事实恢复。
5. 包装 `InterruptedException` 覆盖 claim、订单查询、Stock GET、JSON/解析、reserve、rollback、完成写和失败写路径；恢复线程标志，不 `recordFailure`，不处理后续候选。
6. 普通 `SocketTimeoutException`、`InterruptedIOException` 和 Feign timeout 在无中断标志/无 `InterruptedException` cause 时仍执行原一分钟退避；Error 和非数据库 claim RuntimeException 继续沿 17AR 传播。
7. 真实隔离 MySQL 验证入口中断无数据库变化、claim 后中断保留 lease/状态、后续候选可在下一轮处理；原订单库 checksum 和隔离库清理保持。

## 6. 明确不承诺

本轮只提供恢复任务内的可观察 checkpoint，不提供跨服务取消、网络原子性、已提交事务回滚或分布式 exactly-once 保证。
