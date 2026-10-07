# CM-20261007-20：秒杀死信 JSON 完整性评审

## 1. 评审结论与范围

结论：**GO，限定修复 `persistDeadLetter` 的 JSON 完整性判断；不改变 schema、API、状态机、ACK/NACK、历史数据或 Order consumer 其他 JSON parser。**

当前 `ObjectMapper.readValue(raw, Map)` 默认可能忽略 JSON 文档尾随第二根值/垃圾 token，并在重复字段时以后值覆盖先值；随后 normalize/重序列化会丢失原始歧义，甚至把含尾随 token 的文本作为 REPLAY_PENDING 保存并自动重放。

## 2. 局部严格解析契约

严格 UTF-8 解码成功后，`persistDeadLetter` 只对本地死信解析使用独立 `ObjectReader`：

```text
ObjectReader.with(FAIL_ON_TRAILING_TOKENS)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
```

不得修改注入的全局 ObjectMapper、JsonFactory 或其他业务 parser 配置。严格 reader 必须覆盖：

- 根对象后的第二个 JSON 值，无论第二值为 object、array、scalar 或 null；
- 根/嵌套对象重复 key；
- 尾随非空白垃圾 token；
- 非对象 JSON、非法 JSON 和解析异常。

允许合法 JSON 尾随空白、Unicode、emoji 和普通 legacy 载荷沿原路径处理。严格解析失败沿既有 catch 分支：使用原 UTF-8 文本 SHA-256 生成 `legacy-seckill:hash:<hash>` eventId/requestHash，payload 保存完整原始字符串证据（`rawPayload`），状态 CONFLICT；不得使用规范化后的 Map 重新构造原文。

非法 UTF-8 仍优先走 17T 原始 bytes Base64 隔离分支；不会被严格 JSON reader 替代或误转成文本 hash。

## 3. 持久化、ACK 与幂等边界

- 严格解析失败必须先将完整原始文本证据持久化为 CONFLICT，成功持久化后才按既有 ACK 流程确认；保存失败仍沿 17R 重投/NACK；
- 同一原始文本重复保存使用既有 17I JSON/hash 幂等，返回 0、全行保持不变；
- 不将尾随 token/重复 key 记录为 REPLAY_PENDING，不调用 Stock、不进入自动 replay；
- 不改 eventId/hash 生成规则、列宽守卫、raw bytes 隔离、lease 或历史记录。

## 4. 验收矩阵

1. 真实 MySQL 验证尾随第二对象、array、scalar、null 和 garbage token 均保存完整原文 CONFLICT，不 ACK 前丢证据。
2. 根对象和嵌套对象重复 key 均 CONFLICT，原始文本逐字符保留；不能以后值覆盖前值后继续重放。
3. 合法空白、Unicode、emoji、普通 legacy/JSON 沿原路径；非对象 JSON/非法 JSON 维持原 CONFLICT 语义。
4. 同一原始文本重复全行不变；不同文本/hash 保留首载荷并进入原冲突路径。
5. 注入 ObjectMapper 的全局配置保持不变；其他 Order consumer parser 回归不受影响。
6. 真实 MySQL + Channel/Rabbit 替身验证持久化、ACK/NACK、JSON 幂等；替身不代表真实 broker 网络故障。

## 5. 明确排除

本轮不改 ObjectMapper 全局 Factory、不改 Order consumer 其他解析、不迁移历史歧义 payload、不新增 schema/API/状态，不扩展 17T 原始 bytes、17P 严格列值或 17Q eventId 身份边界。
