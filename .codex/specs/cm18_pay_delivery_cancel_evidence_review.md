# CM-18 16T8：Pay 交付与主动取消本地证据评审

## 1. 结论与位置

结论：**GO，作为 CM18 M1 的本地 delivery/cancel 只读证据切片；保持 16T1–16T7 API、local reader 和 six-source Pay capture 不变，不代替 User/Order/Stock/TC/MQ 取证或 CM18 M2–M4。**

本切片只补 Pay 自有 `pay_tcc_delivery` 与 `pay_tcc_cancel` 两张既有台账的同一 RR 读取和一致性摘要。它不调用 Store、ensureTable、锁、写、RPC、TC、MQ 或任何恢复器。

## 2. 入口与读取事务

新增 default-off：

```text
cloudmall.pay.tcc-delivery-evidence.enabled=false
```

新增内部 GET：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation/delivery-evidence
```

- 仅依赖 16T1 local flag 与本批 flag；共享 `validateForRead` 在任何连接前校验 callback token、caller=`cm18-reconciliation`、ASCII payNo、无 Spring active transaction、无 Seata XID。不得接受外部身份覆盖。
- Gateway 继续禁止该 suffix；Controller 只委托，不直接持有 SQL。`Cache-Control: no-store` 保持。
- 独立 `@Transactional(readOnly=true,isolation=REPEATABLE_READ)` ReadService 在同一 REQUIRED 只读事务中调用既有 `reader.capture(payNo)`、`PayTccDeliveryMapper.selectByPayNo(payNo)`、`PayTccCancelMapper.selectByPayNo(payNo)`；完整样本为 8 次 SELECT、1 个连接。禁止 `Store.read`、`ensureTable`、`FOR UPDATE`、写、RPC、TC、MQ。

## 3. 输出与脱敏

响应固定：

- `scope=PAY_LOCAL_DELIVERY_CANCEL_ONLY`；
- `automaticActionsAllowed=false`；
- 上海偏移 `collectedAt`；
- 原 Pay local evidence；
- 两个白名单事实摘要与固定 reason codes。

delivery/cancel 摘要只包含 `present`、白名单 `state`、`identityMatched`（缺行 null）、`attempts`、`nextRetryAt`（+08:00）、`leasePresent`、`leaseUntil`（+08:00）、`errorPresent`；XID/refund key 只输出 SHA-256 指纹，不输出 raw key、owner、error 或其他凭证。未知 state 输出 `UNRECOGNIZED` 并使用固定冲突码。

## 4. 本地校验契约

### 4.1 Delivery

- 精确核对 payNo、userId、orderNo、金额；amount 使用 BigDecimal 数值比较。
- delivery 额外要求 XID 与原 execution XID 指纹/值一致，refund key 精确为 `refund:<原 payNo>`。
- execution 必须为 `COMMITTED`；缺失、`UNKNOWN`、`BEGIN_UNKNOWN` 只产生缺证阻塞，已知非 COMMITTED 为本地冲突。
- delivery state 白名单：`READY`、`CANCEL_REQUESTED`、`CANCEL_RECONCILING`、`ORDER_SYNC_UNKNOWN`、`REFUND_PENDING`、`SUCCESS`、`REFUNDED`。READY/CANCEL* 与 Pay 只允许 `PENDING` 或 `ORDER_SYNC_UNKNOWN`；其他状态按同名 Pay 状态一致性核对。
- 已知 COMMITTED 但 delivery 缺失返回 `DELIVERY_ROW_MISSING` 阻塞，不创建/补造台账；未 COMMITTED 缺 delivery 不作资金终态结论。

### 4.2 Cancel

- cancel state 白名单：`REQUESTED`、`ORDER_CANCELLED`、`CANCEL_UNKNOWN`、`COMPLETED`、`CONFLICT`。
- `COMPLETED` 要求 Pay `FAILED`；其他 cancel 状态要求 Pay `PENDING`。状态不符为本地冲突。
- cancel 不得与 execution、dispatch、receipt、Legacy intent 或 delivery 同时存在；共存返回固定冲突码，不删除、不修复。
- cancel 成功只证明本地取消台账事实，不消除 base local evidence 缺失、不证明 User/Order/Stock/TC/MQ 或资金终态。

### 4.3 统一排序与边界

分类优先级：本地冲突 → 原 Pay `BEGIN_UNKNOWN`/原缺证 → delivery/cancel 补充缺证 → `BLOCKED_EXTERNAL_EVIDENCE`。重试/租约仅观察，不推断有效 lease、可执行性或恢复许可。LEGACY 若存在 delivery/cancel 为冲突；未知 mode 沿 base 阻塞。

## 5. 验收矩阵

- Unit：双 flag/gate、缺表 503 且不建表、alias/collation 候选不漏、tuple/XID/refund key/mode/state/共存冲突、合法 refund+COMMITTED、合法 cancel 无 execution、未知 state、日期+08:00、脱敏和 no-store。
- 真实 owned MySQL：Pay 八表/事实同 RR 读取，单连接八 SELECT；并发 writer 在读取窗口修改事实时不混读；完整行、租约、attempts、decision、错误和原库 fingerprint 前后不变。
- 继续验证无 Store/ensureTable/写/RPC/TC/MQ；资源 finally 清理。未决 base local evidence 不被本地台账摘要伪装为资金终态。

## 6. 交付边界

本文件只冻结 Pay delivery/cancel 本地证据读取；不新增表、列、API 写入、消息、恢复动作或统一跨服务证据包。CM18 仍需 User/Order/Stock/TC/MQ 及 M2–M4 验收。
