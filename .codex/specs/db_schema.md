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

### 3.1 `mall_user`

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 用户 ID |
| `username` | VARCHAR(64) UNIQUE NOT NULL | 登录名 |
| `password_hash` | VARCHAR(255) NOT NULL | 密码摘要，不存明文 |
| `role` | VARCHAR(16) NOT NULL DEFAULT 'USER' | 用户角色，当前支持 `USER`、`ADMIN` |
| `nickname` | VARCHAR(128) | 昵称 |
| `phone` | VARCHAR(32) | 联系电话，脱敏展示 |
| `avatar_url` | VARCHAR(512) | 头像地址 |
| `status` | TINYINT NOT NULL DEFAULT 1 | 1 正常，0 禁用 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义的审计时间 |

索引：`username` 唯一索引。密码不得明文保存，算法由实现采用安全单向散列，不改变业务契约。当前规格将账号、基础资料和角色字段合并在 `mall_user`，不另建 `user_account`、`user_profile`、`user_role`、`user_account_role`。

### 3.2 `user_address`

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 地址 ID |
| `user_id` | BIGINT NOT NULL | 所属用户 |
| `receiver_name` | VARCHAR(64) NOT NULL | 收货人 |
| `receiver_phone` | VARCHAR(32) NOT NULL | 收货电话 |
| `region_detail` | VARCHAR(512) NOT NULL | 地区及详细地址 |
| `is_default` | TINYINT NOT NULL | 是否默认 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义的审计时间 |

索引：`idx_user_id(user_id)`。默认地址切换由用户服务按 `user_id` 更新 `is_default`，当前 SQL 未额外建立 `idx_user_default`。

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

### 5.4 `order_reservation_intent`

记录普通订单在跨服务库存预扣期间的可恢复意图，避免地址校验后、库存预扣后订单本地写入失败时留下孤立预扣。该表不按订单月分片，以便恢复任务扫描所有未完成意图；`order_no` 全局唯一，`(user_id,idempotency_key)` 组合唯一。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 意图内部 ID |
| `order_no` | VARCHAR(64) UNIQUE NOT NULL | 预扣幂等订单号 |
| `user_id` | BIGINT NOT NULL | 下单用户 |
| `idempotency_key` | VARCHAR(128) NOT NULL | 用户请求幂等键；与 `user_id` 组成唯一约束 |
| `items_snapshot` | JSON NOT NULL | 服务端校验后的 SKU/数量快照 |
| `status` | VARCHAR(32) NOT NULL | `INIT`/`RESERVE_UNKNOWN`/`RESERVED`/`COMMITTED`/`RELEASED` |
| `last_error` | VARCHAR(512) | 最近一次补偿错误 |
| `next_retry_at` | DATETIME(3) | 下次恢复时间 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义的审计时间 |

约束：`uk_intent_user_key(user_id,idempotency_key)`、`uk_intent_order_no(order_no)`；索引：`idx_intent_status_retry(status,next_retry_at)`、`idx_intent_user(user_id,created_at)`。流程约束：地址/商品校验通过后先在一个已提交的本地事务中写 `INIT`；库存预扣成功后在另一个本地事务中更新 `RESERVED`；订单与 timeout outbox 同事务成功后更新 `COMMITTED`；确认回滚后更新 `RELEASED`。扫描任务只处理超过恢复宽限期的 `INIT`/`RESERVED`，并按 `order_no` 重试同一预扣请求后回滚。`RESERVED` 意图必须保存完整 SKU/数量快照，不能依赖 Redis reservation key 存活。

### 5.5 `order_outbox`

订单本地事务与 RabbitMQ 超时消息之间的可靠交付表。该表不按订单月分片，dispatcher 按状态和下次重试时间扫描；不改变订单事实表的月度分片策略。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | outbox 内部 ID |
| `event_id` | VARCHAR(128) UNIQUE NOT NULL | RabbitMQ 事件幂等 ID |
| `event_type` | VARCHAR(64) NOT NULL | `ORDER_TIMEOUT` |
| `order_no` | VARCHAR(64) NOT NULL | 订单号 |
| `payload` | JSON NOT NULL | 含 `orderNo`、`expireAt`、`eventId`、`traceId` |
| `status` | VARCHAR(32) NOT NULL | `PENDING`/`PUBLISHED` |
| `attempts` | INT NOT NULL DEFAULT 0 | 投递尝试次数 |
| `next_retry_at` | DATETIME(3) NOT NULL | 下次投递时间 |
| `last_error` | VARCHAR(512) | 最近一次投递错误 |
| `published_at` | DATETIME(3) | publisher confirm 成功时间 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义的审计时间 |

