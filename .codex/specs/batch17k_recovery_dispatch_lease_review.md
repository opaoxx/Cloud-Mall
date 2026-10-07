# CM-20261007-20：秒杀死信恢复 dispatch lease 保护评审

## 1. 评审结论与范围

结论：**GO，限定修复 `SeckillMessageRecovery.replayDue` 的批次 lease 过期和发送前 stale worker 边界；不改变 Rabbit 拓扑、状态机、payload、eventId 或自动续租模型。**

当前 replayDue 整批复用一个 `LocalDateTime now`，最多处理 50 行；后续行可能在 claim 时获得已经过期的 lease。Stock 验真 RPC 返回后也没有重新确认 owner/lease/state，旧 worker 仍可能向 Rabbit 发送或写完成状态。

## 2. Service 时间与逐行 claim

`SeckillMessageRecovery` 增加私有 `Clock recoveryClock = Clock.system(ZoneOffset.ofHours(8))` 或等价测试接缝，不注册 Bean、不外部配置、不改变 public 构造器/API。时间 helper 每次按 Shanghai 毫秒采样：

- 扫描开始用 `scanNow` 查询 due rows；
- 每行 claim 前重新采样 `claimNow`，leaseUntil=`claimNow+30s`；
- 每次 retry/完成计划使用实际失败或完成时新鲜 service now，不复用批次 scanNow；
- 不自动续租；claim 输入必须保持原有效非空 token/leaseUntil>now 约束。

`findDue/claim` 本轮只统一边界为 `lease_until <= now`，与完成写严格 `lease_until > now` 对齐；不新增业务参数或状态。

## 3. 只读发送前 lease 守卫

在 `verifyStockFact` 开始前和 Rabbit send 紧前，各调用一次 Mapper 只读方法 `hasActiveLease(id, owner)` 或等价语义方法。方法内部使用 fresh Shanghai DATETIME(3) now，精确校验：

```text
id = ?
AND BINARY lease_owner = BINARY ?
AND lease_until IS NOT NULL
AND lease_until > now
AND status IN ('REPLAY_PENDING','REPLAYED','QUARANTINED','VERIFYING')
```

守卫失败时不调用 Stock、不发送 Rabbit、不写 replay 完成；当前 worker 直接结束，后续由 due 扫描/新 owner 继续。Stock RPC 返回后必须重新检查，避免慢调用期间 lease 过期、owner 被替换或状态变为 CONFLICT/RESOLVED。

既有 `markReplayed`/`markVerification` 完成 CAS 仍由 17J 负责，发送守卫不能替代完成写 CAS。

## 4. 发送竞态边界

守卫检查与 Rabbit send 之间不是原子操作：检查通过后 lease 仍可能在网络发送期间过期或被接管，已发消息无法撤回。本轮不引入分布式锁、自动续租或发送事务；依赖原 eventId/orderNo/requestHash 幂等和 Order/Stock 消费端事实校验收敛。文档、日志和测试不得宣称发送前检查能够完全阻断在途发送。

Rabbit confirm/returned、lease 完成写、退避和错误记录沿既有逻辑；发送成功后完成 CAS 若因 lease 失效返回 0，不能覆盖新 owner 或冲突终态。

## 5. 验收矩阵

1. 可变 fake clock 验证批次第一行慢调用后第二行按新鲜 claimNow 获得有效 lease，不沿用 scanNow+30s。
2. Stock RPC 返回前 lease 到期、owner 被替换、状态变为 CONFLICT/RESOLVED：不发送 Rabbit、不写完成状态。
3. 发送紧前守卫失败：不发送、不修改 payload/hash/eventId/状态；新 owner 后续可继续处理。
4. 正常 active lease 两次守卫通过，发送原 payload/eventId/hash 稳定，完成 CAS 可正常收敛。
5. `lease_until == now` 不能通过 active lease，随后可按 `<=now` 被重新 claim。
6. 真实 MySQL + fake mutable Clock + Stock/Rabbit 替身覆盖慢调用、到期、owner 替换、CONFLICT/RESOLVED、正常发送和原字段不变。
7. 说明替身 Rabbit 不等价真实 broker 网络 confirm；检查与发送之间的不可原子窗口保留为后续分布式可靠性议题。

## 6. 明确排除

本轮不改自动续租、批次上限、Rabbit 拓扑、payload/eventId/hash、状态机、历史数据、Order API 或 Stock 业务；不扩展到所有分布式发送竞态，只增加本地 lease/state 的新鲜读保护。
