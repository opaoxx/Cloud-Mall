# CM-20261007-20：秒杀死信原始字节保存评审

## 1. 评审结论与范围

结论：**GO，限定修复 `persistDeadLetter` 对非法 UTF-8 原始消息的保存边界；不改变 schema、API、状态机、lease、ACK/NACK 或历史数据。**

当前 `new String(message.getBody(), UTF_8)` 使用替换策略，非法字节可能被替换为 U+FFFD，造成不同原始字节归一为同一文本并被 eventId/hash 幂等合并，或被替换后的 JSON 误判为可重放消息。合法 JSON 中真实存在的 U+FFFD 字符不能被误判为非法编码。

## 2. 严格解码契约

死信持久化入口必须先使用 `CharsetDecoder` 配置 `CodingErrorAction.REPORT` 解码 UTF-8：

- 合法 UTF-8：沿现有 JSON/legacy 解析、normalize、saveDlq 和 ACK 流程；合法文本中的 U+FFFD 保持正常业务语义；
- `CharacterCodingException`：不得进入 JSON 解析、legacy normalize、Stock 验真或自动重放；按原始 bytes 生成隔离证据；
- 其他编程/数据库异常继续沿现有保存失败、延迟重投和 ACK/NACK 边界，不被吞掉。

## 3. 非法字节隔离证据

非法 UTF-8 采用原始 bytes 的 SHA-256 生成稳定身份：

```text
eventId   = legacy-seckill:bytes:<sha256>
requestHash = <sha256>
status    = CONFLICT
payload   = {"rawPayloadBase64":"<Base64(bytes)>","rawEncoding":"base64","reason":"INVALID_UTF8"}
```

隔离 payload 必须是合法 JSON 文本，保存仍使用原 `recoveryNow()`、`saveDlq` 和既有 ACK/NACK 流程；不得将原始 bytes 直接按字符串拼入 JSON。相同原始 bytes 重复投递应由 17I/17Q 精确 eventId/hash/JSON 幂等返回 0，保留首记录全字段；不同 bytes 不得合并。该状态不能进入自动 replayDue 发布路径。

推荐使用 private helper 完成 decoder/Base64/证据构造，不新增 public 类型、数据库列或外部 API；若在入口提前持久化后 return，必须保持与正常 persistDeadLetter 相同的异常、ACK 和重投语义。

## 4. 不变边界

- 合法 UTF-8 中文、emoji、合法 U+FFFD 和正常 JSON/legacy payload 不进入 INVALID_UTF8 隔离；
- 非法 bytes 嵌入看似完整 JSON 时仍优先按字节解码失败处理，不生成 REPLAY_PENDING 业务事件；
- 不自动修复历史已被替换/丢失的 bytes，不重新构造 eventId，不改变历史 payload；
- 不改变 17P 严格保存、17Q eventId 精确身份、17I JSON 比较、17N recoveryClock、17R ACK/NACK 和 17S interrupt 语义。

## 5. 验收矩阵

1. 80/81 等非法 UTF-8 bytes 分别持久为 CONFLICT，Base64 可逐字节还原，eventId/hash 稳定且不相同。
2. 非法 bytes 嵌入业务 JSON 不被替换后重放，状态为 CONFLICT，Stock/Rabbit 不被调用。
3. 相同非法 bytes 重复保存全字段不变；不同 bytes 不误幂等。
4. 合法含 U+FFFD、中文、emoji 的 UTF-8 JSON/legacy payload 正常沿原流程保存/ACK。
5. 真实 MySQL 验证 payload/eventId/hash/时间和原库 checksum；Channel ACK 使用既有替身，不宣称真实 broker 网络验证。

## 6. 实施门与范围声明

实现限定在原始 bytes decoder 和非法编码隔离证据构造；不新增 schema/API/状态、消息拓扑或历史迁移，不扩展其他 recovery/lease 问题。本轮只防止非法输入的字节丢失和错误幂等合并。
