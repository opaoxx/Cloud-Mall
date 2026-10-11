# CM-18 16T33：秒杀 legacy reservation SKU 精确读取

## 1. 结论与范围

本切片 GO，基线为 `d96c551`，只修正两个秒杀 legacy GET 路径对 `sku_id` 数值的非精确读取：主 `SeckillQuotaSettlementService.reservation` 的 `requiredPositive` 使用 `Number.longValue()` 可能截断小数或回绕超长整数；`StockServiceImpl.reservationResult` fallback 直接 `longValue()` 还可能把 0/负数当作已知状态或导致异常。

本批只增加两个 private read guard，不新增工具、依赖、SQL、DDL、过滤、事务、写入、GET 路径或全局 ObjectMapper 改动。T31 原 order identity guard 必须继续先于 SKU 解析；T32 settle、ensureTable、recovery、Redis/MQ 和普通 flows 保持。

当前生产 `sku_id` 为 signed BIGINT；0/-1 可在真实现行列直接验证，null 只在 owned nullable ALTER 中演练。小数、超长/unsigned 只在 owned 临时 DECIMAL(65,3) 或等价 legacy 列中演练，不宣称现行生产 signed BIGINT 能存这些值；Float/Double 也只在 owned legacy 列模拟，即使 7.0 也拒绝且不尝试还原原始数字。生产 DDL 本身完全不改。

## 2. 主秒杀 reservation guard

在 T31 identity guard 通过后，主 `reservation` 的 `ACCEPTED` legacy Map 调用 `requiredPositive`。该 helper 必须：

- 拒绝 null、文本、Float/Double、非 finite 或其它非可信类型；
- 对 BigDecimal 使用 `longValueExact()`；其它 Number 先以 `new BigDecimal(number.toString())` 后 `longValueExact()`；
- 结果必须为正 long；小数（包括 BigDecimal `7.9`）、超出 long、0 和负数均抛既有 `SECKILL_SETTLEMENT_CORRUPT` 500，使用固定不含 raw value 的消息；
- 捕获 `IllegalArgumentException`/`ArithmeticException` 并沿上述既有 500 错误契约返回，不回显原值。

identity 失败仍优先返回 T31 的请求 orderNo echo、`UNKNOWN/items=[]`，即使 SKU 同时非法也不得先抛 SKU 500。精确合法 Long/整数 Number、BigInteger、BigDecimal `7.000` 和 Long.MAX_VALUE 的状态/响应保持。

主 legacy 非 `ACCEPTED` 继续原 `return null` 语义；不把本批数值 guard 扩到普通 reservation、写路径或 settlement 行维度。

## 3. Stock fallback legacy guard

`StockServiceImpl.reservationResult` 在 T31 fallback order identity guard 通过后，读取精确行的 `sku_id` 必须沿既有 `exactReservationNumber` 精确转换，再 `longValueExact()` 并要求正值：

- null、文本、Float/Double、非 finite、零、负数、小数、超 long、unsigned/BigInteger 超范围和任何转换异常统一返回现有 `unknownReservationProof(requestedOrderNo)`，即请求 orderNo echo、`UNKNOWN/items=[]`；
- 不返回 null、不伪装 NOT_FOUND、不落入普通 flows/request 分支；
- fallback 精确合法 `ACCEPTED`/`REJECTED`/`PENDING` 继续原状态映射；invalid SKU 的 `REJECTED` 也必须保守为 `UNKNOWN`，不能沿旧错误状态映射；
- T31 identity guard 仍优先于 SKU guard，错 order alias + 坏 SKU 先返回 UNKNOWN/items=[]，不抛 SKU 500。

该 guard 不改变普通 flows、request snapshot、Stock outer/Order fallback 之外的数量和来源证明；Order 实际消费 invalid fallback 时不得映射 `SUCCEEDED`。

## 4. 不变边界

- 不改 SQL、Mapper、DDL、schema、字段类型、过滤条件、事务、写状态机、Redis/MQ、业务 API、Controller 路径或全局 JSON 序列化。
- 不改 T31/T32 的 order identity/read-write guard、T32 的 settle insert-before-lock 与冲突回滚；不处理其它 tuple/provenance、required-positive 维度或 CM18 M1–M4。
- 只读数值 guard 不修改原 Map、数据库事实、source 原库或 legacy 状态；错误 reason 不包含 raw SKU。

## 5. 验收矩阵

- Unit：主/回退路径分别覆盖 null、文本、Float/Double、非 finite、7.9、7.0、0、负数、超 long/unsigned、精确 Long/BigInteger/BigDecimal `7.000`/Long.MAX；覆盖 T31 identity 优先级。主 invalid 保持既有 500，fallback invalid 为 UNKNOWN/items=[]。
- Owned MySQL/MyBatis：当前 signed BIGINT 合法边界与 0/-1、owned nullable null；legacy DECIMAL(65,3) `7.9`、超 long/unsigned 和 Float/Double 演练；验证原 settlement/legacy/reservation 行、source checksum 和状态时间数量在每次 GET 前后均不变。只有准备阶段允许 owned ALTER/INSERT，GET 正负例本身不得改任何 facts；生产 DDL 完全不变。
- 实际 Stock Mapper/Service/Controller 与 Order fallback bridge 验证 invalid `REJECTED` SKU 保持 UNKNOWN 而非 CONFLICT/SUCCEEDED；不产生写 SQL、Redis/MQ 或普通库存写。
- 保留 T31/T32、普通 flows/Order/Pay/Gateway 回归；owned 临时列/行和 HTTP/executor 资源 finally 清理，不启动业务 App、RM、TC 或固定端口。

本切片只冻结 legacy SKU 精确读取，不宣称解决 Stock 全 tuple、库存余额、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
