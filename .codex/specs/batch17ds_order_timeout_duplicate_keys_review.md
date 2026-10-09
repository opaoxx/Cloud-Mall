# CM-20261009-17DS：订单超时 JSON 重复键拒绝评审

## 1. 结论与范围

结论：**GO，仅在 timeout Message 的 per-call tree reader 上拒绝同一 JSON object 内的重复字段名。**

DR 已拒绝多 root，但同一 object 的后值仍可能覆盖前值；重复 `eventId/orderNo/eventType/expireAt` 会掩盖冲突，optional 或 nested 数据也会丢失前值。本批在既有 `FAIL_ON_TRAILING_TOKENS` reader 上增加 `FAIL_ON_READING_DUP_TREE_KEY`，不改变共享 ObjectMapper 全局设置或其他解析策略。

## 2. Reader 契约

timeout Message 使用同一个 per-call immutable reader 同时启用：

```java
mapper.reader()
    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
    .readTree(rawPayload)
```

重复键在任意 JSON tree 深度（root、optional、nested object、array 中 object）均触发 Jackson `MismatchedInputException`，沿 DR 原格式错误分支保留 cause；不进入 DP 数字 legacy、JsonProcessing raw fallback、订单 Mapper、Stock/RPC 或业务状态。重复同值也拒绝；不同 object 各自出现同名字段仍合法。

不调用全局 `mapper.enable`/配置，不启用 `STRICT_DUPLICATE_DETECTION` 作为全局策略，不改 Map/POJO 或其他服务解析。单 root、尾随空白、DP whole ASCII digits、DQ 原生文本 object、quoted/raw String 及 String 业务规则保持。

## 3. 不变量与验收

- object 必填字段、eventType/orderNo 文本门、DB 到期事实、CAS/action、事务、ACK/retry、TTL/DLX、outbox SQL/DDL/proof/API/message 保持。
- unit 覆盖 root 四字段每项 duplicate 的 same-value 与 null-before-valid-value 组合，以及 trace/optional、nested payload/child/array/extension 重复键；均格式拒绝、cause 保留、无 Mapper/Stock 调用，共享 Mapper flags 前后不变。
- 合法单 object、不同 object 同名字段（含 array）、quoted、bare numeric、raw letters 均沿原业务查询/CAS=0；不新增 duplicate-key 迁移、历史重放或 JSON 宽度规则。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
