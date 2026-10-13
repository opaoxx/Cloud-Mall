# CM-18 16T156：Pay Legacy refund marker null

## 1. 结论与范围

GO。Pay `confirmRefundLedger` 核 key/type/APPLIED/amount，却忽略 User operation 的 `refundOperationKey`；User 对 REFUND 按既有契约返回 marker null，非 null marker 是坏证明，不能结清 REFUNDED。

本批只补 Pay 消费既有 REFUND marker 字段的 null 门，不改 User API、DEBIT 外层 marker、GET/RPC 次数或 terminal。

## 2. Null marker gate

在 `confirmRefundLedger` 原 key/type/state/amount 条件中追加 `refund.refundOperationKey() == null`：

- null 保持健康，包括 outer DEBIT marker 使用 custom refund key 的合法历史；
- non-null（空串、空白、自指、其它 key 均包括）沿原 `CONFLICT/409` 与 catch/retry；
- 不新增 GET/RPC，不改 fixed POST key、T77/T155 early/latest proof 或 User response。

## 3. 保持项与验收

early marker 和 latest recheck 两入口均受门；Order 优先、T155等待、terminal/local transaction、refund key、尾零/指数金额与资源 cleanup 保持。不能 REFUNDED 的已发 refund 不撤回、不补退；同 pay 补 marker null 后可恢复且不重复 refund。

- Unit/native：early/latest 非 null marker 变体拒绝；missing/404/Order gate 优先；完整 null marker 健康/custom key/尾零/指数通过。
- Owned Pay + controlled User Feign，原 Pay checksum/pool close；不启动 App/RM/TC，不宣称 DEBIT 最新性、关联集合、资金来源或 fencing。

## 4. 交付边界

实现仅允许在 `confirmRefundLedger` 增加 refund marker null 条件与必要 JavaDoc/测试；不得修改 User/API/SQL/TCC/terminal 或外层 DEBIT marker 语义。
