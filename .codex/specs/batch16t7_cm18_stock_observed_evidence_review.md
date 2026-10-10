# CM-18 16T7：Pay/Stock 只读观察证据评审

## 1. 结论与位置

结论：**GO，作为 CM18 M1 的独立 Stock 观察切片；不依赖 16T2 User/16T3 Order flags，不改变既有 Pay、Stock GET、写状态机或其他取证切片。**

16T7 在 Pay 本地可信 anchor 后，事务外调用现有 Stock `GET /api/stock/reservations/{orderNo}`，只观察订单号、终态和已返回 item vector。它不证明 Order 完整 SKU、Stock scene/user/payNo 归属、余额、原始 flow、跨库原子性或资金终态。

## 2. 入口和安全门

新增独立 default-off 配置：

```text
cloudmall.pay.tcc-stock-evidence.enabled=false
```

新增内部 GET：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation/stock-evidence
```

- 仅检查 16T1 local flag、16T7 flag、callback token、caller=`cm18-reconciliation`、payNo、Spring active transaction 和 Seata XID；不检查 User/Order flags。
- 共享 gate 与 Gateway 双层路径阻断在 Pay/Stock DB、Feign、事务前执行；不接受 HTTP 提供 orderNo、userId、XID、branch、operation key 或 item vector。
- 可信参数只来自 Pay `ReadService.capture` 的原 anchor；无 anchor 时沿原 local classification 增加 `LOCAL_ANCHOR_NOT_TRUSTED`，不发 Stock GET。
- Stock GET 窗口保存/clear/finally 恢复 Pay `AuthContext`，使用 anchor orderNo；Stock GET 不需要 callback token/user header，不能传 caller role。该清理不掩盖 transaction/XID 门，不修改全局 Feign interceptor。

## 3. Stock GET 与响应边界

- 新增只读 `StockEvidenceClient`，`@FeignClient(name="cloud-mall-stock", contextId` 唯一)；只声明现有 reservation GET，不声明 POST。沿现有默认 3s/5s 与 NEVER retry，不新增依赖。
- 接收响应只进入内部 `StockReservationEvidenceDTO`，用 JsonNode 保留 native token 语义；不直接把 Stock VO/raw items 作为外部报告对象。
- `orderNo` 缺失或非 native text 为 `STOCK_EVIDENCE_INCOMPLETE`；native text 但不等于 anchor orderNo 为 `STOCK_EVIDENCE_CONFLICT`，不回显错单原文。stock state 白名单固定为 `RESERVED/CONFIRMED/ROLLED_BACK`，并允许在缺证摘要中展示 `INIT/UNKNOWN/NOT_FOUND/REJECTED`；未识别状态为 incomplete。
- items 缺失、空、非数组、元素缺字段/额外字段、非 native integral number、非正数或越界为 `STOCK_EVIDENCE_INCOMPLETE`；合法重复 SKU 或合法 item state 与 aggregate state 不一致为 `STOCK_EVIDENCE_CONFLICT`，不推断终态。
- 合法 item vector 按 numeric sku 升序拼接每行 `sku:quantity:state;`，字段均为整数/固定 enum 且无歧义，再计算 SHA-256；报告只输出 observed item count、orderNo 指纹、itemVector 指纹，不输出 raw items、订单号原文、错误、operation/key 或凭证。

null/error envelope、null body、4xx/5xx、Feign transport、Stock DB/Controller 不可用均为 `STOCK_EVIDENCE_UNAVAILABLE`；HTTP 409 不自动视为资金冲突。合法 `ROLLED_BACK + Pay COMMITTED` 不作相反终态冲突，后续取消/退款仍需其他证据。

## 4. 双 Pay capture 与分类

1. 第一次 Pay RR capture 必须返回 local `BLOCKED_EXTERNAL_EVIDENCE` 和可信 anchor；Pay RR 事务关闭后执行一次 Stock GET。
2. Stock GET 成功或失败后重新 Pay RR capture；anchor、local snapshot、identifiers、classification、reason 除 collectedAt 外必须相同，否则优先 `LOCAL_EVIDENCE_CHANGED`。
3. 最高只返回 `BLOCKED_REMAINING_EVIDENCE`，并保留 USER/ORDER/TC/MQ 未查询标识；Stock GET 未发起时 skip 也包含 STOCK；发起后只移除 STOCK。响应固定 `scope=PAY_STOCK_OBSERVED_ONLY`、`automaticActionsAllowed=false`、Shanghai `collectedAt`、原 Pay `localEvidence` 和 `stockEvidence={queryAttempted,status,state whitelist,identityMatched,ledgerApplied? absent,observedItemCount nullable,orderNoSha256,itemVectorSha256 only when fully verified}`；不输出资金终态、automatic action 或跨库原子性结论。

允许分类：`LOCAL_ANCHOR_NOT_TRUSTED`（沿原 local 分类）、`LOCAL_EVIDENCE_CHANGED`、`STOCK_EVIDENCE_UNAVAILABLE`、`STOCK_EVIDENCE_INCOMPLETE`、`STOCK_EVIDENCE_CONFLICT`、`BLOCKED_REMAINING_EVIDENCE`。

## 5. 验收矩阵

- Unit：双 flag/gate、无 anchor、native text/order mismatch、状态白名单、UNKNOWN/NOT_FOUND/REJECTED/INIT、null/error/HTTP 失败、item 缺字段/额外字段/非 native number/重复 SKU/排序 vector、永不 POST 和 AuthContext finally 恢复。
- 真实 owned Pay/Stock 库：实际 Stock Controller/SqlMapper 读链和真实 Feign/SpringMvcContract/全局 authHeaders；Stock 只 SELECT，不访问 Redis/Rabbit/TC，不写状态。Pay 两次 RR、owned 资源和原库 checksum 保持。
- Stock sibling target/classes 与标准 Boot jar 已声明 Sentinel core 仅用于测试 childloader，临时资源 finally 删除；不启动业务 App、不占固定端口。
- 本批不验证 Order 完整 SKU、Stock balance、scene/user/payNo 归属、raw flow、单次 GET 后跨库稳定性或真实资金终态。

## 6. 交付边界

本文件只冻结 Pay→Stock 只读观察证据，backend 按本文及 API/DB 相邻条款实现；不修改 Stock 生产 API/写状态机、Pay/User/Order 业务、DDL、消息、Redis 或 CM18 主项关闭判据。
