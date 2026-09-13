# CloudMall 商品数据扩充与前端体验改造方案

## 目标与范围

本轮解决两个问题：确认订单新增地址的手机号必须是 11 位中国大陆手机号；商品数据从当前少量演示数据扩充到 `F:\hmall\sql\hm-item.sql` 的完整商品目录，并在现有 API 契约内提升首页、商品目录、详情与结算页面的信息层次和电商视觉表达。

不直接迁移 hmall 的订单、支付和购物车数据：CloudMall 订单按月分表，购物车以 Redis 为事实源，支付状态和金额契约也不同，直接导入会产生不可验证的状态和归属问题。

## 阶段与 Git 回滚点

1. `database`: 备份当前 CloudMall 数据库，加载 hmall 商品源表，执行幂等 ETL，校验行数、金额、状态、库存和规格抽样后提交。
2. `frontend`: 在数据库阶段提交之后，补齐手机号前后端校验（前端即时约束，后端 `@Pattern` 防绕过），再增强淘宝式信息架构和视觉层，运行 lint/test/build 与浏览器回归后提交。

每一阶段只提交对应文件；临时数据库备份和源 SQL 不提交仓库。回滚数据库使用阶段前备份，回滚代码使用 Git 阶段提交。

## 商品 ETL 契约

源表为 `` `hm-item`.item ``，一条源 item 保留为一个 CloudMall 商品及一个 SKU，避免猜测 SPU 聚合关系：

- `item.id` → `product.id`；SKU 主键使用同一源 ID 在 SKU 表中的独立命名空间，生成 `sku_id = 700000000000 + item.id`，并检测 BIGINT 溢出和重复。
- `item.name` → `product.name`；`item.image` → `product.main_image`；品牌、规格和源销量/评论信息拼入 `product.description`，不新增字段。
- `item.price`（分）除以 100 → `product.price` 与 `product_sku.price`（元，DECIMAL(18,2)）。
- `item.status=1` → 上架 `1`；`status IN (2,3)` → 下架 `0`。删除状态不丢弃，保留为可审计的下架商品；管理台可查看并决定是否上架。
- `item.stock` → `stock_sku.total_quantity` 与 `available_quantity`；预扣、已售初始化为 0，`version=0`。
- `item.spec` 解析为 JSON 对象；空值、非法 JSON 或数组统一为 `{}`，合法对象保留键值字符串。
- `item.category` 按名称去重生成根分类，使用稳定的 `900000000000 + CRC32(category)` ID；发生哈希碰撞时按名称排序追加序号。商品 `category_id` 通过名称映射得到。
- SKU `sku_code` 为 `HM-<item.id>`，`product_sku.status` 与商品状态一致。
- 商品热度初始化为源 `sold` 的可比计数（`view_count=sold`、`search_count=comment_count`、`hot_score=sold`），不改变 API 字段。

ETL 先在临时源库完成导入，再在事务/分批语句中清理 CloudMall 商品、SKU、参数、热度和库存旧种子，保留用户、地址、订单和支付事实；由于购物车以 SKU 为键且源 SKU 已替换，迁移完成后清理旧的 `cart:*` Redis 缓存，避免失效 SKU 导致购物车接口报错。重复执行不产生重复行。迁移脚本记录源库依赖和校验查询，禁止把 hmall 的密码明文写入 CloudMall。

## 手机号校验契约

地址请求的 `phone` 使用 `^1[3-9]\\d{9}$`。前端输入设置 `inputMode="tel"`、`maxLength={11}`、`pattern` 并在提交前展示错误；后端 DTO 同步校验，确保非 11 位、字母和其他格式无法绕过 UI。

## 前端体验目标

保持现有路由、接口、金额字符串和状态机不变，使用真实商品数据；允许静态促销文案和权益卡片作为展示，不伪造促销/评价/优惠券 API。增强内容包括：顶部品牌与搜索、分类入口和侧栏、活动/权益视觉区、商品数量和排序筛选、商品卡片信息密度、详情页规格和购买区、结算步骤与金额明细、购物车数量入口，以及移动端响应式布局。

## 验收标准

- 导入后 `product`、`product_sku`、`stock_sku` 行数与源 item 行数一致（或按明确过滤规则解释），分类数量按 distinct category 一致；随机抽样价格分转元、库存、图片、规格正确。
- `admin` 用户与现有用户数据不受影响；浏览器可分页浏览大量商品，详情页可加入购物车并完成结算预览。
- 手机号为空、字母、非 11 位和不以 1 开头的号码在前端被阻止，后端接口也返回校验错误；合法 11 位号码可保存。
- `npm run lint`、`npm test -- --run`、`npm run build` 通过；浏览器回归确认首页、目录、详情、结算布局无溢出。
