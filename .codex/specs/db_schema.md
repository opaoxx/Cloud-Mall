# CloudMall 云购微商城数据库设计规格

## 1. 设计原则

- MySQL 8.0 保存业务事实数据；各服务按边界拥有自己的表，禁止跨服务直接读表。
- 主键统一使用 `BIGINT` 类型的业务无关唯一标识；生成算法由实现选择不改变字段契约。
- 金额使用定点数而非浮点数；API 金额使用字符串并映射为 Java `BigDecimal`；数据库时间使用 Asia/Shanghai 语义的 `DATETIME(3)`。
- 公共审计字段建议包含 `created_at`、`updated_at`；是否加入逻辑删除字段按表的状态语义决定。
- 本项目使用一个 MySQL 8.0 实例，按服务划分逻辑库；文档是逻辑设计，不是 SQL 初始化脚本。字段长度、字符集和排序规则按实现环境采用与 MySQL 8.0 兼容的统一配置。

## 2. 数据库归属

| 服务 | 逻辑数据 |
| --- | --- |
| user | 用户、角色、用户角色、地址 |
| product | 商品、分类、商品参数、商品热度 |
| order | 订单主表、订单明细、订单状态流水 |
| stock | 库存、库存流水、秒杀活动库存 |
| pay | 支付记录、支付回调记录 |
| cart | 购物车以 Redis 为核心，不要求 MySQL 购物车表；Redis 数据 TTL 到期即失效，本期不增加 MySQL 持久化兜底 |

## 3. 用户域表

### 3.1 `user_account`

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 用户 ID |
| `username` | VARCHAR(64) UNIQUE NOT NULL | 登录名 |
| `password_hash` | VARCHAR(255) NOT NULL | 密码摘要，不存明文 |
| `status` | TINYINT NOT NULL | 1 正常，0 禁用 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义的审计时间 |

索引：`uk_username(username)`、`idx_status_created(status, created_at)`。密码不得明文保存，算法由实现采用安全单向散列，不改变业务契约。

### 3.2 `user_profile`

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 资料 ID |
| `user_id` | BIGINT UNIQUE NOT NULL | 用户 ID |
| `nickname` | VARCHAR(64) | 昵称 |
| `phone` | VARCHAR(32) | 联系电话，脱敏展示 |
| `avatar_url` | VARCHAR(512) | 头像地址 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义的审计时间 |

### 3.3 `user_role`、`user_account_role`

`user_role(id, role_code UNIQUE, role_name, status, created_at, updated_at)` 保存角色；`user_account_role(user_id, role_id, created_at)` 使用联合主键 `(user_id, role_id)`，分别建立 `idx_role_id(role_id)`。

### 3.4 `user_address`

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 地址 ID |
| `user_id` | BIGINT NOT NULL | 所属用户 |
| `receiver_name` | VARCHAR(64) NOT NULL | 收货人 |
| `receiver_phone` | VARCHAR(32) NOT NULL | 收货电话 |
| `region_detail` | VARCHAR(512) NOT NULL | 地区及详细地址 |
| `is_default` | TINYINT NOT NULL | 是否默认 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义的审计时间 |

索引：`idx_user_id(user_id)`、`idx_user_default(user_id, is_default)`。

## 4. 商品域表

### 4.1 `product_category`

`id BIGINT PK`、`parent_id BIGINT NOT NULL`、`name VARCHAR(128) NOT NULL`、`sort_no INT NOT NULL`、`status TINYINT NOT NULL`、`created_at`、`updated_at`。索引：`idx_parent_status(parent_id, status)`、`uk_parent_name(parent_id, name)`。本期分类采用有限树形层级，具体展示层级不扩展新的接口契约。

### 4.2 `product`

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 商品 ID |
| `category_id` | BIGINT NOT NULL | 分类 ID |
| `name` | VARCHAR(255) NOT NULL | 商品名称 |
| `main_image` | VARCHAR(512) | 主图 |
| `description` | TEXT | 描述 |
| `price` | DECIMAL(18,2) NOT NULL | 当前销售价 |
| `status` | TINYINT NOT NULL | 1 上架，0 下架 |
| `version` | INT NOT NULL | 乐观并发版本 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义的审计时间 |

索引：`idx_category_status(category_id, status)`、`idx_status_updated(status, updated_at)`；搜索索引由 ES 管理。

### 4.3 `product_sku`

