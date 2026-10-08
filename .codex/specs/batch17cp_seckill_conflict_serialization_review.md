# CM-20260924-20：秒杀冲突证据序列化评审

## 1. 结论与范围

结论：**GO，限定 `recordSeckillConflict` 的证据序列化和稳定事件号生成。**

当前序列化失败会伪造 `{"serializationError":"true"}` 并正常返回，可能 ACK 没有可靠证据的冲突消息；稳定 ID 的 fallback 还会再次序列化或使用 `Map.toString()`，造成标识与证据分离。本批只修冲突取证，不改变正常创建、已提交重放、普通订单、Stock、消息/DTO/DDL、指纹或历史行。

## 2. 单次序列化与异常边界

`recordSeckillConflict` 必须先调用 `ObjectMapper.writeValueAsString(event)` 恰好一次：

- 成功得到 payload 后，稳定 eventId 按非空原 eventId → `SeckillRequestFingerprint.eventId(orderNo)` → `legacy-seckill:hash:` + SHA-256(payload) 顺序生成；stable helper 接收成功 payload 字符串，不再次序列化，不使用 `Map.toString()`；
- 序列化抛 Exception 时抛 `IllegalStateException("秒杀冲突证据序列化失败", cause)`，不提交新 claim、订单、conflict 或 Resolve，不正常返回或 ACK；若冲突发生在既有 claim=0/读取路径，允许原有 claim/read 尝试，但既有事实保持不变。外层保持原 Rabbit retry/DLQ 包装并保留两层 cause；
- Error 不捕获、不包装，保持现有传播；
- payload 成功后后续 Mapper 写入错误仍沿原失败/ACK 语义，不伪造证据或重写 eventId。

缺失/空白 requestHash 仍沿原 SHA-256(payload) 规则；CN 17 的 raw/payload 双写必须使用同一成功 payload 文本。历史 `serializationError` 行不自动回溯或伪造修复。

## 3. 兼容与验收

- 显式 eventId、非空 orderNo、匿名 JSON 的成功 ID 结果保持原值；正常创建和已 COMMITTED 重放不调用冲突序列化。
- 前置非法字段路径覆盖零 claim/业务 RPC；取证路径覆盖显式 eventId、仅 orderNo、匿名 JSON 三种稳定 ID 层级，以及既有 dedup claim=0 后的 orderNo/hash 冲突。序列化异常均无新 conflict/订单/Resolve 提交，既有事实保持，异常 cause 两层保留；Error 原样传播。
- 健康冲突记录验证 CN 两列原文一致、唯一键和重复幂等不变；历史 `serializationError` 记录只保留人工取证边界。
- 不把替身 Jackson 故障描述为真实 Rabbit 坏 Map，也不扩展 decoder、原始 body、指纹、状态或数据库迁移。
