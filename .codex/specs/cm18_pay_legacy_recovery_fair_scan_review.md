# CM-18 16T87：Pay Legacy recovery fair scan

## 1. 结论与范围

GO。`findDue` 按 `next_retry_at,id LIMIT 50`，前部长期坏 tuple 会持续占满窗口，后部健康 Legacy 意图饥饿。16T84 的前置拒绝不会修改坏行，不能靠状态变化自然移出扫描头。

本批只改 `recoverDue` 的进程内有界扫描游标；保留旧 `findDue` 供其它 caller/历史测试，不改 claim、process、RPC 或业务状态。

## 2. Mapper 查询

新增 internal `findDueAfter(now, afterId, limit)`：保留旧 findDue 的两表 BINARY LEGACY、active status、due 与未过期 lease 条件，只增加 `id > afterId`（afterId null 时无下界），按 `intent.id` 升序并 LIMIT；不要求 id 为正，不对负/零/Long.MAX 做算术，limit 仍做正数 clamp。

该查询改变扫描次序为 due 过滤后的主键轮转，只承诺有界候选返回，不承诺物理扫描/性能或已完成全部历史坏行修复。旧 `findDue` SQL、签名和其他调用保持。

## 3. recoverDue 游标算法

使用进程内 `AtomicReference<Long> scanAfterId`，默认 null，重启复位；不持久化、不写数据库。每轮：

1. 捕获 `start = scanAfterId.get()` 和同一 `now`；先请求 `findDueAfter(now,start,50)`。
2. 若 start 非 null 且首段为空，从 null wrap，再请求最多 50；每轮最多两次查询、最多处理 50 条。若首段非空则不额外填充短批。
3. 逐条调用原 `process(payNo)`，保留 T83 上下文门与原 catch；坏 tuple、claim 失败、业务异常仍推进扫描，不修事实。
4. 查询成功后将末条实际候选 id 作为 end；按 `compareAndSet(start,end)` 推进，避免同实例并发旧轮回退游标。空首段且 wrap 也为空时 end=null；查询异常不推进，保留 start 并返回原异常/日志语义。

游标只代表扫描进度，不证明处理成功或完成；连续新增 due 行、重启和跨实例各自游标不承诺强公平。id 为 null 的映射行不能作为可靠 cursor，应保守不推进或沿现有异常处理，不用伪造 id。

## 4. 保持项与边界

不改 T79–T86、claim/lease/token、T84 tuple proof、SQL 写入、状态、RPC、TCC、API、DDL、磁盘 cursor、全局调度或坏行 repair。旧按 due 时间优先的 `findDue` 仅对旧 caller 保留；`recoverDue` 本轮明确采用 due 资格 + id 轮转顺序。

不宣称所有 corrupt row mapper/history 类型均已收敛、跨进程公平、远端资金安全或完整 fencing。

## 5. 验收

- Unit：游标首次 null、wrap、空页、DAO 查询异常不推进、process 全部失败仍推进、并发 CAS 不回退；同一轮候选返回/处理总数不超过 50，最多两次查询。
- Owned MySQL：头部 60 个坏 tuple 与尾部 51 个健康 Legacy，连续 tick 能处理健康候选；wrap 后头部仍只被观察、不改坏 facts。验证负/零/Long.MAX id、due/lease/status/mode 过滤与 limit。
- 健康流程：claim/retry、T83 上下文隔离、正常 initiate/scan 与旧 `findDue` caller 回归；owned 资源 finally 清理，不启动 App/RM/TC。

## 6. 交付边界

实现仅允许新增 internal after-id 查询、`recoverDue` 内存游标及必要 JavaDoc/测试；不得新增持久游标、模块、API 或修改业务 process。
