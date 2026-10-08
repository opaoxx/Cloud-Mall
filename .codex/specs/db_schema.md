# CloudMall 云购微商城数据库设计规格

## 1. 设计原则

- MySQL 8.0 保存业务事实数据；各服务按边界拥有自己的表，禁止跨服务直接读表。
- 主键统一使用 `BIGINT` 类型的业务无关唯一标识；生成算法由实现选择不改变字段契约。
- 金额使用定点数而非浮点数；API 金额使用字符串并映射为 Java `BigDecimal`；数据库时间使用 Asia/Shanghai 语义的 `DATETIME(3)`。
- 商品、订单和订单明细的金额列继续使用既有 `DECIMAL(18,2)` DDL；写入前由订单应用拒绝不能精确表示到分的非零 `BigDecimal`，不依赖数据库隐式舍入，也不因此修改表结构或历史数据。
- `DECIMAL(18,2)` 的应用容量语义为最大可表示值 `9999999999999999.99`；订单写入前由应用比较并拒绝超容量单价、行金额或总额，DDL、索引和历史数据保持不变。
- `mall_order_item` 的 `product_id BIGINT NOT NULL` 与 `product_name_snapshot VARCHAR(255) NOT NULL` 继续使用既有 DDL；订单应用按非空和 Unicode code point 不超过 255 预检，禁止依赖非严格 SQL 截断或默认值，不修改表结构和历史行。
- `mall_order.address_snapshot JSON NOT NULL` 与 `mall_order_item.sku_snapshot JSON NULL` 的字段定义和 null 语义保持不变；普通订单应用在 intent/Stock/订单 SQL 前完成 JSON 序列化，避免依赖数据库阶段才暴露序列化失败，不修改 DDL 或历史数据。
- 秒杀消息的 `userId`、`activityId`、`skuId` 继续映射既有 BIGINT/业务身份字段；消费者在写入前必须无损解析，禁止依赖数据库截断、回绕或非严格模式转换，不修改字段、索引、历史行或消息表结构。
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
| `balance` | DECIMAL(18,2) NOT NULL DEFAULT 0.00 | Legacy 可用余额事实 |
| `frozen_balance` | DECIMAL(18,2) NOT NULL DEFAULT 0.00 | 第 14 轮 User TCC 冻结额；不混入 Legacy 可用余额 |
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

索引：`idx_category_status(category_id, status)`、`idx_product_updated_id(updated_at DESC, id DESC)`；搜索索引由 ES 管理。`idx_product_updated_id` 服务无筛选的最新排序列表，必须以实际 `EXPLAIN ANALYZE` 验证后迁移。带 `status` 或 `category_id` 的复合索引不视为已存在，需基于实际执行计划和写入代价另行评审。所有会影响公开索引的商品、SKU、参数、上下架和删除写操作必须在同一本地事务内把 `version` 严格递增一次；事件使用提交后的版本。迁移前必须审计并修正历史更新未递增造成的重复版本，不能依赖时间戳猜测新旧。

### 4.3 `product_sku`

`id BIGINT PK`、`product_id BIGINT NOT NULL`、`sku_code VARCHAR(64) UNIQUE NOT NULL`、`spec_json JSON NOT NULL`、`price DECIMAL(18,2) NOT NULL`、`status TINYINT NOT NULL`、`created_at`、`updated_at`。索引：`idx_product_id_id(product_id, id)`。该索引服务商品详情和列表映射按商品读取 SKU 并按 `id` 稳定排序；`product_id,status` 组合索引不视为已存在，只有实际执行计划证明需要时再单独评审。`spec_json` 使用属性名到属性值的 JSON 对象表达 SKU 规格，订单明细保存不可变 `sku_snapshot`。

### 4.4 `product_parameter`

`id BIGINT PK`、`product_id BIGINT NOT NULL`、`param_name VARCHAR(128) NOT NULL`、`param_value VARCHAR(512) NOT NULL`、`sort_no INT NOT NULL`、`created_at`、`updated_at`。索引：`idx_product_sort(product_id, sort_no)`。

### 4.5 `product_hot_stat`

`product_id BIGINT PK`、`view_count BIGINT NOT NULL`、`search_count BIGINT NOT NULL`、`hot_score DECIMAL(18,6) NOT NULL`、`stat_date DATE NOT NULL`、`updated_at DATETIME(3) NOT NULL`、`source_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'`、`source_ref VARCHAR(128) NULL`。现有目录行由 `hm-item.item` 导入，`view_count` 实际保存源 `sold`，`search_count` 实际保存源 `comment_count`，`hot_score` 初始保存源 `sold`；`LEGACY_IMPORT` 来源标记说明它们是历史快照，不是本地实时浏览、搜索或成交事实。在线指标事件与独立版本协议待 CM-21 后续阶段实现，现阶段不得宣称浏览事件已经通过 RabbitMQ 写入。

### 4.6 `product_index_outbox`

商品事实写入事务与索引事件的可靠交付表。商品创建、修改、上架、下架和删除在修改 `product`（以及同一商品的 SKU/参数事实）时，在同一本地事务插入一条索引事件；事务提交后才由 dispatcher 投递 RabbitMQ。事件只携带事实定位信息和版本，消费者必须重新读取当前 MySQL 商品事实，不得信任消息内的价格、名称或上下架状态。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | outbox 内部 ID |
| `event_id` | VARCHAR(128) UNIQUE NOT NULL | 稳定事件号；重试和重放不得改变 |
| `product_id` | BIGINT NOT NULL | 商品事实 ID |
| `product_version` | INT NOT NULL | 事务提交后的商品版本 |
| `event_type` | VARCHAR(32) NOT NULL | `UPSERT`/`HIDE`；删除、下架都使用 `HIDE` |
| `source` | VARCHAR(32) NOT NULL | `LIVE`/`REBUILD`/`LEGACY_REPLAY` |
| `payload_json` | JSON NOT NULL | 完整 JSON envelope；事务内固定 `eventId/eventType/productId/productVersion/occurredAt/traceId/source/payloadVersion`，重试原样发布 |
| `status` | VARCHAR(32) NOT NULL | `PENDING`/`UNKNOWN`/`PUBLISHED`/`DEAD`/`CONFLICT` |
| `attempts` | INT NOT NULL | 投递尝试次数 |
| `next_retry_at` | DATETIME(3) NOT NULL | 下次投递时间 |
| `last_published_at` / `ack_deadline_at` | DATETIME(3)/DATETIME(3) | 最近一次确认发布时间、消费 ACK 截止时间 |
| `lease_owner` / `lease_until` | VARCHAR(64)/DATETIME(3) | dispatcher 租约 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | 审计时间 |

