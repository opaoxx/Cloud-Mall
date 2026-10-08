# CM-20260924-20：普通订单 Legacy 库存响应身份评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderServiceImpl.paymentState` 在无 `order_stock_action` 记录时读取 Legacy Stock 响应缺少订单号身份校验的问题。**

当前 `legacyStockActionStatus` 只检查响应 envelope、data 和 status。错单或缺失 `data.orderNo` 可能被当作当前订单的 CONFIRMED/ROLLED_BACK，进而影响支付结清或退款授权。

本轮只保护无动作行的 Legacy 只读查询，不修改已有 action 行优先级、Mapper、SQL、API/JSON/Feign 契约、订单状态、租约、中断、支付流程或其他 HTTP 入口。

## 2. Legacy 响应身份门

在既有响应成功且 `response.data != null` 后、比较 expected/opposite 与响应 status 之前，执行：

```java
Objects.equals(order.orderNo, response.data.orderNo)
```

精确区分大小写、重音和尾随空格，不 trim、不大小写折叠、不从请求路径或 status 补全订单号。

以下情况均返回 `UNKNOWN`，不进入 SUCCEEDED/CONFLICT：

- data.orderNo 为 null 或 blank；
- 返回其他订单号；
- 大小写、重音、尾随空格不同；
- response data/orderNo 无法提供合法原始字符串。

Stock envelope 非成功、data 缺失、Feign/熔断异常继续返回原 `UNKNOWN` 语义。

## 3. paymentState 路径边界

1. 先按现有 `find(orderNo)` 和用户归属校验执行；跨用户请求必须先返回 403，不调用 Stock。
2. `orderStockActionMapper.statusForOrder(orderNo)` 非 null 时，继续直接使用已有 action 状态，不进入 Legacy 查询，本轮不改变 action 优先级。
3. 仅 action 状态为 null 时调用 `legacyStockActionStatus(order)`；`PENDING_PAYMENT` 继续直接返回 `NONE`，不调用 Stock。
4. 非待支付订单继续在现有位置按订单状态计算 expected/opposite：CANCELLED 期望 ROLLED_BACK，其他既有支付/完成终态保持期望 CONFIRMED；本轮不移动该本地计算。
5. 响应身份正确后，期望状态返回 `SUCCEEDED`，相反状态或 REJECTED 返回 `CONFLICT`，其他状态返回 `UNKNOWN`；身份错误一律 UNKNOWN。

本方法全程只读：不得写 order、stock action、库存流水、支付事实或 repair 数据。

## 4. 兼容与排除边界

- 不修改 StockReservationStatusVO 字段、Stock Feign 方法、Stock 服务响应分支或错误码。
- 不修复 `statusForOrder` 的 collation/alias、`findOrder` 身份、其他支付 HTTP 入口或已有终态 action。
- 不新增 RPC、查询、表字段、状态或库存动作；不把 Stock/RPC 替身测试描述为真实错单退款事故。
- 正确身份的 Legacy 响应仍沿原支付状态矩阵；错误身份不会授权结清或退款。

## 5. 验收矩阵

1. unit 覆盖 PAID/COMPLETED/CANCELLED 与 CONFIRMED/ROLLED_BACK/REJECTED/RESERVED/INIT/NOT_FOUND，以及 null/blank/别单/大小写/重音/尾随空格身份；错误身份均返回 UNKNOWN。
2. 正确身份的已有 Legacy 合法 fixture 必须显式携带真实 `orderNo`；正确状态矩阵保持 SUCCEEDED/CONFLICT/UNKNOWN，不能用缺失身份伪造合法响应。
3. 真实隔离 MySQL 无 action 行时，Service 读取真实订单并调用 Stock 替身；错误响应只返回 UNKNOWN，不写任何订单、action、支付或库存事实；修正为正确响应后可返回原成功状态。
4. 回归 PENDING_PAYMENT/NONE、已有 action 优先、跨用户 403 先于 Stock、envelope 错误/异常 UNKNOWN、订单字段/归属/payment-state 格式、原订单 checksum 和随机 fixture 清理。
5. 不宣称真实 Stock 错单网络故障、Feign 网络或退款事故验证；只验证本地响应身份门和只读状态映射。

## 6. 明确不承诺

本轮只防止 Legacy Stock 响应错单影响 paymentState 结清/退款授权，不提供 Stock 响应签名、历史数据修复、跨服务 exactly-once 或其他入口的身份校验。
