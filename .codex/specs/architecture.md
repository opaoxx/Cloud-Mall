# CloudMall 云购微商城系统架构规格

## 1. 架构目标与边界

CloudMall 采用 Spring Boot + Spring Cloud Alibaba 微服务架构，按单一职责拆分服务。网关作为统一入口，服务通过 Nacos 注册发现和配置管理；业务服务独立部署、独立迭代。本文只定义设计边界，不提供业务实现代码；Docker Compose 作为本地开发部署编排入口，具体脚本属于后续交付物。

## 2. 固定技术栈

| 层次 | 技术与版本 | 落地场景 |
| --- | --- | --- |
| 服务框架 | Spring Boot 2.7.18；Spring Cloud 2021.0.5；Spring Cloud Alibaba 2021.0.5.0 | 微服务基础运行时 |
| 入口 | Spring Cloud Gateway 3.1.5 | 路由、鉴权、跨域、过滤、限流预处理 |
| 注册配置 | Nacos 2.2.0 | 注册发现、配置管理、开发/测试隔离 |
| 稳定性 | Sentinel 1.8.6 | 秒杀限流、热点参数、熔断降级 |
| 事务 | Seata 1.5.2 | 订单/库存及支付/订单一致性协作 |
| 异步 | RabbitMQ 3.11-management | 超时关单、支付通知、库存处理、浏览统计 |
| 缓存 | Redis 7.0 | 登录态、购物车、热点数据、秒杀库存、限流计数 |
| 数据 | MySQL 8.0；Sharding-JDBC 4.1.1 | 业务持久化、订单按时间分表 |
| 检索日志 | Elasticsearch 7.17.0 + Kibana 7.17.0 | 商品检索、日志分析 |
| 观测 | Micrometer Tracing 1.9.5 + Zipkin 2.23.19；Prometheus 2.47.0 + Grafana 9.5.5 | 链路与指标监控 |
| 前端/代理 | React + Vite；Nginx | 页面构建、静态资源代理、请求分发 |
| 环境 | Docker Compose | 本地开发编排基础设施与 Nginx；业务服务前期保持 IDEA 本地运行，后期再镜像化 |

## 3. 服务划分与依赖

| 服务 | 职责 | 主要依赖/边界 |
| --- | --- | --- |
| `cloud-mall-gateway` | 统一入口、路由、Token 鉴权、过滤、跨域、限流预处理 | Nacos、Sentinel；不承载业务数据 |
| `cloud-mall-user` | 注册、登录、权限、个人信息、登录态 | MySQL、Redis；用户事实数据归属本服务 |
| `cloud-mall-product` | 商品/分类/参数 CRUD、上下架、ES 检索、热度统计 | MySQL、ES、Redis、RabbitMQ；商品事实数据归属本服务 |
| `cloud-mall-cart` | Redis 购物车、勾选结算、跨设备同步 | Redis、商品服务查询；不负责库存扣减 |
| `cloud-mall-order` | 订单创建、快照、状态流转、关单、分表 | MySQL、Sharding-JDBC、Seata、RabbitMQ；订单事实数据归属本服务 |
| `cloud-mall-stock` | 预扣、确认、回滚、防超卖、秒杀库存 | MySQL、Redis、Seata、RabbitMQ；库存事实数据归属本服务 |
| `cloud-mall-pay` | 模拟支付、回调、支付状态同步 | MySQL、RabbitMQ、Seata；不接真实支付渠道 |
| `cloud-mall-common` | 统一返回、异常、工具、常量、分页封装 | 仅公共能力，不放业务规则或业务表 |

跨服务调用统一使用 Spring Cloud OpenFeign，并经 Nacos 服务发现；外部前端请求统一经 Gateway。服务间不得直接读写其他服务数据库，订单中的商品名称、价格等使用不可变快照。连接超时为 3 秒，读取超时为 5 秒，仅对幂等 GET 允许有限重试。

## 4. 核心调用与一致性

### 4.1 普通下单

`React → Nginx → Gateway → order → product/cart/地址校验 → [已提交本地事务] INIT → stock 预扣 → [已提交本地事务] RESERVED → [本地事务] order 保存 + timeout outbox 记录 + COMMITTED → 返回待支付`。地址归属、商品快照和数量校验必须在调用 stock 之前完成。INIT 必须先提交，不能把远程预扣放在未提交的本地事务之后再依赖回滚。库存预扣与订单本地写入不假定跨服务本地事务可以自动回滚：预扣结果必须按 `orderNo` 幂等，订单创建失败时由同步补偿和 `order_reservation_intent` 扫描任务继续回滚孤立预扣。

