# CM-20261007-20：Stock 秒杀 outbox attempts 边界评审

## 1. 评审结论与范围

结论：**GO，限定修复秒杀 outbox attempts 的 INT 溢出与负值污染；不改变表、签名、API、状态机、退避、批次或中断语义。**

当前 claim 直接 `attempts+1`，达到 INT 上限时严格 MySQL 可能报错并中断 dispatch；负 attempts 还可能被自动加一后继续外发或被误当作正常计数。

## 2. Claim 饱和契约

claim SQL 保留现有认领时点和所有 lease/state 条件，只将递增改为：

```sql
CASE WHEN attempts < 2147483647 THEN attempts + 1 ELSE attempts END
```

合法 attempts `0..2147483647` 均可认领；MAX 值保持饱和，不溢出、不 abort 整批。DEAD/UNKNOWN/PENDING 的既有重试、NACK、10 秒退避和完成 CAS 语义保持。

## 3. 负值 fail closed

候选扫描 SQL 必须在 LIMIT 前增加 `attempts>=0`，避免低 id 负值记录占用正常窗口。claim、active lease guard、markPublished/recordFailure 完成 CAS 也必须要求 attempts 非负；负值记录：

- 不外发、不调用受理/Stock/Rabbit 写操作；
- 不自动清租约、不重置为 0、不猜测修复；
- 直接 claim/guard/完成写返回 0 或跳过，保留原 row 供人工/独立迁移核验。

## 4. 不变边界

- 不新增表/字段/API/状态，不改变 attempts 递增发生在 claim 的既有时点；
- 不改变 `findDue` 排序、batch-size、marker scan、lease、completion fresh time、17AE guard、ACK/NACK 或中断；
- 一条 MAX attempts 发送失败并进入 DEAD 时，不能阻断同批下一条正常 outbox；
- 不扩展为一般数据库故障隔离或分布式 attempts 全局一致性。

## 5. 验收矩阵

1. 真实 MySQL attempts=INT_MAX-1 claim 后为 INT_MAX；INT_MAX 重复 claim 保持 INT_MAX，无 SQL 越界。
2. 负 attempts 低 id 记录在 LIMIT 前排除，limit=1 可读取后续正常行；负值 direct claim、active guard、两种完成写均零更新且 row 不变。
3. MAX attempts outbox NACK/DEAD 不 abort 整批，下一行可正常 PUBLISHED。
4. 合法 0..MAX 仍遵循原状态、退避、lease、publisher confirm 和完成 CAS。
5. 原库 checksum、payload/eventId/hash、状态和 lease 规则回归通过；不宣称解决其他 DA/中断问题。

## 6. 实施门与范围声明

实现限定在候选/claim/guard/完成 SQL 的 attempts 条件和 claim 饱和表达式；不修改业务服务 API、表结构、状态机、调度预算、Rabbit 拓扑或历史负值数据。