`id BIGINT PK`、`product_id BIGINT NOT NULL`、`sku_code VARCHAR(64) UNIQUE NOT NULL`、`spec_json JSON NOT NULL`、`price DECIMAL(18,2) NOT NULL`、`status TINYINT NOT NULL`、`created_at`、`updated_at`。索引：`idx_product_status(product_id, status)`。`spec_json` 使用属性名到属性值的 JSON 对象表达 SKU 规格，订单明细保存不可变 `sku_snapshot`。

### 4.4 `product_parameter`

`id BIGINT PK`、`product_id BIGINT NOT NULL`、`param_name VARCHAR(128) NOT NULL`、`param_value VARCHAR(512) NOT NULL`、`sort_no INT NOT NULL`、`created_at`、`updated_at`。索引：`idx_product_sort(product_id, sort_no)`。

### 4.5 `product_hot_stat`

`product_id BIGINT PK`、`view_count BIGINT NOT NULL`、`search_count BIGINT NOT NULL`、`hot_score DECIMAL(18,6) NOT NULL`、`stat_date DATE NOT NULL`、`updated_at DATETIME(3) NOT NULL`。本期按商品维度维护累计热度，浏览事件经 RabbitMQ 异步写入。

## 5. 订单域表

### 5.1 `mall_order_${yyyyMM}`

逻辑表名为 `mall_order`，物理表按下单时间月度分片，例如 `mall_order_202608`；订单号格式为 `yyyyMM` 前缀加 UUID，可直接定位订单月份。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 订单内部 ID |
| `order_no` | VARCHAR(64) UNIQUE NOT NULL | 对外订单号，幂等查询键之一 |
| `user_id` | BIGINT NOT NULL | 下单用户 |
| `status` | VARCHAR(32) NOT NULL | `PENDING_PAYMENT`/`PAID`/`CANCELLED`/`COMPLETED` |
| `total_amount` | DECIMAL(18,2) NOT NULL | 商品总额 |
| `pay_amount` | DECIMAL(18,2) NOT NULL | 应付金额 |
| `address_snapshot` | JSON NOT NULL | 下单时地址快照 |
| `expire_at` | DATETIME(3) | 待支付过期时间 |
| `paid_at` | DATETIME(3) | 支付完成时间 |
| `cancelled_at` | DATETIME(3) | 取消时间 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义的分片路由及审计时间 |

约束：金额非负；状态只能按状态机迁移；订单号 `yyyyMM + UUID` 全局唯一且可定位月份。索引：`idx_user_created(user_id, created_at)`、`idx_status_expire(status, expire_at)`、`idx_created_at(created_at)`。订单、明细和状态日志均按 `created_at` 月度分表；跨月用户订单列表按时间范围路由并聚合分页结果。

### 5.2 `mall_order_item_${yyyyMM}`

与订单同月分片，字段：`id BIGINT PK`、`order_id BIGINT NOT NULL`、`order_no VARCHAR(64) NOT NULL`、`product_id BIGINT NOT NULL`、`sku_id BIGINT NOT NULL`、`product_name_snapshot VARCHAR(255) NOT NULL`、`sku_snapshot JSON`、`unit_price DECIMAL(18,2) NOT NULL`、`quantity INT NOT NULL`、`line_amount DECIMAL(18,2) NOT NULL`、`created_at DATETIME(3) NOT NULL`。索引：`idx_order_id(order_id)`、`idx_order_no(order_no)`；数量大于 0，行金额须与单价和数量一致。

### 5.3 `order_status_log_${yyyyMM}`

字段：`id BIGINT PK`、`order_id BIGINT NOT NULL`、`order_no VARCHAR(64) NOT NULL`、`from_status VARCHAR(32)`、`to_status VARCHAR(32) NOT NULL`、`event_type VARCHAR(64) NOT NULL`、`operator_id BIGINT`、`remark VARCHAR(512)`、`created_at DATETIME(3) NOT NULL`。索引：`idx_order_created(order_id, created_at)`。用于审计和状态追踪，不替代订单主状态。

## 6. 库存域表

### 6.1 `stock_sku`

`sku_id BIGINT PK`、`product_id BIGINT NOT NULL`、`total_quantity INT NOT NULL`、`available_quantity INT NOT NULL`、`reserved_quantity INT NOT NULL`、`sold_quantity INT NOT NULL`、`version INT NOT NULL`、`updated_at DATETIME(3) NOT NULL`。约束：各数量非负，`total_quantity = available_quantity + reserved_quantity + sold_quantity` 的维护规则需在事务内保证。索引：`idx_product_id(product_id)`。