若进程在库存预扣成功后、意图更新为 RESERVED 前崩溃，恢复任务在宽限期后使用原 `orderNo` 和完整快照重试同一预扣请求；库存端必须返回原预扣结果而不是再次扣减。恢复任务随后将意图推进为 RESERVED 并回滚，或在订单已成功提交时识别为 COMMITTED。恢复任务与创建请求通过状态条件更新/租约避免同时处理同一意图。

订单状态迁移使用条件更新（`where order_no=? and status=?`）并检查影响行数。支付、手工取消和超时关单都先在本地事务中提交订单状态与 `order_stock_action` 意图，再在事务外调用 stock；因此 `stock.confirm` 已提交但响应丢失时，订单不会因本地事务回滚而退回 `PENDING_PAYMENT`。只有从 `PENDING_PAYMENT` 成功迁移到 `PAID` 的调用方可以创建 `CONFIRM` 动作，只有从 `PENDING_PAYMENT` 成功迁移到 `CANCELLED` 的调用方可以创建 `ROLLBACK` 动作；影响行数为 0 时重新读取状态并按已完成迁移返回幂等结果，禁止创建相反库存动作。

### 4.2 支付成功

`pay` 校验模拟支付请求并记录支付结果，以 `payNo` 作为唯一操作键调用 user 的余额扣款；扣款和 `balance_ledger` 在 user 本地事务内完成。扣款成功后向 order 发起幂等支付同步。order 在本地事务中把订单 CAS 为 `PAID` 并写入 `order_stock_action(CONFIRM)`，提交后才调用 stock.confirm；调用超时只把动作置为 `UNKNOWN`，不会回滚已提交的 `PAID`。恢复任务查询 stock 最终状态并重试同一操作键。同步超时/断连时进入 `ORDER_SYNC_UNKNOWN`，由支付恢复任务查询订单状态；只有查询明确得到 `CANCELLED`/未支付终态才创建唯一退款操作，未知期间不得退款。回调、扣款、退款、库存确认均按操作键幂等。

CM-18 第 12A 阶段完成支付路由的持久协议隔离：`pay_record.debit_mode` 在创建时冻结，`pay_debit_intent.debit_mode` 从原支付记录复制；数据库迁移把历史行写为 `LEGACY`，Java 旧对象的 null 只作兼容，持久化空串、小写值、尾随空格和未知值均不支持。当前运行配置仅允许 `LEGACY`，`TCC` 或未知配置启动失败。恢复器在 claim 前以及调用 User/Order 前双重校验记录与意图模式；模式为 TCC、未知或不一致时返回 `PAY_DEBIT_MODE_UNAVAILABLE`（503），不把未实现 TCC 意图降级到普通余额扣款，也不修改租约或状态。恢复扫描必须在 SQL 层 JOIN 两表并以 `BINARY debit_mode='LEGACY'` 精确筛选，不能先取固定窗口再在 Java 过滤。12A 不接入 Pay TCC、Seata TCC 分支或支付 TCC HTTP 接口。

第 14 轮在此基础上只批准 User 侧本地 TCC 就绪状态机：`mall_user.frozen_balance`、共享 `balance_debit_attempt` 和 `balance_tcc_debit` 负责冻结、Confirm/Cancel 和 Legacy/TCC 的 payNo 互斥。Legacy 归属最终只能为 `APPLIED/REJECTED`，TCC 归属只能为 `TRYING/CONFIRMED/CANCELLED`；归属同时冻结最终 `balance_ledger.operation_key`，TCC 使用 `ledgerKey=payNo`，Legacy 使用请求 operationKey，Confirm 禁止重新推导账本键。一个 User 资金资源固定绑定一个 XID、一个 branchId 和一个 payNo。Confirm/Cancel 仍是内部本地事务方法，没有 HTTP/Feign 入口；Pay 继续只走 LEGACY。Fence、真实 Seata RM 回调、Pay 编排和 Order/Stock 联动仍未接入，CM-18 整体未闭环。

第 15A 将 User 资金资源接入真实 `service.BalanceTccDebitLocalResource` LocalTCC RM，固定 `prepare(BusinessActionContext,@BusinessActionContextParameter(paramName="request") BalanceTccBranchDTO) boolean`、`commit(BusinessActionContext) boolean`、`rollback(BusinessActionContext) boolean`。资源关闭自动 Fence（`useTCCFence=false`），在同一 User `REQUIRED` 本地事务中显式调用官方 `TCCFenceHandler` 和业务 callback；Fence `SUSPENDED` 与业务 Cancel tombstone 一起提交，防止 Fence 清理后迟到 Try 复活。资源默认关闭 `cloudmall.user.tcc-resource.enabled=false`、`seata.enabled=false`、`enable-auto-data-source-proxy=false`；Pay 继续 LEGACY。第 15B 再验证进程中断和 TC 暂失，CM-18 仍未闭环。

