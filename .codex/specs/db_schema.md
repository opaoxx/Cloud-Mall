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

超时关单只能基于订单主表事实的非空 `expire_at` 判断期限：待支付订单若 `expire_at` NULL，消费入口在状态 CAS/stock action 前拒绝并保留原行；不得用 outbox payload、created_at 加固定时长或当前时间回填 deadline。非待支付状态继续原 no-op，非空期限沿原 future/past 与 CAS 语义。`expire_at DATETIME(3)` nullable DDL、索引、历史 NULL 和所有其他订单/动作表不变。

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

`OrderReservationIntentMapper` 的共享 RowMapper 在 `findByUserAndKey` 与 `findRecoverable` 中将 `next_retry_at`、`created_at`、`updated_at` 以 typed `LocalDateTime` 组件读取；`next_retry_at` NULL 保持 NULL。该读取不改变本表字段、DDL、索引、SQL、参数、排序、limit、claimRecovery、markReleased、status、`order_no`/幂等键/快照事实或异常日期策略；不设置 SQL mode/时区、不增加 filter/repair，也不宣称其他非法历史日期已闭环。

为避免恢复扫描被年份下界前的历史日期阻断或占据 LIMIT，`findRecoverable` 在原资格条件之后、LIMIT 前增加三项无参数字面量谓词：`created_at >= '1000-01-01 00:00:00.000'`、`updated_at >= '1000-01-01 00:00:00.000'`，以及 `(next_retry_at IS NULL OR next_retry_at >= '1000-01-01 00:00:00.000')`。原扫描仍绑定 `staleBefore`、`now`、`limit` 三个参数，保留全行投影、状态/排序/limit、typed RowMapper、NULL next 语义和恢复写入链；`findByUserAndKey` 保持原按用户与幂等键读取候选，不应用该过滤。下界前行不被恢复扫描读取、修复、删除或改状态；点查询仍沿原行为处理。本条只保证本批合法年份下界资格，不设置 SQL mode/driver zero-date 参数，不新增零月日/超月末或其他非法日期过滤，不进行历史 repair。

在上述 EB 年份下界之后，`findRecoverable` 在 LIMIT 前增加三组无参数零组件资格：`MONTH(created_at)>0 AND DAYOFMONTH(created_at)>0`、`MONTH(updated_at)>0 AND DAYOFMONTH(updated_at)>0`，以及 `(next_retry_at IS NULL OR (MONTH(next_retry_at)>0 AND DAYOFMONTH(next_retry_at)>0))`。本条只筛除恢复扫描中的零月/零日；原 `staleBefore`、`now`、`limit` 三参数、全行投影、状态/排序/limit、typed RowMapper、NULL `next_retry_at`、`claimRecovery`、`markReleased`、`RELEASED` 写入和 Recovery/Stock 链保持。`findByUserAndKey` 仍按用户与幂等键读取候选，不应用该过滤；坏行点查沿原读取或异常传播。不得设置 SQL mode/driver/时区，不新增月末或完整日历过滤，不修复历史行。本表无 operationKey、finish、lease 或 attempts 字段，本条不引入这些概念。

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

`attempts` 的失败更新使用 SQL 饱和递增，最大值为 INT_MAX；负值行不参与失败更新且不由本批自动修复。该应用语义不改变列类型、索引、退避时间或历史数据。

Dispatcher 只对读取到的 outbox PO 快照执行非负 attempts guard；数据库列仍为 `INT NOT NULL`，本批不通过 DDL、扫描过滤或历史修复处理异常负值。

Outbox 扫描可在 LIMIT 前使用 `attempts>=0` 过滤，避免负值异常行占据候选窗口；该过滤不改变列、索引或历史行，dispatcher 仍需保留读取快照 guard。

