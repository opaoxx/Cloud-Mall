# CM-20261007-20：Stock 秒杀认领幂等键精确身份评审

## 1. 评审结论与范围

结论：**GO，限定修复秒杀请求认领在 MySQL CI/PAD SPACE 排序下的幂等键别名误合并；不改变 schema、唯一索引、API、orderNo、payload/hash 或状态机。**

当前 `SeckillRequestClaimService.claim` 通过数据库排序规则锁定 `(user_id,idempotency_key)` 记录后，未核验返回行的 `idempotency_key` 是否与入参精确相等；Stock 入口主要比较 hash，可能把大小写、重音或尾空格不同的新键合并到旧 orderNo/Redis 路径。

## 2. 认领身份契约

在 `insertSeckillPendingIgnore` 后、返回 StockService 前，同一事务内锁定记录后必须执行 `Objects.equals(incoming idempotencyKey, row.idempotency_key)` 精确比较：

- 区分大小写，保留重音和尾空格差异，不 trim；
- 不相等时抛既有 `SECKILL_IDEMPOTENCY_CONFLICT`/409，使当前 claim 事务回滚；
- 不调用 Redis、不写受理 marker/outbox、不改旧 row；
- 精确同 key 重放继续返回原 orderNo/payload/hash/status；
- 同 key 但不同 user 仍由现有 `(user_id,idempotency_key)` 语义独立处理；
- null/空白/列宽校验和 INSERT IGNORE/schema 约束保持原边界。

数据库唯一索引不迁移，因此排序规则别名可能仍无法共存；冲突响应是安全拒绝，不自动改键、合并或修复历史记录。

## 3. 业务与事务边界

精确 key 校验必须发生在 Redis/受理逻辑之前，并位于 claim 的现有事务内。StockService 只能在 claim 返回精确身份事实后继续 hash/orderNo/Redis 路径；已有 metadata 缺失导致的 503 不得被别名请求绕过为受理成功。

## 4. 验收矩阵

1. 真实 MySQL 对大小写、重音、尾空格三类 alias：返回 409，原 row 完整保持，无 Redis/accept/outbox 调用。
2. 精确重复同 user/key 继续原 orderNo/idempotency/hash/status 语义；不同 user 使用独立记录。
3. 初次请求因 metadata/依赖不可用返回 503 后，alias 重试仍 409 且零 Redis；exact 重试继续原事实。
4. claim 事务 rollback 后不留下 alias 新行、不改变旧 row 或状态；原数据库 checksum 保持。
5. Redis/acceptance/Rabbit 使用替身只验证本地调用边界，不宣称网络故障覆盖。

## 5. 明确排除

本轮不迁移 collation/索引、不新增字段/API、不改变 orderNo/eventId/payload/hash 生成、不修改 Redis marker 或 outbox 状态机，不扩展其他幂等键历史修复。
