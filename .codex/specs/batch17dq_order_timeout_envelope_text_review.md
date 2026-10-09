# CM-20261009-17DQ：订单超时 Envelope 必填文本字段评审

## 1. 结论与范围

结论：**GO，仅收紧 timeout object envelope 的必填字段类型和空白语义。**

当前 object 分支使用 `asText()` 读取 `eventType/eventId/expireAt/orderNo`：缺失、null、数字、布尔或数组可能被转成文字/空字符串，导致坏 envelope 静默进入原订单查询、格式错误分类不稳定，甚至把非文本 `orderNo` 送入业务 Mapper。本批只保护 object envelope 入口，不改变 DP 裸数字兼容或原 String/fallback 路径。

## 2. Object envelope 契约

object 分支必须同时满足：

- `eventType` 使用 `textValue()` 精确等于 `ORDER_TIMEOUT`；非文本节点自然不匹配；
- `eventId`、`orderNo`、`expireAt` 使用私有 `hasNonblankText(JsonNode event, String fieldName)` 校验：节点存在、`isTextual()` 为 true 且 `textValue().isBlank()` 为 false；
- `orderNo` 始终使用原 `textValue()`，不 trim、不转换数值、不拼接、不校验额外格式/宽度；envelope 的 `expireAt` 仅执行文本资格校验，不解析、不转换、不与数据库日期匹配；到期判定仍使用订单数据库事实中的 `expireAt`。

缺失、JSON null、空字符串、ASCII/Unicode 空白、数字、布尔、数组、对象或 eventType 别名均抛原 `IllegalArgumentException` 格式错误，不委托 `consumeTimeout(String)`，不访问订单 Mapper、Stock/RPC 或业务状态。`catch` 仍只捕获 `JsonProcessingException`，不得把坏 object 转进 DP legacy fallback。

## 3. 兼容与不变量

- DP 的整份 ASCII digits integral root→raw String 兼容不变；quoted JSON string、raw 字母 fallback、合法 object envelope 和原非法 root 行为保持。
- 原订单号格式/月路由、精确身份、expireAt 到期判断、状态 CAS、order_stock_action、事务、ACK/retry、TTL/DLX、outbox SQL/DDL、proof、公开 API 和消息拓扑不变。
- 不增加 eventId 前缀/宽度校验，不解析 expireAt，不修改可选 payload/trace/null 字段，不自动重放历史坏消息。

## 4. 验收矩阵

- unit：对 eventId/orderNo/expireAt 的 missing/null/empty/ASCII+em-space blank/number/bool/array/object 共 24 组合，均在业务 Mapper/RPC 前格式拒绝；eventType 七类非精确值拒绝，orderNo 含空白原样进入原业务格式规则，不 trim。
- unit：合法 object 使用真实 Message 和实际 String 业务方法到达原查询/CAS=0 且不调用 Stock；quoted/raw fallback、DP 数字 root 和其他既有非法 root 回归保持。
- 不改 envelope 必填字段之外的 payload/trace 行为，不扩展 content type、全面 JSON grammar 或历史重放规则。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
