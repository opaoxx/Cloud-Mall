# CM-20260924-20：订单 Outbox 身份候选扫描评审

## 1. 结论与范围

结论：**GO，仅修复 timeout outbox 扫描窗口被空白 eventId/orderNo 占满的问题。**

DA/CR 只保护 dispatcher 当前 PO，坏身份行仍可能在 SQL LIMIT 50 前占据窗口。本批在 `findPendingOutbox` 的既有 status、event_type、attempts、due 条件之后、LIMIT 前增加 eventId/orderNo 非 Java whitespace eligibility。

## 2. SQL 契约

使用 Java 17 固定的显式正则模式，两个列分别绑定同一 pattern 参数：

`[^\x{0009}-\x{000D}\x{001C}-\x{0020}\x{1680}\x{2000}-\x{2006}\x{2008}-\x{200A}\x{2028}-\x{2029}\x{205F}\x{3000}]`

SQL 使用 `REGEXP_LIKE(event_id, ?, 'c')` 与 `REGEXP_LIKE(order_no, ?, 'c')`，不使用 TRIM、ICU `[:space:]` 宽类或 BINARY regex；prepared binding 避免 SQL 字面量反斜杠问题。该模式与 Java 17 `String.isBlank` 的 25 个 whitespace code points 对齐，允许任一非空白字符原样保留。

保留方法签名、原 due 值和 limit 值、status/type/attempts、排序和投影；SQL 新增两个相同 pattern 绑定，参数顺序为 `due, pattern, pattern, limit`。NULL 仅 unit seam 验证，真实 Schema NOT NULL 不改。regex 查询异常继续传播，不自动降级。

## 3. 验收与边界

- 真实 MySQL：50 个空/全空白 orderNo（eventId 唯一）后合法事件可扫描并发布，坏行完整保持；覆盖 Java 25 whitespace 与相邻边界、NBSP/U+2007/U+202F、emoji、混合值和首尾非空字符。
- unit 仅回归已有 20 项 guard/null/错误/中断/proof；JavaBlank 真值矩阵由真实 SQL IT 覆盖，不新增 regex raw seam 或声称 unit 对照 SQL。仍不宣称所有 Unicode SQL 组合、大数据性能或全局 DB 故障窗口覆盖。
- 不修改写 SQL、proof、consumer、DDL、index、API、消息、历史记录或 DA 当前 PO guard；JDK17 是固定运行基线，升级需另行评审。

## 4. 数据库/API 应用语义

`order_outbox.event_id/order_no` 既有 NOT NULL、VARCHAR、索引和历史事实保持；本批只在 LIMIT 前增加身份 eligibility，拒绝空白候选，不自动修复或删除坏行。
