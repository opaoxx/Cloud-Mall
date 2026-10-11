# CM-18 16T36：秒杀 settlement 原幂等键维度校验

## 1. 结论与范围

本切片 GO，基线为 `39cb117`，补充 settlement 原受理 `idempotencyKey` 的维度门。当前 settlement PO 继承 ACCEPTED 事实但 `validateSettlement` 未校验该字段，null、空白或 literal `"null"` 仍可能被 GET 报为已知状态或被写路径接受 noop/CAS。

本批只在既有 `validateSettlement` 中增加一项原幂等键校验；不扩展为 full tuple proof，不改 SQL、DDL、事务、锁序、Redis/MQ、恢复器、legacy、普通库存、API 或全局序列化。

## 2. 原幂等键规则

在 T31/T32 原 order identity guard 之后，沿既有 `validateSettlement` 的原维度检查顺序增加：

```text
idempotencyKey != null
&& !idempotencyKey.equals("null")
&& !idempotencyKey.isBlank()
&& idempotencyKey.codePointCount(0, idempotencyKey.length()) <= 128
```

不 trim、不 casefold、不把 uppercase `NULL` 当 literal `null`，不使用 `String.length()` 代替 code point 数；合法的包围空格文本、uppercase `NULL` 和恰好 128 个 Unicode code points 沿原事实语义保留。null、literal `"null"`、空白、超过 128 code points 均为维度损坏。

该门不校验 operation key、user/actor/活动全 tuple；T34/T35 operation key 门和其它维度门继续按既有顺序执行。

## 3. GET/写错误语义与优先级

- GET 的 settlement identity 通过后，原 `validateSettlement` 发现幂等键损坏时继续抛既有 `SECKILL_SETTLEMENT_CORRUPT` 500、固定“秒杀结算事实维度无效”消息；不得降级为 T31/T35 的 `UNKNOWN/items=[]`。
- 写路径在 operation key 派生、状态 noop 和 CAS 前执行同一维度门；损坏幂等键固定既有 500，无 raw key/order/error 回显，不触发 noop/CAS/普通库存/Redis/MQ 写。
- T31/T32 order identity 仍优先于本维度门；错 order alias 先返回 T31 GET `UNKNOWN/items=[]` 或 T32 409，即使幂等键同时损坏。合法 identity 后，quantity/revision/numeric dimension 的既有检查仍按原顺序优先于 operation key 门。
- T34/T35 operation key 门仍在 `validateSettlement` 之后；不改变 OPEN、RELEASE_PENDING、PROJECTION_BLOCKED、CONFIRMED、RELEASED 的既有状态/方向协议。

## 4. 不变边界

- 不改 `StockServiceImpl.seckill` 的 literal `"null"`、`isBlank`、code point 128 入口校验；本批只保护已有 settlement 事实读取/写入。
- 不改 settlement Mapper SQL、参数、DDL、VARCHAR(128) schema、锁、事务传播、迁移、recovery、Redis/MQ、legacy/普通路径、Controller/API 或全局 ObjectMapper。
- 不修 full tuple、operation/actor/活动身份、source provenance、TC/MQ 或 CM18 M1–M4；T31–T35 既有规则保持。

## 5. 验收矩阵

- Unit：GET/confirm/rollback 覆盖 null、literal `null`、空白、超长 code points、合法包围空格、uppercase `NULL`、恰好 128 个非 BMP Unicode code points；验证 identity/quantity/revision 优先级，坏键保持既有 500。
- Owned MySQL：当前 VARCHAR(128) 直接验证 blank/literal null；null/超长仅 unit 或 owned 准备阶段演练，不修改 source DDL。验证 GET 正负例和写负例前后所有 settlement/legacy/source facts、状态、时间、数量不变；合法边界正例按原协议允许状态/CAS/revision/operation 变化。
- 复用 T34/T35 实际 Controller/transaction proxy/SQLIT，验证坏维度不产生 UPDATE、普通 `stock_flow`/`stock_sku`/request/cache、Redis/MQ/RM/TC 写；exact valid idempotency key 不降低既有操作 key、状态和 recovery 回归。
- 资源 finally 清理，source 库只做 checksum；不启动业务 App、RM、TC 或固定端口。

本切片只冻结 settlement 原幂等键维度，不宣称完整资金 tuple、库存、TC/MQ 或 CM18 M1–M4 闭环；CM20/CM21 仍等待 CM18 整体完成。