第 16A 增加 Pay/User TCC 派发桥但不切换对外支付：Pay 先校验原支付单精确 `TCC + PENDING`，在一次性 dispatch 台账中提交 XID/载荷和 POST_ATTEMPTED，再通过 Nacos/Feign 调用 User Try；User 先写 `user_tcc_receive=CLAIMED`，唯一 winner 才进入 LocalTCC。POST 响应未知只能由原 payNo/XID GET 查询，禁止重发。Gateway/Auth 先阻断所有 `tcc-debits` 服务路径及编码/参数变体；Confirm/Cancel 仍只由 TC 回调。Pay receipt、主编排、Order/Stock 和 Pay 对外入口切换仍后置。

第 16B1 增加 Pay 自有 `pay_tcc_receipt` LocalTCC 资源，action name 固定 `cloudmallPayDebitReceipt`，其状态 `CLAIMED/TRYING/CONFIRMED/CANCELLED` 是 Pay 侧事实，不是 User 镜像。receipt prepare 先复用 16A dispatch 的持久 anchor，再由唯一 CLAIMED winner 注册 Pay branch；User dispatch 由独立测试/后续编排以同一 XID 调用，不由 receipt Try 自动发起。资源默认 `cloudmall.pay.tcc-receipt.enabled=false`，开启时须启用 Pay bridge/Seata、关闭 AT 自动代理并复用同一 Pay DataSource Fence。Confirm/Cancel 只由 TC RM 回调，Pay 对外支付、Order/Stock、退款和主编排仍未接入。

第 16B2 增加内部 Pay coordinator Service：一次性 execution claim 后只能 begin 一个 XID，并在同一 XID 下协调 Pay receipt/User 两个 RM。execution 的 decision 单向 NONE→COMMIT/ROLLBACK；recover 不接管 Try、不创建新 XID，只重试原 decision RPC。没有 XID、没有 decision 或任一端持久事实未知时不猜资金结论；配置 `cloudmall.pay.tcc-coordinator.enabled=false` 默认关闭，超时上限 120000ms，Pay 公网入口仍为 LEGACY。

第 16C 增加内部 TCC delivery worker：READY 仅由本地 COMMITTED execution 种子生成，不能当作远程 User 证据。每次调用 Order 或退款前重新读取本地 Pay proof 和 User live TCC 状态；User DEBIT 已有退款事实时禁止 paid，只能在 Order CANCELLED 且库存回滚 SUCCEEDED 后用固定退款键核验。ROLLEDBACK 直接不建 job；PayRecord 终态与 delivery 台账在同一 Pay 本地事务更新，Legacy PayConsistencyService 继续隔离。

第 16B3 只增加 coordinator 有界恢复扫描：每实例单线程、每批最多 20 条，SQL 先精确筛选 TCC 且 tuple 一致、已知 XID、到期且无有效恢复 lease 的 execution。调度器只调用原 `recover`，不调用 execute/begin/Try；decision NONE 只读观察，BEGIN_UNKNOWN/xid NULL 跳过，已有 decision 只重试同向原 XID。随机 token lease 和 5–300 秒退避防止坏记录占满窗口；Pay 对外入口、Order/Stock 和退款不变。

恢复 lease 默认 60 秒，过期后允许旧 RPC 与新实例同向重叠；token CAS 只保护调度字段完成，不能修改 execution decision/state。冲突只记录 recovery_last_error 和退避，不由扫描器写 CONFLICT；每轮 10 秒预算且不强杀在途 RPC，UNKNOWN 保留人工核验，不产生热循环告警。

### 4.3 超时关单

order 创建待支付订单时，在保存订单的同一本地事务中写入 timeout outbox 记录；事务提交后由 dispatcher 以 publisher confirm 投递到现有 TTL + DLX 链路，并以同一 `eventId` 标记发送成功。dispatcher 对未发送或确认超时记录持续重试，不能在事务内直接依赖 `convertAndSend` 的返回值。若 RabbitMQ 已 confirm 但 outbox 更新失败，记录保持 `PENDING` 并重发同一 `eventId`；消费者按 `eventId`/订单 CAS 幂等处理。兼容存量 String 订单号消息：消费者将其转换为 `legacy-timeout:{orderNo}`，从订单读取 `expireAt` 后按相同 CAS 关单路径处理；新消息使用包含 `eventId`、`eventType`、`orderNo`、`expireAt`、`traceId` 的 JSON envelope。关单消费者检查订单号、事件号和过期时间，只有 CAS 成功取消待支付订单后通知 stock 回滚。

