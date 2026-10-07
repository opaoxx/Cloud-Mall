# CM-20261007-20：Stock marker UNKNOWN 写入隔离评审

## 1. 评审结论与范围

结论：**GO，限定隔离 `recoverPendingMarkers` 中 marker 缺失且超过 grace 后的 `markSeckillUnknown` 数据库写失败；不改变 marker GET、scan/cursor、accept、outbox、状态、schema、API 或失败补偿。**

当前 Redis GET 已按 17AC 单条隔离，但 marker 缺失且超过 grace 后的 MySQL `markSeckillUnknown` 仍直接冒泡，可能阻断同批后续记录和独立 outbox 扫描。

## 2. 单次 UNKNOWN 写边界

仅对该一次 `markSeckillUnknown` 调用增加小范围 try/catch：

- DataAccessException：记录 noteMysqlFailure + WARN，continue 后续 marker 候选；不重试、不 accept、不清理 marker、不改变 row/cursor、不额外猜测已提交；
- 返回 1：保持原“已转 UNKNOWN”日志和后续流程；
- 返回 0：保持原 alreadyCommitted/事实核验语义，不增加新的补偿或状态；
- 非 DataAccess RuntimeException/Error：继续传播，保留原编程/业务错误边界；
- scan SQL DataAccessException：继续原传播并结束本轮；
- Redis GET、17AA 时间判断、17AB keyset 游标、accept catch 和独立 outbox 扫描语义不变。

若数据库在 UPDATE 已提交后客户端收到异常，不能二次写、不能通过查询猜测 UNKNOWN/COMMITTED，失败行保留数据库真实事实，后续扫描再处理。

## 3. 验收矩阵

1. 真实 MySQL 首 PENDING 的 UNKNOWN 转换触发器失败：失败行整行保持，后续 matching marker 行仍能 accept，并沿既有 outbox/PUBLISHED 流程继续。
2. 模拟 UPDATE 已提交后抛 DataAccessException：不二次写、不猜测状态，保留真实 UNKNOWN/已提交事实，独立 outbox 仍可继续。
3. `markSeckillUnknown` 非数据库 RuntimeException/Error 继续抛出；扫描 SQL DA 继续传播。
4. 0 行结果继续执行原 alreadyCommitted/事实核验，不新增补偿。
5. Redis/acceptance/Rabbit 使用替身时只验证本地状态/CAS；不宣称共享 Redis 或真实 broker 故障闭环。

## 4. 明确排除

本轮不新增 state/schema/API，不增加失败补偿/重试台账，不修改 marker/UNKNOWN 业务语义、cursor、lease、时间、accept 或 outbox；只避免单条 UNKNOWN 写数据库故障阻断同批。
