# CM-18 16T3：Pay/Order 基本事实只读证据评审

## 1. 结论与位置

结论：**GO，作为 CM18 M1 的第三个只读切片；独立于 16T2 User 取证，保持 16T1/16T2 行为，不代表 Order 完整快照、库存收尾或 CM18 主项闭环。**

16T3 只依赖 16T1 local flag 与本批 Order flag，复用 16T2 的 Pay `ReadService.capture` 获取可信 Pay anchor，但不要求 16T2 User flag、不调用 User、不返回 User evidence。事务外调用现有 `OrderClient.get(orderNo,userId)` GET，核对订单基本原身份和金额；它不调用 `paymentState`，避免间接查询 Stock 或把库存镜像混入本切片；不向 Order 写状态，不查询 User/Stock/TC/MQ。

## 2. 入口和安全门

新增独立 default-off 配置：

```text
cloudmall.pay.tcc-order-evidence.enabled=false
```

新增内部 GET：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation/order-evidence
```

- 复用 16T1 local flag、callback token、caller、payNo、Gateway 双层路径阻断和 `validateForRead`/context 409，并检查本批 Order flag；不检查或依赖 16T2 User flag。任一门在 Pay DB、Feign、事务前失败，16T1/16T2 原行为不变。
- 不接受 caller 提供 orderNo、userId、金额、XID、branch 或 operation key；这些全部来自第一次 Pay `capture` 的可信 anchor。
- wrapper 先校验所有 flag、凭证、caller、payNo、Spring active transaction 和 Seata XID，再进入独立 ReadService；Order GET 在 Pay RR 事务结束后执行。
- Order GET 窗口保存/清理/恢复 Pay `AuthContext` 的 userId/role，使用显式 anchor userId，防止 Feign `authHeaders` 污染 `X-User-Id`；不传 caller role，不修改全局拦截器。

## 3. 可信 Pay anchor 与 Order GET

只有 Pay capture 返回可信 anchor、local classification 为 `BLOCKED_EXTERNAL_EVIDENCE` 且 Pay 本地摘要保持不变时，才允许调用 Order GET。16T3 不重新构造 anchor，不使用 HTTP 输入身份。

复用现有 `OrderClient.get(orderNo,userId)`，沿现有 Feign 3 秒连接/5 秒读取超时和默认 `NEVER_RETRY`；本批不改 `OrderClient`、Feign 配置或新增依赖。不得调用 `paymentState`，因为该路径可能读取 Stock action；不得调用 `paid`/`cancel` 或任何 Order 写接口。

Order 返回只采集基本证据：`orderNo`、`userId`、`payAmount`、`status`。不把 items、address、created/expire time、stockActionStatus、支付终态或退款事实声称为本批已验证的 Order snapshot。

## 4. 双次 Pay 快照与分类

1. 第一次 Pay capture 结束后关闭 Pay RR 事务，取得原 anchor。
2. 事务外执行一次 Order GET；不持有 Pay 锁、User 事务或 Seata XID。
3. GET 成功或失败后重新执行一次独立 Pay capture；除各次采集时间外，local report、snapshot、identifiers、classification、reason 和 anchor 必须相等，否则优先 `LOCAL_EVIDENCE_CHANGED`。

允许分类：

| classification | 条件 | 处理 |
| --- | --- | --- |
| 原 local 分类 + `LOCAL_ANCHOR_NOT_TRUSTED` | 无可信 Pay anchor | 不发 Order GET |
| `LOCAL_EVIDENCE_CHANGED` | Order GET 前后 Pay capture 改变 | 丢弃组合结果，保留原事实 |
| `ORDER_EVIDENCE_UNAVAILABLE` | null/error response、404/403/5xx、Feign transport 或 Order DB 不可用 | 固定 reason，不写 Pay/Order |
| `ORDER_EVIDENCE_INCOMPLETE` | orderNo/userId/payAmount/status 缺失或 status 未在白名单 | 阻塞，不推断支付/退款 |
| `ORDER_EVIDENCE_CONFLICT` | orderNo、userId 或 BigDecimal payAmount 与 Pay anchor 不一致 | 固定冲突码，不猜订单归属 |
| `BLOCKED_REMAINING_EVIDENCE` | 基本 Order 字段与 Pay anchor 数值匹配，状态为 `PENDING_PAYMENT/PAID/COMPLETED/CANCELLED` 任一 | 仍缺 User/Stock/TC/MQ，不能输出资金终态 |

合法的 `Order CANCELLED + Pay COMMITTED` 不自动视为冲突；本批不判断库存或退款收尾。只有基本身份/金额字段冲突才归 `ORDER_EVIDENCE_CONFLICT`。不输出 raw error、XID、branch、operation key、凭证；摘要使用白名单状态和字段级指纹。

响应固定 `scope=PAY_ORDER_BASIC_ONLY`、`automaticActionsAllowed=false`、上海 `collectedAt`、原 Pay local evidence 和 `orderEvidence` 摘要。成功发起 Order GET 后 `externalSystemsNotQueried` 只移除 ORDER，USER/STOCK/TC/MQ 仍保留；未发起时保留 ORDER。

## 5. 只读实现与验收

- 复用 Order 现有公开 GET → owned/归属校验 → `OrderSqlMapper` 主行只读链；本次只验证一次主行 SELECT 的无锁、无写语义，明细第二 SELECT 不进入报告；不新增 Order API、DDL、Mapper 写方法或生产依赖。不把 paymentState/stockActionStatus 纳入报告，不宣称 Order RR 或完整 snapshot。
- Order 认证必须使用实际 `AuthInterceptor`/Feign authHeaders 语义；显式 anchor userId 必须是唯一 User header，线程中请求方的其他 AuthContext userId/role 不得污染。
- Unit：双 flag、凭证/caller/payNo/context、无 anchor、HTTP 输入身份覆盖、null/error/403/404/5xx、字段缺失/未知状态、wrong order/user/amount、合法四状态和 `CANCELLED + COMMITTED` 均覆盖；不调用任何 POST/Stock/TC。
- 真实 owned Pay/Order 两库：使用 sibling Order classes/target/classes、实际 Controller/get/Mapper、随机 loopback HTTP server 和真实 SpringMvcContract/Feign；只允许 GET，任何 POST 或写 SQL 立即失败。Pay/Order 原库 checksum、主行和所有资金/订单字段前后不变，所有资源 finally 清理。
- 覆盖缺月表 404/503、Order 归属 401/403、Order GET 后 Pay 本地变化、Order tuple 冲突、AuthContext 被设为其他 userId 后线上仍只发送 anchor userId；不启动业务应用、TC 或固定端口。

## 6. 交付边界

本文件只冻结 16T3 M1 只读范围，backend 按本文及 API/DB 相邻条款实施。该切片不读取 Stock、TC、MQ，不声明完整订单快照/库存收尾/退款事实，也不关闭 CM18 主项；CM18 仍需 closure plan 的 M1–M4。
