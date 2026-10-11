# CM-18 16T44：秒杀恢复 claim 受理来源原子门

## 1. 结论与范围

本切片 GO，补足 recovery claim 对现存 `seckill_reservation` ACCEPTED 来源的原子核验。当前 claim 只核 settlement scalar/lease 条件，错 order/status/CI alias 或活动、SKU、用户、幂等键不一致的 settlement 仍可能领取 lease。

本批只扩展现有 `SeckillQuotaSettlementMapper.claim` 的 WHERE，保留原 scalar 参数、SET、签名和原显式事务边界；不新增独立 query、DTO、DDL、Redis、API、显式锁序、claim 后窗口或其它状态机规则。该关联 EXISTS 属于 SQL 谓词变更，可能改变 InnoDB 读取/锁足迹；本批不承诺物理扫描、性能或并发锁压力不变。

## 2. ACCEPTED EXISTS 条件

claim 原有 order/revision/operation/status/nextRetry/lease 条件全部保留，并追加一个相关 `EXISTS` 来源条件：

- 从 `seckill_reservation` 按原唯一 `order_no` 读取候选；
- accepted 原始 `order_no` 与请求 `#{orderNo}` 精确 BINARY 相等，且与当前 settlement `order_no` 精确 BINARY 相等；
- accepted `status` 精确为既有受理状态集合 `ACCEPTED`、`PUBLISHED` 或 `ORDER_CREATED`；拒绝 `PENDING`、`UNKNOWN`、`REJECTED` 及大小写/空白 alias。该集合与 `SeckillEventTimeResolver`、`SeckillOrderOutboxDispatcher`、`SeckillMessageRecovery` 的现有兼容契约一致；
- accepted `activity_id`、`sku_id`、`user_id` 与当前 settlement 对应维度 exact 相等；
- accepted `idempotency_key` 与当前 settlement 精确 BINARY 相等。

该 EXISTS 使用现有 claim scalar 参数/目标行字段，不新增方法参数或旁路 query；原 order 唯一索引候选仍用于检索，再以 BINARY 条件做精确校验；不以请求字段覆盖 settlement，不 trim/casefold，不把 CI alias 当来源匹配。accepted 缺失、状态错误、order/维度/幂等 alias 或 null 导致 EXISTS 不成立，claim 返回 0，不领取 lease、不调用 Lua、不写 retry/其它状态。

生产 accepted 数量固定为 1；本批不验证 request_hash、payload、Redis APPLIED marker、Order 因果或完整 tuple beyond accepted 来源。

## 3. 与既有 recovery 链路的关系

健康 pending/blocked candidate 通过 candidate guard、T40 claim readback 和本 EXISTS 后继续原 lease/Lua/mark/retry；T37 candidate gate、T38 CAS readback、T39 游标、T40 lease/事实 readback、T41 write fence、T42 original snapshot binding 保持。claim 后 source 变动窗口不由本批关闭。

本批不改变 claim 的影响行数语义、recoverDue 单批 50、失败/lease0/坏 candidate 推进、Redis keys/Lua、用户计数、receipt replay、普通库存和 source facts。

## 4. 验收矩阵

- 复用 T37/T40 owned MySQL+Redis CandidateGuard fixture：健康 seed 覆盖三种合法受理状态 × pending/blocked 及 receipt replay，且 tuple 完全相同；bad matrix 覆盖 missing accepted、`PENDING/UNKNOWN/REJECTED`、CI order alias、数字维度不一致、idempotency alias；实际 claim=0、无 lease/Redis/事实写。
- 健康 pending/blocked release、receipt replay、blocked-confirm skip 和 T37–T43 回归保持；source Stock checksum 与 owned facts 前后核验，owned DB/Redis keys finally 清理。
- 验证真实 SQL 可执行、同源事务下坏来源拒绝、合法来源成功；不新增 DDL/参数/公开 API，不声称 Redis APPLIED、request hash、Order/全资金因果或 claim 后 source 变动窗口已关闭，也不宣称并发锁压力或物理 scan 性能已验收。
- NULL/超长坏字段仅 unit 或 owned 准备阶段演练，不 ALTER 生产 schema；无业务 App、固定端口、RM/TC。

本切片只冻结 claim 时现存 ACCEPTED 来源绑定，不处理 fencing、Lua/SQL 跨系统原子、全 tuple provenance 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
