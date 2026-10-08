# CM-20260924-20：订单 Outbox Payload 身份一致性评审

## 1. 结论与范围

结论：**GO，仅校验 timeout outbox payload 中已存在的三项身份字段。**

Dispatcher 当前 envelope 使用 row 的 eventId/eventType/orderNo，但 payload 内同名字段未核验，可能出现内外两套身份。本批在 parse 成功、CU 中断检查之后、TTL/send 之前增加一致性门。

## 2. Payload identity guard

仅当 payload `containsKey` 时校验以下字段：

- payload `eventId`、`eventType`、`orderNo` 的 value 必须是 String；
- 分别使用 row 对应原字符串的 `equals(Object)` 精确比较；null、非 String、大小写/重音/尾空格差异均 mismatch；
- 字段缺失保持既有旧 minimal-payload 兼容，不补键、不 trim、不 toString、不重建 payload；
- mismatch 抛 `IllegalArgumentException`，沿原 general catch 记录一次 failure/退避，不调用 Rabbit、不完成；后续候选继续；
- 原 parse、expireAt/TTL、CU 中断、CS/CR/CV 数据库异常边界保持。guard 不扩展 payload root 语法、其他字段或 MQ/SQL 原子性。

## 3. 验收与数据库/API 语义

- 真实 MySQL + Rabbit 替身覆盖 valid expireAt 下三字段 null/非 String/alias mismatch 不发布；合法 full payload 与缺字段 legacy payload 保持原流程。
- unit 覆盖 guard 前中断、payload mismatch failure、后续候选、Error/DB 失败边界；不改变 scan、Mapper SQL、proof、DDL、index、API、消息结构或历史记录。
- `order_outbox.payload JSON NOT NULL` 继续保存原 JSON；本批只校验已存在的 identity 字段，不新增字段、不修历史 payload、不回填缺失键。