### 4.4 秒杀

Gateway/Sentinel 保护入口，Redis 原子预扣热点库存；库存服务先持久化不可变 `seckill_reservation` 和 `seckill_order_outbox`，再由 publisher-confirm dispatcher 投递 RabbitMQ，最后才返回 `accepted=true`。`accepted` 只表示 Redis 预扣和可恢复 outbox 已存在，不代表订单已落库。outbox 使用稳定 `eventId` 持续退避重试；达到告警阈值后仍继续自动恢复，不能因 RabbitMQ 或商品服务暂时停机让请求永久丢失。进入死信后由订单服务写入 `seckill_message_recovery`，重放前必须通过 stock 的只读 reservation 验真接口确认 `ACCEPTED/PUBLISHED/ORDER_CREATED`；`NOT_FOUND`、`REJECTED` 或查询未知进入隔离/核验，不创建幽灵订单。该接口仅经 Nacos/Feign 直连，要求 `X-Internal-Service: cloud-mall-order`，不经 Gateway；该 Header 是现有项目约定的调用边界标记，不宣称提供完整服务认证。只有不可解析载荷或哈希冲突停止自动恢复。旧版无 `eventId` 消息按订单号、用户幂等键或规范化载荷哈希生成稳定 legacy eventId 后进入同一恢复链路。订单消费者以 `userId + idempotencyKey + requestHash` 原子认领；同哈希重复消息幂等确认，不同哈希进入冲突终态并告警。持久化订单、秒杀受理和库存补偿最终通过既定事务与恢复规则收敛。不得把 Redis 预扣结果直接当作最终支付成功。

秒杀对账使用现有 Redis Key `stock:consistency:lock` 的随机 token：`SET key token NX PX 20000` 获取；续期和释放都必须使用比较 token 的 Lua，释放脚本仅在当前值等于 token 时 `DEL`。对账任务每 5 秒以相同 token 续期；续期失败或发现 token 不再持有时立即停止后续写入，不能在 `finally` 中无条件删除 Key。锁过期后新实例可以接管，旧实例不得继续修改投影；每个批次写入前再次检查 token。锁只防止并发对账，库存数量正确性仍由 MySQL 事实、秒杀受理台账和 Redis 原子脚本共同裁决。

### 4.5 普通库存缓存一致性

普通库存写路径以 MySQL 为唯一事实源：先在独立事务中提交 `stock_reservation_request(order_no,items_hash)=INIT` 的不可变认领，再在第二个库存事务中锁定该请求行，完成所有 SKU 的条件更新、`stock_flow` 幂等流水和 `stock_cache_repair` 记录，并递增 `stock_sku.version`，最后标记 `RESERVED`；事务提交后才异步更新 Redis。库存不足时第二个事务整体回滚，随后由独立事务在同一请求行上持锁标记 `REJECTED`，保留原哈希，不能让不同快照重新占用同一订单号。Redis 更新失败、进程在提交后退出或结果未知时，修复器扫描持久化记录重试。Redis 数量必须携带数据库版本，Lua 以版本条件原子覆盖，旧修复任务不能覆盖新库存。预扣/确认/回滚请求都先按订单号、快照哈希和流水幂等，再执行数据库条件更新；Redis 不可用只影响投影，不允许用购物车库存或旧缓存作交易结论。

这项调整明确替代普通库存的“先 Lua 扣 Redis、再写 MySQL、失败时盲目回补”路径。若保留兼容旧 Redis 预扣数据，必须先把该 SKU 标记为 `REPAIR_BLOCKED`，等待对应 `stock_flow` 完成或超时裁决后再以 MySQL 版本重建，禁止直接 `MSET/SET` 覆盖。

### 4.6 Feign 写请求结果未知

库存预扣通过 `orderNo` 固定成幂等操作。Feign `POST /reservations` 读取超时、连接断开或返回解析失败时，订单服务必须保留 `order_reservation_intent=RESERVE_UNKNOWN`，不得生成新订单号或把未知当作失败。恢复任务先调用 `GET /reservations/{orderNo}`；状态为 `RESERVED` 才能依据订单状态确认或回滚，状态为 `REJECTED` 才能释放意图，持续未知则重试并告警。商品读取超时只能有限重试幂等 GET；最终仍不可用时返回服务暂不可用，订单不能使用购物车缓存价格或库存继续创建。

