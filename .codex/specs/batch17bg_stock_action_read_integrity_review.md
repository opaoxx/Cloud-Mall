# CM-20260924-20：库存动作读取完整性评审

## 1. 评审结论与范围

结论：**GO，限定修复 `paymentState` 读取已有库存动作时缺少 actionType/operationKey 完整性核验的问题。**

17BE 已绑定 action 行与订单号，但非法 actionType、空/错误/反方向 operationKey 或订单终态与动作方向相反时，仍可能把 action status 直接报告为 SUCCEEDED/CONFLICT，影响支付结清或退款授权。

本轮只保护已有 action 的只读授权证据，不修改状态机写入、恢复器、SQL schema、API/JSON/Feign、租约、计数、Pay/Stock 或历史数据。

## 2. Mapper 查询契约

### 2.1 兼容旧签名

保留既有：

```java
String statusForOrder(String orderNo)
```

仍使用单次 `WHERE order_no=?` 候选查询，但投影完整 tuple：`order_no, action_type, operation_key, status`。Java 私有 helper 依次精确核验：

1. 存储 orderNo 与请求 orderNo `Objects.equals`；
2. actionType 精确为 `CONFIRM` 或 `ROLLBACK`；
3. operationKey 精确等于 `storedOrderNo + ":" + storedActionType`。

任一不符返回非 null 的 UNKNOWN；无候选仍返回 null。比较区分大小写、重音和尾随空格，不 trim、不重建、不修正历史字段。该旧签名不增加 SQL 往返。

### 2.2 带期望方向的 overload

新增内部语义方法：

```java
String statusForOrder(String orderNo, String expectedActionType)
```

复用同一次候选查询和 helper；除 2.1 完整 tuple 门外，再要求 expectedActionType 精确为 `CONFIRM`/`ROLLBACK` 且等于存储 actionType。expectedActionType 为 null 或非法时，只要存在候选 action 就返回 UNKNOWN；无候选仍返回 null。

不得为 overload 新增 SQL、RPC、数据库写入或公共 HTTP 字段。

## 3. paymentState 集成

用户归属校验完成后，根据本地订单状态计算只读期望方向：

- `PAID` 或 `COMPLETED`：`CONFIRM`；
- `CANCELLED`：`ROLLBACK`；
- 其他订单状态：null。

paymentState 使用带期望方向 overload：

- 有 action 且 tuple/方向完整匹配：返回原 status，保持现有 action 优先级；
- 有 action 但 tuple、key 或期望方向不可信：返回 UNKNOWN，绝不进入 Legacy fallback；
- 无 action：继续原 17BD Legacy 路径，包含 PENDING_PAYMENT/NONE；
- 跨用户 403 仍在 action/Stock 查询前返回。

全路径只读，不写 action、订单、库存、支付或 repair 记录。

## 4. 兼容与排除边界

- 不修改 action 写入、恢复器、状态枚举、SQL WHERE、schema、collation、lease、attempts、items 或 user 守卫。
- 不 trim、大小写转换、修复历史 key/type；非法 tuple 只返回 UNKNOWN。
- 旧 `statusForOrder(String)` 继续保留，供其他内部调用兼容；overload 不改变 HTTP/Feign/API 契约。
- 无 action 的 Legacy 状态和 17BD 响应身份门保持；不称历史坏行自动修复或真实资金事故闭环。

## 5. 验收矩阵

1. 真实 MySQL 通过四种 action status 验证精确合法 tuple 原样返回；非法 actionType、null/空 key、别单 key、反方向 key、大小写/重音/尾空格变体均 UNKNOWN。物理 NOT NULL 限制下 null key 可用 unit seam 覆盖。
2. paymentState 覆盖 PAID/COMPLETED 期望 CONFIRM、CANCELLED 期望 ROLLBACK，以及反方向 action；不可信 action 均 UNKNOWN、不 fallback、不调用 Stock。
3. 无 action 时继续 17BD Legacy 正确响应、PENDING_PAYMENT/NONE、已有 action 优先、跨用户 403；SQL/连接错误继续传播。
4. action 表整行、订单/payment-state JSON 和 checksum 保持；恢复器、AV/AW/AX/AY/AZ/BA/BB 及既有 action/creation 回归不变。
5. 验证单次 Mapper 查询、无新增 SQL 往返/写入；Stock/RPC 替身不宣称真实网络或资金事故验证。

## 6. 明确不承诺

本轮只校验 paymentState 的已有 action 读取授权证据，不推进状态、不清洗历史坏行、不修其他 HTTP 入口或全量库存事实归属。