为避免历史 MySQL 零日期在 `findPendingOutbox` 全 row 时间映射阶段阻断健康候选，扫描 WHERE 在 LIMIT 前增加四个不带参数的下界谓词：`next_retry_at`、`created_at`、`updated_at` 均 `>= '1000-01-01 00:00:00.000'`，`published_at IS NULL OR published_at >= '1000-01-01 00:00:00.000'`。这只是候选资格过滤，不改变 `order_outbox` DDL、索引、driver zero-date 行为、SQL mode、SELECT 投影、排序、limit 或历史事实；下界前行不读取、不修复、不删除、不写状态，NULL/年份 1000/9999 保持原语义。本条不提供完整日历校验或 `ALLOW_INVALID_DATES` 修复。

在 17DK 下界谓词之后、LIMIT 前继续增加部分零日期资格过滤：`MONTH(next_retry_at)>0 AND DAYOFMONTH(next_retry_at)>0`、`MONTH(created_at)>0 AND DAYOFMONTH(created_at)>0`、`MONTH(updated_at)>0 AND DAYOFMONTH(updated_at)>0`，以及 `published_at IS NULL OR (MONTH(published_at)>0 AND DAYOFMONTH(published_at)>0)`。MySQL 官方函数允许从日期表达式提取月份和月内日期，返回值包含 0；本条只用其识别零月/零日，不进行完整日历转换。表结构、索引、SQL mode、driver、全 row 投影、参数、排序、limit、历史事实和其他状态保持；零月/零日行不读取、不修复、不删除、不写状态。

在 17DL 零月/零日谓词之后、LIMIT 前继续增加日历上限资格：`DAYOFMONTH(next_retry_at)<=DAYOFMONTH(LAST_DAY(next_retry_at))`、`DAYOFMONTH(created_at)<=DAYOFMONTH(LAST_DAY(created_at))`、`DAYOFMONTH(updated_at)<=DAYOFMONTH(LAST_DAY(updated_at))`，以及 `published_at IS NULL OR DAYOFMONTH(published_at)<=DAYOFMONTH(LAST_DAY(published_at))`。`LAST_DAY` 返回非法/NULL 参数的 NULL 时不放行该非空行；本条只排除超出当月末日的部分非法日期，不改变表、索引、driver、SQL mode、投影、参数、排序、limit 或历史事实，也不提供完整日期/ALLOW_INVALID_DATES 修复。

`findPendingOutbox` 读取 `DATETIME(3)` 全 row 时，对 `next_retry_at`、`published_at`、`created_at`、`updated_at` 使用 JDBC `ResultSet.getObject(column, LocalDateTime.class)` 直接组件读取；`published_at` nullable 语义保持。该读取替换 Timestamp/GregorianCalendar 映射，不改变 `order_outbox` DDL、字段精度、索引、DK/DL/DM eligibility、SQL 参数、投影、排序、limit、driver zero-date/partial-date 策略或历史数据；不设置系统时区、不解析字符串、不修复合法切换日期，也不承诺其他服务时间路径兼容。

订单 outbox 的状态条件在扫描、PUBLISHED CAS 和 failure 更新中使用 `CAST(status AS BINARY)=CAST('PENDING' AS BINARY)` 精确匹配；`status VARCHAR(32)` DDL、索引和既有状态值不变，历史别名状态不由本批修复。该语法避免 MySQL 8.0 的 BINARY 表达式弃用警告；event_type 的既有 BINARY 条件边界保持。

订单 timeout dispatcher 的扫描在 LIMIT 前以 BINARY 精确匹配 `event_type='ORDER_TIMEOUT'`；`event_type VARCHAR(64) NOT NULL` DDL 与索引不变，其他类型记录保留原事实并等待专属处理器。

订单 outbox 完成/失败 UPDATE 在执行时继续精确校验 `CAST(event_type AS BINARY)=CAST('ORDER_TIMEOUT' AS BINARY)`；完成同时要求 attempts 非负，失败沿既有 attempts 条件。该应用资格门不改变 DDL、索引或历史记录。

Outbox 数据库异常隔离仅属于 dispatcher 控制流：扫描失败不改变表事实，单候选写失败不追加未知状态或二次 failure 写，已 confirm 但终态更新失败保持原行供既有重试/人工核验；表结构和 SQL 条件不变。

