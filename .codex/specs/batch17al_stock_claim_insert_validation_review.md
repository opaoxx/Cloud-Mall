# CM-20261007-20：Stock 秒杀认领输入与 INSERT 严格校验评审

## 1. 评审结论与范围

结论：**GO，限定防止秒杀认领 INSERT IGNORE 静默截断/默认值写入；不改变 schema、时间、hash 业务算法、事务、API、历史记录或 Redis 状态机。**

当前 `insertSeckillPendingIgnore` 可能静默截断 orderNo/key/hash；ClaimService 的返回 key 检查无法保护直接 Mapper 调用或 orderNo/hash，Stock 入口也未阻止 blank/超长 key。

## 2. 入口参数门

StockService 秒杀入口必须在 Sentinel/claim/Redis 之前拒绝：

- idempotencyKey null/blank；
- Unicode code point 数超过 128；

沿既有 `INVALID`/400 语义返回，不创建 reservation、marker/outbox，不调用 Redis、Stock 受理或 Rabbit。合法 128 code point（含非 BMP emoji）继续处理。

## 3. Mapper 物理列守卫

保留 `insertSeckillPendingIgnore` 方法名与 public 签名；“Ignore”只表示唯一键重复幂等，不表示忽略输入/SQL 错误。该方法必须执行普通参数绑定 INSERT，并只捕获 `DuplicateKeyException` 返回 0；其他 DataAccessException/SQL 错误直接传播。Java/Mapper 物理守卫必须拒绝：

- orderNo null/blank 或 code point 超过 64；
- idempotencyKey null/blank 或 code point 超过 128；
- requestHash 非 null 且 code point 超过 64；requestHash 可以为 null，沿现有 nullable/兼容列契约，不新增业务 hash 校验；
- occurredAt null，因现有 required DATETIME 列不允许缺失；
- payload/hash 的 JSON/业务合法性仍由现有列和上层契约处理，不在本轮猜测扩展。

宽度使用 code point 计数，不用 Java UTF-16 `length()`；超宽/非法 required 输入在执行 SQL 前抛明确 INVALID/参数错误，不留下行。合法边界和 nullable metadata 不改变。

唯一键 DuplicateKeyException 才提供原有重复幂等效果；截断/约束/连接/JSON 等其他错误不得吞掉或返回成功，不改变 public 方法签名或调用事务。

## 4. Claim 与兼容边界

ClaimService 的 ForUpdate、exact key、原 hash 比较、alias 409 和并发首事实规则保持；Mapper 直接调用也必须受到物理列守卫。不同 user 的同 key 继续按现有唯一索引独立处理；不迁索引/collation，不修历史截断记录，不改变时间语义。

## 5. 验收矩阵

1. 真实 MySQL strict/non-strict 模式验证 orderNo/key/hash 超宽、required null 均拒绝且零行；不依赖 SQL mode 静默行为。
2. 合法 128 code point key、非 BMP emoji、合法 orderNo 和 nullable requestHash 边界保持；精确重放、alias 409、并发同 key 首事实回归通过。
3. Stock 入口 blank/129 code point key 在 Sentinel/claim/Redis 前返回 400，数据库/Redis 零调用；128 emoji 可正常 claim。
4. Mapper/Claim/Stock 的原 hash、payload、occurredAt、状态和事务回归通过；原库 checksum 不变。

## 6. 明确排除

本轮不新增 schema/API/状态，不改变时间/hash 算法，不迁移历史记录，不扩展通用 JSON/lease/网络错误处理；只防止新认领输入和 SQL 保存静默变形。
