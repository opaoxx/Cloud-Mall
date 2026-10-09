# CM-20261009-17DU：订单超时关单缺失 Deadline 评审

## 1. 结论与范围

结论：**GO，仅在 `consumeTimeout(String)` 的订单状态门之后、任何状态 CAS/库存动作之前拒绝缺失 `expire_at` 的待支付订单。**

当前 `mall_order_${yyyyMM}.expire_at` 允许 NULL；timeout consumer 只在 `expireAt != null && now.isBefore(expireAt)` 时拒绝未来订单，NULL 会直接继续 CAS 为 `CANCELLED` 并写 rollback action，无法证明已到期。本批补充缺失期限门，避免没有截止时间证据的订单被超时消息关闭。

## 2. 业务门契约

在 `consumeTimeout(String orderNo)` 已读取订单并确认 `status == PENDING_PAYMENT` 后、解析/比较期限和调用 `setStatus` 前：

```java
if (order.expireAt == null)
  throw new BizException(ErrorCodes.STATUS, "订单超时期限缺失，暂不关单", 409);
```

非 `PENDING_PAYMENT` 订单继续原 no-op，包括 NULL deadline 的 PAID/CANCELLED/COMPLETED/OTHER 状态；不会新增查询、回填或动作。待支付且 deadline 非 NULL 时继续使用原 Shanghai `now()` 与 `now.isBefore(OffsetDateTime.parse(expireAt))` 判断：未来仍返回原 409，已过期仍执行原状态 CAS 和 rollback action 意图。不得从 `created_at + 30m`、Message envelope `expireAt` 或当前时间推导/替代订单事实。

## 3. 不变量与验收

- `expire_at` nullable DDL、订单状态机、Mapper/CAS、`order_stock_action`、事务、Stock 调用、ACK/retry、outbox TTL、SQL、API/message 保持；不修复历史 NULL，不改变 DTO 或 schema。
- 真实 SQL IT 覆盖 NULL deadline 的 String/JSON envelope/quoted 三入口：待支付 NULL 保持完整 row、不写 action、不调用 Stock；past deadline 保持原 CANCELLED + rollback action；future deadline 保持原 409；PAID/CANCELLED/COMPLETED/OTHER NULL 保持原 no-op。
- 既有 Service 时间采样、CAS=0 证明、其他异常日期和消息解析边界不扩展；Order 数据库指纹与 owned 隔离库清理保持。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
