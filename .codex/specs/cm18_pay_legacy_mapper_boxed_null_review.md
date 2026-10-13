# CM-18 16T164：Pay Legacy mapper boxed NULL

## 1. 结论与范围

GO。PayDebitIntentPO 的 id/userId/attempts 为 boxed 类型，但 ROW_MAPPER 使用 getLong/getInt，把 SQL NULL 映射为 0，掩盖真实历史 NULL。

本批只修三项 JDBC 映射表达，不改变业务/SQL/domain API。

## 2. Exact mapping

ROW_MAPPER 仅替换：

- `id = resultSet.getObject("id", Long.class)`；
- `userId = resultSet.getObject("user_id", Long.class)`；
- `attempts = resultSet.getObject("attempts", Integer.class)`。

其它字段、日期、amount、SQL、PO 类型/注解/API/DDL 保持。NULL 真实保留为 null；非 NULL 0/positive/negative/Integer boundary 数值保持。

## 3. 保持项与边界

T163 SQL attempts>=0 自然拒绝 NULL/负值，不自动取得恢复资格；不做 nullable migration、历史修复、SQL NULL 布尔语义或时间 NULL 保护。既有 nonnull 业务/资格/lease/RPC 回归保持。

## 4. 验收

- Actual native JDBC projection：不 ALTER 生产 schema，通过 test JdbcTemplate 子类固定 SELECT projection 验证真实 ROW_MAPPER/driver NULL 与数值映射；full facts 只读。
- 正常 0/positive/negative/boundary 与 T158–T163 回归；资源 finally 清理，不启动 App/RM/TC。

## 5. 交付边界

实现仅允许上述三项 ROW_MAPPER 赋值、准确 JavaDoc/测试；不得修改 SQL、PO 类型、Service/API、DDL 或历史数据。
