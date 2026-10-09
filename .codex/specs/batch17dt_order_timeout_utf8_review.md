# CM-20261009-17DT：订单超时 Message 严格 UTF-8 解码评审

## 1. 结论与范围

结论：**GO，仅在 timeout Message 进入 JSON/legacy 解析前使用严格 UTF-8 decoder，拒绝 malformed byte sequence。**

当前 `new String(message.getBody(), UTF_8)` 会替换非法字节；损坏的 eventId、optional trace 或 nested payload.note 仍可能形成可解析 JSON 并进入业务。Java CharsetDecoder 默认替换策略不能作为消息完整性证明。本批只保护 Message 初始解码，不改变 body、content type、encoding 猜测或后续 JSON/业务规则。

## 2. 解码契约

每条 Message 在 `readTree`、DP/DQ/DR/DS object 解析和业务委托前，以新建 decoder 执行：

```java
StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(message.getBody()))
    .toString()
```

`CharacterCodingException` 转为原格式 `IllegalArgumentException` 并携带 cause，立即结束，不访问订单 Mapper、Stock/RPC 或 String 业务；原 body bytes 不修改、不重写。合法 UTF-8 的中文、emoji、真实 U+FFFD（EF BF BD）继续允许；不读取 content type/encoding，不设置全局 charset，不增加 fallback 解码。

## 3. 不变量与验收

- DP integral ASCII raw、DQ object 文本门、DR single-root、DS duplicate-key、quoted JSON、raw letters 和 String 业务路径均在严格解码之后沿原规则运行；SQL/事务/ACK/TTL/配置/proof 不变。
- unit/IT 覆盖 lone continuation、截断序列、bad continuation、overlong、surrogate UTF-8、超 Unicode 范围等 12 malformed byte 组合；验证 cause 为 `CharacterCodingException`、body 原样、无 Mapper/Stock/RPC。
- 合法中文、emoji、真实 U+FFFD、quoted/raw digits/raw letters 继续到原查询/CAS0；不禁合法 U+FFFD。
- 不承诺 escaped JSON 孤立 surrogate、全字段语义/尺寸、历史 binary replay 或其他全服务解码策略。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