索引：`uk_product_index_event(event_id)`、`idx_product_index_outbox_retry(status,next_retry_at,lease_until)`、`idx_product_index_outbox_ack(status,ack_deadline_at)`、`idx_product_index_product(product_id,product_version)`。同一商品允许多个版本事件共存；事件发送成功不代表 ES 已应用。`PUBLISHED` 事件在 `ack_deadline_at` 前未出现 `product_index_consumption=APPLIED/STALE` 时，dispatcher 必须以同一 `event_id` 重新投递并延长截止时间；不得因为已发布就永久停止扫描。`occurredAt`、`traceId` 和其他 envelope 字段只从 `payload_json` 读取，dispatcher 不得用当前时间或当前 MDC 重建 payload。

### 4.7 `product_index_consumption`

商品索引消费者的持久 ACK/处理记录。`event_id` 是幂等键，消费者完成 ES 写入或确认该事件已被更高版本覆盖后才写 `APPLIED`/`STALE`；失败保留 `RETRY`，不可解析或载荷冲突进入 `QUARANTINED`。ACK 写失败时消息不得确认，重复投递必须安全重入。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `event_id` | VARCHAR(128) PK | 对应 outbox 事件 |
| `product_id` | BIGINT NOT NULL | 商品事实 ID |
| `event_version` | INT | 事件版本；旧消息可为空 |
| `applied_version` | INT | 实际写入或观察到的 ES 版本 |
| `status` | VARCHAR(32) NOT NULL | `PROCESSING`/`APPLIED`/`STALE`/`RETRY`/`QUARANTINED` |
| `attempts` | INT NOT NULL | 消费尝试次数 |
| `last_error` | VARCHAR(1024) | 最近错误摘要，不写堆栈和敏感数据 |
| `lease_owner` / `lease_until` | VARCHAR(64)/DATETIME(3) | 消费租约 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | 审计时间 |

索引：`idx_product_index_consume_retry(status,updated_at,lease_until)`、`idx_product_index_consume_product(product_id,applied_version)`。该表是消费 ACK 和恢复依据，不承担商品事实。

### 4.8 `product_index_dlq_quarantine`

无法安全解释的历史索引消息隔离表。消费 DLQ 时必须先在 MySQL 保存消息元数据、完整原始 payload 和 SHA-256，再确认 RabbitMQ 消息；禁止对 `application/x-java-serialized-object` 使用 Java 原生反序列化，也禁止把未知载荷盲目转换为新 JSON envelope。`product_id` 可为空，后续优先通过商品事实和 outbox 重建，不能安全归属的消息保留证据并人工核验。应用接受的原始 payload 上限为 1 MiB，超过上限时不得截断后 ACK；只能记录元数据和摘要、保持消息未确认并告警。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `quarantine_id` | VARCHAR(128) PK | `queue + message_id + sha256` 的稳定键 |
| `queue_name` | VARCHAR(255) NOT NULL | 来源队列/DLQ |
| `content_type` | VARCHAR(128) | 原始 content type |
| `content_encoding` | VARCHAR(64) | 原始编码 |
| `payload_sha256` | CHAR(64) NOT NULL | 原始 payload 摘要 |
| `raw_payload` | LONGBLOB NOT NULL | 完整原始消息字节；应用层限制不超过 1 MiB，不反序列化 |
| `payload_size` | INT UNSIGNED NOT NULL | 原始 payload 字节数 |
| `product_id` | BIGINT | 仅在安全解析并核验后填写 |
| `reason` | VARCHAR(512) NOT NULL | 隔离原因 |
| `status` | VARCHAR(32) NOT NULL | `QUARANTINED`/`REVIEWED`/`REBUILT` |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | 审计时间 |

索引：`uk_product_index_quarantine_hash(queue_name,payload_sha256)`、`idx_product_index_quarantine_status(status,created_at)`。只有 `raw_payload` 已完整写入且 SHA-256 校验通过时才允许 ACK 原 DLQ；超限、写入失败或摘要不一致必须保持未 ACK。

### 4.9 `product_index_rebuild_job`

存量索引重建控制表。重建按商品 ID 游标分批读取当前 MySQL 事实，直接调用有界 ES Bulk；实时商品变更仍走 `product_index_outbox`。禁止服务启动时一次性扫描并发送全表。Bulk 批次失败时不推进游标，重试同一批即可依靠 ES external version 幂等收敛。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `job_id` | VARCHAR(64) PK | 重建任务号 |
| `target_index` | VARCHAR(128) NOT NULL | 目标 ES 索引名，如 `cloudmall_product` |
| `cursor_product_id` | BIGINT NOT NULL | 已生成事件的最大商品 ID |
| `batch_size` | INT NOT NULL | 单批最大商品数，默认 200 |
| `max_events_per_minute` | INT NOT NULL | 限速阈值，按本地开发资源配置 |
| `last_batch_count` | INT NOT NULL | 最近一次批量商品数 |
| `last_bulk_at` | DATETIME(3) | 最近一次 Bulk 请求时间 |
| `index_generation` | INT NOT NULL | 目标索引重建代次；索引丢失时递增 |
| `status` | VARCHAR(32) NOT NULL | `PENDING`/`RUNNING`/`PAUSED`/`COMPLETED`/`FAILED` |
| `lease_owner` / `lease_until` | VARCHAR(64)/DATETIME(3) | 扫描租约 |
| `last_error` | VARCHAR(1024) | 最近错误摘要 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | 审计时间 |

