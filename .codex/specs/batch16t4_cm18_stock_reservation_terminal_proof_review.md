# CM-18 16T4：普通库存预扣 GET 终态证据评审

## 1. 结论与范围

结论：**GO，限定修复 Stock 普通 reservation GET 的多 SKU 终态聚合证明；不改变写状态机、请求协议、SQL/DDL、Redis、消息或秒杀路径。**

当前普通 flows 分支以任一已结清 SKU、`confirmed>0`/`rollback>0` 和 Java `int` 加法推导整单状态；多 SKU 部分结清、混合方向、request 行覆盖或加法溢出会把不完整事实报告为 `CONFIRMED`/`ROLLED_BACK`。Order 的 `legacyStockActionStatus` 会把该 GET 结果用于旧无动作订单的 `SUCCEEDED` fallback，因此错误终态可能影响 Pay 交付/退款判断。

本批只修 `StockServiceImpl.reservationResult` 普通 flows 只读 GET；不新增诊断 endpoint，不改 Order/Pay 生产逻辑，不把订单状态或 Pay 状态直接当库存终态。

## 2. 普通 flows 聚合契约

### 2.1 每行数值验证

- `sku_id` 必须是正 `Long`；`reserved_quantity` 必须是正整数；`confirmed_quantity`、`rolled_back_quantity` 必须是非负整数。
- JDBC `Number` 统一以 `BigDecimal` 精确读取并验证整数性、非负/正边界；不得用 `intValue()`、浮点、静默 null→0、截断、回绕或默认化。
- 每行必须满足 `confirmed + rolled_back <= reserved`，加法使用不会溢出的精确方式；任一非法数值、空 flows、reserved 非正或超量均返回 `UNKNOWN` 且 `items=[]`。

### 2.2 每 SKU 与整单状态

每个 SKU 只允许以下状态：

- `confirmed == reserved && rolled_back == 0` → `CONFIRMED`；
- `rolled_back == reserved && confirmed == 0` → `ROLLED_BACK`；
- `confirmed == 0 && rolled_back == 0` → `RESERVED`；
- 其他组合 → `UNKNOWN`，整单返回 `UNKNOWN` 且不虚构部分终态。

所有 SKU 必须得到同一个合法状态，整单才返回该状态；多 SKU 混合 `CONFIRMED`/`ROLLED_BACK`/`RESERVED` 或任一 `UNKNOWN` 均返回 `UNKNOWN`。返回的 `items` 只在所有行数值和状态合法时构造，并保留每个 SKU 的原整数事实。

若存在普通 reservation request 行，其状态只能作为交叉证据：只有 request status 与上述整单聚合得到的同一合法状态一致时才可沿用；request 缺失不覆盖合法 flows，request 与 flows 矛盾返回 `UNKNOWN`。request 行不能把不完整或非法 flows 覆盖成终态。

### 2.3 保持的行为

- 秒杀 settlement/flows 分支、普通 reserve/confirm/rollback 写方法、Redis、消息、请求/响应字段、HTTP 路径和 Mapper SQL/DDL 保持。
- GET 仍是原非 RR 读取；本批不新增事务或跨 SELECT 一致性承诺。读取竞争导致事实不一致时允许保守返回 `UNKNOWN`，调用方按原订单号重查。
- Order `legacyStockActionStatus` 继续使用 GET 结果；本修复只保证错误 partial/mixed flows 不再返回成功终态，不改变 Order 的 fallback 规则。

## 3. 验收矩阵

- Unit 红/绿覆盖：单 SKU 完整确认/回滚/保留、多 SKU 同向完整、部分结清、混合方向、request 缺失/一致/矛盾、null/非整数/负数/超量、`Integer.MAX_VALUE` 与加法溢出；非法与混合均 `UNKNOWN/items=[]`，不发 Stock 写调用。
- 真实 MySQL：普通旧 flows 表写入多 SKU及混合 `stock_flow`，分别验证无 request 与有 request；只读 GET 的 SQL/行事实前后不变，request 矛盾不会覆盖 flows，`CONFIRMED`/`ROLLED_BACK` 仅在所有 SKU 完整同向时出现。
- Order fallback 链：StockClient mock 返回实际 Stock GET 结果，旧无 `order_stock_action` 订单的 `payment-state` 只能在合法整单终态下映射 `SUCCEEDED`；partial/mixed/非法/UNKNOWN 保持未知，不调用 Stock 写接口、不推进 Pay/退款。
- 保持秒杀 GET、普通写接口、Redis、消息和既有 API/字段回归；本批不覆盖读中多次 SELECT 的完整快照，只验证保守 UNKNOWN 可接受。

## 4. 交付边界

本文件只冻结普通 reservation GET 终态证明，backend 按本文及 API/DB 相邻条款实现；本轮不修改业务测试、Maven、Order/Pay 生产代码或 CM18 其他 M1 切片。
