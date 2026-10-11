# CM-18 16T30：Pay 消费 Order 基本证据原生解码修正

## 1. 结论与范围

本切片 GO，基线为 `f8841a1`，只修正已评审 16T3 Order 基本证据消费者对 JSON token 的解码边界。现有 `OrderClient.OrderView` 的 Long/BigDecimal 自动 Jackson 转换可能把 userId 文本、小数 userId、numeric 金额（例如 `10.00`）强转为合法对象，导致取证误判为 MATCHED；金额文本 `10.0000` 则是合法的无损尾零兼容输入。本切片只替换取证 Service 的 Order 依赖和 DTO 解码，不扩充 Order 证据范围，不改变旧 OrderClient 或任何业务调用。

## 2. GET-only 依赖与调用链

新增取证专用 `OrderBasicEvidenceDTO`：`orderNo`、`userId`、`payAmount`、`status` 四字段均为 JsonNode 原生 token。新增唯一 contextId 的 `OrderBasicEvidenceClient`：

```text
GET /api/orders/{orderNo}
X-User-Id: <trusted Pay anchor userId>
```

只替换 16T3 取证 Service 的依赖；既有 `OrderClient`、paid/cancel/paymentState、其它业务 Feign 和全局 JSON 反序列化保持不变。Order GET 仍是事务外一次 GET，沿 3 秒连接、5 秒读取和默认 NeverRetry；Pay 首次/第二次 RR、可信 anchor、AuthContext clear/finally restore、固定 callback/caller、Gateway/no-store/local-change 优先全部沿 16T3。

不接受 HTTP 提供 orderNo/userId/金额/XID/branch/operation key，不调用 Order 写接口、paymentState、Stock、User、TC、MQ。取证仍只报告 Order 基本四字段，不宣称 items、地址、时间、库存动作、退款或完整订单快照。

## 3. 原生 token 校验

校验顺序在字段输出或匹配前完成，错误不强转、不回显原 body：

- `orderNo`、`status` 必须是原生文本且长度不超过 128；status 只允许 16T3 白名单 `PENDING_PAYMENT/PAID/COMPLETED/CANCELLED`；缺失、数字、数组、对象、非文本 status 或超过固定 128 字符上限均为 `ORDER_EVIDENCE_INCOMPLETE`。
- `userId` 必须是原生 integral number，可无损转换为 positive long；文本数字、小数、浮点、decimal、boolean、溢出和非正值均为 `ORDER_EVIDENCE_INCOMPLETE`。
- `payAmount` 必须是原生文本且长度不超过 128，匹配不带空白、`+`、指数或其它前缀的十进制格式；解析为 BigDecimal 后要求 `stripTrailingZeros().scale()<=2` 且绝对值不超过 `9999999999999999.99`。`10.0000` 等无损尾零文本合法；zero 可是合法格式，但与 Pay 正 anchor 不相等时归冲突。数字 token、float token、缺失、非法格式、超长和超容量均为 `ORDER_EVIDENCE_INCOMPLETE`。
- 四字段原生类型和格式均合法，但 orderNo/userId/payAmount 与可信 Pay anchor 不一致时为 `ORDER_EVIDENCE_CONFLICT`。合法 `CANCELLED + Pay COMMITTED` 仍只报告基本匹配，不推断库存、退款或资金终态。

字段 token 缺失、类型或格式非法时，仅该字段对应的指纹/金额摘要为 null；不能保留非法 token 原文，也不能生成假转换指纹。其它字段若合法则沿原 T3 的逐字段指纹规则保留；字段均合法但 tuple 与 anchor 矛盾时仍沿原 T3 冲突分类和合法字段指纹，不把 Jackson 默认强转结果当作证据，不改变旧生产 API 的 JSON 兼容语义。

## 4. 输出与不变契约

响应继续使用 16T3 的 `scope=PAY_ORDER_BASIC_ONLY`、`automaticActionsAllowed=false`、Shanghai `collectedAt`、Pay localEvidence、Order 基本摘要、固定 reasonCodes 和字段级指纹。分类保持：无 anchor 不发 GET；GET/Order DB/transport/error envelope 归固定 `ORDER_EVIDENCE_UNAVAILABLE`；原生 token 缺失或类型/格式错误归 `ORDER_EVIDENCE_INCOMPLETE`；合法字段与 anchor 矛盾归 `ORDER_EVIDENCE_CONFLICT`；四状态基本匹配最高为 `BLOCKED_REMAINING_EVIDENCE`。

原 T3 的 Order/Pay 双快照与 AuthContext 隔离不改；只读、无写、无 Order 状态动作、无 Stock/TC/MQ 访问不改。失败不输出 raw error、原 body、XID、branch、operation key、凭证或未经核验的摘要。16T3 的 `CANCELLED + COMMITTED` 兼容行为保留。

## 5. 验收与边界

- Unit 正式覆盖三类坏 token：string userId、numeric/float userId、numeric/float payAmount，以及缺失/非文本 status、超长/指数/加号/空白金额；均必须在 Order 证据前置校验中阻塞，不能进入 MATCHED。覆盖 `10.0000` 等合法尾零、zero 格式与四个合法 status。
- 真实 owned Pay/Order/Feign 验收使用 CommonWebConfiguration 的真实金额字符串序列化，不把 standalone fixture 的数字金额误当生产响应；真实 HTTP 响应 token 变异只验证取证消费者，不改变 Order 生产 API。Pay/Order 原表 checksum、订单和资金 facts 不变，资源 finally 清理。
- 继续覆盖 16T3 的 trusted anchor、唯一 `X-User-Id`、双 Pay RR、local change、no-store/Gateway、无 POST、3s/5s/NeverRetry 和缺表/错误响应；不启动业务 App、TC 或固定端口。
- 不改 Order DTO/Controller/Mapper/DDL/状态机/动作、旧 OrderClient、全局 CommonWebConfiguration、Stock/User/TC/MQ、旧业务 JSON 反序列化或 CM18 M2–M4。原 SKU、库存动作、统一证据包和主项闭环仍未覆盖。