任务按 `(product_id > cursor_product_id) ORDER BY product_id LIMIT batch_size` 读取，Bulk 成功后才推进游标；部分失败或 ES 中断不推进游标并退避重试。任务暂停、进程重启或 ES 不可用时保留游标，恢复后从未完成批次继续；重复读取同一批依靠 external version 幂等，不产生 88,000 条额外 outbox 和消息堆积。周期检查发现目标索引不存在或代际标识改变时，必须在租约内把 `index_generation` 加一、`cursor_product_id=0`、状态置为 `RUNNING`，从头限速重建；`COMPLETED` 不是永久终态。`FAILED` 只表示本轮失败并保留错误，达到退避时间后仍可由同一任务重试，不得丢弃游标。实时事件与 backfill 共用 external version 规则，不能让 backfill 旧版本覆盖实时新版本。

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

约束：金额非负；状态只能按状态机迁移；订单号 `yyyyMM + UUID` 全局唯一且可定位月份。索引：`idx_user_created(user_id, created_at)`、`idx_status_expire(status, expire_at)`、`idx_created_at(created_at)`。现有 `idx_user_created` 能支持用户和时间范围的主要过滤，但没有覆盖稳定排序的 `order_no`，也未证明能覆盖带 `status` 条件的全部查询；实现必须显式使用 `created_at DESC, order_no DESC`，先以 `EXPLAIN` 和实际分页耗时验证，当前不凭推测新增索引或改表。若后续证据表明深分页计划退化，再单独评审复合索引及迁移窗口。订单、明细和状态日志均按 `created_at` 月度分表；跨月用户订单列表按时间范围路由并聚合分页结果。

### 5.2 `mall_order_item_${yyyyMM}`

与订单同月分片，字段：`id BIGINT PK`、`order_id BIGINT NOT NULL`、`order_no VARCHAR(64) NOT NULL`、`product_id BIGINT NOT NULL`、`sku_id BIGINT NOT NULL`、`product_name_snapshot VARCHAR(255) NOT NULL`、`sku_snapshot JSON`、`unit_price DECIMAL(18,2) NOT NULL`、`quantity INT NOT NULL`、`line_amount DECIMAL(18,2) NOT NULL`、`created_at DATETIME(3) NOT NULL`。索引：`idx_order_id(order_id)`、`idx_order_no(order_no)`；数量大于 0，行金额须与单价和数量一致。

### 5.3 `order_status_log_${yyyyMM}`

字段：`id BIGINT PK`、`order_id BIGINT NOT NULL`、`order_no VARCHAR(64) NOT NULL`、`from_status VARCHAR(32)`、`to_status VARCHAR(32) NOT NULL`、`event_type VARCHAR(64) NOT NULL`、`operator_id BIGINT`、`remark VARCHAR(512)`、`created_at DATETIME(3) NOT NULL`。索引：`idx_order_created(order_id, created_at)`。用于审计和状态追踪，不替代订单主状态。

### 5.3.1 `seckill_order_dedup`

秒杀消息消费使用独立表作为用户幂等请求的原子认领，避免给普通订单共享的 `order_idempotency` 表增加秒杀专属字段或状态。`user_id + idempotency_key` 跨活动全局只绑定一个规范化请求载荷，不能因为重复消息改写 `order_no`。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 幂等记录 ID |
| `user_id` | BIGINT NOT NULL | 用户 |
| `idempotency_key` | VARCHAR(128) NOT NULL | 客户端幂等键 |
| `order_no` | VARCHAR(64) NOT NULL | 首次成功生成的订单号 |
| `request_hash` | CHAR(64) NOT NULL | 规范化活动、SKU、用户、地址和数量载荷 SHA-256 |
| `status` | VARCHAR(16) NOT NULL | `PROCESSING`/`COMMITTED` |
| `event_id` | VARCHAR(128) | 首次消费事件 ID |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义 |

约束：`uk_seckill_dedup_user_key(user_id,idempotency_key)`、`uk_seckill_dedup_order(order_no)`，并建立 `idx_seckill_dedup_event(event_id)`。消费者在同一订单本地事务内原子插入/锁定该记录并写订单；同键同哈希的并发重复消息读取 `COMMITTED` 后确认消费，不重建订单；同键不同哈希进入独立 `seckill_order_dedup_conflict` 并确认消息，不进入 RabbitMQ 重试/死信。迁移前必须审计存量 `(user_id,idempotency_key)` 重复行：保留最早的原 `order_no` 作为事实，冲突行不得静默合并，标记为人工待处理；新增唯一约束前不得接受新的同键请求。

首次消费完成时，应用必须以当前源条件/SQL 语义下 `markCommitted` 单行 UPDATE 结果 1 作为 `PROCESSING→COMMITTED` 的完成门；非 1 或写入异常不得继续完成/Resolve，不新增二次状态读取，既有 DDL、条件和历史事实保持不变。

### 5.3.2 `seckill_order_dedup_conflict`