failure writer `recordOutboxFailure` 仍执行一次既有 UPDATE 并返回影响行数：`1` 只表示本次原 SQL 影响一行，dispatcher 可告警记录“失败退避已记录”，不作 full-row 证明且不承诺未来调度；`0` 必须告警“失败退避更新 0 行，本次未确认记录退避，保留当前事实”，0 可能来自并发状态变化、缺行或特定 affected-rows 语义下匹配但 SET 无变化，本批不读取、不推断、不补写、不重发或修复，也不得把它解释为 retry 已记录、peer 已完成或完整事实证明；非 `0/1` 属于并发写结果异常，由 dispatcher 抛带原发送/解析异常 cause 的 `Optimistic(DataAccess)` 并沿单候选隔离。failure UPDATE 的 SQL、资格条件、饱和计数、错误剪裁、退避时间、列、索引和状态不变；writer 自身数据库/运行时错误、Error 与中断沿原控制流，已写入结果不撤回，任何结果不提供未来重试/完成或 MQ/DB 原子性保证。本条不扩展全局 JDBC affected-rows 模式支持。

订单 outbox 的 `next_retry_at DATETIME(3)` 仍由 dispatcher 以 Asia/Shanghai 当前时间加既有正 `retry-delay-seconds` 计算；failure UPDATE 前必须拒绝时间计算异常或结果年份超出 MySQL DATETIME 可表示的 `1000..9999`，并沿单候选 DataAccessException 隔离，不执行该次 failure UPDATE，不读取、不重发、不改写原事实。计算异常携带原发送/解析异常 cause，并保留计算异常 suppressed 证据；可表示的大正数仍按原退避时间写入。`next_retry_at` 列、精度、SQL、索引、Schema 和历史数据不变；本条不新增上限、不自动纠正、不扩展 DATETIME(3) 舍入/截断或全局配置语义。

订单 outbox future 消息的 TTL 毫秒值由订单模块纯函数对正 Duration 向上取整：整毫秒保持，纳秒余量加一；zero/negative 仍为 0 并走既有 DLX 路由。该数学转换不写数据库、不改变 `order_outbox` Schema、SQL、next_retry_at/published_at/updated_at floor、索引、状态、消息或 topology；转换溢出沿 dispatcher 原 failure，不猜测到期或完成。本条不扩展数据库时间精度、其他 TTL、Service consumer 或 future queue/FIFO 语义。

`recordOutboxFailure` 在原 failure UPDATE 前把调用方 `nextRetryAt` 和一次上海当前 `updatedAt` 都以 `LocalDateTime.truncatedTo(ChronoUnit.MILLIS)` 向下归一后绑定；两个时间不做 timezone 转换、四舍五入、替代或重采样。非 null 且年份 `1000..9999` 是 Mapper 内部参数契约，非法值在首次 JDBC 调用前抛 `InvalidDataAccessApiUsageException`，不因 WHERE 找不到行或状态/类型/attempts 不合资格而跳过校验。`next_retry_at`/`updated_at DATETIME(3)` DDL、SQL 参数位置、索引、SQL mode、历史数据、失败计数和返回行数不变；应用 floor 不依赖全局 `TIME_TRUNCATE_FRACTIONAL`，本条不扩展其他时间写路径或 DATETIME(3) 舍入边界。

`markOutboxPublished` 在原完成 UPDATE 前对 `publishedAt` 使用同一 `normalizeOutboxWriteTime` 毫秒 floor helper；非 null 且年份 `1000..9999` 是 Mapper 参数契约，非法值即使行缺失或资格不符也在首次 JDBC 前抛 `InvalidDataAccessApiUsageException`。合法归一值不转换时区、不采新时间、不修改调用方对象，并按原 SQL 参数位置同时写入 `published_at`、`updated_at`；两列得到同一精确 floor 值。完成 SQL、WHERE、索引、返回行数、`DATETIME(3)` DDL、SQL mode、历史数据、17DD proof 和其他时间路径不变，不新增列/API/message 或历史修复。

