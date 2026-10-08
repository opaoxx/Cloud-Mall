# CM-20260924-20：普通订单快照前置序列化评审

## 1. 结论与范围

结论：**GO，限定普通 `Order.create` 在创建 intent/调用 Stock 前完成地址和全部 SKU JSON 快照序列化。**

当前地址快照在 Stock 预扣后才序列化，SKU 快照在订单/前项明细写入后逐项序列化；Jackson 失败前可能已执行订单/前项明细 SQL，本地事务回滚后独立 RESERVED intent 仍保留并进入库存补偿。本批只调整普通创建的本地准备时序。

不修改秒杀 consumer、Cart、Stock、Pay、Seata、消息、表结构、字段、DTO、金额/时间/状态机或历史数据。

## 2. 前置准备位置

保留既有请求、CF–CK 门、价格/金额计算、Product 和地址查询顺序。全部 SKU 的身份、价格、精度、容量、metadata 以及 line/total 门通过后，在 `reservationIntentService.createIntent`、Stock 预扣和任何订单 SQL 之前：

1. 使用原 `ObjectMapper` 对地址对象调用既有 `toJson` 一次，缓存 `addressSnapshot`；
2. 按原 `req.items` 顺序对每个 `SkuView.skuSnapshot()` 调用既有 `toJson` 一次，缓存 `List<String>`；
3. 后续写 `OrderPO.addressSnapshot` 和 `OrderItemPO.skuSnapshot` 只使用缓存值，不再次序列化或改变索引/顺序。

null 继续由既有 `toJson` 产生 JSON `null`，转义、字段、金额和原对象语义保持不变。序列化异常保持既有 `BizException` INTERNAL/500 和原错误消息语义（现有实现不携带 cause）；由于发生在 intent/Stock 前，不产生新的 intent、Stock、订单/明细/outbox 或 Cart POST，也不进入库存补偿。

## 3. 幂等与失败边界

- 直接订单幂等投影命中仍在准备前返回，不查询 Product、不序列化、不产生 Cart 调用。
- 投影缺失的晚期 intent fallback 保持原 Product/金额查询和 intent 查询位置；本批允许不可序列化输入在 fallback 前失败，合法可序列化输入保持原 fallback 结果，不新增 SQL、查询或“先 fallback 后再核价”。
- 17CE 前置 Cart 只读 GET 可能已经发生；失败路径不 POST/DELETE。合法普通创建仍使用缓存快照完成原本地事务和 afterCommit 清理。
- 秒杀路径不改，即使存在同名 snapshot 工具也不纳入本批。

## 4. 验收与数据库语义

- 真实 Order MySQL/事务 + ObjectMapper/远端替身：地址、首项 SKU、后项 SKU 序列化失败均在 intent/Stock/订单写入前返回，fixture 无残留；合法地址和全部 SKU 快照只序列化一次并原样写入。
- 覆盖 null、转义、字段和金额表示、原 items 顺序、前项不产生部分明细；原异常分类、CE/CF–CK、幂等、失败和时间回归保持。
- ObjectMapper 异常注入是本地编排测试，不宣称真实 HTTP 或坏对象可从生产 Product/用户服务返回。
- `address_snapshot JSON NOT NULL` 与 `sku_snapshot JSON NULL` 的既有 DDL 和 null 语义保持；本批不修改表结构、约束、历史数据或 SQL。