主队列消费发现同一用户幂等键对应不同载荷、orderNo 不一致或事件哈希非法时，写入独立冲突表后确认消息；不得改写原 `seckill_order_dedup`，也不得把正常主队列冲突误记为死信恢复记录。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 冲突记录 ID |
| `event_id` | VARCHAR(128) UNIQUE NOT NULL | 触发冲突的事件 ID |
| `user_id` | BIGINT NOT NULL | 用户 |
| `idempotency_key` | VARCHAR(128) NOT NULL | 冲突幂等键 |
| `order_no` | VARCHAR(64) | 冲突消息携带的订单号 |
| `existing_order_no` | VARCHAR(64) | 原幂等记录订单号 |
| `request_hash` | CHAR(64) NOT NULL | 冲突消息哈希；非法结构也保存原始规范化字节的 SHA-256 |
| `existing_request_hash` | CHAR(64) | 原幂等记录哈希 |
| `conflict_type` | VARCHAR(32) NOT NULL | `HASH_MISMATCH`/`ORDER_NO_MISMATCH`/`INVALID_PAYLOAD` |
| `payload` | JSON NOT NULL | 原始冲突消息 |
| `raw_payload` | LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL | 当前 ObjectMapper 序列化得到的完整 UTF-8 JSON 文本；历史行允许 NULL，不从 payload 伪造回填 |
| `status` | VARCHAR(16) NOT NULL | `RECORDED`/`REVIEWED`/`RESOLVED` |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | 审计时间 |

唯一约束：`uk_seckill_conflict_event(event_id)`、`uk_seckill_conflict_fingerprint(user_id,idempotency_key,request_hash,conflict_type)`。重复冲突消息按唯一键幂等记录；`RECORDED` 表示消息已确认且不重试，`REVIEWED/RESOLVED` 只由人工对账流程推进。`payload` 是结构化 JSON 视图，`raw_payload` 是当前序列化文本证据，不参与唯一键或幂等匹配。该表与 `seckill_message_recovery` 分工明确：前者记录主队列即时冲突，后者记录 DLQ 的可重放/隔离状态。

冲突台账只接受成功序列化的 payload；序列化失败不得写入伪造证据或确认消息。event_id/request_hash 的派生必须基于同一成功 payload，既有唯一键和历史 `serializationError` 行不自动迁移。

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

### 5.6 `order_stock_action`

支付确认、手工取消和超时关单不能在订单本地事务中直接调用库存。订单状态迁移与库存动作意图必须先独立提交，再由恢复任务执行远程确认/回滚，覆盖库存已提交但响应丢失、订单事务回滚和进程崩溃窗口。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 动作 ID |
| `order_no` | VARCHAR(64) UNIQUE NOT NULL | 订单号；每个订单只允许一个最终库存动作 |
| `action_type` | VARCHAR(16) NOT NULL | `CONFIRM`/`ROLLBACK` |
| `operation_key` | VARCHAR(128) UNIQUE NOT NULL | 传给 stock 的稳定幂等键 |
| `status` | VARCHAR(16) NOT NULL | `PENDING`/`UNKNOWN`/`SUCCEEDED`/`CONFLICT` |
| `attempts` | INT NOT NULL DEFAULT 0 | 远程调用/恢复次数 |
| `next_retry_at` | DATETIME(3) NOT NULL | 下次恢复时间 |
| `lease_owner` | VARCHAR(128) | 当前恢复任务实例 |
| `lease_until` | DATETIME(3) | 恢复租约到期时间 |
| `last_error` | VARCHAR(512) | 最近错误 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义 |

索引：`uk_order_stock_action_order(order_no)`、`uk_order_stock_action_key(operation_key)`、`idx_order_stock_action_retry(status,next_retry_at,lease_until)`。订单状态 CAS 成功后，必须在同一订单本地事务中插入对应动作；远程调用只能发生在事务提交之后。动作完成以 stock 的最终状态查询为准：目标状态已达到视为成功；结果未知保留 `UNKNOWN` 并重试同一操作键；发现相反终态标记 `CONFLICT` 并告警，禁止继续执行相反动作或伪造订单状态。

### 5.7 `seckill_message_recovery`

秒杀订单死信由订单服务持久化接管，避免 DLQ 消息只停留在 RabbitMQ。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 恢复记录 ID |
| `event_id` | VARCHAR(128) UNIQUE NOT NULL | 原事件 ID |
| `payload` | JSON NOT NULL | 原始 envelope |
| `request_hash` | CHAR(64) NOT NULL | 规范化载荷哈希 |
| `status` | VARCHAR(16) NOT NULL | `DEAD`/`VERIFYING`/`REPLAY_PENDING`/`REPLAYING`/`RESOLVED`/`QUARANTINED`/`CONFLICT`；`DEAD` 仅表示达到告警阈值，仍可重试 |
| `attempts` | INT NOT NULL DEFAULT 0 | 重放次数 |
| `next_retry_at` | DATETIME(3) NOT NULL | 下次重放时间 |
| `lease_owner` | VARCHAR(128) | 当前重放任务实例 |
| `lease_until` | DATETIME(3) | 数据库租约到期时间 |
| `last_error` | VARCHAR(512) | 最近错误 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | 审计时间 |

DLQ consumer 先写入该表再确认死信消息；replayer 必须先通过 `GET /api/internal/stock/seckill/reservations/{orderNo}` 验证 stock 的持久受理状态，再决定重放。只有 `ACCEPTED`、`PUBLISHED` 或 `ORDER_CREATED` 才能进入 `REPLAY_PENDING` 并使用原 `eventId`/payload 发布；`NOT_FOUND`/`REJECTED` 标记 `QUARANTINED` 并持续核验，查询未知标记 `VERIFYING` 并退避重试，均不得创建订单或丢弃消息。同键不同哈希标记 `CONFLICT` 并告警，不自动重放。

## 6. 库存域表

### 6.1 `stock_sku`

`sku_id BIGINT PK`、`product_id BIGINT NOT NULL`、`total_quantity INT NOT NULL`、`available_quantity INT NOT NULL`、`reserved_quantity INT NOT NULL`、`sold_quantity INT NOT NULL`、`version INT NOT NULL`、`updated_at DATETIME(3) NOT NULL`。约束：各数量非负，`total_quantity = available_quantity + reserved_quantity + sold_quantity` 的维护规则需在事务内保证。索引：`idx_product_id(product_id)`。

