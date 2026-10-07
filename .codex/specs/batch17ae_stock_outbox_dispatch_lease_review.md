# CM-20261007-20：Stock 秒杀 outbox 逐行 dispatch lease 评审

## 1. 评审结论与范围

结论：**GO，限定修复秒杀 outbox dispatcher 的整批时间/owner复用和受理事实查询前后 lease 失效窗口；不改变 markerClock、状态机、claim 计数、10 秒失败退避间隔、Rabbit 拓扑或网络原子性边界。**

当前 dispatch 整批复用 owner/now，后行可能在旧 lease 到期后才 claim；`dispatchOne` 发送前后没有基于当前 owner/lease 和 outbox/PUBLISHED 状态的数据库事实 guard，失效或接管 worker 仍可能发送。

## 2. 时间与逐行 claim

Dispatcher 增加私有 `Clock outboxClock = Clock.systemUTC()` 或等价测试接缝，不注册 Bean、不外部配置、不改变 public 构造器；该 clock 只用于 outbox scan/claim/完成/失败时间，不能替换 markerClock/marker grace 时间。

- 扫描使用一次 scanNow；
- 每行使用新鲜 claimNow，leaseUntil=`claimNow+30s`；
- claim/finish/failure 的时间按同一 caller 时刻转固定 `+08:00`、截断毫秒；
- 失败退避与 updatedAt 复用一次 fresh 完成 now；原 10 秒失败退避间隔不变。

## 3. Outbox lease guard

Mapper 增加 `findActiveSeckillOutboxLease(id, owner, checkedAt)` 或等价只读方法，返回 deadline：

```text
id=?
AND BINARY lease_owner=BINARY ?
AND lease_until IS NOT NULL
AND lease_until > normalizedCheckedAt
AND status IN ('PENDING','UNKNOWN','DEAD')
```

Service 在 dispatchOne 中：

1. 读取 Stock 受理事实前执行前置 guard；
2. Mapper 查询异常：记录 warning/noteMysqlFailure 并 skip；正常无记录、失效或返回 deadline 在查询返回时已到期：安静 skip，不记数据库故障指标；所有情况均不调用 Stock、不发 Rabbit、不写失败、不清理 lease；
3. 读取并核验 Stock 事实后、`convertAndSend` 紧邻位置再次 guard；
4. 后置 guard 失败同样 skip；Stock 已读取但不可撤回，不声称网络原子性；
5. 只有两次 guard 均通过才发送；发送后仍由既有完成 CAS/17AD lease 保护裁决。

只捕获预期 DataAccessException/空结果作为 fail-closed；unexpected Runtime/Error 保持原异常路径。guard 不续租、不修改 outbox 状态。

## 4. CAS 与扫描边界

- find/claim 的 lease 到期条件统一为 `<= now`，与完成 guard 严格 `> now` 对齐；
- claim 保留现有 `attempts=attempts+1` 认领递增语义，不修改其计数规则；
- outbox 失败退避、updatedAt、PUBLISHED/UNKNOWN/DEAD 状态和原 payload/eventId/hash 保持；
- 扫描查询失败仍按原调度边界处理；本轮不新增全局公平、续租或多实例调度保证；
- 已经发送的消息无法撤回，重复/迟到发送继续依赖 eventId/orderNo/hash 幂等和消费端事实。

## 5. 验收矩阵

1. 慢首行导致第二行按新鲜 claimNow 获得有效 lease，不复用整批旧 lease。
2. claim 后、Stock 受理查询后 lease 到期/owner 被替换/PUBLISHED 终态：前/后 guard 阻止发送和失败写，原 lease/行事实保持。
3. guard 查询异常、空结果和返回期间跨 deadline：skip，不调用 Stock/Rabbit，不清 lease。
4. lease 恰好到期可被新 worker claim；有效 lease完成/失败仍按原 CAS。
5. 发送已在途时后续 lease 失效只能由完成 CAS 拒绝，不能撤回已发消息。
6. 真实 MySQL + Stock/Rabbit 替身验证慢调用、接管、终态、guard 异常、时间边界和原字段稳定；不宣称真实 broker 网络原子性。

## 6. 实施门与范围声明

实现限定在 outboxClock、逐行 claim 时间、Mapper lease 读取和 dispatchOne 受理事实查询前/发送前 guard；不改 markerClock、marker 扫描、状态/API/schema、claim 递增规则、Rabbit 拓扑或自动续租。
