# CM-18 16T37：秒杀恢复候选事实门

## 1. 结论与范围

本切片 GO，基线为 `a180f40`，只修正 `SeckillQuotaSettlementRecovery.recoverOne` 在 claim/Redis key 派生前对候选 settlement 的事实校验。当前恢复器只检查 operation key 后缀、revision=2、quantity=1，错 order、错状态、错活动/用户维度或坏幂等键仍可能领取 lease 并拼接错误 Redis key。

本批只增加一个 private recovery candidate guard；不改变 `recoverDue` 的单批 50、lease、claim SQL、Lua、markReleased/CAS、retry、DDL、API、Redis/MQ 或其它状态机语义。

## 2. 候选门与允许事实

在 `claim`、owner 生成、Redis key 派生和 Lua 调用前校验候选：

- PO 非 null；
- `orderNo` 非 null、非 blank、非 literal `"null"`，且 code point 数不超过 Order 既有上限 64；
- status 精确为 `RELEASE_PENDING` 或 `PROJECTION_BLOCKED`；
- operation key 必须精确为 `orderNo + ":ROLLBACK"`，不接受仅 `endsWith`、大小写/重音/尾随空格 alias、其它方向或 null；
- revision 精确为 2，quantity 精确为 1；
- activityId、skuId、userId 均为正且可无损表示的 long；
- idempotencyKey 遵循 T36 原规则：非 null、非 literal `"null"`、非 blank、code point 数不超过 128；不 trim/casefold，不用 `String.length()`。

任何条件失败均返回 false：不 claim、不生成 lease/owner、不派生 Redis keys、不执行 Lua、不 mark/retry、不改数据库状态，不输出 raw 值。`PROJECTION_BLOCKED` 但 operation key 为 `orderNo:CONFIRM` 的既有 blocked-confirm 事实保持跳过，不转换为 rollback。

## 3. 保持的恢复链路

候选门通过后，继续原 `recoverOne`：以原 orderNo/operationKey/revision claim，按原 activity/sku/user/idempotency 组合派生 keys，执行 release Lua，按原回执调用 markReleased 或 projection blocked/retry。保留缺 key、Lua 结果、markReleased=0 readback、lease 竞争和 errorSummary 的现有语义；本批不修 markReleased=0 误成功或 claim 后 TOCTOU。

`recoverDue`、每批 50、定时触发、lease owner/until、数据库 SQL/DDL、Redis Lua、普通库存、settle/GET/legacy、T31–T36 既有规则不变。健康 rollback candidate 仍只释放一次；用户计数不递减。

## 4. 验收矩阵

- Unit：null/blank/literal null/超长 orderNo、状态错误、operation key suffix alias/CONFIRM/foreign/null、revision/quantity 错、activity/sku/user 非正或溢出、idempotency null/blank/literal null/超长，均在 claim 前返回 false 且无 Redis/SQL retry/write。
- Owned MyBatis/MySQL + 真实 Redis/Lua：坏候选保留原事实、不领取 lease、不生成/删除非预期 keys；健康 RELEASE_PENDING rollback candidate 执行原 release/receipt，重复恢复不多加 quota、不减少 user count。健康 PROJECTION_BLOCKED rollback 方向沿原链路；blocked-confirm 仅跳过。
- 使用随机 owned database、activity/sku/order/idempotency 和精确预期 Redis keys；finally 精确 DEL/DROP，source Stock checksum 不变。不 ALTER 生产 schema；NULL/超长坏 schema 值仅 unit 或 owned 准备阶段演练。
- 无业务 App、固定端口、RM/TC；T36 valid idempotency/Unicode边界与 T31–T36 read/write 回归保持。

本切片只冻结恢复候选事实门，不处理 claim 后 TOCTOU、CAS readback、全 tuple/provenance、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
