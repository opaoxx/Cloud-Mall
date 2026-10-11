# CM-18 16T45：秒杀 transition 受理来源 SQL 门

## 1. 结论与范围

本切片 GO，只修正 settlement `OPEN/PROJECTION_BLOCKED` transition 写入在 source `seckill_reservation` 缺失或 tuple 不一致时仍可 CAS 的问题。基于 T44 的受理来源 EXISTS，三个 transition 方法必须在同一 UPDATE 中确认当前 settlement 仍有合法受理来源。

本批只扩展 `transitionOpenToConfirmed`、`transitionToReleasePending`、`transitionBlockedToConfirmed` 三条现有 UPDATE 的 WHERE；保留原 predicates、SET、签名、显式事务/锁边界、T44 claim、T31–T44、Redis/MQ、API 和 DDL。

## 2. Source EXISTS 条件

三条 transition SQL 均追加与 T44 相同的相关 EXISTS：

- `seckill_reservation` 按原唯一 order_no 候选；
- accepted order_no 与请求 `#{orderNo}`、当前 settlement order_no 精确 BINARY 相等；
- source status 精确为 `ACCEPTED/PUBLISHED/ORDER_CREATED`，拒绝 `PENDING/UNKNOWN/REJECTED` 和 CI alias；
- activity_id、sku_id、user_id 与当前 settlement exact 相等；
- idempotency_key 与当前 settlement 精确 BINARY 相等。

保留原 order/status/revision/operationKey CAS 条件及 OPEN 的 null/exact direction SQL 兼容。不存在/坏 source 时 SQL affected rows=0，Service 沿原 `SECKILL_SETTLEMENT_UNKNOWN` 503；不新增 source query、GET、DTO 或 source 修复。

## 3. 状态与边界

- OPEN confirm、OPEN rollback、PROJECTION_BLOCKED confirm 三个 transition 均受 source gate；合法三种 source 状态可按原 SET/CAS/revision 变化。
- 原 INSERT→FOR UPDATE/REQUIRED 顺序保持；source gate 不改变 noop、相反动作冲突、pending、receipt/recovery 或 T35 nonterminal operation key 语义。
- SQL EXISTS 是谓词变更，可能改变 InnoDB 读取/锁足迹；不承诺物理扫描、性能或并发锁压力不变，不声称跨 SQL/Redis 原子、request hash、Order/全资金因果或 claim 后窗口闭合。
- 不改终态 noop、普通库存、legacy、Lua、Redis、TC/MQ、DDL、Mapper 签名或 API。

## 4. 验收计划

- 复用 T34 owned MySQL fixture：source 缺失、状态错误、order alias、tuple/幂等不一致 × 三 transition，实际 affected rows=0、Service/Controller 返回既有 503，facts 不变。
- 合法 `ACCEPTED/PUBLISHED/ORDER_CREATED` × OPEN/PROJECTION_BLOCKED transition 均可按原协议成功；T35 跨向原 503、终态 noop、T44 claim 来源和 T31–T44 回归保持。
- 真实同事务 SQL 验证 source gate 可执行、坏 source 拒绝、正例状态/CAS 变化；source checksum、owned facts 和资源 finally 清理，不启动 App/固定端口/RM/TC。

本切片只冻结 transition 写入的受理来源门，不处理完整 tuple provenance、跨 SQL/Redis fencing 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