### 4.7 跨月订单列表与长运行建表

`GET /api/orders` 由 order 服务根据时间窗口枚举命中的 `mall_order_${yyyyMM}` 物理表。请求时间先按 same instant 规范为 `Asia/Shanghai`（`+08:00`），再计算月表范围，并用左闭右开 `[start,end)` 条件；不能直接从原始请求 offset 计算 `YearMonth`。列表查询在单个只读 `REPEATABLE_READ` 本地事务中完成逐月 `COUNT` 和目标页读取，使 `total` 与 `items` 对同一 InnoDB 快照计算；事务只覆盖本次读取，不跨请求保持。由于不同物理月份的 `created_at` 区间按分片不变量严格不重叠，服务按月份倒序遍历，依据每月计数跳过完整月份，仅对承载目标页的月份执行有界 `LIMIT/OFFSET` 批次，并在批次内按 `created_at DESC, order_no DESC` 稳定排序。页码偏移用 `long` 计算并检查溢出，内存只保留目标页和当前批次。

表枚举、计数、批量读取任一步骤发现缺物理表、数据库异常或结果不完整，都返回 `ORDER_QUERY_SHARD_UNAVAILABLE`（HTTP 503），丢弃已读数据，不返回部分页。列表读路径不执行建表，也不通过捕获 `DataAccessException` 后继续返回成功来掩盖缺页。`OrderTableInitializer` 及定时任务只负责按配置窗口预建未来月份，并在建表失败时记录可告警状态；历史缺表由运维/迁移流程补齐后再重试查询。发现订单落在错误月份时作为数据异常告警并失败，不通过全量扫描默默兜底。

现有 `idx_user_created(user_id, created_at)` 作为第一阶段过滤索引保留。实测 `mall_order_202609` 的用户和时间范围查询命中该索引，但在 `ORDER BY created_at DESC, order_no DESC` 下出现 `Using filesort`；当前表仅 17 行，排序开销不足以支持立即迁移。增加 `(user_id, created_at, order_no)` 可能减少 tie-break 排序，但会增加每月表的索引存储、写入和历史分表迁移成本，且带 `status` 条件的查询收益尚未由执行计划证明。因此本轮不新增索引；后续以接近生产规模的 `EXPLAIN ANALYZE`、深分页延迟和写入成本为依据另行评审。

### 4.8 商品事实与 ES 索引可靠同步

商品服务把 MySQL `product`、SKU、参数及上下架状态作为唯一事实。所有影响公开索引的商品写事务必须把 `product.version` 严格递增一次，并同时写入 `product_index_outbox`；提交后 dispatcher 才向 RabbitMQ 的商品索引队列发布 JSON envelope。事务内禁止直接依赖 `convertAndSend` 的返回值。outbox 使用数据库租约和退避扫描，发布同时处理 publisher confirm 与 returned message：确认成功且未被退回才标记 `PUBLISHED`；连接断开、确认超时、returned 或状态更新失败保留同一 `eventId` 为 `UNKNOWN/PENDING`，重试不得生成新事件号。旧代码已存在未递增版本时，迁移必须先审计并修正版本单调性。`product_index_outbox` 只接收商品实体事件；分类 API 仍正常写分类事实，但分类变更本轮不再向商品索引交换机发送 `CATEGORY_CHANGED`，避免无索引作用的消息与 `product_id NOT NULL` 模型冲突。

索引事件至少包含 `eventId`、`eventType=PRODUCT_INDEX_UPSERT|PRODUCT_INDEX_HIDE`、`productId`、`productVersion`、`source`、`occurredAt`、`traceId`、`payloadVersion`。新 dispatcher 必须显式使用 JSON converter 和 `content_type=application/json`，不得让默认 Java 序列化成为新消息格式。除定位字段外，消费者不信任消息快照，而是按 `productId` 读取当前 MySQL 事实并构造索引文档。事件版本小于当前事实版本时仍投影当前事实，不能让旧消息覆盖新数据；ES 文档保留 `productVersion` 和 `visible` 字段，`HIDE` 写入不可检索的 tombstone 文档而立即物理删除必须另行评审，避免旧 UPSERT 在删除/下架后复活。ES 写入使用 external version 或等价的版本条件，旧版本被拒绝后记录 `STALE`。因此实时事件与 backfill 并发时，backfill 读到的旧版本即使晚到也只能被 ES 拒绝；同一商品更新必须递增 `product.version`，不能用相同版本覆盖顺序。

