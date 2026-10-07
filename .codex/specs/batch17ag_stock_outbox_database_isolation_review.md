# CM-20261007-20：Stock 秒杀 outbox 数据库异常隔离评审

## 1. 评审结论与范围

结论：**GO，限定隔离 outbox dispatcher 单条数据库失败并分离发送与完成写；不改变状态、payload、attempts、lease、10 秒规则、API 或 Rabbit 拓扑。**

当前 claim 无单条 DataAccessException 隔离；dispatchOne 的发送 try 包含 markPublished，已发送成功但 PUBLISHED 写失败会被误判为 publishFailure，再写 UNKNOWN/DEAD；recordFailure 数据库异常还可能终止整批。

## 2. 异常分层契约

- scan 查询 DataAccessException 保持原边界：直接传播并结束本轮；不伪造候选。
- 每个 outbox 候选由外层独立 try 处理：claim/事实读取/状态完成等 DataAccessException 只记录 noteMysqlFailure+WARN 并继续后续候选；失败行不猜状态、不强清 lease、不覆盖未知事实。
- claim 的非数据库 RuntimeException/Error 继续传播；不把所有 Runtime 当作数据库故障。
- 17AE 的 lease guard DataAccessException 仍由其 guard 内部 fail closed/skip 语义负责，不被本轮重复分类。

## 3. dispatchOne 发送与完成写分离

每条候选固定分段：

```text
parse/事实核验/send/confirm try
  → 发送失败时一次 recordFailure
  → 发送成功后 try 外一次 markPublished
```

- send/parse/resolve/confirm 的普通异常沿原路径只调用一次 `recordFailure` 并 warn/return；recordFailure 自身 DataAccessException 交给外层候选隔离，不再次 recordFailure；
- send/confirm 成功后只调用一次 `markPublished`；markPublished DataAccessException 直接向外层候选隔离，不能回写 UNKNOWN/DEAD，不能再次发送或记录 failure；
- markPublished 返回 0/状态竞争仍沿原 CAS 语义，不猜成功/失败、不强行清理别人的 lease；
- payload、eventId、hash、attempts、lease、nextRetryAt 和状态机不变。

## 4. 不变边界

- 不改 scan 查询失败、中断、17AE guard、claim 饱和、完成 CAS、10 秒失败退避间隔、状态枚举或 Rabbit confirm；
- 不强清 DataAccessException 失败行 lease，不以数据库异常推断 publish/commit 结果；
- Redis/acceptance/Rabbit 为替身时只验证本地异常边界，不宣称共享容器/真实网络故障闭环。

## 5. 验收矩阵

1. 真实 MySQL 触发器使首行 claim 失败：记录告警并继续后行；scan 查询失败仍传播。
2. 首行 send 成功但 PUBLISHED 写失败：不调用 recordFailure、不回写 UNKNOWN/DEAD，后行继续。
3. 首行 NACK/failure 写失败：不重复写 failure，后行继续。
4. 事实读取 DataAccessException 不误写 recordFailure；其他 Runtime/Error 仍传播。
5. 正常 PUBLISHED、UNKNOWN/DEAD failure、claim/lease/attempts 和原 payload/hash 回归通过。
6. 原库 checksum、Redis/acceptance/Rabbit 替身和 10 秒失败退避边界保持；不宣称数据库全局故障下所有后续记录成功。

## 6. 实施门与范围声明

实现限定在 dispatcher 候选 try/catch 分层、dispatchOne send/confirm 与完成写边界；不新增 schema/API/state，不改变失败退避、lease 续期、扫描公平或消息拓扑。claim/完成写的其他 RuntimeException/Error 仍传播；send/parse/resolve 的普通异常继续沿既有 recordFailure 分类，不扩大原异常边界。dispatchOne 的 DataAccessException 必须在普通 Exception catch 前直接 rethrow 到候选层，事实读取/完成写数据库失败不得被转换为 recordFailure。
