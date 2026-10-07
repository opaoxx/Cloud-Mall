# CM-20261007-20：Stock 秒杀 outbox 完成写 lease CAS 评审

## 1. 评审结论与范围

结论：**GO，限定修复 `StockSqlMapper.markSeckillOutboxPublished/recordSeckillOutboxFailure` 的过期 worker 完成写；不改变 claim、发送前 guard、批次时钟、计数、中断、扫描或消息拓扑。**

当前完成 SQL 只按 event_id/lease_owner 更新，未校验 lease 有效期、源状态和 eventId 大小写/尾空格精确身份；旧 worker 可能覆盖 PUBLISHED、过期 lease 或新 owner 的 outbox。

## 2. Mapper 完成 CAS 契约

Mapper 不新增 Clock/Bean；保持调用方即时提供的 `publishedAt`/`updatedAt` 参数语义。每次完成调用先将 caller 时间按 same instant 转固定 `+08:00` 并截断到 DATETIME(3)，再以同一 `completionNow` 绑定 updated_at 与 lease guard：

```sql
WHERE event_id=?
AND BINARY event_id=BINARY ?
AND BINARY lease_owner=BINARY ?
AND lease_until IS NOT NULL
AND lease_until > completionNow
AND status IN ('PENDING','UNKNOWN','DEAD')
```

第一条 `event_id=?` 保留索引定位，第二条 BINARY 条件防止 CI/PAD SPACE 别名误命中。owner null/blank、eventId null/blank、token 大小写/尾空格不完全匹配、过期/等于/NULL lease、新 owner、PUBLISHED/其他终态均返回 0，不清理他人 lease，不抛异常。

## 3. 时间与签名边界

- `markSeckillOutboxPublished`、`recordSeckillOutboxFailure` 的现有 public 签名和 int 返回保持不变；publishedAt/updatedAt 是 caller 完成时刻契约，由 Dispatcher 即时提供，Mapper 只负责规范化并复用；
- published_at/updated_at 使用本次 completionNow；failure 的 nextRetryAt 继续沿用调用方既有退避参数，不能被完成时间替换；failure updated_at 使用 completionNow；
- 原错误截断、其他字段和成功/失败状态语义保持；CAS=0 是正常 stale/竞争结果，不抛错、不二次写。

## 4. 不变边界

不改 claim 计数、发送前 lease guard、批次扫描时钟、17K/17S 中断、Rabbit confirm、payload/eventId/hash、outbox schema 或状态枚举。该批只保护完成写，不能撤回已经发送的消息，也不保证完整分布式网络原子性。

## 5. 验收矩阵

1. 真实 MySQL 固定 caller 完成时间参数验证 lease future 正常完成；expired、equal、NULL、owner case/trailing、replaced owner、PUBLISHED/未知状态均返回 0 且整行不变。
2. eventId case/trailing 别名返回 0，不污染其他记录；valid `PENDING/UNKNOWN/DEAD` 三源状态分别可完成。
3. PUBLISHED 后迟到 failure 不覆盖终态；旧 worker 不能清除新 owner lease。
4. published/updated 使用同一规范化 caller completionNow，failure nextRetryAt 仍为原 caller 退避，错误截断保持原语义；测试用不同参数验证边界。
5. 原 claim、发送、扫描、lease 和 Stock/Rabbit 回归保持；不宣称 Mapper 独立验证墙钟或覆盖 SQL 锁等待期间的时间变化。

## 6. 实施门与范围声明

实现限定在两个 Mapper 完成 CAS、caller 时间规范化和 JavaDoc；不改业务服务、API/schema、claim/guard/scan/interrupt 或历史 outbox，不扩展其他 stale worker 问题。
