# CM-20260924-20：普通订单恢复数据库异常隔离评审

## 1. 评审结论与范围

结论：**GO，限定隔离普通订单恢复任务的单条数据库异常，避免首条候选中断整批恢复或把数据库写入失败误当作业务失败再次处理。**

当前 `recoverStaleReservations` 在无保护的候选循环中执行 `claimRecovery`；首条 claim 或恢复过程的数据库异常会直接中断后续候选。`recoverOne` 又捕获所有 `Exception` 并调用 `recordFailure`，会把状态写入、订单查询等数据库异常误当作可记录的业务失败；当 `recordFailure` 自身失败时还可能遮蔽原始恢复证据。

本轮只调整普通恢复候选的异常边界，不修改恢复状态机、库存动作、租约语义、SQL、schema、Feign、恢复排序或其他服务。

## 2. 扫描与单条候选隔离

### 2.1 扫描查询边界

`findRecoverable(staleBefore, now, 50)` 保持在候选循环之外。扫描 SQL 的 `DataAccessException` 或其他异常继续向调度入口传播；没有可靠候选快照时不得猜测、重试或人为构造候选。

保留现有参数和时间语义：固定最多 50 条、创建时间超过 2 分钟、claim 租约 1 分钟、失败退避 1 分钟。

### 2.2 每条候选边界

每条候选必须独立包围 `claimRecovery` 与已成功 claim 后的 `recoverOne`：

- `claimRecovery` 返回非 1：继续下一条，不改变当前行。
- 当前候选任一数据库操作抛 `DataAccessException`：记录 warn 并继续下一条；不得执行本候选后续 RPC、库存动作或补偿写入，不强制清理租约，不猜测数据库提交结果。
- 非 `DataAccessException` 的 RuntimeException、Error 或中断语义不被本轮吞掉，继续沿既有外层异常路径传播；不得把所有 RuntimeException 视为数据库故障。

该隔离只保护候选之间的循环推进，不提供数据库全局故障下的成功保证，也不新增重试次数或后台补偿线程。

## 3. recoverOne 异常分类

`recoverOne` 保留原来的订单存在检查、Stock 状态查询、原订单号 reserve、rollback、状态判断和 `markReleased` 顺序。

### 3.1 数据库异常

在 `recoverOne` 的 catch 顺序中，`DataAccessException` 必须先于通用 `Exception` 处理，并重新抛给候选边界。数据库异常不得触发 `recordFailure`，不得再次发起库存 RPC，不得强制更新当前 intent。

适用范围包括本地订单查询、`forceCommitted`、`markReleased` 及其他 Mapper 写入/读取异常；候选边界统一 warn 后继续后续候选。

### 3.2 业务、RPC 和解析异常

非数据库异常继续保持现有单条业务失败语义：

1. 先以原异常记录 warn，保留 `orderNo` 和原异常证据；
2. 尝试一次既有 `recordFailure(orderNo, error, now+1 minute)`；
3. `recordFailure` 成功后结束当前候选，不重复 RPC 或重复写状态；
4. `recordFailure` 自身抛 `DataAccessException` 时交给候选边界隔离，只记录该数据库失败，不再次 `recordFailure`，也不覆盖已经记录的原业务异常日志。

库存 Feign、JSON 解析、非法状态、响应身份校验等非数据库错误仍可按既有一分钟退避处理。Stock 响应的订单号身份门沿 17AQ 保持不变。

## 4. 事实与租约边界

- claim 成功后发生数据库异常时，不强制释放或延长租约；让既有租约自然到期，后续扫描按原条件重新竞争。
- 不新增 owner、lease、attempts、状态或错误码字段，不改变 claim 的 SQL 条件和返回值。
- 不把“写入可能已提交后返回数据库异常”猜成成功/失败，不执行反向补偿或重复库存动作。
- `recordFailure` 数据库异常不应导致同一候选二次恢复、二次 RPC 或二次数据库写入。
- 保留 17AQ 的错单响应保护：身份校验失败属于业务异常，允许一次 `recordFailure`；若该写入本身数据库失败，则按本轮数据库边界隔离。

## 5. 验收矩阵

1. 单测验证首条 `claimRecovery`、`orderExists`、`forceCommitted`、`markReleased` 和 `recordFailure` 的 `DataAccessException`：后续候选仍继续，失败行不被猜测更新。
2. 单测验证扫描查询异常仍直接传播；claim 的非数据库 RuntimeException 和 Error 不被误吞；普通 RPC/解析异常仍只调用一次 `recordFailure`。
3. 单测验证 `recordFailure` 自身数据库失败时不二次记录、不重复调用 Stock，并保留原业务异常日志/调用顺序。
4. 真实隔离 MySQL 通过触发器或等价故障注入阻断首条 claim、`RELEASED` 写入和 `last_error` 写入：首条原事实及租约保持可核验，后续候选仍可完成原有 `RELEASED` 收敛，数据库异常不转成错误状态。
5. 回归 17AQ 身份校验、已提交订单 `forceCommitted`、固定 50 条扫描、2 分钟陈旧窗口、1 分钟 claim/退避、原订单库 checksum 和隔离库清理。

## 6. 明确不承诺

本轮不处理租约所有权校验、过期 worker、items 快照校验、线程中断、批次时钟或数据库全局不可用下的恢复保证；不保证网络发送或已提交事务可撤回。
