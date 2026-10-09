# CM-20261009-17DR：订单超时消息单 JSON Root 解析评审

## 1. 结论与范围

结论：**GO，仅让 timeout Message 入口拒绝有效 JSON 后的额外 root token，避免多根内容进入订单业务路径。**

当前 `ObjectMapper.readTree(rawPayload)` 默认允许读取首个 JSON root 后忽略尾随有效 JSON；合法 envelope、`{}`、`[]`、`null`、布尔、多个 quoted 订单号等内容可能因此进入业务查询并产生错误 404/格式分类。本批只收紧该入口的单 root 解析，不改变原 fallback 或业务规则。

## 2. 解析契约

每次 Message 解析使用不可变 per-call reader：

```java
mapper.reader()
    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .readTree(rawPayload)
```

该配置只作用于本次 tree 读取，不调用全局 `mapper.enable`、不改变共享 ObjectMapper、duplicate-key、JSON 宽度或其他解析规则。尾随空白仍合法。

- `MismatchedInputException`（包括单 root 读取发现尾随有效 JSON 的该类结构错误）转换为原格式 `IllegalArgumentException` 并携带原 exception cause；不委托 raw legacy，不访问订单 Mapper/Stock/RPC。
- 其他 `JsonProcessingException` 继续原 `rawPayload.trim()` fallback，再由原 String 业务格式规则决定；不宣称所有非法语法统一改为同一错误分类。
- DP integral whole ASCII digits、quoted JSON、raw 字母 fallback、DQ object 原生文本门和 String 业务（月/格式/身份/到期/CAS/action）保持。

## 3. 不变量与验收

- 多根/尾随有效 JSON 不进入业务 Mapper；合法单根 envelope 及原空/空白/不支持 root 拒绝行为保持。
- 不改 ACK/retry、事务、TTL、outbox、SQL、DDL、proof、配置、消息或 consumer 业务逻辑，不自动重放历史消息。
- 新 unit：8 类 multi-root（valid envelope、`{}`、`[]`、`null`、数字、text、true、quoted orderNo + `{}`/第二 quoted）均在业务前拒绝；4 类合法格式用真实 Message + String 业务路径到达原查询/CAS=0，且共享 Mapper 配置 flag 前后保持 false；6 类 empty/unsupported root 保留原格式拒绝。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