订单 outbox Schema、索引、状态和 SQL 不因 dispatcher 配置校验改变。构造器读取既有 `batch-size`（Integer，默认 50）、`confirm-timeout-ms`（Long，默认 5000）和 `retry-delay-seconds`（Long，默认 10）后，在 mandatory 设置前要求三值均为正；非正值以包含 property 名/原值的 `IllegalArgumentException` fail-fast，且不触发扫描、发送、SQL 或 RabbitTemplate 修改。合法正值继续原 scan limit、confirm 等待和 retry 退避；Mapper 的非正 limit 归一规则、outbox 表字段/索引/状态、消息与路由保持不变。本条不增加列、索引、API、配置框架依赖或正值上限，也不宣称任意超大正值的时间计算和性能问题已解决。

发布完成 proof 只读当前事实的 partial PO；`findPublicationFacts(eventId)` 方法签名不变，投影固定为 `event_id,status,event_type,order_no,attempts`，不读取 payload、时间、错误或其他 full-row 字段，不用于重投。`markOutboxPublished` 影响行数 0 不直接代表成功，只有唯一行同时满足原 event_id 精确相等、status=PUBLISHED、原 order_no 精确相等、event_type=ORDER_TIMEOUT 且 attempts 非 NULL/非负时才可幂等收敛。attempts 不要求等于发送快照，可合法增长 1 或饱和为 INT_MAX；Mapper 以 nullable 精确方式读取它，NULL 仅是 unit proof 输入，`attempts INT NOT NULL` DDL 不变。任一事实不满足时由 dispatcher 抛 Optimistic(DataAccess) 并沿单候选隔离，不写 failure、不重发、不修事实；读前后中断、扫描/查询数据库异常、完成写 1 行及非 0/1 原规则保持。该读取不新增列、索引或状态写入，也不提供 MQ 与 DB 原子证明。

订单 outbox 的 `order_outbox` Schema、索引、状态和 SQL 不因到期路由改变。dispatcher 仅在已读取 payload 的 `expireAt` 解析为上海当前时刻以前或恰好当前时，将消息发送到既有 `OrderMessagingConfiguration.DLX` 和空 routing key，并清除 per-message `expiration`；未来时间继续发送 `TIMEOUT_EXCHANGE` 并设置至少 `1ms` expiration。两条路径都必须先通过原 publisher confirm/mandatory returned，再执行既有 `PUBLISHED` CAS 与 17DD proof；direct DLX binding 缺失时 returned 保持 PENDING，不把 exchange confirm 当作 timeout consumer 已处理。现有 DLX、DLQ、binding 和 consumer 拓扑不变，不新增表、列、索引、队列或消息字段。本条只解决已到期快照绕过 TTL FIFO 阻挡，不覆盖未来消息队头、消费者时间判定或所有 TTL 延迟场景。

订单 outbox 完成/失败更新保留 `event_id` 索引候选，并使用 `CAST(event_id AS BINARY)=CAST(? AS BINARY)` 精确核验身份；`event_id VARCHAR(128) UNIQUE NOT NULL`、索引和历史数据不变，不新增列或迁移。

订单 outbox 扫描可在 LIMIT 前使用 Java17 whitespace 对齐的 `REGEXP_LIKE` eligibility 排除空白 eventId/orderNo；pattern 以 UTF-8 HEX 参数经 `UNHEX` 恢复并显式 `COLLATE utf8mb4_bin`，避免 session 反斜杠与隐式 collation 差异。pattern 仍为 UTF-8 文本，不 CAST BINARY、不改列排序规则；NOT NULL/字段宽度/索引/历史行不变，扫描不自动修复坏身份。

订单 timeout dispatcher 对读取到的 PO 额外要求 eventId/orderNo 非空白；`event_id`/`order_no` 的既有 NOT NULL DDL、索引、扫描和历史行保持，不依赖该 guard 自动修复数据库坏值。

订单 timeout payload 身份校验只读 JSON 中已存在的 eventId/eventType/orderNo，缺失保持兼容；`payload JSON NOT NULL` DDL、字段、索引和历史 JSON 不变，不新增身份列或回填缺失键。