索引：`idx_outbox_status_retry(status,next_retry_at)`、`idx_outbox_order(order_no)`。订单创建事务必须同时插入该表；只有 RabbitMQ publisher confirm 成功后才能标记 `PUBLISHED`。若 confirm 已成功但 `PUBLISHED` 更新失败，记录仍保持 `PENDING` 并重试同一 `eventId`；重复消息由关单 CAS 保证无副作用，不能生成新的事件 ID。发送超时、连接失败或进程中断保留 `PENDING`，dispatcher 重试；消费者以 `eventId` 和订单状态条件更新共同保证幂等。

## 6. 库存域表

### 6.1 `stock_sku`

`sku_id BIGINT PK`、`product_id BIGINT NOT NULL`、`total_quantity INT NOT NULL`、`available_quantity INT NOT NULL`、`reserved_quantity INT NOT NULL`、`sold_quantity INT NOT NULL`、`version INT NOT NULL`、`updated_at DATETIME(3) NOT NULL`。约束：各数量非负，`total_quantity = available_quantity + reserved_quantity + sold_quantity` 的维护规则需在事务内保证。索引：`idx_product_id(product_id)`。

### 6.2 `stock_flow`

`id BIGINT PK`、`sku_id BIGINT NOT NULL`、`order_no VARCHAR(64) NOT NULL`、`flow_type VARCHAR(32) NOT NULL`（RESERVE/CONFIRM/ROLLBACK/SECKILL_RESERVE）、`quantity INT NOT NULL`、`idempotency_key VARCHAR(128) UNIQUE NOT NULL`、`created_at DATETIME(3) NOT NULL`。索引：`idx_sku_created(sku_id, created_at)`、`idx_order_no(order_no)`。

### 6.3 `stock_cache_repair`

正常库存的 MySQL 状态是事实源，Redis 只保存可售数量投影。每次成功改变 `stock_sku` 的本地事务必须同时写入一条待执行的缓存修复记录，覆盖数据库提交后进程退出、Redis 连接失败和回补结果未知等窗口。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 修复记录 ID |
| `sku_id` | BIGINT NOT NULL | SKU |
| `target_available` | INT NOT NULL | 本次数据库提交后的可用库存 |
| `source_version` | INT NOT NULL | 对应 `stock_sku.version`，每次库存变更递增 |
| `status` | VARCHAR(16) NOT NULL | `PENDING`/`APPLIED`/`STALE` |
| `attempts` | INT NOT NULL DEFAULT 0 | 修复尝试次数 |
| `next_retry_at` | DATETIME(3) NOT NULL | 下次重试时间 |
| `lease_owner` | VARCHAR(128) | 当前修复任务实例标识 |
| `lease_until` | DATETIME(3) | 数据库租约到期时间；为空或已过期才可重新认领 |
| `last_error` | VARCHAR(512) | 最近错误 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义 |

索引：`uk_stock_cache_version(sku_id,source_version)`、`idx_stock_cache_retry(status,next_retry_at,lease_until)`。修复器通过 `lease_owner/lease_until` 在 MySQL 中按条件认领记录；租约只是避免多个修复实例同时处理同一记录，不是库存正确性的依据。Redis 不使用分布式锁承担一致性。Redis Key `stock:available:{skuId}` 使用 hash 字段 `quantity`、`version`。修复 Lua 只在 Redis 当前版本小于等于 `source_version` 时原子写入数量和版本；发现更高版本则将旧记录标记 `STALE`。启动校准读取 MySQL 当前版本并写入一次全量投影。

## 7. 支付域表

### 7.1 `pay_record`

`id BIGINT PK`、`pay_no VARCHAR(64) UNIQUE NOT NULL`、`order_no VARCHAR(64) UNIQUE NOT NULL`、`user_id BIGINT NOT NULL`、`amount DECIMAL(18,2) NOT NULL`、`status VARCHAR(32) NOT NULL`（PENDING/ORDER_SYNC_UNKNOWN/SUCCESS/REFUND_PENDING/REFUNDED/FAILED/CLOSED）、`paid_at DATETIME(3)`、`created_at DATETIME(3)`、`updated_at DATETIME(3)`。订单号唯一保证同一订单只有一个有效支付记录，重复支付/回调按幂等规则安全返回。

### 7.2 `pay_callback_log`

