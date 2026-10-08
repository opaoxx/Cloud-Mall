# CM-20260924-20：普通订单商品 SKU 身份绑定评审

## 1. 结论与范围

结论：**GO，限定普通 `Order.create` 的 Product 响应 SKU 身份校验。**

当前请求 SKU 用于库存与 Cart，而 Product 响应中的 `SkuView.skuId` 用于价格和订单明细；响应身份不一致时会形成同一订单内的库存 SKU、价格 SKU 和明细 SKU 分裂。本批只在普通创建链路增加响应身份门。

不扩展秒杀、商品价格语法/范围、金额 scale/sign、productId、历史数据或真实网络演练；不修改 Product Feign DTO/API、数据库、SQL、库存请求结构、状态机、Seata 或 Cart 契约。

## 2. 校验位置与失败语义

在既有 Product response 的 `null`、非 0 code 或 data 缺失门之后，且在 BigDecimal 金额计算、`rows`/`Stock.Line` 构造、intent 创建、库存预扣和任何订单本地写入之前，要求：

- 当前请求 `item.skuId` 与 `resp.data.skuId()` 使用 `Objects.equals` 精确匹配；null、错 SKU、类型无法表示为同一 Long 均视为不可信；
- 不 trim、大小写/别名归一化或从请求 SKU 猜测响应 SKU；异常沿既有 INTERNAL/500 语义返回；
- 第一项合法、后续项错身份时，整个创建失败，不得创建 intent、调用 Stock、写订单/明细/idempotency/outbox，也不得提交 Cart POST；已按 17CE 完成的 Cart 只读预采样和地址/Product 前序调用允许存在；
- Product 404、Feign 异常、依赖 503、请求 400、幂等投影命中和 intent fallback 保持既有顺序与语义。

该门只保护普通创建的身份绑定；不改变请求 SKU 进入 `StockClient.Line` 的原顺序/数量，也不改变匹配响应的原价格、时间和后续流程。

## 3. 兼容与验收

- 合法响应 SKU 与请求 SKU 精确一致时，继续使用现有价格、Stock 请求、订单明细和 Cart 清理流程。
- code=0/data 非空但响应 SKU 为 null 或其他 SKU 时返回既有 INTERNAL/500；不得 fallback 到请求 SKU、继续金额计算或补写任何本地事实。
- 测试使用真实 Order MySQL/事务，Product、Stock、Cart 为替身：覆盖首项合法后项错身份、null/错 SKU、合法全链路、Product 404/Feign 异常及原 400/幂等/17CE 回归；验证错误路径零 intent/Stock/订单相关写入和零 Cart POST。
- 不要求错误身份场景 Cart 零读或所有 Product 零读；17CE 允许已有前置只读采样和前序 Product 调用。

## 4. 明确不承诺

本批不校验商品其他字段、不修价格范围、不改变历史订单、不扩展秒杀或其他 Product 调用方，也不宣称覆盖真实跨服务网络故障。