订单 timeout 消费入口可兼容历史裸数字订单号：仅当整份 Message body 去首尾空白后为 ASCII `[0-9]+` 且 JSON root 为 integral number 时，直接把原 trimmed 文本交给既有订单号读取/月份路由/精确 CAS 流程。该兼容不改变 `order_outbox`、订单表、`order_stock_action` 或任何 SQL/DDL/索引；负数、小数、指数、数字前缀和附加 JSON 不放宽，quoted string/object envelope、ACK/retry、TTL/DLX、历史消息事实均保持原语义，不自动修复或重放。

timeout object envelope 的 eventType、eventId、orderNo、expireAt 仅接受原生文本：eventType 精确 `ORDER_TIMEOUT`，其余三字段为非空白 textual 值；字段缺失/null/空白或非文本不进入业务订单查询。该入口校验不改变 outbox/订单/stock action 表、SQL、索引、ACK、TTL/DLX 或历史消息，不新增身份前缀/宽度/日期 grammar，也不把坏 object 归入裸数字 legacy 或自动重放。

timeout object 的 eventId/orderNo/expireAt textual 值还必须通过 Java UTF-16 代理配对检查：高代理后必须紧邻低代理，低代理不得孤立；不满足时不读取订单/库存事实、不写 outbox/订单/action、不改变 ACK/TTL/DLX 或历史消息。该入口约束不修改 Schema、SQL、索引、数据库 `expire_at` 权威、UTF-8 decoder、JSON grammar 或其他字符串字段规则；合法代理对/中文/emoji/U+FFFD 保持原语义。

timeout Message 的 JSON tree 读取为单次 per-call `ObjectReader` 配置 `FAIL_ON_TRAILING_TOKENS`；尾随有效 root 不进入订单业务查询。`MismatchedInputException` 走原格式错误并保留 cause，其他 JsonProcessingException 保持原 raw legacy fallback；该解析边界不改变 outbox、订单、stock action 表、SQL、索引、ACK、TTL/DLX、历史事实或消息重放规则，也不改变共享 ObjectMapper 全局配置。

该 per-call `ObjectReader` 同时配置 `FAIL_ON_READING_DUP_TREE_KEY`，同一 JSON object 任意树深度的重复键拒绝解析；重复同值也拒绝，不同 object 的同名键不受影响。重复键失败不访问订单/库存事实、不改 outbox/订单/stock action 表、SQL、索引、ACK、TTL/DLX 或历史消息；不启用全局 duplicate detection，不改变其他服务 Map/POJO 解析。

订单 timeout Message 在 JSON tree 读取前必须严格按 UTF-8 解码；malformed/unmappable bytes 在首次 JSON/legacy/订单事实读取前抛带 `CharacterCodingException` cause 的格式错误，不写 outbox/订单/stock action，不改变原消息 bytes。合法 UTF-8 中文、emoji、U+FFFD 保持；该入口不猜 content type/encoding、不改变 SQL/DDL/ACK/TTL/DLX、历史事实或其他服务解码策略。

线程中断只改变 dispatcher 是否继续执行后续动作，不修改 outbox 表结构或自动清理事实；已发送/已确认但未完成写入的消息仍依赖原 eventId 幂等和重试，不能由中断检查推断最终状态。

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

`OrderStockActionMapper.findDue` 对 `next_retry_at DATETIME(3)` 使用 JDBC `ResultSet.getObject("next_retry_at", LocalDateTime.class)` 直接读取；其他列、SQL、参数、状态/attempts/lease 条件、排序、limit、DDL、索引、历史日期和 Recovery 语义保持。该边界只避免 Connector/J GregorianCalendar cutover 读取异常，不设置系统时区、不使用字符串 fallback、不扩展 zero/partial-invalid 日期或全服务时间策略。

`order_stock_action` 恢复扫描在原 `next_retry_at<=?` 后、LIMIT 前增加单一字面量下界 `next_retry_at >= '1000-01-01 00:00:00.000'`；不增加 prepared 参数，不改变表、字段、索引、其他 SQL、normalizedNow、状态/attempts/lease、排序、limit、claim/finish/retry 或历史事实。下界前行不进入恢复候选、不修复/删除/写状态；不设置 SQL mode/driver 参数，不新增 zero/partial-invalid 或其他列过滤。

