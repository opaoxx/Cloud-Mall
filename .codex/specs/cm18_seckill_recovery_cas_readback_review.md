# CM-18 16T38：秒杀恢复 markReleased CAS 读回证明

## 1. 结论与范围

本切片 GO，基线为 `c754474`，修正恢复器在 `markReleased` 返回 0 后只凭 `status=RELEASED` 判定 handled 的问题。并发 writer、错 tuple、错误 receipt 或别订单读回均不能被当作本次释放完成。

本批只新增 private CAS-readback proof；T37 candidate guard、claim、Lua、SQL/DDL、锁序、recoverDue limit50、lease、API、Redis/MQ、普通库存和其它状态机不变。仍不处理 scan 窗口、claim 后 TOCTOU、完整资金 tuple 或其它 M1–M4 闭环。

## 2. markReleased=0 后的 immutable proof

Lua 返回本次正 `redisRevision` 且 `markReleased(...) != 1` 时，读取当前 settlement；只有以下条件全部成立才返回 handled=true：

- current 非 null，status 精确为 `RELEASED`；
- current.id 非 null 且等于候选原始 id；
- current.orderNo、activityId、skuId、userId、idempotencyKey、quantity、revision、operationKey 分别与候选原始值 `Objects.equals` 精确相等；
- 候选原始 `createdAt` 必须先非 null，且 current.createdAt 与其精确相等。该字段是 source schema 的 NOT NULL 创建事实，纳入比较以证明读回的是同一不可变行；原/current 均为 null 不得通过；
- current.redisRevision 精确等于本次 Lua 返回的正 `redisRevision`。

不比较 `updatedAt`、lease_owner、lease_until、lastError 或其它可变诊断字段；不新增 owner/错误文本/时间窗口规则，也不使用 raw 字段生成补偿键。id、原始维度、原操作键和 createdAt 是同一持久行的不可变身份/来源证明，Redis revision 是本次 Lua 回执证明。

任何缺失、状态/tuple/createdAt/receipt 不匹配均视为 CAS_UNKNOWN：沿既有日志和 retry 返回 false，不把当前坏 RELEASED 修复成其它状态，不用读回 raw 字段调用 retry，不重复 Lua 或 markReleased。

## 3. 正常与竞争语义

- markReleased 返回 1：沿原成功路径 handled=true，不新增读回。
- markReleased 返回 0 且 immutable proof 全部通过：允许另一合法 writer 已完成本次释放，handled=true，不重复增加 quota、不减少 user count。
- markReleased 返回 0 且 proof 失败/读回 null：沿原 CAS_UNKNOWN + retry 候选返回 false，保留原 operationKey/revision；retry 的 SQL/租约条件不改。
- 当前是 RELEASED 但 id/tuple/createdAt/redisRevision 不匹配，即使 status 相同也不能计数完成；不修复当前坏终态。

## 4. 验收矩阵

- Unit：4 组 readback 结果覆盖合法同一行、null、错误 status、id/tuple/key/createdAt/redisRevision 任一不匹配；特别覆盖原/current `createdAt` 均 null 必须拒绝；assert handled/retry/无二次 Lua 及不回显 raw 字段。
- Owned MyBatis + 真实 Redis/Lua：复用 T37 fixture；用受控 `markReleased=0` hook 演练另一 writer 写 RELEASED 后合法同一行读回，确认一次释放/回执重放不多加 quota、不减少 user count；错误 facts 仅允许测试准备阶段修改，readback 后核 snapshot/事实未被修复。
- 覆盖 current createdAt 相同/不同、id/原维度/operation key 错、receipt revision 错、null current；source Stock checksum、owned DB/Redis keys finally 清理，坏 readback 不新增 retry 以外写。
- 健康 RELEASE_PENDING/PROJECTION_BLOCKED rollback 方向、T37 candidate gate、T31–T36 read/write 回归保持；NULL source NOT NULL 字段仅 unit 演练，不 ALTER 生产 schema。

本切片只冻结 CAS 读回证明，不修 claim 后 TOCTOU、scan 窗口、全 tuple/资金因果或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
