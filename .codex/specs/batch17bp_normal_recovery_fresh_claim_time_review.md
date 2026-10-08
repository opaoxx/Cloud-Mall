# CM-20260924-20：普通订单恢复每候选 Claim 时间评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderReservationRecovery` 扫描时间复用到后续候选 claim 的问题。**

当前恢复任务在扫描开始时采样一次 `now`，随后 50 条候选都使用该旧时间执行 `claimRecovery(orderNo, now, now+1 minute)`。扫描、前序候选处理或外部调用变慢时，后续候选获得的 lease 起点可能已过时，甚至在写入时已经接近/超过有效窗口。

本轮只调整普通恢复循环的 claim 时间采样，不修改 Mapper SQL、schema、lease 模型、Clock 构造器/Bean、扫描窗口、候选上限或其他恢复边界。

## 2. 时间采样契约

- 扫描开始的 `scanNow` 继续用于 `staleBefore=scanNow-2 minutes` 和 `findRecoverable(scanNow, 50)`；扫描参数、50 条上限和陈旧窗口不变。
- 每个候选进入 try、通过中断检查后，在调用 `claimRecovery` 前采样新的 `claimedAt=LocalDateTime.now(Asia/Shanghai)`。
- 同一候选的 `claimedAt` 同时用于 `claimRecovery(orderNo, claimedAt, claimedAt.plusMinutes(1))`；不得复用 scanNow、前一候选时间或调用后重新拼接第二份 claim 时间。
- 不引入 Clock、构造器参数、配置 Bean、公开 API 或数据库字段；保持当前系统时钟和上海 LocalDateTime 语义。

## 3. 既有恢复边界

- claim 前/后中断检查、非 1 返回跳过、17AR 单候选 DataAccessException 隔离和非数据库异常传播保持。
- claim 成功后 `recoverOne`、Stock 身份/回滚证明、17BO 存在性身份、错误/退避和后续候选处理不变。
- 普通恢复路径继续遵循现有 `next_retry_at` 互斥领取语义；本批不引入或暗示该路径已有独立 owner lease fencing、attempts 计数或新的租约模型。
- 本轮不改变 claim SQL、现有领取时间/退避参数、状态机、RPC、事务或历史数据。不把时间更新描述为跨实例 fencing，也不承诺真实长 RPC/网络事故已经解决；只修复本地候选 claim 起点过时。

## 4. 验收矩阵

1. 真实隔离 MySQL、真实 intent Mapper/Recovery 与本地 30ms 屏障：扫描返回两条候选，首候选处理和扫描前序延迟约 30ms；捕获第二候选可用时间，验证其 `next_retry_at` 不早于该时间 floor 到毫秒后再加 1 分钟。Stock 与 orderExists/forceCommitted 依赖使用替身或 Mockito 注入，不作为真实远端/故障 SQL 证据。
2. 不实际等待一分钟，通过数据库捕获参数和候选处理屏障验证 fresh claim；旧 scanNow 复用实现应在红灯中显示第二候选时间过时。
3. unit 验证 scan 参数仍使用同一 scanNow/staleBefore/50，两个候选 claim 时间分别采样，第二次不早于首候选完成后的当前时间，并继续传入同值 `claimedAt` 与 `claimedAt.plusMinutes(1)`。
4. 回归 17AR 数据库隔离、17AS 中断、17BN rollback proof、17BO orderExists 身份和现有 Stock/Recovery 流程；不修改 Mapper SQL、schema、Clock seam、原领取语义或 API。
5. 真实 MySQL/intent Mapper/Recovery 使用隔离夹具，Stock/orderExists/forceCommitted 使用替身和短延迟；不宣称真实 SQL 写异常、真实远端调用、一分钟等待或跨实例长 RPC 事故演练，原订单 checksum 与随机 fixture 清理保持。

## 5. 明确不承诺

本轮只保证普通恢复每候选 claim 使用新鲜本地时间，不提供全局时钟同步、跨实例 lease fencing、远端调用撤回、数据库全局可用性或其他服务时间闭环。
