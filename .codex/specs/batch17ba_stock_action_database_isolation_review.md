# CM-20260924-20：库存动作恢复数据库异常隔离评审

## 1. 评审结论与范围

结论：**GO，限定隔离 `OrderStockActionRecovery` 的单条数据库异常，避免首条动作阻断整批恢复或把本地写失败误当作远端未知结果再次写入。**

当前 `recoverDue` 直接循环执行 claim/recoverOne；首条数据库异常会阻断后续动作。`recoverOne` 的通用 RuntimeException catch 还会把 `finish` 的数据库异常转成 `retry`，可能在完成写结果未知时重复更新或覆盖事实。

本轮只增加候选级数据库异常边界，继承 17AZ 的逐候选 UUID token 和 finish/retry 有效 lease fencing；不修改 SQL/schema、findDue/claim 条件、状态、attempts、30 秒 lease、退避、操作键、时间、Feign、Stock 调用或中断语义。

## 2. 扫描与候选隔离

### 2.1 findDue

`actionMapper.findDue(now, 50)` 保持在循环外。扫描 SQL/数据库异常继续向调度入口传播，不构造候选、不猜测状态、不执行 Stock 或 Mapper 补偿写。

### 2.2 每条候选

每条候选必须独立包围本次 UUID token 的 `claim` 和成功 claim 后的 `recoverOne(action, claimOwner)`：

- claim 返回非 1：继续下一条，丢弃 token；
- claim 或 recoverOne 内任一 `DataAccessException`：记录 warn 后继续下一条，不清理/延长 lease，不追加 RPC，不猜测提交结果，不再写 retry；
- claim 自身抛出的非数据库 RuntimeException 或 Error：沿原异常路径传播并停止批次；不将所有 RuntimeException 视为数据库故障；
- recoverOne 内既有业务、RPC、解析、身份、状态异常仍按第 3.2 节的一次 UNKNOWN retry 处理。

候选级隔离不承诺数据库全局故障下的成功，也不增加重试线程或次数。

## 3. recoverOne 异常分类

### 3.1 数据库异常优先

`recoverOne` 的 catch 顺序必须在通用 RuntimeException 前捕获 `DataAccessException` 并原样抛给候选边界。适用 `finish` 成功/冲突分支、首 GET/写后 GET 相关本地操作以及 `retry` 写入。

数据库异常不得进入 `retry`，不得发起第二次 Stock 动作，不得因完成写异常再写 UNKNOWN；候选边界只记录并继续后续候选。

如果 `finish` 已在数据库提交后返回异常，保持数据库已提交结果，不猜测、不补写、不重复动作；该事实由后续有效 token 扫描按 17AZ fencing 处理。

### 3.2 非数据库业务/RPC异常

非数据库 RuntimeException 继续沿既有流程：

1. 先记录包含原异常和 action 身份的 warn；
2. 使用本次 `claimOwner` 调用一次 `actionMapper.retry`，沿原 UNKNOWN/退避算法；
3. `retry` 成功后结束当前候选，不重复 RPC；
4. `retry` 自身抛 `DataAccessException` 时交由候选边界隔离，不二次 retry、不换 token、不覆盖原异常证据。

17AV/17AW/17AX 身份、类型、操作键失败均属于该业务异常路径；17AZ 的 lease 到期或 token 被接管导致 retry 0 行时，不追加其他写入或猜测成功。

## 4. 既有 fencing 与动作边界

- 每条候选的 UUID token 必须继续用于 claim、全部 finish 和 retry；不能因为数据库异常换用实例 owner 或新 token。
- finish/retry 的 `lease_owner`、`lease_until > now`、状态和 BINARY 比较由 17AZ 保持；0 更新不是异常成功，也不触发额外 retry。
- Stock/RPC 已执行或在途时不能撤回；数据库异常不触发相反动作。
- 不修改 findDue/claim 的 `<` 到期条件、attempts+1、30 秒 lease、LIMIT/排序、nextRetry 和状态枚举。

## 5. 验收矩阵

1. 真实隔离 MySQL 触发器阻断首条 claim、finish SUCCEEDED、finish CONFLICT（首 GET/第二 GET 两方向）和 retry 写入；首条原行/已认领 lease 可核验，后续健康动作仍完成，finish DB 失败不追加 retry。
2. 触发器或等价故障验证 retry 自身 DB 失败不二次写、不重复 Stock 动作；已提交后抛 DataAccessException 的结果保持事实，若使用 unit 模拟必须明确不等价于真实提交后异常。
3. 扫描表缺失/`findDue` 数据库异常仍直接传播；claim 非数据库 RuntimeException/Error 仍传播；普通 Stock/RPC/状态异常只调用一次 retry，并验证原异常日志先于数据库失败日志。
4. 真实 Recovery 验证 17AZ token 继续传播、旧/新 worker 互不覆盖、后续候选继续；原 identity/action/key/created/attempts/lease 和 checksum 保持。
5. 回归 17AV/17AW/17AX/17AY、Lease、ActionError/Identity/Creation IT 与 unit；Stock/RPC 为替身时不宣称真实 Stock 故障或网络撤回验证。

## 6. 明确不承诺

本轮只隔离单候选数据库异常，不提供数据库全局停机保证、远端 RPC 撤回、跨服务 exactly-once、额外租约续期或其他恢复器的数据库隔离。