### 6.2 `stock_flow`

`id BIGINT PK`、`sku_id BIGINT NOT NULL`、`order_no VARCHAR(64) NOT NULL`、`flow_type VARCHAR(32) NOT NULL`（RESERVE/CONFIRM/ROLLBACK/SECKILL_RESERVE）、`quantity INT NOT NULL`、`idempotency_key VARCHAR(128) UNIQUE NOT NULL`、`created_at DATETIME(3) NOT NULL`。索引：`idx_sku_created(sku_id, created_at)`、`idx_order_no(order_no)`。

### 6.2.1 `stock_reservation_request`

普通预扣必须先以订单号建立全局请求认领，不能只依赖按 SKU 拆分的 `stock_flow.idempotency_key`。该表防止同一 `orderNo` 携带不同 SKU 集合时分别通过“未见流水”检查并占用不同库存。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 请求 ID |
| `order_no` | VARCHAR(64) UNIQUE NOT NULL | 全局预扣请求键 |
| `scene` | VARCHAR(32) NOT NULL | `NORMAL` 等场景 |
| `items_snapshot` | JSON NOT NULL | 按 `skuId` 升序规范化后的 SKU/数量集合 |
| `items_hash` | CHAR(64) NOT NULL | 规范化快照 SHA-256；同一订单号必须完全匹配 |
| `status` | VARCHAR(32) NOT NULL | `INIT`/`RESERVED`/`REJECTED`/`CONFIRMED`/`ROLLED_BACK` |
| `result_code` | VARCHAR(64) | 库存不足、参数冲突等最终结果 |
| `last_error` | VARCHAR(512) | 最近恢复错误 |
| `next_retry_at` | DATETIME(3) | INIT 恢复任务的下次尝试时间 |
| `lease_owner` | VARCHAR(128) | 当前库存事务/恢复任务实例标识 |
| `lease_until` | DATETIME(3) | 租约到期时间；用于恢复陈旧 INIT |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义 |

索引：`uk_stock_request_order(order_no)`、`idx_stock_request_status(status,next_retry_at,lease_until)`。预扣分两阶段执行：第一阶段在独立本地事务中插入并提交 `INIT` 请求认领，固定 `order_no`、`scene`、`items_snapshot` 和 `items_hash`；第二阶段在库存事务中按订单号锁定该行，确认仍为 `INIT` 后获取租约，更新全部 SKU、写入 `stock_flow`/`stock_cache_repair`，最后将请求标记 `RESERVED` 并提交。任何 SKU 不足时第二阶段事务整体回滚，随后在独立事务中锁定同一 `INIT` 行并标记 `REJECTED`，保留原哈希；因此拒绝结果不会释放订单号让不同快照重新认领。相同 `orderNo` 携带不同 `items_hash` 返回 `ORDER_RESERVATION_KEY_REUSED`，不得改变库存。确认/回滚先按请求状态 CAS，再按其快照处理，重复调用不得重复改变数量。进程在第二阶段提交前退出时，恢复任务等待 `lease_until` 到期后锁定 `INIT` 行重试；若事务实际已提交，行状态为 `RESERVED`，恢复任务不得再次预扣。只有确认租约到期且请求仍为 `INIT`、不存在已提交预扣流水时，恢复任务才可将其标记 `REJECTED`。

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

### 6.4 `seckill_reservation`

秒杀库存受理事实表。`(user_id,idempotency_key)` 和 `order_no` 均不可变；重复请求不得改写原订单号。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 受理记录 ID |
| `activity_id` / `sku_id` / `user_id` | BIGINT NOT NULL | 秒杀请求维度 |
| `order_no` | VARCHAR(64) UNIQUE NOT NULL | 异步订单号 |
| `idempotency_key` | VARCHAR(128) NOT NULL | 客户端幂等键；同一用户跨活动全局唯一 |
| `request_payload` | JSON NOT NULL | Lua 扣减前持久化的完整规范化请求，必须包含 addressSnapshot |
| `request_hash` | CHAR(64) NOT NULL | 规范化请求哈希 |
| `status` | VARCHAR(32) NOT NULL | `PENDING`/`UNKNOWN`/`ACCEPTED`/`REJECTED`/`ORDER_CREATED`/`CONFLICT`；`UNKNOWN` 必须持续核验原 Redis marker 和 outbox，不能停止恢复 |
| `last_error` | VARCHAR(512) | 最近错误 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | 审计时间 |

唯一约束：`uk_seckill_global_request(user_id,idempotency_key)`、`uk_seckill_order(order_no)`，并建立 `idx_seckill_activity_sku(activity_id,sku_id,status)`。同一用户同一幂等键跨活动也必须比较 `request_hash`：同哈希返回原 `order_no`，不同哈希返回 `SECKILL_IDEMPOTENCY_CONFLICT`，不得改订单号、重复扣 Redis 或发布新事件。迁移现有按活动分组的唯一约束前，必须审计跨活动重复键；保留最早原 `order_no`，冲突行标记 `CONFLICT` 并告警，不得静默生成新订单。`ACCEPTED` 只表示 Redis 预扣成功且 outbox 已持久化，不代表订单已落库。

### 6.4.1 `seckill_quota_settlement`

