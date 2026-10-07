# CM-20261007-20：秒杀死信持久化时钟一致性评审

## 1. 评审结论与范围

结论：**GO，限定修复 `SeckillMessageRecovery.persistDeadLetter` 的首次持久化时间来源；不改变 schema、API、状态机、JSON 幂等、lease 或历史数据。**

当前 `persistDeadLetter` 最终 `saveDlq(..., LocalDateTime.now())` 使用 JVM 默认时区和纳秒精度，而 17K recovery scan/claim 使用固定 `+08:00`、毫秒语义的私有 recoveryClock。JVM 默认时区为 UTC 或本地偏移不一致时，首轮 `next_retry_at/created_at/updated_at` 会与恢复扫描事实偏移；月末亚毫秒值还可能在 MySQL DATETIME(3) 写入时发生跨月四舍五入。

## 2. 持久化时钟契约

`persistDeadLetter` 必须复用现有 `SeckillMessageRecovery` 私有 `recoveryClock` 和 `recoveryNow()` 语义：

- 每次死信持久化调用只采样一次 Shanghai `+08:00` 时间；
- 立即向下截断到 DATETIME(3) 毫秒；
- 将同一个值传给 `saveDlq`，由 Mapper 写入 `next_retry_at`、`created_at`、`updated_at`；
- 不再直接调用 `LocalDateTime.now()`，不读取 JVM 默认时区；
- 不改变 `saveDlq` 的 JSON 原始载荷、eventId、request_hash、状态和 INSERT IGNORE 语义。

该时间值是持久事实的接收/调度时间，不是事件 occurredAt；事件 occurredAt 仍由 17H 时间门独立核验。

## 3. 失败、重复与 ACK 边界

- 合法/非法 JSON、legacy payload 和冲突 payload 均使用同一 recoveryClock 时间语义进入原有保存/冲突路径；非法 JSON 的原始 payload 保留规则不变。
- 相同 eventId/hash/JSON 等价重复仍由 17I MySQL JSON 幂等比较返回 0，三列时间和全部状态/lease/退避字段保持首记录事实，不更新时间。
- 首次持久化失败或 publisher/延迟重试确认失败继续沿现有 Channel ACK/nack 语义，不把时钟修复变成额外状态转换。
- 不自动修复历史 recovery 记录，不迁移已有默认时区时间。

## 4. 验收矩阵

1. 真实 MySQL + `persistDeadLetter` + Channel ACK 替身，固定 Instant/Clock 并切换 JVM 默认时区，验证 `next_retry_at=created_at=updated_at` 使用同一 Shanghai 毫秒值。
2. 月末 `23:59:59.999500` 等纳秒边界向下截断，不被 MySQL DATETIME(3) 四舍五入到下月；首轮 recovery scan 在同一 now 下可 due。
3. 合法 JSON、非法 JSON、legacy payload、冲突 eventId 和等价重复 JSON 均验证原有保存、冲突、ACK/nack 和 JSON 首载荷语义不变。
4. 重复 saveDlq 返回 0 时三列和 status/attempts/lease/next_retry/error 全量保持不变。
5. 17K recoveryClock、17J completionClock、17I JSON 幂等和 Order 现有 DLQ/recovery 单测回归通过。

## 5. 实施门与范围声明

实现仅允许把 `persistDeadLetter` 的最终时间参数替换为复用的 `recoveryNow()`；不新增 Bean/配置/API，不改变 saveDlq 签名、表结构、状态机、lease、Rabbit 拓扑或历史记录。本轮只修首次持久化时区/精度一致性，不宣称完整恢复分布式时钟或 broker 网络问题已闭环。