在 17DY 下界之后，`order_stock_action` 恢复扫描仅对 `next_retry_at` 增加 `MONTH(next_retry_at)>0 AND DAYOFMONTH(next_retry_at)>0` 两个无参数资格谓词；不改变表、字段、索引、其他 SQL、3 参数、投影、排序、limit、状态/attempts/lease、claim/finish/retry 或历史事实。该门只过滤零月/零日，不校验合法月份上限/超月末，不设置 global/JDBC 策略，不修复其他非法日期。

在 17DZ 零月/零日谓词之后，`order_stock_action` 恢复扫描仅对 `next_retry_at` 增加 `DAYOFMONTH(next_retry_at)<=DAYOFMONTH(LAST_DAY(next_retry_at))` 单一无参数资格谓词；不改变表、字段、索引、其他 SQL、3 参数、投影、排序、limit、状态/attempts/lease、claim/finish/retry 或历史事实。该门只排除超出当月最后一天的 retry 日期，不扩展其他时间列、完整日历、历史修复、global/driver 策略。

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

普通库存 reservation GET 的非空 flows 终态只读聚合必须逐 SKU 精确验证：`reserved_quantity` 为正整数，`confirmed_quantity`/`rolled_back_quantity` 为非负整数，且 confirmed+rollback 不超过 reserved；Number 以 BigDecimal 验证整数性，不使用 int 截断、溢出或 null 默认。单 SKU 仅完整确认、完整回滚或两者均零分别产生 `CONFIRMED`、`ROLLED_BACK`、`RESERVED`；多 SKU 必须全部已观察行同向同状态，否则返回 `UNKNOWN` 且不生成终态 items。flows 为空且无 request 的既有 NOT_FOUND、flows 为空但有 request 的既有 request 分支不受本门覆盖。普通 reservation request 行若存在只能与 flows 聚合状态一致才可作为交叉证据，不能覆盖矛盾/缺失/非法 flows；request 缺失不否定合法 flows。本批只证明已观察 SKU 数量/状态与 request.status 一致，不验证完整 SKU 集合、items_snapshot/items_hash、Stock 余额或秒杀身份。该 GET 不新增 RR/事务一致性、DDL、索引或写入语义，不改变秒杀路径和普通写状态机；Order fallback 只能据合法完整同向结果判断库存已结清。

16T5 在上述普通非空 flows+request 分支内，必须核对 request `order_no`、`scene=NORMAL`、非空 `items_snapshot` 和 `items_hash`。独立 ObjectMapper 仅用于该 proof，启用 trailing token/duplicate tree key 拒绝；native integral 只针对 MySQL 读取后的持久 JSON 树，数据库若已将 `2.0` 规范为 `2`，按当前 semantic canonical JSON/hash 兼容核验，不能重构或声称验证已丢失的原 token 类型、重复键或前置语法。重建严格升序、无重复、正 Long SKU/正 int quantity 的 `StockLineDTO` 列表，复用 writer 的 canonical JSON 与 SHA-256 算法，不能对 MySQL JSON raw 文本 hash。重建集合必须与已观察 flows 的 SKU/quantity 一一对应，缺、多、重复、读取后数量/hash/identity 不符均返回 UNKNOWN，不改 facts。空 flows 外层分支、秒杀、写状态机、SQL/DDL、Redis、消息和非 RR 语义保持；本条不提供完整订单 SKU、Stock 余额或秒杀身份证明。

16T6 `findReservationFlows` 仍是单次普通 flows 汇总 SELECT，保留 `WHERE order_no=?`、GROUP BY `sku_id`、原参数和数量汇总，并额外投影 `MIN(order_no)`、`COUNT(DISTINCT BINARY order_no)` 与 invalid flow metadata。binary distinct count 只作为 provenance 证明，不在 WHERE 过滤或伪装缺行；Service 先要求查询 orderNo 与 MIN 精确相等、count=1、invalid count=0，再执行 16T4/16T5 数量及 request proof。原 order alias、混合 order、unknown/NULL flow_type、非正 quantity、负值抵消、metadata 缺失/异常均保留 facts 并返回 UNKNOWN；不新增 DDL/索引/写入，不改变空 flows、秒杀和非 RR 语义。