秒杀受理之后的独立最终配额结算事实。该表不替换 `seckill_reservation` 的 `ACCEPTED` 语义，也不写入普通 `stock_flow`。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PRIMARY KEY | 结算 ID |
| `order_no` | VARCHAR(64) UNIQUE NOT NULL | 秒杀订单唯一键 |
| `activity_id` / `sku_id` / `user_id` | BIGINT NOT NULL | 从受理事实 `INSERT ... SELECT` 固定复制 |
| `idempotency_key` | VARCHAR(128) NOT NULL | 原受理幂等键 |
| `quantity` | INT NOT NULL | 当前固定为 1 |
| `status` | VARCHAR(32) NOT NULL | `OPEN`/`RELEASE_PENDING`/`RELEASED`/`CONFIRMED`/`PROJECTION_BLOCKED`/`CONFLICT` |
| `revision` | BIGINT NOT NULL | 单订单结算 revision；OPEN=1，rollback 方向=2；不等同活动全局 Redis revision |
| `operation_key` | VARCHAR(160) | `orderNo:ROLLBACK` 或 `orderNo:CONFIRM` |
| `redis_revision` | BIGINT | 释放结算 Lua 成功 receipt 中的活动全局 revision；OPEN 阶段为空 |
| `last_error` | VARCHAR(512) | 可恢复错误摘要 |
| `next_retry_at` | DATETIME(3) | 恢复扫描时间 |
| `lease_owner` / `lease_until` | VARCHAR(128) / DATETIME(3) | 恢复租约 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义 |

约束：`uk_seckill_settlement_order(order_no)`、`uk_seckill_settlement_operation(operation_key)`；索引 `idx_seckill_settlement_retry(status,next_retry_at,lease_until)`、`idx_seckill_settlement_activity(activity_id,sku_id,status)`。受理事务使用锁定的 `seckill_reservation` 事实插入 `OPEN`；reconciler 只可为已有 `ACCEPTED` ledger 补建 OPEN，不可把缺失 op marker 猜测成 RESERVED。

Redis 受理 Lua 必须原子初始化/递增 `seckill:quota:revision:{activityId}:{skuId}`，并写 `seckill:quota:op:{orderNo}=RESERVED|orderNo|acceptanceRevision`。释放 Lua 只能匹配该 RESERVED marker 或同 operationKey、同 settlement revision 的 APPLIED marker；缺 quota key、marker、活动 revision 或元数据时保持 `RELEASE_PENDING/PROJECTION_BLOCKED`，禁止 SET 重建或 INCR 回补。`seckill:user:{activityId}:{userId}` 只增不减。

### 6.5 `seckill_order_outbox`

库存服务将秒杀订单事件持久化后再异步投递 RabbitMQ，覆盖 Redis 预扣成功后进程退出和 publisher confirm 未知窗口。

| 字段 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | outbox ID |
| `event_id` | VARCHAR(128) UNIQUE NOT NULL | 稳定消息 ID，重试不得改变 |
| `order_no` | VARCHAR(64) UNIQUE NOT NULL | 对应受理订单 |
| `idempotency_key` | VARCHAR(128) NOT NULL | 客户端幂等键 |
| `request_hash` | CHAR(64) NOT NULL | 载荷哈希 |
| `payload` | JSON NOT NULL | 事件 envelope |
| `status` | VARCHAR(16) NOT NULL | `PENDING`/`PUBLISHED`/`UNKNOWN`/`DEAD` |
| `attempts` | INT NOT NULL DEFAULT 0 | 投递次数 |
| `next_retry_at` | DATETIME(3) NOT NULL | 下次投递时间 |
| `lease_owner` | VARCHAR(128) | 当前 dispatcher 实例标识 |
| `lease_until` | DATETIME(3) | outbox 认领租约到期时间 |
| `last_error` | VARCHAR(512) | 最近错误 |
| `published_at` | DATETIME(3) | publisher confirm 时间 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | 审计时间 |

索引：`idx_seckill_outbox_scan(status,next_retry_at,lease_until)`。dispatcher 先以 `status IN (PENDING,UNKNOWN,DEAD)`、`next_retry_at<=now` 且租约为空/已过期为条件，CAS 写入 `lease_owner` 和 `lease_until` 认领记录；发布前后都不得处理不再持有的租约。dispatcher 检查 returned message 并等待 publisher confirm；成功标记 `PUBLISHED` 并清空租约，连接/确认结果未知保持 `UNKNOWN` 并用同一 `eventId` 重试。达到告警阈值后可标记 `DEAD` 并告警，但 `DEAD` 仍参与持续退避扫描，不能停止重试、删除受理台账或直接把 Redis 预扣当作订单成功。只有不可解析或不可变载荷冲突才进入不可自动恢复的 `CONFLICT`。

## 7. 支付域表

### 7.1 `pay_record`

`id BIGINT PK`、`pay_no VARCHAR(64) UNIQUE NOT NULL`、`order_no VARCHAR(64) UNIQUE NOT NULL`、`user_id BIGINT NOT NULL`、`amount DECIMAL(18,2) NOT NULL`、`status VARCHAR(32) NOT NULL`（PENDING/ORDER_SYNC_UNKNOWN/SUCCESS/REFUND_PENDING/REFUNDED/FAILED/CLOSED）、`debit_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY'`（LEGACY/TCC）、`paid_at DATETIME(3)`、`created_at DATETIME(3)`、`updated_at DATETIME(3)`。订单号唯一保证同一订单只有一个有效支付记录，重复支付/回调按幂等规则安全返回。`debit_mode` 在支付记录创建时冻结；历史行和缺失值按 LEGACY 处理，后续配置变化不得改写。

### 7.2 `pay_callback_log`

`id BIGINT PK`、`pay_no VARCHAR(64) NOT NULL`、`callback_id VARCHAR(128) UNIQUE NOT NULL`、`callback_status VARCHAR(32) NOT NULL`、`payload JSON`、`processed_at DATETIME(3)`、`created_at DATETIME(3) NOT NULL`。`callback_status` 冻结 `RECEIVED_SUCCESS`/`RECEIVED_FAILURE` 为仅收到未处理状态（`processed_at=NULL`），处理完成后使用 `SUCCESS`/`FAILED`；并发首次请求通过独立事务 `INSERT IGNORE` 认领同一 callbackId。payload 保存不可变 payNo/userId/amount/success；旧 `{}` 仅在同 payNo、同方向且当前 Pay 字段已校验时兼容。用于模拟回调幂等和审计；不得保存真实支付敏感数据。

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