消费者先以 `eventId` 认领 `product_index_consumption`，完成 ES 写入并持久化 `APPLIED`/`STALE` ACK 后才确认 RabbitMQ 消息；ACK 失败不得确认。重试、进程重启和重复消息按 `eventId` 幂等。ES 不可用、部分 bulk 失败或 MySQL 读取失败进入 `RETRY` 并退避；不可解析、缺少商品 ID 或不可变载荷冲突进入 `QUARANTINED`，不得猜测商品并写 ES。outbox 标记 `PUBLISHED` 时同时记录 `last_published_at` 和 `ack_deadline_at`；若到期仍没有对应 `APPLIED/STALE` ACK，dispatcher 按租约重新发布同一 `eventId`，将截止时间退避延长并告警，直到 ACK 或人工隔离。DLQ listener 对新 JSON 消息先完整保存原 payload（应用上限 1 MiB）和 SHA-256，再按 `eventId` 写消费记录为 `RETRY`；两项持久化成功后才确认 DLQ。replayer 扫描 `RETRY` 并使用原 envelope/同一 `eventId` 发布回主队列，不能等待 dispatcher 永久发现。若新 JSON 消息没有对应 outbox，先校验 eventId、productId、eventType 和版本，再从当前 MySQL 商品事实创建同一 `eventId` 的恢复 outbox（`source=LEGACY_REPLAY`）；商品事实缺失且事件明确为 HIDE 时才允许生成版本化 tombstone，否则进入 `QUARANTINED`。当前两条 DLQ 样本来自 `cloudmall.product.exchange`，失败原因为旧 `ProductIndexConsumer` 用 JSON 解析 Java 序列化字节（`Unexpected character code 172`）；载荷是 `content_type=application/x-java-serialized-object` 的 Base64 Java 序列化数据（`rO0ABXNy...`），不能按新 JSON envelope 盲目重放，禁止不受限 Java 原生反序列化；先写 `product_index_dlq_quarantine` 保存交换机、队列、content type、完整原始 payload、payload size 和 SHA-256，再确认原消息。原始 payload 超过 1 MiB、MySQL 写入失败或 SHA-256 校验不一致时不得 ACK，必须保持可重投并告警。优先通过商品事实和 outbox/rebuild 重建；只有能在受控解析后核验稳定 `productId` 的旧消息才可按当前事实生成 `LEGACY_REPLAY`，否则保持隔离并告警。

当前 ES 缺少 `cloudmall_product` 索引时，创建一个持久化 `product_index_rebuild_job`，按 `product_id` 游标每批最多 200 条读取当前 MySQL 事实，直接使用有界 ES Bulk 重建；实时商品变更仍走 `product_index_outbox`。重建任务不在每次启动时自动全量扫描；只允许一个持有租约的任务运行，可暂停/继续，Bulk 成功后才推进持久游标，部分失败或 ES 中断则不推进并退避重试同一批。该方案避免为 88,476 条商品额外创建约 88,476 条 outbox 和消费消息；重复批次依靠 ES external version 幂等。Bulk 建议 200–500 条并控制每分钟速率、请求耗时、ES heap 和 rejected；部分失败记录失败商品并重试整批或拆分失败项。任务必须周期检查 `indexExists` 和代际标识；即使状态为 `COMPLETED`，发现索引丢失也要在租约内递增 `index_generation`、把游标重置为 0 并重新进入 `RUNNING`，继续限速完整重建。`FAILED` 保留错误和游标，达到退避时间后自动重试，不是永久终态。完成后通过 MySQL 商品数、实时 outbox、消费 ACK 和 ES 文档/tombstone 数核对。实时事件与 backfill 并发时，backfill 使用读取时的 `product.version`，external version 拒绝旧版本倒序覆盖。

可观测性至少记录 `eventId/productId/productVersion/traceId`，并暴露 outbox PENDING/UNKNOWN/DEAD、消费 RETRY/QUARANTINED、重建游标/速率、ES rejected/版本冲突和 MySQL 与 ES 数量差异。验收必须注入：本地事务回滚、confirm 丢失、returned、dispatcher 重启、重复消息、旧版本乱序、ES 停机恢复、bulk 部分失败、两条旧 DLQ 消息及 88,476 商品限速重建；任何阶段都不得因索引暂不可用阻塞商品事实写入。ES 查询切换属于后续 CM-20260924-19，本轮只冻结可靠写入和重建。

