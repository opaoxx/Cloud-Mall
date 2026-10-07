# CM-20261007-20：秒杀死信恢复认领后 attempts 快照评审

## 1. 评审结论与范围

结论：**GO，限定修复 replayDue 使用扫描旧快照计算 attempts 的陈旧写问题；不改变 claim 计数、退避算法、表/API、续期或网络发送原子性。**

当前扫描行可能在 claim 前读取 attempts=0；另一 worker 完成并释放 lease 后，旧扫描线程仍可能 claim 成功并用旧值计算 retryCount，导致 attempts/退避回退。修复必须以本次成功认领后的持久快照作为验真、发送和完成计数来源。

## 2. 认领后持久快照

`replayDue` 成功 claim 后立即调用 Mapper 固定语义方法 `findClaimed(long id, String owner)`，返回当前 owner/有效 lease 下的完整快照：

```text
id,event_id,request_hash,payload,attempts,status,lease_until
```

读取条件必须精确包含：id、`BINARY lease_owner`、非空 lease、`lease_until > queryNow`、四个可恢复源状态。查询返回后 Mapper 内部再次用 fresh completionClock 检查 deadline；无记录、数据库异常、owner 不符、状态终态或 lease 已到期均跳过本轮，不调用 Stock、不发送 Rabbit、不写完成。

后续 verifyStockFact、payload 解析、envelope 发送、retryCount 和 markReplayed 都必须使用该快照，不能继续使用 `findDue` 返回的旧 Map。`findClaimed` 复用 17J/17K 的精确 owner、有效 lease、四状态 SQL guard；结果返回后仍由 Mapper 用 fresh completionClock 复核截止时间。后置 17K `hasActiveLease` 和 17J 完成 CAS 继续保留。

## 3. attempts 单调与边界

- claim 本轮不承担 attempts 递增；保留现有 attempts 由有效完成写回的原语义。
- retryCount 使用认领后快照 attempts + 1；Service 先校验 attempts 是整数 `0..Integer.MAX_VALUE`，再用 long 计算并饱和到 `Integer.MAX_VALUE`，不得 int 溢出。
- 当前持久 attempts 为负数或无法解释时不猜测归零：记录可观测错误，跳过 Stock/Rabbit，不执行完成写，保留原 lease/事实等待后续处理。
- `markReplayed` 原 SQL 增加 `attempts>=0 AND attempts<=retryCount` 单调条件；保持 public void 签名不变，retryCount 负值自然零更新。条件失败时零更新，不覆盖更高计数的新事实；不改变 `markVerification` 的计数语义。
- retry 退避算法 `2^min(n,8)` 保留当前实现上限（实际 cap 为 256 秒），只替换 n 的来源为认领后快照和饱和后的 retryCount，不宣称 300 秒上限。

## 4. 竞争语义

旧 scan 行在 claim 前被其他 worker 完成：旧线程 claim 失败或认领后快照反映新 attempts，不会回退计数。

旧线程认领成功后，快照读取/Stock RPC/发送期间 lease 仍可能过期或被接管；沿 17K 守卫和 17J 完成 CAS 处理，已发消息无法撤回，不宣称网络原子性闭环。

## 5. 验收矩阵

1. 真实 MySQL interleaving：findDue 返回旧 attempts，另一 worker claim+完成提高计数并释放 lease，原 scan 后续正确读取新快照或跳过，不回写旧计数。
2. 快照查询 expired/equal/NULL/wrong owner/终态/数据库异常均不外发、不完成；lease 和原事实保持。
3. attempts 正常值、`Integer.MAX_VALUE` 饱和、负数拒绝均通过真实 Mapper/Service 验收；不出现负数或溢出退避。
4. `markReplayed` 单调条件阻止 retryCount 较小的迟到完成写，高计数事实和 payload/status/lease 不被覆盖。
5. 17J/17K lease 完成守卫、Stock/Rabbit 替身确认、原 eventId/orderNo/hash/payload 稳定和原库 checksum 回归通过。

## 6. 明确排除

本轮不把 attempts 递增移到 claim，不改表/API、findDue 批量候选、lease 续期、发送前后非原子窗口、Rabbit 拓扑或其他 stale worker 问题；只修认领后快照和完成计数单调性。
