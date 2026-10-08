# CM-20260924-20：库存动作查询身份评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderStockActionMapper.statusForOrder` 受数据库 collation 影响而借用别订单 action 行的问题。**

当前查询只按 `order_no=?` 返回 status。CI/AI/PAD SPACE 等 collation 可能让大小写、重音或尾随空格不同的 orderNo 命中别行，`paymentState` 随后把该 status 当作当前订单事实，或错误回退到 Legacy 查询。

本轮只增加 action lookup 的候选身份核验，不修改 schema、索引/collation、Service API、Pay/Stock、actionType/key/attempts、订单查询或其他入口。

## 2. Mapper 查询契约

保持 `statusForOrder(String)` 方法签名和单次 SQL 查询，WHERE 仍使用既有 `order_no=?`，以便识别数据库 collation 命中的候选；不得简单改成 BINARY 过滤后把 alias 伪装成“无 action”。

SQL 投影改为至少包含 `order_no,status`，映射为现有 `OrderStockActionPO` 或等价内部行对象，在 Java 中执行：

```java
Objects.equals(requestOrderNo, storedOrderNo)
```

- 无候选行：继续返回 null，`paymentState` 沿原 Legacy fallback。
- 有候选且 orderNo 精确匹配：返回原 status，包括 PENDING/UNKNOWN/SUCCEEDED/CONFLICT 等既有值。
- 有候选但 orderNo 不精确匹配或存储值缺失：返回非 null 的既有 `UNKNOWN`，阻断 Legacy fallback，不修改 action 行。
- 数据库/连接/SQL 错误继续原异常传播，不猜测 null 或 UNKNOWN。

精确比较区分大小写、重音和尾随空格，不 trim、不归一化、不重建订单号，不增加第二次查询。

## 3. paymentState 集成边界

保持当前 Service 的 action 优先级：`statusForOrder` 返回任意非 null（包括身份不可信时的 UNKNOWN）时，不调用 Legacy Stock 查询；只有真实无候选的 null 才走 17BD Legacy 身份门。

因此：

- 精确 action 行返回原 status，原 payment-state 格式保持；
- collation alias 命中返回 UNKNOWN，不能借用别行 status，也不能回退 Legacy 重新授权 SUCCEEDED/CONFLICT；
- 真实无 action 仍沿 17BD 正确响应身份和 PENDING_PAYMENT/NONE 语义；
- 跨用户 403 仍在 action/Stock 查询前返回；全路径只读。

## 4. 兼容与排除边界

- 不迁移字符集、collation、索引，不修复历史 alias action 行。
- 不新增 PO、API、状态、RPC、写操作、repair 或库存动作。
- 不增加 actionType、operationKey、attempts、lease、user/items 等守卫；这些由其他批次负责。
- 不修改 `findOrder` 请求身份、17BD Legacy 响应门、Pay 结算或其他 HTTP 入口。
- 本规格 supersede 17BD 中关于“本轮不修 statusForOrder collation/alias”的排除项，仅限该 Mapper 查询身份；17BD 其他边界保持。

## 5. 验收矩阵

1. 真实 MySQL source clone 验证默认 utf8mb4_unicode_ci 的候选 collation 命中；精确 orderNo 在四种 action 状态下原样返回。
2. 大小写、重音、尾随空格三类 alias 在四种 action 状态下均返回 UNKNOWN；action 表完整不变。
3. 缺失 action 返回 null 并继续 17BD Legacy：正确 response orderNo 可保持原 SUCCEEDED/CONFLICT/NONE 矩阵；存在 alias action 时即使 Stock 返回目标状态也保持 UNKNOWN 且不调用 Stock。
4. 真实 Service 读取订单/action mapper 验证 JSON payment-state 字段、订单归属和只读事实；跨用户 403 不调用 Stock，PENDING_PAYMENT 仍 NONE；不写 Pay、Order、Action 或 Stock。
5. SQL/连接错误继续传播，原订单 checksum、随机 fixture 清理和适用全套回归保持；Stock/RPC 替身不宣称真实网络或退款事故验证。

## 6. 明确不承诺

本轮只防止 action lookup 的 collation alias 借行，不提供字符集迁移、历史行清洗、订单查询身份修复、跨服务 exactly-once 或其他 action 字段合法性校验。
