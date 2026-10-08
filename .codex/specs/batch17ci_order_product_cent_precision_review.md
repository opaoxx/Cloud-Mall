# CM-20260924-20：订单商品单价分精度评审

## 1. 结论与范围

结论：**GO，限定普通创建和秒杀首次消费的 Product 单价必须可精确表示到分。**

数据库商品、订单和明细金额列为 `DECIMAL(18,2)`；当前 Java `BigDecimal` 的三位及以上非零小数可能被数据库隐式舍入，导致 Java 行金额与 SQL 单价/总额事实不一致。本批只增加应用层分精度门，不修改 DDL。

不改变 CF/CG/CH 优先级、直接幂等重放、晚期 intent fallback 的原 Product/金额顺序、价格对象表示、订单/Stock/Cart/Pay/消息/Schema 或历史数据。

## 2. 分精度规则

在 CH 非空非负门之后，使用价格前要求：

`hasCentPrecision(amount) := amount.scale() <= 2 || amount.stripTrailingZeros().scale() <= 2`

- 先判断 `scale() <= 2`，再调用 `stripTrailingZeros()`，避免对极负 scale 的整数执行可能下溢的 scale 变换；这不是展开数值。
- 12.345、0.001、1E-3 及任何去除尾零后仍超过 2 位的小数不通过；
- 12.3000、12.3400、1E+1、整数、零和负零通过；极大 scale 的零值数学上通过，非零且去除尾零后仍超过 2 位的小数拒绝；
- 不 setScale、round、MathContext、字符串重建或修改原 `BigDecimal`，不定义整数位上限。

## 3. 普通创建

在 CF/CH 门之后、`multiply`、intent、Stock、订单/明细/outbox 写入之前执行。失败按既有 INTERNAL/500 返回；首项合法、后项非分精度时整笔不写 intent/订单事实、不调用 Stock、不提交 Cart POST，17CE 前置只读 GET 可存在。

直接订单幂等投影重放不查询 Product；投影缺失的晚期 intent fallback 保持原 Product/金额查询顺序，不增加二次核价或前移 SQL。

## 4. 秒杀首次消费

在 CG/CH 门之后、OrderPO/明细/outbox、dedup `markCommitted` 和 recovery `markResolved` 前执行。失败抛受控 `IllegalStateException`，保留 cause 进入原 Rabbit retry/DLQ；新增 claim 事务回滚，Stock/Cart 不调用，既有 held recovery 事实保持。合法价格沿用原对象、scale、quantity=1、occurredAt 和写入流程。

## 5. 数据库语义与验收

- `DECIMAL(18,2)` 既有 DDL 不变；本条只是写入前拒绝不可精确表示到分的非零小数，不能描述为数据库自动校验或全项目金额闭环。
- 真实 MySQL/事务覆盖普通首项/后项非分价格、秒杀 claim 回滚/held recovery、修正价格重试成功，以及合法尾零、科学表示、零价和 `12.3400 * 2 = 24.68`。
- 单测覆盖极端负 scale/极大 scale 零值安全通过、非零极大 scale 安全拒绝、原对象/scale 保持、CF/CG/CH/幂等/时间/清理回归；极端值只做验证和直接 Service+mock 测试，不做巨值/巨大 scale 的真实 SQL 或支付成功承诺。
- 不在本批处理总额/单价整数容量溢出、Pay 金额范围、Product 管理写入、历史坏数据或真实网络故障。