`id BIGINT PK`、`pay_no VARCHAR(64) NOT NULL`、`callback_id VARCHAR(128) UNIQUE NOT NULL`、`callback_status VARCHAR(32) NOT NULL`、`payload JSON`、`processed_at DATETIME(3)`、`created_at DATETIME(3) NOT NULL`。用于模拟回调幂等和审计；不得保存真实支付敏感数据。

### 7.3 `balance_ledger`（用户库）

余额扣款与退款必须和账本写入同一本地事务；Redis 只可作为重复请求的加速缓存，不能作为余额事实或退款依据。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 账本 ID |
| `user_id` | BIGINT NOT NULL | 用户 |
| `operation_key` | VARCHAR(128) UNIQUE NOT NULL | 扣款或退款操作键 |
| `operation_type` | VARCHAR(16) NOT NULL | `DEBIT`/`REFUND` |
| `original_operation_key` | VARCHAR(128) | 退款对应的原扣款键 |
| `pay_no` / `order_no` | VARCHAR(64) NOT NULL | 支付和订单关联键 |
| `amount` | DECIMAL(18,2) NOT NULL | 正数金额 |
| `state` | VARCHAR(16) NOT NULL | `APPLIED`/`REJECTED` |
| `balance_before` / `balance_after` | DECIMAL(18,2) NOT NULL | 审计快照 |
| `created_at` | DATETIME(3) NOT NULL | 创建时间 |

索引：`uk_balance_operation(operation_key)`、`idx_balance_order(order_no,created_at)`、`idx_balance_original(original_operation_key)`。退款操作必须以 `original_operation_key` 唯一对应已应用扣款，且退款总额不得超过原扣款；扣款与 `mall_user.balance` 条件更新在同一事务中完成。

### 7.4 `pay_debit_intent`

支付服务在调用用户余额服务前，必须先在支付库独立提交扣款意图。该表覆盖进程在用户扣款提交后、`pay_record` 更新前退出的窗口；恢复任务扫描该表，而不能只扫描支付记录状态。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 意图 ID |
| `pay_no` | VARCHAR(64) UNIQUE NOT NULL | 支付号，也是用户账本扣款操作键 |
| `order_no` | VARCHAR(64) UNIQUE NOT NULL | 订单号 |
| `user_id` | BIGINT NOT NULL | 用户 |
| `amount` | DECIMAL(18,2) NOT NULL | 扣款金额 |
| `status` | VARCHAR(32) NOT NULL | `INIT`/`DEBITED`/`ORDER_SYNC_UNKNOWN`/`SUCCESS`/`REFUND_PENDING`/`REFUNDED`/`FAILED` |
| `attempts` | INT NOT NULL DEFAULT 0 | 恢复尝试次数 |
| `next_retry_at` | DATETIME(3) NOT NULL | 下次恢复时间 |
| `lease_owner` | VARCHAR(128) | 当前恢复任务实例标识 |
| `lease_until` | DATETIME(3) | 恢复租约到期时间 |
| `last_error` | VARCHAR(512) | 最近错误 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义 |

索引：`uk_pay_debit_intent_pay(pay_no)`、`idx_pay_debit_recovery(status,next_retry_at,lease_until)`、`idx_pay_debit_order(order_no)`。创建意图与支付请求幂等键绑定，并在调用 user 前独立提交。恢复任务先查询用户账本操作：不存在则用同一 `payNo` 重试扣款；存在已应用扣款则进入订单状态查询；存在拒绝记录则进入 `FAILED`。恢复任务使用数据库租约和退避字段，超过阈值进入告警状态并继续可重入扫描，不能因扫描不到 `ORDER_SYNC_UNKNOWN` 的 `pay_record` 而遗漏已扣余额。

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
- 支付成功、手工取消和超时关单均必须检查条件更新影响行数；只有 CAS 成功的状态迁移方可触发库存确认/回滚。未完成的库存预扣必须能通过 `order_reservation_intent` 按订单号恢复。

## 12. 设计冻结说明

本轮已冻结单一 MySQL 8.0 实例按服务划分逻辑库、BIGINT 主键、Asia/Shanghai 语义 `DATETIME(3)`、`yyyyMM + UUID` 订单号、订单/明细/状态日志按 `created_at` 月度分表、当前月份及测试月份预建物理表、跨月列表按时间范围聚合、购物车 JSON value/30 天 TTL，以及秒杀 Redis 原子/Lua 预扣和活动 TTL。本文仍只描述逻辑设计，不生成 SQL。
