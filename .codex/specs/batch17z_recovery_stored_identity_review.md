# CM-20261007-20：秒杀恢复持久身份一致性评审

## 1. 评审结论与范围

结论：**GO，限定修复 recovery verify 对持久 row 身份与 payload 身份的核验缺口；不改变正常生成、consumer、API、schema、历史迁移或 legacy 兼容格式。**

当前 verify 主要核对 payload 业务 hash 与 Stock 事实，send 使用 row.event_id 作为消息身份，但未确保 row.event_id/request_hash 与 payload 内 eventId/requestHash 一致。历史 17P 前截断、异常或手工存量可能因此持续重发错误身份，且无法被 17M 的已提交 resolution 正确收敛。

## 2. 存储身份门

在 17H 时间门之后、Stock RPC 之前，必须校验 recovery row 与 payload 的持久身份：

- row.event_id 非 blank String；payload.eventId 非 blank String；二者使用 `String.equals` 精确匹配，大小写、尾空格、重音差异均拒绝；
- row.request_hash 非 null String；payload.requestHash 为 String；
- 先按既有业务指纹计算 payload 的非 null computed hash；computed hash 必须为真实业务 hash；
- row.request_hash 与 payload.requestHash 均与 computed hash `equalsIgnoreCase` 匹配，保留合法 64 hex 指纹大小写兼容；
- 不猜 missing eventId/hash，不补写 payload，不修 metadata，不把当前 eventId/requestHash 重新生成后覆盖原事实；
- 不要求 Stock fact 提供 eventId，继续保持既有 Stock API/legacy 兼容边界。

任一身份字段、类型、精确 eventId 或 hash 关系不满足时，在调用 Stock RPC 前标记原 `CONFLICT`/5 分钟退避，保留 row 的 eventId/request_hash/payload 原值；lease 完成写继续受 17J/17Y CAS 保护。

## 3. Legacy 与正常边界

- legacy payload 只要 row.event_id 与 payload.eventId 均存在且精确相同、row/payload hash 与可计算业务 hash 匹配，即使 payload 不是当前 canonical 形式，也可继续既有 Stock 验真/重放；
- 正常新格式生成路径不变；本轮只保护 recovery 存量身份，不要求迁移历史 row；
- metadata eventId 大小写/尾空格、payload eventId 缺失/数值类型、metadata hash 不同、payload hash 数值/类型错误均在 Stock/Rabbit 前隔离；
- 17M resolution 只处理完整业务身份，异常 row 不因重复消费自动 RESOLVED。

## 4. 不变边界

- 时间门、Stock 证据分层、17J 完成写、17K lease guard 和 17O 状态规则保持；
- 不新增 RPC/API/schema/status，不修改 Stock 事件接口，不校验 Stock.eventId；
- 不自动修复历史 payload/metadata，不改 orderNo、eventId、request_hash 或原始 JSON；
- 完整身份且 hash case 合法时继续原发送；不完整/冲突身份不调用 Stock、不发送 Rabbit。

## 5. 验收矩阵

1. 真实 MySQL 直接种植 metadata eventId 大小写/尾空格/不同值、body missing/numeric eventId、metadata hash 不同、body hash 类型/数值异常：均在 Stock/Rabbit 前 CONFLICT；原 event_id/request_hash/payload/created_at/attempts 保持，status/lease/next_retry/updated_at/last_error 按原 CONFLICT/5 分钟完成语义更新。
2. row/payload eventId 精确相同、metadata/body hash 与 computed hash 合法大小写匹配：继续 Stock 验真和重放。
3. legacy 非 canonical 但内外 eventId/hash 完整一致：保留原兼容路径。
4. 缺失/冲突身份不影响 17Y DataAccessException、lease、退避和 ACK/recovery 语义；不因异常 row 误 RESOLVED。
5. 17M/17H/17O/17P/Q/U/V 相关 MySQL/单测和原库 checksum 回归通过。

## 6. 实施门与范围声明

实现限定在 recovery verify 的持久身份校验 helper 与调用顺序；不改正常格式生成、consumer、Stock API、状态机、schema、历史迁移或 Rabbit 拓扑。本轮只阻止存量身份错配导致的错误重发和无法收敛。