第 14 轮新增 `balance_debit_attempt` 和 `balance_tcc_debit` 只服务 User 本地 TCC 就绪状态机。Legacy 归属最终为 `APPLIED/REJECTED`，TCC 归属为 `TRYING/CONFIRMED/CANCELLED`；同一 `pay_no`、XID、branchId 的唯一约束防止 Legacy/TCC 互斥失败和同资源重复分支。余额不足的 TCC Try 整笔回滚，不写失败归属。Confirm/Cancel 目前是 User 内部本地事务方法，尚未接入 Seata Fence、Pay TCC 或 HTTP 编排。

`balance_debit_attempt` 至少包含 `pay_no`、`operation_key`、`ledger_key`、`mode`、`xid`、`branch_id`、`user_id`、`order_no`、`amount`、`state` 和审计时间，唯一约束为 `pay_no`、`operation_key`、`ledger_key`、`xid`、`(xid,branch_id)`；Legacy 的 XID 为 NULL，MySQL 唯一索引允许多个 NULL。TCC 的 `operation_key=tcc-debit:<payNo>`，但 `ledger_key=payNo`；Legacy 的 `ledger_key` 等于请求 operationKey，Confirm 只能使用归属行的不可变 ledger_key。`balance_tcc_debit` 至少包含相同业务键、`balance_before`、`balance_after_try` 和状态，唯一约束为 `operation_key`、`xid`、`(xid,branch_id)`、`pay_no`。两表与 `mall_user` 使用同一 User 数据源和锁顺序；任何未来 Fence 表必须继续位于同一数据源。

第 15A 使用 Seata 1.5.2 官方 `tcc_fence_log` 作为唯一 Fence 事实来源；不复制其表结构、不建立第二张 Fence 表。`tcc_fence_log` 与 `mall_user`、`balance_debit_attempt`、`balance_tcc_debit` 必须在同一 User 数据源和本地事务中写入。资源适配器使用 `useTCCFence=false`，由适配器显式调用官方 Fence API；空回滚的 Fence `SUSPENDED` 与业务 `CANCELLED` tombstone 一起提交。进程中断和 TC 暂失不属于 15A，后置 15B。

第 16A 新增 Pay `pay_tcc_dispatch` 和 User `user_tcc_receive` 作为跨服务一次性派发事实。Pay dispatch 只能绑定原 `pay_record` 中精确 `TCC + PENDING` 的 payNo/user/order/amount；User receive 以 payNo、XID、operationKey、ledgerKey 唯一认领，`CLAIMED` 写入后才允许进入 LocalTCC。两张表不替代资金事实，重复 POST 只读取它们和 User attempt/TCC/ledger，不能注册第二 branch。GET 状态必须由一致快照核对，`TRYING` 不等于已扣款，终态不得被未知结果覆盖。

第 16B1 新增 Pay `pay_tcc_receipt`，为 Pay 自有 LocalTCC/Fence 事实，不是 User 状态镜像。表状态只允许 `CLAIMED/TRYING/CONFIRMED/CANCELLED`，唯一约束为 payNo、XID、operationKey、ledgerKey，branch_id 在 CLAIMED 时可空，绑定后不可变。`PayTccReceiptService.prepare` 先复用 dispatch anchor，再由唯一 winner 注册 action name `cloudmallPayDebitReceipt` 的 Pay branch；空 Cancel 对已存在 CLAIMED 行绑定原 branch 并写 CANCELLED，receipt 行不存在时不得凭空创建。配置默认关闭，Pay receipt 与 dispatch/Fence 必须使用同一 Pay DataSource，禁止 AT 自动代理。

新 receipt 只允许绑定原 `pay_record` 的精确 TCC/PENDING 事实和同 XID 的 `pay_tcc_dispatch(state=PREPARED)`；已有 receipt 重放只核对原事实。被拒第二 branch 只有 Fence `SUSPENDED`、本 branch 无 receipt 行且另一 branch 有确定永久 owner 时才可安全 ACK。

第 16B2 `pay_tcc_execution` 的唯一键为 `pay_no、xid、operation_key、ledger_key`，不设置 lease/attempts；INSERT winner 的执行权不可转移。`xid` 可空只表示 begin 未确认，必须保留 BEGIN_UNKNOWN，不能新建 XID。decision 只能 NONE/COMMIT/ROLLBACK 单向推进，recover 不能新增 decision。

第 16C `pay_tcc_delivery` 的 READY 只表示本地 COMMITTED execution 派生的待核验意图，不代表 User/Order 远程证据已完成；ROLLEDBACK execution 不建 job。每次 RPC 前重查本地 proof 和 User live TCC tuple；PayRecord 状态与 delivery 状态在同一 Pay 本地事务同向更新。User DEBIT 已有任何 refundOperationKey 时不能 paid，退款只能使用唯一 `refund:<payNo>` 并在 POST 后 GET 核验。

第 16E 新增 `pay_tcc_cancel`：唯一 `pay_no`，冻结 user/order/amount，状态为 `REQUESTED/ORDER_CANCELLED/CANCEL_UNKNOWN/COMPLETED/CONFLICT`，并带 attempts、next_retry_at、随机 lease_owner/lease_until、last_error。取消表无条件初始化；`tcc-cancel.enabled` 只控制新取消入口和调度，不控制 execution/bridge/receipt 的 gate。`COMPLETED` 只允许在无 execution/dispatch/receipt、Order CANCELLED + stock SUCCEEDED 的短事务内与 Pay `PENDING→FAILED` 同步 CAS；台账不拥有 XID/decision。首次取消、TCC execution claim、bridge.prepare、receipt.claim 均先锁 `pay_record` 再检查该台账；已有台账永久阻止未来 begin。迁移与新 schema 必须保持上述唯一键和 due 索引，默认调度关闭。

