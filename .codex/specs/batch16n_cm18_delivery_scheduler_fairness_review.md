# CM-20261007-18：16N TCC delivery 调度公平与阶段隔离评审

## 1. 评审结论

结论：**GO，限定修复 delivery scheduler 的两阶段饥饿和异常隔离；不改变单阶段业务语义。**

当前 `PayTccDeliveryScheduler.recoverDue` 固定先执行 `findMissing/ensure`，再执行 `findDue/deliver`。种子阶段查询或任务连续耗尽 10 秒时，due 阶段始终无法开始；外层统一 catch 还会让种子查询异常直接跳过 due 阶段。本轮只修复调度公平、共享预算和阶段错误隔离，不新增 schema、flag、RPC 或 delivery 状态。

## 2. 调度契约

### 2.1 两个阶段和预算

每轮仍由同一单实例 `running` 门保护，整轮共享启动后的 10 秒 `System.nanoTime` 预算，不强杀已经发出的 RPC：

1. seed 阶段：查询 `findMissing`，最多取 20 条；逐条调用 `store.ensure`。
2. due 阶段：查询 `findDue`，最多取 20 条；逐条调用 `service.deliver`。

每阶段最多 20 条，但两阶段共同消耗同一轮预算。启动另一个阶段的查询前必须检查剩余预算；预算已耗尽时不得启动查询、认领或新的任务。每条任务开始前也必须检查预算；当前 RPC 超时或超出预算不被线程强杀，返回后只允许当前阶段记录异常并结束本轮。

### 2.2 首选阶段轮换

首轮 seed 优先，后续轮次在 seed 和 due 之间交替首选。轮次优先状态只在调度器实例内更新，并在 `finally` 释放 `running` 后保留到下一轮；重入调用直接返回且不得改变优先状态。一次阶段异常或单条任务异常只记录该阶段/该任务，不得跳过另一个阶段，只要共享预算尚未耗尽就继续执行另一阶段。

阶段优先不意味着强制执行：首选阶段查询异常、返回空结果或预算耗尽时，按剩余预算尝试另一阶段；预算耗尽后立即结束，不再启动另一阶段查询。

### 2.3 时间测试 seam

生产路径必须使用真实 `System.nanoTime` 计算 deadline，不能使用 wall clock 判断 10 秒预算。允许增加 `protected` 或 package-private 的 `LongSupplier` 测试注入点；Spring 使用的现有三参数 public 构造器/API 保持不变，生产默认 supplier 必须等价于 `System::nanoTime`。测试可用单调递增 supplier 确定性验证预算、阶段轮换和异常隔离，不修改调度公共契约。

## 3. 异常与状态边界

- `findMissing` 查询异常只记录 seed 阶段错误；若剩余预算未尽，仍执行 due 查询。
- `findDue` 查询异常只记录 due 阶段错误；不影响下一轮优先级轮换和 `running` 释放。
- `ensure`/`deliver` 单条异常只记录该 payNo，继续同阶段后续候选，除非预算耗尽。
- 任何阶段都不得因扫描异常写 Pay FAILED、改变 execution decision、调用 TC、调用 Order cancel 或修改 User ledger。
- `finally` 必须释放 `running`，异常不能导致调度器永久卡在运行中。
- 继承 16L 过期 lease CAS 保护；迟到 delivery worker 的本地写入仍由 Store/Mapper 拒绝。

## 4. 验收矩阵

1. 首轮 seed 优先；连续轮次 seed/due 首选交替，两个阶段长期均能获得执行机会。
2. seed 查询抛异常且预算未尽时，due 查询仍执行；due 查询抛异常时，seed 阶段仍按优先顺序执行。
3. 首选阶段任务异常不会阻断另一阶段；阶段查询异常不会把共享预算错误标记为已耗尽。
4. 单调时钟推进到 deadline 后，不再启动另一阶段查询，也不开始新的 `ensure`/`deliver`；在途 RPC 不被强杀。
5. supplier 测试确认 deadline 使用 `System.nanoTime` 语义，不受 wall clock 调整影响。
6. 调度异常、重入和提前 return 后 `running` 均释放；下一轮仍可执行并保留正确的优先级轮换。
7. 每阶段候选上限仍为 20，整轮不超过共享 10 秒预算；16C delivery、16L lease、16E cancel、16F/G rollback、16K abort 和 Legacy 路径回归不受影响。

## 5. 实施门

实现限定在 `PayTccDeliveryScheduler` 及其调度测试。完成后执行阶段异常/轮换/预算单测、相关真实集成测试、全仓构建和 `git diff --check`。规格评审通过后再委派 backend 实现。