### 6.2 `stock_flow`

`id BIGINT PK`、`sku_id BIGINT NOT NULL`、`order_no VARCHAR(64) NOT NULL`、`flow_type VARCHAR(32) NOT NULL`（RESERVE/CONFIRM/ROLLBACK/SECKILL_RESERVE）、`quantity INT NOT NULL`、`idempotency_key VARCHAR(128) UNIQUE NOT NULL`、`created_at DATETIME(3) NOT NULL`。索引：`idx_sku_created(sku_id, created_at)`、`idx_order_no(order_no)`。

## 7. 支付域表

### 7.1 `pay_record`

`id BIGINT PK`、`pay_no VARCHAR(64) UNIQUE NOT NULL`、`order_no VARCHAR(64) UNIQUE NOT NULL`、`user_id BIGINT NOT NULL`、`amount DECIMAL(18,2) NOT NULL`、`status VARCHAR(32) NOT NULL`（PENDING/SUCCESS/FAILED/CLOSED）、`paid_at DATETIME(3)`、`created_at DATETIME(3)`、`updated_at DATETIME(3)`。订单号唯一保证同一订单只有一个有效支付记录，重复支付/回调按幂等规则安全返回。

### 7.2 `pay_callback_log`

`id BIGINT PK`、`pay_no VARCHAR(64) NOT NULL`、`callback_id VARCHAR(128) UNIQUE NOT NULL`、`callback_status VARCHAR(32) NOT NULL`、`payload JSON`、`processed_at DATETIME(3)`、`created_at DATETIME(3) NOT NULL`。用于模拟回调幂等和审计；不得保存真实支付敏感数据。

## 8. 购物车 Redis 逻辑结构

MySQL 不保存购物车主数据。逻辑结构：`cart:{userId}` → Hash，field 为 `skuId`，value 为 JSON（数量、勾选状态、加入时间等）；Key TTL 为 30 天，过期即清理。任何结算请求都必须重新读取商品价格、上下架状态和库存，Redis 购物车不能成为价格事实源。

## 9. 秒杀 Redis 逻辑结构

秒杀结构固定为：`seckill:stock:{activityId}:{skuId}` 保存可预扣数量，`seckill:user:{activityId}:{userId}` 保存用户参与/限购标记，`seckill:product:{activityId}:{skuId}` 保存热点商品摘要。库存预扣使用 Redis 原子操作/Lua，活动相关 Key 设置与活动窗口匹配的 TTL；不得把 Redis 预扣结果直接当作最终支付成功。

## 10. Sharding-JDBC 订单分表策略

- 逻辑表：`mall_order`、`mall_order_item`、`order_status_log`。
- 分片方式：按 `created_at` 的年月路由到 `_${yyyyMM}` 物理表；订单明细和状态日志跟随订单月份，避免跨月关联失配。
- 分片键：订单主表使用 `created_at`；`order_no` 的 `yyyyMM` 前缀用于无时间条件的精确查询定位月份。
- 路由要求：列表查询必须提供时间范围；无时间范围的全量查询禁止作为常规接口。
- 建表策略：开发阶段预建当前月份及测试需要的物理表，不能在本文档中生成初始化 SQL。
- 跨分片列表按时间范围聚合、排序和分页；业务接口必须避免依赖跨分片强一致聚合。

## 11. 统一状态约束

- 用户：正常/禁用。
- 商品：上架/下架。
- 订单：待支付 → 已支付 → 已完成；待支付 → 已取消；其他迁移必须经过评审。
- 支付：待支付/成功/失败/关闭；成功不可重复写入。
- 库存：可用、预扣、已售、回滚通过数量和流水表达，不允许负数。
- 所有状态更新带当前状态条件或版本条件，重复消息必须安全返回。

## 12. 设计冻结说明

本轮已冻结单一 MySQL 8.0 实例按服务划分逻辑库、BIGINT 主键、Asia/Shanghai 语义 `DATETIME(3)`、`yyyyMM + UUID` 订单号、订单/明细/状态日志按 `created_at` 月度分表、当前月份及测试月份预建物理表、跨月列表按时间范围聚合、购物车 JSON value/30 天 TTL，以及秒杀 Redis 原子/Lua 预扣和活动 TTL。本文仍只描述逻辑设计，不生成 SQL。
