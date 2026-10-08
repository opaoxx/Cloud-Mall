# CM-20260924-20：订单商品必填元数据评审

## 1. 结论与范围

结论：**GO，限定普通创建和秒杀首次消费写入订单明细前的 Product 必填元数据门。**

`mall_order_item` 的 `product_id`、`product_name_snapshot` 为 NOT NULL/有宽度的持久化字段；当前 Product 响应缺失或超宽可能在 intent/Stock 后才由 SQL 暴露。本批只保护这两个字段，不改变 DDL。

共享谓词：`hasPersistableProductMetadata(sku) := productId != null && productName != null && productName.codePointCount(0, productName.length()) <= 255`。

不增加 productId 正数/父商品关系规则，不校验 skuCode、skuSnapshot 内容或 JSON 宽度，不 trim/normalize/truncate，不修改 Cart、Product 管理、Pay、Stock、消息、Schema、历史数据或其他调用方。

## 2. 普通创建

在 CF/CG 身份、CH 非空非负价格、CI 分精度、CJ 容量门之后，且在 multiply/rows/intent/Stock/订单 SQL 前执行：

- productId null、productName null 或 name 超过 255 Unicode code points 按既有 INTERNAL/500；
- 空串、空白、尾空格和 255 个 emoji 合法并原样保存；
- 首项合法、后项 metadata 非法时整笔不创建 intent、不调用 Stock、不写订单/明细/outbox、不提交 Cart POST；17CE 前置只读 GET 可存在；
- 直接订单幂等投影重放不查询 Product，晚期 intent fallback 保持原 Product/金额顺序。

## 3. 秒杀首次消费

在 CG/CH/CI/CJ 后、OrderPO/明细/outbox、dedup `markCommitted` 和 recovery `markResolved` 前执行同一谓词。失败抛受控 `IllegalStateException`，保留 cause 沿 Rabbit retry/DLQ；新增 claim 事务回滚，Stock/Cart 不调用，既有 held recovery 不变。合法字符串和 Long.MAX productId 按原对象/SQL 语义写入。

## 4. 兼容与验收

- productName 按 Unicode code point 计数，数据库 VARCHAR255 的 UTF-8 字节容量与孤立 surrogate/编码问题不在本批处理。
- `skuSnapshot` nullable 和原 JSON/null 语义保持；其序列化失败、字段宽度、父商品关系另批。
- 真实 MySQL/事务覆盖普通首/后项 null id、null name、256 code points，秒杀新 claim 回滚/held recovery，合法 255 汉字/emoji/空白和 Long.MAX productId 原样落库。
- 单测验证 CF/价格门优先级、异常 cause/Error、正常 CF/CG/CH/CI/CJ、幂等、时间和清理回归；不宣称真实网络演练。

## 5. 数据库应用语义

既有 `product_id BIGINT NOT NULL`、`product_name_snapshot VARCHAR(255) NOT NULL` DDL 不变。本批只在应用写入前按非空与 Unicode code point 宽度拒绝不可信 Product metadata，不自动修复历史行或改变表结构。