## 5. 数据与缓存边界

- MySQL 是用户、商品、订单、库存、支付等事实数据的持久化来源。
- Redis 登录态 TTL 为 2 小时，购物车 value 使用 JSON 且 TTL 为 30 天；必须区分用户登录态、购物车、热点商品、秒杀库存、限流计数。Redis 连接、超时或命令失败时，Gateway Bearer 校验、用户登录 Token 写入、购物车读写和秒杀入口统一返回 503 `REDIS_SERVICE_UNAVAILABLE`，不得降级为匿名、空购物车或 `accepted=true`；商品/分类公开 GET 不依赖 Token Redis。
- Elasticsearch 是商品检索索引，不替代 MySQL 事实数据；商品变更通过 RabbitMQ 异步同步并记录失败结果。
- 订单表由 Sharding-JDBC 按 `created_at` 路由到月度逻辑表，开发阶段预建当前月份及测试需要的物理表。
- 日志通过 Kibana 分析，Micrometer Tracing 将 Trace ID 透传至 Zipkin，Prometheus 抓取 Spring Actuator 指标端点并在 Grafana 展示。

## 6. Docker Compose 本地开发部署说明

Docker Compose 是本地开发部署的统一编排入口，使用 Docker Desktop 管理基础设施容器、网络、数据卷、依赖启动顺序和健康检查。必须编排以下组件：Nacos、MySQL、Redis、RabbitMQ、Seata、Elasticsearch、Kibana、Zipkin、Prometheus、Grafana、Sentinel-Dashboard 和 Nginx。各组件版本与认证约定如下：

| 容器组件 | 版本 | 认证 |
| --- | --- | --- |
| MySQL | 8.0 | `root/root` |
| Redis | 7.0 | 密码 `root` |
| RabbitMQ | 3.11-management | `root/root`，不用 `guest` |
| Nacos | 2.2.0 | `root/root` |
| Sentinel-Dashboard | 1.8.6 对应面板 | 默认无认证；显式开启时 `root/root` |
| Seata-Server | 1.5.2 | 默认无认证 |
| Elasticsearch/Kibana | 7.17.0 | 开发环境关闭安全认证；ES 如开启认证密码 `root` |
| Zipkin | 2.23.19 | 默认无认证 |
| Prometheus | 2.47.0 | 默认无认证 |
| Grafana | 9.5.5 | `root/root` |
| Nginx | 以计划书预置镜像为准 | 默认无认证 |

Compose 至少应提供统一的本地开发网络、持久化数据卷、健康检查和必要的启动依赖；容器间连接使用 Compose 服务名及容器端口，宿主机映射仅为 IDEA、浏览器或运维控制台访问所需。不得因 Compose 映射改变既定端口：Gateway/user/product/cart/order/stock/pay 仍分别使用 8080/8081/8082/8083/8084/8085/8086，React/Vite 开发服务器仍使用 5173。

本轮不将七个业务服务纳入 Compose 启动集合。现有业务 Dockerfile 均以已构建的 `target/*.jar` 为输入，且当前开发约束要求业务服务前期由 IntelliJ IDEA 以标准 Maven 多模块工程本地运行；强行纳入会引入镜像构建前置条件、与 IDEA 进程的端口冲突及两套运行时配置。业务服务后期完成镜像化后，另行评审 Compose 扩展，不改变服务边界和固定端口。Nginx 虽由 Compose 编排，但只承担本地静态资源代理和请求分发；前端开发阶段仍可由 Vite 在 5173 提供资源。

## 7. 配置、安全与可观测性约束

- Nacos 配置按开发/测试环境隔离；敏感配置仅按本地练习约定使用，不得宣称生产安全。
- Gateway 统一处理跨域、鉴权和入口级过滤；服务仍需校验用户身份和资源归属，不能只信任网关头信息。
- Sentinel 只用于计划书范围内的限流、熔断、降级和热点参数保护，不引入复杂风控。
- 统一返回结构和错误码由 common 提供；API 契约以 `.codex/specs/api_spec.md` 为准。
- 通过 Micrometer Tracing 透传 Trace ID 串联 Gateway 到各服务，Prometheus 从 Spring Actuator 指标端点采集至少覆盖 JVM、QPS、响应时间和中间件状态的指标。
- 禁止引入 Zuul、Eureka、Kubernetes、多级缓存及计划外中间件。

## 8. 部署阶段

