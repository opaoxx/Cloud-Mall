# CM-18 16T40：秒杀恢复 claim 后 lease/事实读回门

## 1. 结论与范围

本切片 GO，基线为 `ea78a3a`，修正恢复器在 claim 成功后直接使用扫描快照拼 Redis/Lua 的风险。claim 期间另一 writer 可能改变 tuple、状态、owner 或 lease，导致旧 snapshot 释放错误投影并尝试 mark 错事实。

本批只增加 claim 成功后、Lua 前一次既有 `findSettlement` 读回证明；不新增 SQL/DDL/事务，不改 T37 candidate gate、T38 CAS readback、T39 扫描游标、claim/锁序、Lua、retry、API、Redis/MQ 或其它状态机。

## 2. Claim readback proof

claim 返回 1 后，以 fresh `now()` 读取当前 settlement；必须同时满足：

- current 非 null；
- current status 精确为 `RELEASE_PENDING` 或 `PROJECTION_BLOCKED`；
- current operationKey 精确为原 `orderNo:ROLLBACK`，并与原候选一致；
- current 与原候选复用 T38 immutable identity proof：id、orderNo、activityId、skuId、userId、idempotencyKey、quantity、revision、operationKey、createdAt（原值非 null且 current 精确相等）；
- current.leaseOwner 精确等于本次 claim 生成的 owner；
- current.leaseUntil 非 null 且 `isAfter(freshNow)`。

不比较 updatedAt、lastError、其它可变诊断字段；不新增 owner/lease 延期或 repair。createdAt/id 规则沿 T38，不把两个 null 当作同一事实。

## 3. 读回结果与 Lua 边界

- readback wrong/null/lost lease/expired lease/status drift/immutable drift：返回 false，不调用 Lua、markReleased 或 retry，不修改事实；不能用原 SQL CI 条件 retry 去改错行。
- readback 查询抛异常：沿既有 catch/retry 原候选字段返回 false，但不调用 Lua；错误摘要仍沿既有规则。
- readback 合法 pending/blocked rollback 且 lease 仍由本 owner 持有：使用 readback 证明后的同一原始 fields 调用既有 Lua/mark/retry；pending 与 blocked rollback 均保持原链路。
- 本门只覆盖已观察到的 claim 后漂移/失租/超时；不声称读回后到 Lua 之间无窗口，不提供 fencing，不形成跨 SQL/Redis 原子闭环。

T39 的每轮扫描 query 返回候选行数与处理预算仍按原上限；本批每个成功 claim 增加一次既有点读，不把 claim readback 计入扫描 query cardinality 的“两次有界 query”预算，也不宣称总 SQL/物理扫描性能上限。

## 4. 验收矩阵

- Unit：claim 后 readback 覆盖合法同一 pending/blocked、immutable tuple/status/owner/lease 任一漂移、lease 过期、null current、查询异常；错误场景均无 Lua/mark，查询异常仅沿旧 retry。
- 复用 T37/T38/T39 owned MySQL+Redis fixture：真实 claim SQL 后由受控 writer 修改 immutable/state/owner/expiry，再执行被测点读；预留旧投影证明原 bug 不能释放错误事实，readback 后不修坏行。合法 claim+fresh read/diagnostic fields 变化仍执行一次 Lua。
- source Stock checksum、owned DB/Redis keys、lease/线程资源 finally 清理；NULL createdAt/其它 NOT NULL 坏字段仅 unit，不 ALTER 生产 schema；无业务 App、固定端口、RM/TC。
- 保留 T31–T39、单批 50、游标、candidate gate、CAS readback、普通/秒杀回归；不修改 claim 后 TOCTOU 之后的 fencing 需求。

本切片只冻结 claim 后的一次事实/lease读回门，不处理读回后窗口、分布式 fencing、完整资金 tuple、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
