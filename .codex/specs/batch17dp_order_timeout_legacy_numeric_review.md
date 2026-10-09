# CM-20261009-17DP：订单超时消息裸数字订单号兼容评审

## 1. 结论与范围

结论：**GO，仅兼容历史 timeout 消息中整份正文为 ASCII 数字的裸订单号。**

当前 `consumeTimeout(Message)` 先对 raw body `readTree`；裸数字正文会被 Jackson 解析为 integral number node，因此不会触发原 `JsonProcessingException` fallback，最终按 envelope 格式拒绝。历史消息可能直接携带 `20261042`、超出 long 的数字文本或更长数字订单号。本批只在 Message 解析入口增加精确 legacy 分支，继续委托原 `consumeTimeout(String)`。

## 2. Message 解析契约

在现有 JSON textual/object 分支与原 `JsonProcessingException` fallback 边界内，增加：

```java
event.isIntegralNumber() && rawPayload.trim().matches("[0-9]+")
```

命中后必须使用 `rawPayload.trim()` 原字符串调用既有 `consumeTimeout(String)`；不得 `longValue`、`doubleValue`、`asText()`、数值重序列化、content type 猜测或根据数值大小改写订单号。该分支保留命中正文的原 String，不做数值变换；原 Jackson 解析约束、业务订单号格式和数据库宽度保持，不新增任意长度或前导零识别保证，继续由原订单 Mapper、月份规则、精确 order_no/CAS、到期判断和库存动作决定结果。

以下内容不得进入该 legacy 分支：负数、小数、指数、带 `+`/其他数字前缀、数字正文后附加 JSON/非空文本、混合字母或其他字符。它们沿原 JSON 分支及 `JsonProcessingException` fallback 处理，再由原业务格式规则决定；原 quoted JSON string、合法 timeout envelope object、原字母 raw fallback 和其他非法 root 行为保持。

JSON root numeric 与旧裸数字文本存在语法歧义，本批只识别整份 trimmed body 的 `[0-9]+`，不扩展对象内数字字段规则，不自动重放或修复历史拒绝消息。

## 3. 不变量与验收

- 原 orderNo 格式/月表路由、Mapper 精确身份、订单到期时间、状态 CAS、order_stock_action、事务、ACK/retry、Rabbit TTL/DLX、SQL/DDL、17DD proof 与公开 API 不变。
- 新 3 unit 使用真实 `Message` 和实际 `consumeTimeout(String)` 业务方法，不通过 spy 拦截；覆盖 8 位数字、超 long 25 位数字和更长数字文本，确认到达原 Mapper/CAS，CAS=0 时不调用 Stock。
- 保留 quoted JSON、raw 字母 fallback 和 8 类非法 root 拒绝测试；不改变 envelope 必填/类型规则。
- 不新增 payload 字段、content type 规则、队列/消费者、历史重放或解析全局策略。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