第 16G 不新增表；复用 `pay_tcc_execution` 的 recovery_attempts、next_recovery_at、recovery_owner、recovery_lease_until、recovery_last_error 承载第二阶段 rollback projection lease。候选必须在 SQL LIMIT 前排除 cancel/Legacy intent、缺失 dispatch/receipt、非 TCC/非 PENDING、tuple 不一致和无 XID。最终 Pay FAILED 只在 token/lease 有效且锁定 Pay+execution 的短事务内完成；不改 execution decision/state、callback log 或 cancel 台账。

第 16B3 只增加恢复调度字段 `recovery_attempts、next_recovery_at、recovery_owner、recovery_lease_until、recovery_last_error`；这些字段不拥有 XID/branch/decision，不可接管一次性 Try。lease 使用随机 token，释放使用 token CAS；终态和业务状态不被调度迁移改写。

恢复默认 lease 60 秒；旧 RPC 过期后可与新实例重叠，但只能同 decision 幂等执行。`next_recovery_at` 默认 CURRENT_TIMESTAMP(3)，新 execution claim 显式写入创建时间后 30 秒；历史迁移不改写原业务字段。调度器最多每轮 20 条，attempt 封顶 31，退避 5 秒指数增长至 300 秒。

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
| `debit_mode` | VARCHAR(16) NOT NULL DEFAULT `LEGACY` | 从原 `pay_record` 复制的不可变扣款路由；历史/缺失值为 LEGACY |
| `attempts` | INT NOT NULL DEFAULT 0 | 恢复尝试次数 |
| `next_retry_at` | DATETIME(3) NOT NULL | 下次恢复时间 |
| `lease_owner` | VARCHAR(128) | 当前恢复任务实例标识 |
| `lease_until` | DATETIME(3) | 恢复租约到期时间 |
| `last_error` | VARCHAR(512) | 最近错误 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | Asia/Shanghai 语义 |

索引：`uk_pay_debit_intent_pay(pay_no)`、`idx_pay_debit_recovery(status,next_retry_at,lease_until)`、`idx_pay_debit_order(order_no)`。创建意图与支付请求幂等键绑定，并在调用 user 前独立提交；意图的 `debit_mode` 必须从对应 `pay_record` 复制并与其一致，不得从当前运行配置重新推导。恢复任务在 claim 前和任何 User/Order 调用前校验模式；只有 LEGACY 才执行现有账本恢复，TCC/未知/不一致返回 `PAY_DEBIT_MODE_UNAVAILABLE`（HTTP 503），不修改租约、尝试次数和状态，也不能退回 LEGACY 扣款。恢复任务先查询用户账本操作：不存在则用同一 `payNo` 重试扣款；存在已应用扣款则进入订单状态查询；存在拒绝记录则进入 `FAILED`。恢复任务使用数据库租约和退避字段，超过阈值进入告警状态并继续可重入扫描，不能因扫描不到 `ORDER_SYNC_UNKNOWN` 的 `pay_record` 而遗漏已扣余额。

12A 只增加上述两列及路由隔离保护，不新增 TCC receipt、余额冻结字段或 TCC HTTP 接口；这些只能在真实 Seata TCC Try/Confirm/Cancel 回调契约评审后落地。`TCC` 值在当前阶段只能作为被拒绝的持久协议状态，不能默默降级为 LEGACY。

## 8. 购物车 Redis 逻辑结构

MySQL 不保存购物车主数据。逻辑结构：`cart:{userId}` → Hash，field 为 `skuId`，value 为 JSON（数量、勾选状态、加入时间等）；Key TTL 为 30 天，过期即清理。任何结算请求都必须重新读取商品价格、上下架状态和库存，Redis 购物车不能成为价格事实源。

## 9. 秒杀 Redis 逻辑结构

秒杀结构固定为：`seckill:stock:{activityId}:{skuId}` 保存可预扣数量，`seckill:user:{activityId}:{userId}` 保存用户参与/限购标记，`seckill:product:{activityId}:{skuId}` 保存热点商品摘要。库存预扣使用 Redis 原子操作/Lua，活动相关 Key 设置与活动窗口匹配的 TTL；不得把 Redis 预扣结果直接当作最终支付成功。

## 10. Sharding-JDBC 订单分表策略

- 逻辑表：`mall_order`、`mall_order_item`、`order_status_log`。
- 分片方式：按 `created_at` 的年月路由到 `_${yyyyMM}` 物理表；订单明细和状态日志跟随订单月份，避免跨月关联失配。
- 分片键：订单主表使用 `created_at`；`order_no` 的 `yyyyMM` 前缀用于无时间条件的精确查询定位月份。
- 路由要求：列表查询必须提供时间范围；无时间范围的全量查询禁止作为常规接口。
- 建表策略：启动时只校验既有物理表；定时任务预建当前月份之后的配置窗口内月份表，覆盖长运行跨月场景。读路径不得为了补齐缺失的历史表而隐式建表；命中月份缺表时列表请求整体失败并返回明确的分片不可用错误。
- 跨分片列表按时间范围聚合、排序和分页。先逐月 `COUNT`，再按月份倒序使用计数跳过完整月份，仅对承载目标页的月份执行有界 `LIMIT/OFFSET` 批次；月度时间区间严格不重叠时，该顺序与全局 `created_at DESC, order_no DESC` 的 k 路归并等价，内存只随目标页和当前批次增长。页码偏移用 `long` 计算并检查乘法溢出；发现订单落在错误月份时作为数据异常告警并失败，不通过全量扫描默默兜底。任一月份读取失败时丢弃本次聚合并整体失败，不返回部分结果。

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