1. 环境阶段：通过 Docker Compose 启动基础设施与 Nginx，验证认证、网络、数据卷、健康状态和宿主机访问入口。
2. 基础阶段：构建 common、Gateway、Nacos 配置和观测链路。
3. 业务阶段：按 user/product/cart/order/stock/pay 服务边界开发和联调。
4. 稳定性阶段：落地 Sentinel、RabbitMQ、秒杀和链路/指标监控。
5. 交付阶段：业务服务打包 Docker 镜像，Nginx 代理 React/Vite 静态资源，完成全流程联调。

## 9. 设计冻结说明

本轮已冻结 OpenFeign + Nacos 服务通信、3 秒连接超时、5 秒读取超时、仅幂等 GET 有限重试、RabbitMQ TTL + DLX 延迟关单、Seata file 模式、RabbitMQ 商品索引异步同步、Micrometer Tracing Trace ID 透传、Spring Actuator 指标端点、Docker Compose 本地编排范围以及 IDEA/Vite 本地端口。Docker Compose 的具体容器名、网络/卷名称、镜像构建细节和生产化安全不属于本地代码实现契约。

### CM-20261007-18 公开支付协议选择（16D）

新建支付单的 `pay_record.debit_mode` 可由显式配置选择 `LEGACY` 或已接入的 `TCC`，默认仍为 `LEGACY`；历史支付、扣款意图和 TCC execution 始终按持久模式路由。TCC 成功只走既有 coordinator → delivery，禁止进入 Legacy debit/refund。TCC 主动失败在 16D 默认下只读本地事实并保守返回 202；16E 独立 cancel opt-in 仅对无 execution 且完整取消证据场景允许取消台账与 Pay FAILED 同事务 CAS；已有 execution 仍 202。完整 `ROLLEDBACK` 双端 live 证据仍可按 16D 规则 CAS FAILED，且不改既有 decision。callbackId 先以独立事务预绑定 payload，`RECEIVED_SUCCESS/RECEIVED_FAILURE` 配合 `processed_at=NULL` 表示未处理，跨支付复用返回 409。

### CM-20261007-18 TCC 主动取消（16E）

TCC 主动取消使用独立 `pay_tcc_cancel` 台账和 `cloudmall.pay.tcc-cancel.enabled=false` opt-in 开关；该开关不属于 16D TCC 创建必需依赖，取消表无条件初始化，入口 gate 始终生效。首次无 execution 的取消在 pay_record 行锁下持久抢占，并与 coordinator execution claim、bridge.prepare、receipt.claim 共用 cancel gate；提交后才调用 Order.cancel。只有 Order `CANCELLED`、stock action `SUCCEEDED` 且再次确认无 execution/dispatch/receipt 时，才在短事务内将台账 `COMPLETED` 与 Pay `FAILED` 同步 CAS；未知/库存未完成进入有界恢复。已有 execution 不改 decision、不主动 recover。Legacy fail 事务保持不变，TCC cancel 通过 `NOT_SUPPORTED` 入口确保远程 RPC 在本地事务外。

### CM-20261007-18 TCC 已回滚失败入口收尾（16F）

16F 只补已有 `ROLLEDBACK` execution 的公开 failure 收尾：在 `NOT_SUPPORTED` 边界核对 Pay receipt/Fence 与 User live TCC 的同 XID/branch `CANCELLED`、no-ledger 证据，再由短事务 CAS Pay FAILED；callback 同步完成失败回调日志。非 ROLLEDBACK、证据未知或冲突均保留 202，不主动 recover/TC/Order/User 写入，不创建取消台账、不改 decision，不增加自动投影 scanner。无 execution 的主动取消继续由 16E 独立 opt-in 负责。

### CM-20261007-18 TCC 后台 ROLLEDBACK 失败投影（16G）

16G 在既有 recovery scheduler 中增加第二阶段，复用 `tcc-recovery.enabled`、recovery_attempts/next_recovery_at/随机 lease 和有界预算；独立 projection service 不依赖公开 PaymentService。整轮最多 20 条、共享启动后 10 秒预算，首轮旧 recovery 优先、后续轮次交替优先，两阶段候选 LIMIT 使用剩余额度；阶段异常隔离且失败/未知独立退避，避免长期饥饿。SQL 在 LIMIT 前过滤 ROLLEDBACK、决议/XID/原 tuple、TCC、Pay 未失败、dispatch/receipt 存在及 cancel/Legacy intent 缺失。User live CANCELLED/no-ledger 证明后，以 token/lease 校验的 Pay+execution 短事务 CAS FAILED；不改 decision、不调用 TC/Order/User 写入、不自动完成 callback。
