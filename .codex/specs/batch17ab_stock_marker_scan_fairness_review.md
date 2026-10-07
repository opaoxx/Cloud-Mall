# CM-20261007-20：Stock 秒杀 marker 扫描公平性评审

## 1. 评审结论与范围

结论：**GO，限定修复秒杀 marker 缺失扫描的低 id 前缀阻塞；不改变状态、payload/hash、accept CAS、outbox、schema 或 API。**

当前 `findPendingSeckillReservations` 固定按 `updated_at,id LIMIT`，旧时间前缀（不一定是低 id）的 UNKNOWN/PENDING 记录可能长期占据窗口；而 marker 缺失转 UNKNOWN 只处理 PENDING，UNKNOWN 不更新时间，导致后续可处理记录长期不可见。新旧扫描必须统一按 id 游标，避免 updated_at 排序在 keyset 切换时漏候选。

## 2. 非持久 keyset 环形扫描

保留现有首段候选方法名但改为与 afterId 段相同的 id 语义，并增加语义 Mapper 方法：

```text
findPendingSeckillReservationsAfter(long afterId, int limit)
```

SQL 必须保持原事实筛选和投影，只增加：

首段查询也必须投影 `id`，并使用 `ORDER BY id LIMIT #{limit}`；不能继续按 `updated_at,id` 排序。首段与 afterId 段共享同一 id 键序。

```sql
status IN ('PENDING','UNKNOWN')
AND id > #{afterId}
ORDER BY id
LIMIT #{limit}
```

Dispatcher 增加私有 nullable `Long markerScanAfterId`：

1. null 时调用首段扫描；非 null 时调用 afterId 扫描；
2. 后段非空时，本轮只处理该批，并在逐条 marker 外部操作前将游标前移到该批最后 id；不因单条操作异常回退游标；
3. 后段为空时，同一轮最多回绕一次首段；回绕批次为空则结束；不为补满 batch 再循环；
4. 整轮总候选不超过原 batchSize，partial batch 不补满；
5. 查询失败不前移游标，保留原 marker/30 秒/accept CAS/state/outbox 语义；
6. 进程重启游标从 null 开始，游标不写数据库、不新增 schema。

回绕后的首段结果若非空，游标前移到首段最后 id；低 id 新插入记录可在回绕或下一轮被重新观察。删除最高游标记录不影响下一次 afterId 查询，后段为空即可回绕。

## 3. 业务边界

- 不改变 UNKNOWN 的含义，不强制更新时间，不把 UNKNOWN 改回 PENDING；
- marker 缺失的 PENDING 仍按原逻辑写 UNKNOWN，marker 存在/状态受理仍按原 accept CAS；
- 不改变 Redis marker、stock reservation、seckill outbox、payload/hash、订单号或状态机；
- 调度仍为本地常规单线程 scheduled 扫描，非持久 cursor；不保证频繁重启或无限流入负载下的全局公平，只消除单轮低 id 窗口永久霸占。

## 4. 验收矩阵

1. 真实 MySQL 构造超过 batchSize 的低 id UNKNOWN 前缀和后续 PENDING：连续 dispatch 能观察后段记录，不因 UNKNOWN 不更新时间阻塞。
2. UNKNOWN marker 后续出现时，keyset 回绕能再次检查并沿原状态/marker 语义处理。
3. 低 id 新记录在回绕后可见；删除最高游标 id 后仍能回绕；空后段只回绕一次。
4. 单批 limit、partial batch、总候选上限严格保持；查询失败不推进游标，下一轮可重试。
5. 逐条 marker/accept/outbox 操作异常不回退已前移游标，也不修改未处理记录的事实。
6. 原 Redis/Stock/MySQL marker、payload/hash、UNKNOWN/PENDING 状态和原库 checksum 回归通过。

## 5. 实施门与范围声明

实现限定在 Mapper keyset 查询和 Dispatcher 私有非持久游标/单次回绕；不新增状态、字段、API、持久调度表、状态偏好或自动修复 UNKNOWN，不扩展为多实例全局公平/无限流量调度方案。
