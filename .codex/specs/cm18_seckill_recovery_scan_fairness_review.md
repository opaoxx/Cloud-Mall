# CM-18 16T39：秒杀恢复扫描有界环形公平

## 1. 结论与范围

本切片 GO，基线为 `6fa7220`，修正固定 `ORDER BY id LIMIT 50` 在坏 candidate、blocked-confirm 或 claim 失败行长期占据头部时造成的健康候选饥饿。它只增加进程内扫描游标，不改变任何结算事实或恢复动作。

本批不改变 T37 candidate guard、T38 CAS readback、claim/Lua/retry、SQL/DDL/index、API、Redis/MQ、恢复状态机或单实例业务语义；不承诺重启后、持续无限新增行或多实例之间的强公平。

## 2. 进程内游标与查询

恢复器新增 `AtomicReference<Long>` 游标，默认 null，不持久化、不写数据库、不输出 API：

- 游标为 null 时沿原 `findDue(now, 50)` 首段查询；
- 游标非 null 时调用新的语义化 Mapper `findDueAfter(now, afterId, limit)`，保留原 due/status/lease 条件，只增加 `id > afterId`、`ORDER BY id`、`LIMIT`；
- 当前轮固定一个 `now`，最多读取/处理 50 行。游标只代表扫描进度，不代表任何 candidate 已完成或已成功；
- after 页不足 50 时，以剩余预算查询原 `findDue(remaining)` 头段，但只接收 `id <= 本轮 startCursor` 的行，避免 wrap 后重复本轮已扫描区间。首轮 startCursor 为 null 时不执行 wrap；
- 首段空时本轮末游标为 null；任何用于推进的 row.id 为 null 时不能作为可靠游标，末游标置 null，下一轮从首段重新开始；
- after 页满 50 时不 wrap；after 页不足且过滤后仍不足时，读取预算仍不超过 50。

Mapper 新方法只封装 SQL，不隐藏坏 candidate、不延期事实、不 repair/删除行；原 `findDue` 保留给首段和其它既有调用。

## 3. 并发、异常与游标更新

每轮 capture `startCursor`，完成扫描后计算 `endCursor` 为本轮最后一个可可靠扫描 row.id。使用 `compareAndSet(startCursor, endCursor)` 提交进度：

- CAS 成功推进游标；
- 同实例并发轮次发生覆盖时，失败轮不得把游标倒退；该轮已扫描事实仍按本轮处理结果结束；
- candidate 坏、candidate guard false、claim 返回 0、Lua/CAS/retry 失败都视为已扫描并推进，不给坏行额外延期或写 repair；
- 查询失败时保持本轮开始游标并返回 0，不伪造 handled、不提交 endCursor；
- 结果为空且末行 id 不可靠时游标置 null；进程重启游标自然丢失，从首段开始，保持原行为。

游标推进不改变 `recoverOne` 的 handled 计数、lease、Lua、retry 或 T38 readback；仅决定下一轮候选窗口。有限且稳定的 due 集合在多轮中可轮转覆盖，连续新增或重启不作强公平承诺。

## 4. 验收矩阵

- Unit：游标 null 首段、after 页满/不足、wrap 过滤 `id<=startCursor`、空首段归 null、null id 不推进、并发 CAS 不倒退、查询异常保持原游标、坏/claim0 仍推进均覆盖；每轮读取预算不超过 50。
- 复用 T37/T38 真实 MySQL+Redis fixture：前 50 甚至 150 个坏行后有健康行，多轮扫描最终释放健康 candidate；坏事实不变，坏 row 不被延期/repair；head 重新 due、lease 失败仍推进；两轮 Lua/receipt 重放保持幂等。
- owned database、Redis keys、线程/执行器 finally 清理；source Stock checksum 不变；不启动业务 App、固定端口、RM/TC，不使用 SCAN/FLUSH。
- 保留 T31–T38、T37 candidate guard、T38 CAS proof、单批 50、原 Mapper/DDL/index/API/状态机和所有普通/秒杀回归。

本切片只冻结进程内有界扫描公平，不处理 claim 后 TOCTOU、CAS readback 之外的错误、全局分布式公平、历史 repair 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
