# CM-20261009-17DV：订单超时必填文本 Unicode 配对评审

## 1. 结论与范围

结论：**GO，仅收紧 timeout object 的 eventId/orderNo/expireAt 必填文本，使 JSON 解码后的 Java String 必须是 well-formed UTF-16。**

DT 已保护消息原始 UTF-8 bytes；但 JSON escaped 的不配对代理序列可按 RFC 8259 ABNF 进入 Jackson，并在 Java String 中形成孤立 UTF-16 surrogate。当前 native/nonblank 文本门会放行：eventId/expireAt 继续查询/CAS，orderNo 走原业务 404，而不是前置格式错误。本批增加项目层文本约束，不把它称为 UTF-8 或 JSON grammar 错误。

## 2. Unicode 文本门契约

在现有 `hasNonblankText(event, fieldName)` 的 textual + `isBlank` 门之后，对 `eventId`、`orderNo`、`expireAt` 线性检查 Java UTF-16 code units：

- 高代理必须紧邻一个低代理并成对消费；
- 孤立低代理立即拒绝；
- 其他 BMP 字符、合法代理对、中文、emoji、真实 U+FFFD 保持；
- 不 trim、替换、归一化、重编码或修改原 String。

校验失败沿现有 object 格式 `IllegalArgumentException` 返回，不委托 String legacy、不访问订单 Mapper/Stock/RPC。eventType 继续精确 ASCII `ORDER_TIMEOUT`；本轮与原入口均不校验 Message 中 `expireAt` 的 ISO 语法，也不以它替代数据库期限事实，实际到期判定仍由原订单数据库 `expire_at` 路径处理。可选字段保持原语义。

## 3. 兼容与验收

- 不改变 DT strict UTF-8、DR single-root、DS duplicate-key、DP digits、quoted/raw String、全局 ObjectMapper、SQL/DDL、DB expire_at 权威、CAS/action、事务、ACK/retry、TTL 或公开 API。
- 新 unit 使用真实 Message 和实际 String 业务方法：eventId/expireAt 各 8 类合法 JSON escaped 孤立代理组合均前置拒绝且无 SQL；orderNo 同组合沿前置格式拒绝而非原 404；合法代理对/中文/emoji/U+FFFD/quoted/raw 数字字母保持原路径。
- 本批不宣称完整 Unicode/JSON 标准、所有字符串字段或真实 broker/全链路数据库验收；回归保留现有订单 SQL 与 outbox/Rabbit 证据。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