进入既有 `alreadyReserved()` 重放路径时，只有 `reservationResult()` 返回非空 data 且 proof status 精确为 `RESERVED` 才能返回 `ALREADY_RESERVED`。UNKNOWN、其他终态、缺 data 或读取异常固定为 `STOCK_RESERVATION_PROOF_UNAVAILABLE`/503，不修改 request/flows/数量/状态/Redis/消息，不修复或覆盖历史事实；合法 RESERVED 的既有重放 API 语义保持。本门只保护 16T5 snapshot proof 失败不被响应层强制 coerce。

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

CM-18 16T1 的本地证据检查只读 `pay_record`、`pay_tcc_execution`、`pay_tcc_dispatch`、`pay_tcc_receipt`、Pay Fence 和 `pay_debit_intent` 六类既有事实。配置 `cloudmall.pay.tcc-reconciliation.enabled=false` 默认关闭；Controller 只委托先做 enabled、callback token、caller=`cm18-reconciliation`、payNo 校验的 Service wrapper，校验通过后才进入独立 `@Transactional(readOnly=true,isolation=REPEATABLE_READ)` ReadService。它按 `pay_no` 精确查询，使用最多两条 payment 候选核验，不加 `FOR UPDATE`，不执行 claim/lease、Store/bridge/coordinator recovery、TC/RPC、Rabbit 或任何业务写入；不新增表、视图、列、索引或资金事实。payment 使用新增只读 LIMIT 2 Mapper 方法；`PayDebitIntentMapper` 继续是 JdbcTemplate Mapper 并复用无锁 `findByPayNo`，其余五类沿既有 MyBatis 无锁 SELECT。Service 不持有 SQL/JdbcTemplate/QueryWrapper。XID、branch、operation key、ledger key 只生成字段对应的 SHA-256 指纹，普通日志不得记录原值、凭证或错误文本。

payment 零候选返回既有 NOT_FOUND/404 且不查询其余五类；多候选或 payNo 身份别名冲突返回身份冲突/409，不暴露别行；缺表、连接失败或数据库不可用统一返回 `PAY_TCC_RECONCILIATION_UNAVAILABLE`/503，不返回 raw 错误、不创建表。其他来源缺失在 payment 身份确认后进入本地阻塞分类；采集时间、reason code 和状态字段仅使用冻结白名单，未知状态不回显原值。点查/诊断不因 ledgerKey 恰好等于 payNo 而要求隐藏 payNo，测试只禁止 raw key 字段，允许业务 payNo 与对应 fingerprint 同时出现。

CM-18 16T2 不新增 User 资金表、字段、索引或 API 事实。Pay 新增 default-off 的 User evidence 只读采集配置和内部控制流：第一次 Pay RR 只读快照产生 trustedAnchor，事务结束后才通过既有 User state GET 查询 User 的 receive/attempt/debit/ledger/Fence RR 读链，随后重新建立 Pay RR 快照。User GET 不绑定 XID、不注册分支、不写任何 User 事实；不复用 Pay bridge/coordinator/Store，不发送 POST。User GET 前后，collector 必须保存并临时清理线程 AuthContext 的原 userId/role，使用 anchor userId 发起请求，并在 finally 恢复；该局部隔离只防止 Feign authHeaders 污染 `X-User-Id`，不改变全局拦截器、不传 caller role、不掩盖事务/XID 门。User 的 UserTccStateVO 不包含账户 balance/frozen 汇总，16T2 不宣称全账户取证。

