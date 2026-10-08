# CM-20260924-20：普通预扣意图错误文本 Unicode 截断评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderReservationIntentMapper.recordFailure` 与 `markReserveUnknown` 按 UTF-16 `length` 截断 `VARCHAR(512)` 错误文本的问题。**

当前两处使用 `substring(0, Math.min(512, error.length()))`。在 511 个 ASCII 加一个 emoji 等边界下可能截断有效 surrogate pair；512 个 emoji 也只保存 256 个。MySQL `VARCHAR(512)` 的目标语义是最多 512 个 Unicode code point。

本轮只改这两个方法共用的私有裁剪逻辑，不泛化 `OrderOutboxMapper`、`OrderStockActionMapper`、`SeckillMessageRecoveryMapper` 等其他错误字段，不迁移 schema，不改变状态、错误码或时间语义。

## 2. clipIntentError 契约

在 `OrderReservationIntentMapper` 增加私有 `clipIntentError(String)`（名称可等价实现），两处写入共同调用：

- `null` 返回 `null`；
- code point 数量 `<= 512` 时原样返回，包括空字符串、ASCII、BMP、emoji、中文和已有非法输入；
- 超过 512 个 code point 时，使用 `offsetByCodePoints(0, 512)` 计算 UTF-16 终点并截取前 512 个 code point；
- 不主动过滤、替换、Unicode 归一化、trim 或改变大小写；不得把有效 surrogate pair 拆开。

该裁剪只保护可写入的错误文本长度，不新增错误格式校验。Java 字符串中的其他异常 Unicode 输入继续沿原 JDBC/MySQL 错误语义处理。

`insertIfAbsent` 的 17AO 严格校验保持不变：新建 intent 的非 null `lastError` 超过 512 个 code point 仍拒绝，不能在首次 INSERT 中静默截断。

## 3. 方法与状态边界

### 3.1 recordFailure

保留既有 SQL、where 条件、返回值、`next_retry_at` 参数和 Shanghai `updated_at` 语义；仅将绑定的 error 参数改为 `clipIntentError(error)`。`INIT`、`RESERVE_UNKNOWN`、`RESERVED` 三类源状态继续允许记录失败，其他状态保持 0 行更新。

### 3.2 markReserveUnknown

保留既有 `status='INIT'` 条件、目标状态 `RESERVE_UNKNOWN`、参数和更新时间；仅将绑定的 error 参数改为 `clipIntentError(error)`。非 `INIT` 状态继续零行更新，不能因为裁剪而改变状态或其他列。

两方法均不得修改 id、orderNo、userId、idempotencyKey、itemsSnapshot、createdAt 等不可变事实；合法更新仅改变既有错误、nextRetry/status 和 updatedAt 列。

## 4. 数据库与兼容边界

- 以真实 MySQL `VARCHAR(512)` 的 Unicode code point 宽度为验收标准，同时覆盖 strict/non-strict SQL mode。
- 不改变字符集、collation、列宽、schema、索引、事务传播、错误码或 Service 调用顺序。
- 不把错误文本裁剪扩展到其他 Mapper；其他字段的现有行为保持。
- 不修复历史已经截断或损坏的 `last_error`，不新增迁移或补偿任务。
- `nextRetryAt`、`updatedAt` 的来源与精度保持现有契约，本轮不引入新的 Clock。

## 5. 验收矩阵

1. 真实 MySQL strict/non-strict 下，`recordFailure` 与 `markReserveUnknown` 覆盖 null、空串、短文本、512 ASCII/BMP/emoji、513 ASCII，以及 511 ASCII + emoji、混合前缀和 513 个 emoji；保存结果按 code point 精确符合裁剪契约。
2. 512 个 emoji 完整保存，不因 UTF-16 长度被误裁成 256 个；超过 512 个时保留前 512 个完整 code point，不产生孤立 surrogate。
3. `recordFailure` 在 `INIT`、`RESERVE_UNKNOWN`、`RESERVED` 下保持原状态并更新错误/退避；`markReserveUnknown` 仅 `INIT` 迁移，其他状态零行且整行不变。
4. 每次更新验证 id、orderNo、userId、idempotencyKey、itemsSnapshot、createdAt 保持；合法更新仅影响规格允许的 error/status/nextRetry/updated 字段。
5. 回归 17AO 首次严格 INSERT、普通订单创建精度、17AR/17AS 恢复测试和原订单库 checksum；隔离库用例清理完整。

## 6. 明确不承诺

本轮只修普通预扣意图两处错误文本的 Unicode 截断，不提供其他错误字段的统一长度策略，也不承诺历史错误文本修复或跨服务日志编码一致性。
