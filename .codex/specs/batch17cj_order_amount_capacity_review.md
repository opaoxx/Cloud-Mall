# CM-20260924-20：订单金额列容量评审

## 1. 结论与范围

结论：**GO，限定普通创建和秒杀首次消费的 `DECIMAL(18,2)` 数值容量门。**

既有 CI 分精度门仍允许某些单价/组合在写入 `DECIMAL(18,2)` 时溢出、舍入或被非严格模式截断。本批把列容量作为应用层事实门，不修改 DDL，不引入商业价格上限。

最大可表示金额固定为列容量常量：

`MAX_ORDER_AMOUNT = 9999999999999999.99`

即 16 位整数和 2 位小数；单价使用 `BigDecimal.compareTo` 在 multiply 前判断，行金额与候选总额仍按原精确 multiply/add 计算后再比较，所有容量检查均早于 intent、Stock 和 SQL。

## 2. 普通创建

在 CF/CH/CI 门之后：

- 单价 `unitPrice > MAX_ORDER_AMOUNT`：按既有 INTERNAL/500，视为上游商品事实不可信；不得 multiply、创建 intent、Stock 或订单事实。比较优先于乘法，不展开/规范化极负 scale 的巨大整数。
- 对每项先按原逻辑计算价格乘原正 int quantity；`lineAmount > MAX_ORDER_AMOUNT` 时返回既有 INVALID/400，不创建 intent、不调用 Stock、不写订单/明细/outbox、不提交 Cart POST；17CE 只读 GET 可以存在。
- 每项通过后累计原始 BigDecimal 总额；`total > MAX_ORDER_AMOUNT` 时同样返回 INVALID/400，整笔不写、不预扣。
- 不改变原价格对象、scale、Stock 原 items 顺序/数量，不 setScale、round、normalize 或新增 quantity 上限。

直接订单幂等投影重放不查询 Product；投影缺失的晚期 intent fallback 保持原 Product/金额验证顺序，不增加读取或额外核价。

## 3. 秒杀首次消费

在 CG/CH/CI 后、OrderPO/明细/outbox、dedup `markCommitted` 和 recovery `markResolved` 前校验单价 `<= MAX_ORDER_AMOUNT`。秒杀 quantity 固定为 1，因此单价即订单金额；超容量抛受控 `IllegalStateException`，保留 cause，沿原 Rabbit retry/DLQ，新增 claim 事务回滚，Stock/Cart 不调用，既有 held recovery 事实不变。已 COMMITTED 重放仍在 Product 前收敛。

## 4. 边界与验收

- MAX、MAX-0.01+0.01、约 MAX/2 的合法 quantity2 边界可通过；1E+16、单价 5e15×2、6e15+4e15 等超容量分别按单价 500、行/总额 400 处理。
- 极端负 scale 的巨大整数由 `compareTo` 快速判断超容量并拒绝，不展开、不 strip、不进入 multiply/SQL；极端 scale 零值数学比较可通过，但普通混合聚合、JSON/JDBC 表示仍可能有资源边界，只做直接 Service+mock 验证，不承诺巨值 SQL/支付资源行为。
- 真实 MySQL/事务覆盖普通单价/行/总额容量、秒杀 claim 回滚/held recovery、修正 MAX 价格重试；合法价格核对 SQL 与 Java 金额一致。
- 保持 CI 精度、CF/CG 身份、CE 清理、幂等、时间和失败回归；不修历史数据、Pay 范围或总额之外的数据库容量。

## 5. 数据库语义

`DECIMAL(18,2)` DDL 不变。本批仅在应用写入前拒绝超列容量数值，防止数据库隐式溢出/截断，不能描述为商业金额上限或全链路容量闭环。
