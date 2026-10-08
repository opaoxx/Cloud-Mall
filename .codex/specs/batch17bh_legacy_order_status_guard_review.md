# CM-20260924-20：Legacy 订单状态白名单评审

## 1. 评审结论与范围

结论：**GO，限定修复 Legacy 库存只读查询对未知订单状态默认按 CONFIRMED 处理的问题。**

当前 `legacyStockActionStatus` 仅把 `PENDING_PAYMENT` 视为 NONE、把 `CANCELLED` 视为 ROLLBACK，其余状态全部默认 CONFIRM。未知、null（经 view 转为字符串）、大小写变体、尾随空格或自定义状态因此可能借 Stock 目标状态返回 SUCCEEDED/CONFLICT。

本轮只增加 Legacy 终态可判定白名单，不修改订单 status 原值、状态机、SQL、schema、JSON/API、Pay、写路径、恢复器或历史数据。

## 2. Legacy 状态门

`legacyStockActionStatus` 保持现有顺序：

1. `PENDING_PAYMENT` 精确返回 `NONE`，不调用 Stock；
2. 仅精确允许 `PAID`、`COMPLETED`、`CANCELLED` 进入 Stock 只读查询；
3. 其他任何状态直接返回 `UNKNOWN`，不调用 Stock、不 repair、不修改订单或 action。

允许状态的方向保持既有语义：

- `PAID`、`COMPLETED` 期望 `CONFIRMED`，相反为 `ROLLED_BACK`；
- `CANCELLED` 期望 `ROLLED_BACK`，相反为 `CONFIRMED`。

状态比较区分大小写，不 trim、不大小写归一化、不从其他字段推断。订单 response 的原始 status 字段继续原样返回，不改写为规范状态。

## 3. 与 BG/BD 读取边界

- 已有 action 时继续由 17BG expectedActionType/tuple 门决定；未知订单状态下 action 已存在仍按 BG 返回 UNKNOWN，不进入 Legacy。
- 只有无 action 且订单状态为允许的三类终态时，才进入 17BD Legacy Stock orderNo 身份门和原响应状态矩阵。
- 无 action + PENDING_PAYMENT 仍 NONE；无 action + 未知状态 UNKNOWN 且零 Stock 调用。
- 跨用户 403、数据库/SQL 异常传播和全路径只读语义保持。

## 4. 兼容与排除边界

- 不修改订单 status 存储、view 映射、状态枚举、CAS、action 写入、Pay 结算或库存恢复。
- 不修复历史未知状态，不额外查询数据库或 Stock，不增加字段、API、schema、事务或 RPC。
- 不把未知状态防护描述为历史数据修复、真实支付事故或退款事故闭环。

## 5. 验收矩阵

1. unit 覆盖 null/Map 转为字符串的 `null`、blank、`paid`、`cancelled`、`PAID `、重音/大小写变体、OTHER/CLOSED 等状态；每种 Stock 状态均返回 UNKNOWN 且零 Stock 调用。
2. 真实物理 VARCHAR 状态覆盖未知合法值，无 action 时只读返回 UNKNOWN、全表快照不变；不伪造 schema NOT NULL 的 null 行。
3. 精确 PAID/COMPLETED/CANCELLED 的 Legacy 18 状态矩阵保持；PENDING_PAYMENT/NONE、BD response 身份、已有 action/BG 优先、跨用户 403 和 SQL 异常回归保持。
4. 不修改订单原 status、payment-state JSON、action/Stock/Pay 数据；原订单 checksum、fixture 清理和既有创建/恢复测试通过。

## 6. 明确不承诺

本轮只限制 Legacy 可判定终态，不清洗历史未知订单状态，不推进状态机，不修其他订单读取入口或实际支付/退款事故。