trustedAnchor 只能来自 Pay 本地完整且无冲突的 TCC execution/dispatch/receipt/Fence：原 XID 非空，state 非 UNKNOWN/BEGIN_UNKNOWN，Pay branch 与 User branch 均为正且不同，pay/user/order/amount/operation/ledger tuple 一致。anchor 字段只在内存中使用，不能来自请求或进入响应/日志。User 响应必须逐字段核对原 payNo、userId、orderNo、金额、operation/ledger key、XID、User branch、state 和 `ledgerApplied`；金额不得使用 double，User `CONFIRMED` 必须 `ledgerApplied=true`，其他状态不得为 true。Pay 接收侧 UserTccStateVO 保留 primitive `ledgerApplied` 和既有 JSON/其他消费者语义，额外用 `@JsonIgnore ledgerAppliedPresent` 与 `@JsonSetter("ledgerApplied")` 的 `Boolean` setter 记录显式非 null 字段；缺字段/null 时不得把 primitive 默认 false 当作证据，collector 必须返回 `USER_EVIDENCE_INCOMPLETE`。失败/缺证/UNKNOWN/相反终态只分类阻塞或冲突，不改 Pay/User 行。

User GET 前后 Pay 两次 capture 的本地 report、snapshot、identifiers、classification、reason 和 anchor 必须相同（仅忽略 collectedAt）；变化返回 `LOCAL_EVIDENCE_CHANGED`，不声称检测所有 lease/重试 metadata。User GET 的 null body、null/非成功外层响应、404、网络或数据库不可用均为 `USER_EVIDENCE_UNAVAILABLE`；只有现有 ErrorDecoder 明确产生 `PAY_TCC_DISPATCH_CONFLICT` 的 409 才能为 `USER_EVIDENCE_CONFLICT`，其他 HTTP 409 不得推导资金冲突。Pay execution 已 `COMMITTED/ROLLEDBACK`、receipt/dispatch 已对应终态而 User 仍为合法 `TRYING` 时，必须为 `USER_EVIDENCE_INCOMPLETE`，可带固定 `USER_TERMINAL_EVIDENCE_PENDING` reason；已证明相反终态仍为 `USER_EVIDENCE_CONFLICT`。成功匹配最高只为 `BLOCKED_REMAINING_EVIDENCE`，ORDER/STOCK/TC/MQ 仍未验证；不输出 TCC 终态，不新增自动资金处置、历史 repair 或 User 业务依赖。

该诊断读取不改变本表字段、状态、decision、XID、branch、lease 或重试时间。`BEGIN_UNKNOWN`、无 XID、缺 execution、局部 tuple 冲突和 SQL/连接缺失必须保留原事实并分类阻塞；不能通过新 begin/Try、补 branch、猜 TC 决议或写 Pay/User 资金收敛。由于不读取 User/Order/Stock/TC/MQ，完整本地快照也不能证明提交、回滚或退款。

CM-18 16T3 不新增 Order 表、列、索引或写接口。Pay 只在 16T2 `ReadService.capture` 返回可信 anchor、local classification 为 `BLOCKED_EXTERNAL_EVIDENCE` 且两次 Pay capture 稳定时，事务外复用既有 `OrderClient.get(orderNo,userId)` 读取订单基本事实；不要求 16T2 User flag、不调用 User，不调用 `paymentState`，避免把 Stock action/库存镜像混入本地证据，不调用 Order paid/cancel 或其他写路径。Order 现有 GET/owned/OrderSqlMapper 主行查询必须保持只读；本次只验证一次主行 SELECT，明细第二 SELECT 不进入报告，16T3 只采集 `orderNo`、`userId`、`payAmount`、白名单 `status`，不声称 Order RR、items/address/time/stockActionStatus/退款事实已核验。

Order GET 前后两次 Pay capture 的 local report、snapshot、identifiers、classification、reason、anchor 必须相同（忽略 collectedAt），变化返回 `LOCAL_EVIDENCE_CHANGED`。Order null/error/404/403/5xx、传输或数据库不可用分类 `ORDER_EVIDENCE_UNAVAILABLE`；基本字段缺失/未知状态分类 `ORDER_EVIDENCE_INCOMPLETE`；order/user/amount 不匹配分类 `ORDER_EVIDENCE_CONFLICT`。`PENDING_PAYMENT`、`PAID`、`COMPLETED`、`CANCELLED` 任一白名单状态在基本字段匹配时只到 `BLOCKED_REMAINING_EVIDENCE`；合法 `CANCELLED + Pay COMMITTED` 不作相反终态冲突。本批不查询 User/Stock/TC/MQ，不改变 Order/Pay 事实。

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
