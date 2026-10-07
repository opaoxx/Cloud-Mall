# CM-20261007-20：秒杀死信恢复完成写 lease CAS 评审

## 1. 评审结论与范围

结论：**GO，限定保护 `SeckillMessageRecoveryMapper` 的完成写；不改变 claim/findDue、Rabbit 发送、状态机、表结构或公开 API。**

当前 `markReplayed`、`markVerification` 只按 id/lease_owner 更新。租约过期但尚未被接管时，迟到 worker 仍可释放 lease、写入退避或覆盖状态；`saveDlq` 将同 eventId 冲突标记为 CONFLICT 后，原 worker 仍可能用迟到 `markReplayed` 覆盖冲突。

## 2. 完成写 CAS 契约

保持现有 public 方法签名和 `void` 返回不变。每次调用在 Mapper 内采样一次新鲜的上海 DATETIME(3) `now`，不得复用 scheduler 批次 now 或 nextRetryAt：

```text
id = ?
AND BINARY lease_owner = BINARY ?
AND lease_until IS NOT NULL
AND lease_until > now
AND status IN ('REPLAY_PENDING','REPLAYED','QUARANTINED','VERIFYING')
```

UPDATE 的 `updated_at` 必须使用同一次 `now`。租约过期、`lease_until=now`、NULL、owner 大小写不同、owner 已被替换或源状态已为 CONFLICT/RESOLVED 时更新行数为 0，不清理新 lease、不改变 next_retry_at/error/status。

### 2.1 markReplayed

只允许在上述源状态内将记录写为既有 `REPLAYED`，并按原参数写 attempts/next_retry_at/error；不新增目标状态。真实 DLQ 冲突置为 CONFLICT 后，旧 worker 的迟到 markReplayed 必须零更新。

### 2.2 markVerification

只允许现有调用方使用的目标状态 `QUARANTINED`、`VERIFYING`、`CONFLICT`；未知目标值在 Mapper/语义方法入口拒绝，不扩展状态机。源状态仍必须属于四个可恢复状态；已 CONFLICT/RESOLVED 不得被改写。成功写入和零更新均保持现有 void 调用语义。

## 3. Clock 测试接缝

Mapper 增加私有 `Clock completionClock = Clock.system(ZoneOffset.ofHours(8))` 或等价字段；不注册 Bean、不外部配置、不改变公开构造器。测试可用 ReflectionTestUtils 注入固定 clock，验证 `lease_until > now` 的严格边界。claim/findDue 的现有 now、到期条件和批处理时钟本轮不修改。

## 4. 竞态边界

- saveDlq 先将同 eventId 冲突标为 CONFLICT：原 worker 后续完成写必须被源状态条件阻止；首载荷和冲突证据不被覆盖。
- 新 worker 已替换 owner：旧 owner 的完成写必须被 BINARY owner 条件阻止。
- 活跃 owner/lease 且源状态合法：markReplayed/markVerification 正常更新，并清理自己的 lease。
- 本轮不增加发送前二次 lease 检查、不续期 lease、不修复批次内共享时钟、不宣称完整分布式 worker 竞态闭环。

## 5. 验收矩阵

1. 真实 MySQL 固定 completion clock 验证 expired、equal、NULL、wrong-case owner、替换 owner 均全快照不变。
2. 活跃 owner/lease 下 markReplayed 和 markVerification 均可正常完成，updated_at 使用同一次 fresh now。
3. 源状态 CONFLICT、RESOLVED 下两类完成写均零更新；目标状态非法不写入。
4. saveDlq 真实冲突后迟到 markReplayed 不覆盖 CONFLICT；首 payload/hash/status/lease/退避保持。
5. 真实 MySQL 覆盖两个方法、lease 释放和重试 claim 可继续；原始表 checksum 不变。
6. SeckillMessageRecovery 重放/死信 ACK 回归保持通过；不把替身 Rabbit 测试表述为真实 broker 网络验收。

## 6. 明确排除

本轮不改 `saveDlq` JSON 幂等、findDue/claim 到期查询、发送前 lease 复查、lease 续期、scheduler 批次时钟、Rabbit 拓扑、状态表结构或 API；只修完成写的本地 lease/state CAS。
