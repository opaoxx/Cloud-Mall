# CloudMall

CloudMall 是一个面向学习与复盘的云购微商城项目，用于演示基于 Spring Cloud 的微服务电商业务闭环。

项目覆盖：

- 用户注册、登录和个人资料
- 商品、分类、SKU 和商品参数管理
- 商品搜索、分类筛选和商品详情
- Redis 购物车
- 收货地址和订单创建
- 库存预扣、确认和回滚
- 本地余额支付（新用户默认余额 10000）
- 秒杀活动、RabbitMQ 异步处理和基础监控能力
- React/Vite 淘宝式商品发现和交易页面

## 技术栈

后端：Spring Boot 2.7、Spring Cloud 2021、Spring Cloud Alibaba、Nacos、MySQL 8、Redis 7、RabbitMQ、Seata、Sentinel、Elasticsearch。

前端：React、TypeScript、Vite、React Router、Vitest。

## 服务端口

| 服务 | 端口 |
| --- | ---: |
| Gateway | 8080 |
| User | 8081 |
| Product | 8082 |
| Cart | 8083 |
| Order | 8084 |
| Stock | 8085 |
| Pay | 8086 |
| Frontend | 5173 |

Docker Compose 默认提供 MySQL、Redis、RabbitMQ、Nacos、Seata、Elasticsearch、Kibana、Prometheus、Grafana、Sentinel 和 Nginx。

## 快速开始

### 1. 启动基础设施

```bash
docker compose up -d
```

首次启动时，MySQL 会执行 `backend/database/schema.sql`，创建数据库、表和本地演示商品。

Nacos 使用固定 hostname 作为 standalone Raft 节点身份。新数据卷默认使用
`cloud-mall-nacos`；已有数据卷必须把持久 Raft 日志中唯一 peer 的 hostname 写入本机
`.env` 的 `NACOS_NODE_HOSTNAME`，以后保持该值。当前本机恢复值为 `57c05639b175`。
容器重建后 hostname 漂移会让 HTTP 健康端点仍为 UP、命名 Raft 却没有 leader。
恢复前应停写并备份完整的 Nacos data/logs 两个 named volume，核验归档和旧 peer，
再仅重建 Nacos；不要清空 Raft 目录。`.env.example` 的缺省值适用于新数据卷。

Nacos 的 Compose 健康检查同时核对 HTTP UP 和三个命名 Raft 组的本机 leader；
可独立执行 `docker exec cloud-mall-nacos python /home/nacos/bin/cloudmall-healthcheck.py`。
该检查使用容器已有的内部身份配置，首次启动不依赖 bootstrap 尚未创建的 root 用户。

已有 MySQL 数据卷不会重新执行初始化脚本。升级商品读索引时，在项目根目录执行一次（脚本可重复执行）：

```powershell
Get-Content -Raw backend/database/migrate_product_read_indexes.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot
```

### 2. 启动后端

使用 IntelliJ IDEA 导入根目录 `pom.xml`，再分别启动各服务的 `*Application`。

项目使用 JDK 17 目标版本。使用 JDK 21 临时运行 Seata 相关服务时，需要添加：

```text
--add-opens java.base/java.lang=ALL-UNNAMED
```

当前 Spring Cloud OpenFeign 3.1.5 使用 `feign.client.config.default` 配置连接 3000 ms、读取 5000 ms 超时。升级框架前应检查属性前缀是否匹配；未识别的配置不会改变 Feign 默认超时。

### 3. 启动前端

```bash
cd frontend
npm ci
npm run dev
```

浏览器访问 <http://localhost:5173>；使用 Nginx 部署时访问 <http://localhost:8088>。

本地练习环境默认中间件账号约定为 `root/root`，仅用于本地开发，不应直接用于生产环境。

本地管理员账号为 `admin`，密码为 `admin123`。

### 秒杀 Sentinel 规则

Gateway、订单和库存服务启动时自带秒杀限流默认规则。Nacos 可持久保存开发环境规则；首次启动中间件后执行：

```powershell
.\deploy\nacos\publish-sentinel-rules.ps1
```

脚本只创建缺失的 `CLOUDMALL_DEV` 规则，包括 Stock 内部秒杀资源 `cloudmall-stock-flow-rules`；Stock 的本地兜底阈值为单实例 200 QPS。需要用仓库中的规则覆盖现有 Nacos 配置时，显式添加 `-Force`。测试隔离可使用脚本的 `-Group` 参数及 Stock 的 `cloudmall.stock.seckill.sentinel.rule-group` 配置。Sentinel Dashboard 的临时调整不会自动写回 Nacos；需要长期保留的调整应更新 `deploy/nacos/sentinel/` 的 JSON 并发布。

Order 还订阅 `cloudmall-order-degrade-rules`，保护普通库存预扣、秒杀受理、库存状态查询和确认/回滚四类 Feign 调用。普通预扣被熔断后仍按原订单号进入结果未知恢复，不会直接释放库存；订单已提交后的库存动作按原操作键退避。规则仅作用于单个 Order 实例，不代表 Stock 内部 Redis/MySQL 已具备熔断，也不代替持久意图和对账。

Stock 内部依赖保护订阅独立的 `cloudmall-stock-degrade-rules`，共五个资源、十条异常比例/
慢调用规则；首次部署也由上述脚本创建缺失配置。可通过
`cloudmall.stock.dependency-degrade.*` 覆盖本地默认值，并用 `rule-group` 隔离演练。
Redis 保护覆盖普通读写、Lua、扫描和租约；MySQL 保护覆盖完整事务、只读查询与后台
恢复批次，事务在开始前取得保护，提交或回滚结束后才退出。恢复批次慢耗时包含整批
工作，不能当作单条 SQL 延迟。初始化校准/DDL 与 Seata 演练不计入业务熔断统计。

依赖不可用时，Redis 写路径返回 `REDIS_SERVICE_UNAVAILABLE`/503，数据库路径返回
`STOCK_SERVICE_UNAVAILABLE`/503。普通库存缓存读失败仍可读取 MySQL 事实，缓存命中
不因数据库熔断而阻断；双故障不会返回伪造的零库存。熔断只暂停执行，恢复器沿用原
订单号、operationKey、revision、eventId 和意图，禁止自动换键、重复扣减或猜测回补。

### 秒杀订单最终配额结算（CM-24）

已有 MySQL 数据卷先执行可重复迁移，新数据卷由 `schema.sql` 建表：

```powershell
Get-Content -Raw backend/database/migrate_seckill_quota_settlement.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot
```

升级前停止旧 Stock 写入，避免旧受理 Lua 与新结算协议同时处理活动。新受理在扣减配额时
原子保存每个订单的 Redis 操作回执；MySQL 的 `seckill_quota_settlement` 与受理、outbox
一起提交。支付确认只确认秒杀成交事实；取消和超时关单通过持久释放意图回补一次活动
配额，保留用户累计限购计数和原受理幂等键，不改变普通商品库存。

释放尚未确认时，库存状态查询返回 `UNKNOWN`，订单/支付恢复任务继续等待。
Redis 操作成功而响应丢失时，通过原回执确认，不再重复回补。缺少配额、元数据或原
受理回执时保留 `PROJECTION_BLOCKED`，禁止按受理数量猜测重建；需要核验历史事实并
执行受控恢复。旧受理记录可补建 `OPEN`，但不能补造 Redis 回执或猜测其已经释放。

### Seata AT 受控演练

CM-18 第一阶段在 Order 与 Stock 的独立演练表验证全局提交、回滚和 XID 透传，不修改真实订单、库存或支付数据。已有 MySQL 数据卷先执行可重复迁移：

```powershell
Get-Content -Raw backend/database/migrate_seata_cm18_demo.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot
```

确认 Seata Server 健康后，仅在非生产环境为 Order 和 Stock 显式设置 `CLOUDMALL_SEATA_CM18_ENABLED=true`；Stock 同时设置 `CLOUDMALL_SEATA_ENABLED=true`。默认不开启演练入口。演练使用专用内部 Header 和 `cm18-` 前缀事务 ID，结果不能代表真实订单、库存、支付已经具备 Seata 三方原子事务。

### 扩充本地商品目录

需要使用 `F:\hmall\sql\hm-item.sql` 的完整商品数据时，在 MySQL 容器启动后执行：

```powershell
.\backend\database\import_hmall_catalog.ps1
```

脚本会先导入 hmall 商品源表，再将每条商品转换为 CloudMall 商品、SKU、库存、分类和热度数据，并校验迁移行数。它只重建商品和库存演示目录，不迁移 hmall 的订单或支付数据；由于旧购物车中的 SKU 会失效，脚本会清理 CloudMall 的 `cart:*` Redis 缓存。执行前应保留数据库备份。

### 商品关键词搜索切换 Elasticsearch（CM-19）

新版本的商品服务缺省 `cloudmall.product.search.enabled=false`，关键词继续使用原 MySQL 路径；上线 ES 查询前要完成一次本地索引映射扩展和字段回填。不要直接打开搜索开关读取旧索引。

全新数据卷没有旧物理索引时，跳过下述旧索引映射脚本及 `COMPLETED` 游标复位；以 `rebuild-enabled=true` 启动 Product，重建任务会创建带完整映射的新索引并从初始游标回填，完成后执行相同的数量和字段核对。

1. 在索引仍为原本地物理名 `cloudmall_product`、商品搜索尚未启用时执行 `./deploy/elasticsearch/extend-product-search-index.ps1`。脚本先核对现有 `productVersion` 外部版本及字段类型，再增加 `updatedAt/viewCount/searchCount/hotScore` 和受控分页窗口，不删除索引。
2. 以 `cloudmall.product.search.enabled=false`、`cloudmall.product.index.rebuild-enabled=true` 启动新版 Product。其他索引投递、消费和死信重放开关保持 `true`。确认服务启动日志打印的物理索引名及全部开关值。
3. 仅在 `product_index_rebuild_job` 的目标索引为 `cloudmall_product` 且状态为 `COMPLETED` 时，执行下面的受控游标复位。此操作不会清空商品数据或 ES 索引；进度失败后保留游标继续重试。

   ```powershell
   docker exec cloud-mall-mysql mysql -uroot -proot -e "UPDATE cloudmall_product.product_index_rebuild_job SET index_generation=index_generation+1,cursor_product_id=0,last_batch_count=0,last_bulk_at=NULL,status='PENDING',lease_owner=NULL,lease_until=NULL,last_error=NULL WHERE job_id='cloudmall-product-index' AND target_index='cloudmall_product' AND status='COMPLETED'; SELECT ROW_COUNT();"
   ```

4. 等待任务重新达到 `COMPLETED`，核对 MySQL 商品总数/上架数与 ES 文档总数/`visible=true` 数、四个新增字段覆盖率、版本抽样、价格排序和关键词样例；确认 outbox 和消费 ACK 无积压或失败。只有核对通过，才在所有 Product 实例一致地设置 `cloudmall.product.search.enabled=true` 并重启。若查询故障，将开关显式设回 `false`，保留物理索引和回填游标排查。

非空关键词走 ES 的中文分词匹配，原 MySQL 路径只对名称做子串匹配，两者命中总数可能不同；无关键词列表仍走 MySQL。Elasticsearch 或映射不可用时，已启用的关键词路径返回 503，不会静默退回 MySQL。

### 商品读数据库故障排查（CM-16）

商品公开 GET 遇到可确认的数据库连接获取、查询响应超时或通信故障时返回
`PRODUCT_QUERY_UNAVAILABLE`/503，并禁止缓存该错误响应。诊断计数为
`cloudmall.product.read.failures`，原因标签分别是 `connection_acquire`、`sql_timeout`
和 `db_connection`；服务端日志保留 requestId、traceId 和实际异常链。
事务开始异常会检查底层连接证据，未知事务失败、坏 SQL 和其他实现错误仍为 500；
写请求保持原 500 契约，也不增加商品读失败计数。排查时同时核对 Hikari active/pending/
timeout 指标，不能只凭“等待约 30 秒”判定唯一根因或直接扩大连接池。

### 商品热度历史来源标记（CM-21 第一阶段）

现有 `sales` 和 `hot` 排序使用导入目录的历史快照，尚无实时浏览、搜索或成交事件链路。已有 MySQL 数据卷先运行可重复迁移，再按上一节的步骤扩展 ES mapping、关闭搜索开关并重新运行完整商品索引回填，确认每条历史热度记录和 ES 商品文档都有来源字段后再开启搜索：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_product_hot_source.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
.\deploy\elasticsearch\extend-product-search-index.ps1
```

迁移只给仍与 `hm-item.item` 源商品数值逐行一致、来源仍为 `UNKNOWN` 的记录标记 `LEGACY_IMPORT/hm-item.item`；无法确认来源的行保持 `UNKNOWN` 并由只读审计告警。`GET /api/products/{id}/hot-stat` 的 `sourceType/sourceRef` 会说明来源。指标新鲜度审计每 5 分钟只读核对 MySQL 和 ES，历史快照超过 24 小时会告警；它不会自行增加浏览、搜索或销量。

### 支付扣款模式隔离（CM-18 阶段 12A）

支付记录和扣款意图现在持久化 `debit_mode`。新支付在创建时固定模式，后续恢复只读取原记录；默认仍为 `LEGACY`。第 16D 轮允许显式 TCC 试用，必须同时开启下文列出的完整依赖；未知模式或 TCC 依赖未开启会阻止 Pay 启动。

已有数据卷可先执行可重复迁移，存量记录默认归入 `LEGACY`，不改变金额或支付状态：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_pay_debit_mode.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
```

持久记录为 `TCC` 但入口未启用、未知模式或支付单与意图模式不一致时，支付处理返回 `PAY_DEBIT_MODE_UNAVAILABLE`/503。恢复器保留原状态、租约、尝试次数和重试时间，不调用旧余额扣款或退款路径。恢复扫描在 SQL 限制每批 50 条之前，仅选取支付单和意图均为精确 `LEGACY` 的记录，避免隔离记录占满扫描窗口；孤立意图、空串、小写及尾随空格模式也不进入窗口。排查时核对两张表的原始事实，不要手动改模式强行重试。

12A 只负责 Pay 路由隔离。第 14 轮增加的 User 本地资金状态机见下节；真实 Pay/User TCC 回调与 TC 故障验收仍待后续阶段，CM-18 尚未闭环。

### User 本地资金状态机（CM-18 第 14 轮）

User 新增本地余额冻结、确认、取消状态机及持久扣款归属。同一支付号只能归属 LEGACY 或 TCC；历史账本也参与互斥。`cloudmall.user.tcc-debit.enabled=false` 默认禁止新 Try，已有冻结的 Confirm/Cancel 不受此开关或用户禁用状态阻断。空 Cancel 留下归属记录，迟到 Try 不会再次冻结。

已有数据库先执行幂等迁移；迁移增加 `frozen_balance`、`balance_debit_attempt` 和 `balance_tcc_debit`，不会将旧账本转换为冻结：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_user_tcc_state.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
```

这些本地方法由 User RM 调用；第 16A 轮另增受认证的内部 Try/查询桥。公开余额字段继续表示可用余额；对外支付仍只使用 LEGACY。

### User Seata RM/Fence（CM-18 第 15 轮 A）

User 已增加 Seata 1.5.2 LocalTCC 资源。适配器在同一 User 本地事务中调用官方 Fence 和上述资金状态机；空回滚也写永久取消归属，Fence 日志清理后仍会阻断迟到 Try。Fence 查询强制读取最新数据库事实，避免 SDK 的 JDBC 写入被 MyBatis 会话缓存遮蔽。

默认 `cloudmall.user.tcc-resource.enabled=false`、`seata.enabled=false`，自动数据源代理也保持关闭。只有显式开启资源和 Seata，且 Fence/资金共用同一数据源和事务管理器，才能注册 RM；新 Try 还要求 `cloudmall.user.tcc-debit.enabled=true`。Confirm/Cancel 只作为 TC 回调，没有 HTTP 入口。

JDK 17 开启此资源时，启动 JVM 或 IntelliJ VM options 需添加 `--add-opens=java.base/java.lang=ALL-UNNAMED`，以兼容固定版本 Seata 的 CGLIB。已有数据卷可先执行官方 Fence 表的幂等迁移：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_user_tcc_fence.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
```

15A 验证 User RM 与真实 TC 的提交、取消和重试；15B 使用独立 RM JVM 验证提交前中断、提交后 ACK 丢失、重启及客户端 TC 连接暂失。第 16A 轮只完成下述内部桥，对外支付入口与主编排尚未迁入 TCC。

### Pay → User 内部 TCC 桥（CM-18 第 16 轮 A）

Pay 在 `pay_tcc_dispatch` 中独立提交不可变载荷与一次性 POST 权限，再通过 Nacos/Feign 调用 User。User 在进入 LocalTCC 代理前独立提交 `user_tcc_receive=CLAIMED`；并发请求只允许一个实例注册分支。响应丢失后 Pay 仅 GET 核对原 XID、branch 和资金账本，不重发 Try。GET 不改变 User 接收或资金事实。

余额不足的 HTTP 409 不代表事务已取消；无持久拒绝事实时仍为 `TRY_UNKNOWN`，待 TC Cancel 后核对取消归属。只有完整的身份/持久事实冲突错误码返回明确派发冲突。Gateway 在鉴权前阻断新内部命名空间，包括 discovery 服务前缀、编码和路径参数变体；Confirm/Cancel 只接受 TC 回调。

`cloudmall.pay.tcc-bridge.enabled=false`、`cloudmall.user.tcc-http.enabled=false` 默认关闭。启用隔离验收还需要真实 Seata、User RM/本地 Try 开关和未被 AT 代理的同库事务管理器。内部认证复用 `cloudmall.internal.callback-token`，Pay 服务标识固定为 `cloud-mall-pay`。已有数据卷可幂等执行：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_pay_tcc_dispatch.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
Get-Content -Raw -Encoding UTF8 backend/database/migrate_user_tcc_receive.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
```

若进程在独立认领后、发送 POST 或注册分支前退出，原请求保持未知且禁止重发，需进一步核验。16B1 已补下述 Pay receipt 资源；支付主编排、Order/Stock 联动和入口切换仍后置，**CM-18 尚未整体闭环**。

### Pay LocalTCC 接收记录（CM-18 第 16 轮 B1）

`pay_tcc_receipt` 是 Pay 自身的事务资源。先由 `bridge.prepare` 独立保存原 XID/载荷，再独立认领 receipt `CLAIMED`，唯一胜者进入 Pay LocalTCC 代理注册分支。新认领必须绑定原 `TCC + PENDING` 支付和 `PREPARED` 派发记录；重复调用只读取原 receipt。

Pay 的 Try/Confirm/Cancel 仅修改本库 receipt，与官方 `tcc_fence_log` 在同一本地事务提交。空 Cancel 也保存永久 `CANCELLED`；Fence 清理后仍阻止迟到 Try 和相反终态。User 的 Try 由调用方在同一 XID 下单独派发，Pay receipt 回调不调用 User、Order 或退款。两个资源均确认也只说明扣款切片已确认，不会将公开支付状态直接改成 SUCCESS。

`cloudmall.pay.tcc-receipt.enabled=false` 默认关闭；开启时要求 bridge/Seata 启用、AT 自动代理关闭，Fence 与 receipt 使用同一裸数据源和事务管理器。JDK 17 仍需上节的 `--add-opens` 参数。已有数据卷可重复执行：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_pay_tcc_receipt.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
```

16B1 的资源层本身不创建全局事务。16B2 已补下述内部编排；CLAIMED 后、分支注册前退出仍可能保持未知，禁止换 XID 或重注册来猜测恢复。公开支付入口和 Order/Stock 交付仍待后续验收。

### Pay 内部 TCC 主编排（CM-18 第 16 轮 B2）

`PayTccCoordinatorService.execute` 先在 `pay_tcc_execution` 独立认领一次性执行权，唯一胜者才能 begin、持久化 XID，并依次调用 Pay receipt 和 User Try。已有独立 dispatch/receipt 的旧尝试禁止取得新 XID。并发重入只走 `recover`，不会接管首次执行权。

只有两个资源明确 TRYING 才持久化 COMMIT；首次执行无法确认同时受理时持久化 ROLLBACK 请求。决议在 TC RPC 前独立提交，之后只能重试原 XID、原方向。commit/rollback 的响应并不等于资金终态：必须核对 Pay receipt/Fence 与 User 完整事实，两端同时确认或取消后才结束扣款切片。公开支付状态暂不随此结果变为 SUCCESS。

begin 响应丢失且 XID 未落库时保留 `BEGIN_UNKNOWN`；已有 XID 但没有 decision 时恢复只观察，不自动提交、不重做 Try。两个资源或其证据缺失时继续 UNKNOWN，TC Unknown 本身不能推断资金结果。原调用仍在等待 begin 响应时，其他线程的 UNKNOWN 观察不会剥夺它保存原 XID 的权限。

`cloudmall.pay.tcc-coordinator.enabled=false` 默认关闭；启用要求 receipt、bridge、Seata 均启用且 AT 代理关闭。`cloudmall.pay.tcc-coordinator.timeout-ms` 默认 30000，允许范围为 1–120000 毫秒。execute/recover 拒绝继承外部 XID 或本地事务。当前仅提供内部 Java Service，没有新 HTTP；16B3 已增加下述可选恢复扫描，Order/Stock/退款衔接和对外入口切换仍待后续批次。

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_pay_tcc_execution.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
```

### TCC 自动恢复扫描（CM-18 第 16 轮 B3）

`cloudmall.pay.tcc-recovery.enabled=false` 默认关闭，启用时必须已启用 coordinator 及其依赖。第一阶段只调用原 `recover`，不创建 execution/XID，不执行 Try，不为 `decision=NONE` 新增决议。第一阶段在 SQL LIMIT 前排除 `BEGIN_UNKNOWN`、空 XID、终态、CONFLICT、模式或载荷不一致；16G 第二阶段单独处理符合条件的 ROLLEDBACK 支付投影。

默认每 5 秒扫描一次、每轮最多 20 条，开始下一条前检查 10 秒预算，不强杀在途 RPC。新 execution 首次调度在创建后 30 秒；每次恢复使用独立随机 token 和 60 秒租约，RPC 在认领事务提交后执行。租约过期后可能出现同方向 RPC 重叠，原 coordinator 保证幂等；旧 token 不能释放新租约或覆盖新重试时间。

失败与尚未结束的结果按 5、10、20……秒退避，最大 300 秒。尝试计数饱和到 31 后仍继续低频恢复，不会永久停掉；调度器不把异常改写为支付失败。原 decision recovery 只管理调度字段；16G 回滚投影经过两端永久事实核验后才更新 Pay 状态。

可调整 `cloudmall.pay.tcc-recovery.interval-ms`、`batch-size`（1–20）、`lease-seconds`（1–300）和 `budget-ms`（1–10000）。已有 execution 表可重复执行下面的迁移；它只增加调度字段，不改 XID、原载荷、state 或 decision：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_pay_tcc_recovery.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
```

查询限制的是返回批量和调度预算，尚未完成大表容量及新增索引的负载验收。16C 已增加下面的独立交付任务，16D 接入显式开启的公开支付入口。

### TCC 订单交付与取消退款（CM-18 第 16 轮 C）

`pay_tcc_delivery` 从本地 COMMITTED execution 派生 READY 意图；READY 本身不代表远程证据已齐全。每次处理先核对 Pay execution/receipt/Fence，再直接查询 User TCC 当前完整事实和原 APPLIED DEBIT。未知、已回滚或载荷矛盾均不允许支付订单或猜测退款，Legacy 支付恢复器保持原隔离规则。

交付先 GET 订单状态，只有 PENDING_PAYMENT 才发送幂等 paid；无论写响应如何，再 GET 核对结果。只有 PAID/COMPLETED 且库存动作 SUCCEEDED 才将支付和任务同事务置为 SUCCESS。只有 CANCELLED 且库存回滚 SUCCEEDED 才进入 REFUND_PENDING，使用固定 `refund:<payNo>` POST 让 User 校验完整原退款载荷，再 GET 核验 APPLIED REFUND。已出现退款事实时禁止再次 paid。

User 退款现在允许禁用账户清算已确认的 TCC 扣款，前提是匹配的 CONFIRMED attempt 和原 DEBIT/APPLIED 均存在；不依赖账户总冻结额为零。禁用 Legacy 账户退款仍为 404，active Legacy 行为不变。Pay 入站余额响应兼容实际退款字段 `refundOperationKey`，避免退款到账后因读取到空 operationKey 而一直待恢复。

`cloudmall.pay.tcc-delivery.enabled=false` 默认关闭；启用要求 coordinator 及其依赖已启用。默认每 5 秒执行两阶段扫描，每阶段最多 20 条、总预算 10 秒，随机 60 秒 token lease 与 5–300 秒退避。旧 token 不能写本地支付终态或释放新租约；RPC 在本地事务外执行。公开 `/api/payments` 默认新建模式仍为 LEGACY；16D 的显式 TCC 接入见下节。

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_pay_tcc_delivery.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
```

### 公开支付 TCC 路由（CM-18 第 16 轮 D）

新支付可显式选择 `cloudmall.pay.debit-mode=TCC`，并同时启用 Pay 的 `tcc-entry`、`tcc-coordinator`、`tcc-receipt`、`tcc-bridge`、`tcc-recovery`、`tcc-delivery` 六个 `.enabled=true` 开关；Seata 必须启用且 AT 自动数据源代理关闭。User 沿用上文 TCC 本地资金、资源和 HTTP 桥开关。仓库默认仍为 LEGACY，所有 Pay TCC 功能开关默认关闭。

公开成功请求按持久支付模式路由。TCC 首次核验待支付订单后认领唯一 execution；重放恢复原 XID，资金确认后经 delivery 完成订单与库存交付。双端取消及无扣款证据齐全才投影 FAILED。切换新建配置不改变旧支付模式，不得手动改库迁移历史支付。

TCC `mock-fail` 和失败 callback 对未决资金执行返回 202，取消开关关闭时不改变全局决议。16E 在独立开关下支持尚无资金执行的主动取消；16F 支持已经回滚且证明完整的失败收尾。16K在取消开关下支持完整双Try且尚未决议的原执行请求回滚；已COMMIT及其他证据不足场景仍不接管，CM-18未整体闭环。callbackId 在业务处理前独立绑定支付号、用户、金额及方向；`RECEIVED_*` 只表示收到，只有真实支付终态才完成日志，跨支付或相反方向返回 409。

### 未开始扣款的 TCC 主动取消（CM-18 第 16 轮 E）

`cloudmall.pay.tcc-cancel.enabled=false` 默认关闭，是独立于 16D 的可选能力。开启时要求完整 TCC 公开入口已启用。无execution时，`mock-fail` / 失败 callback 在同一支付行锁下确认不存在 execution、dispatch、receipt 或 Legacy 意图后，独立提交 `pay_tcc_cancel`；提交成功后才通过 Order 查询/取消，RPC 不持有本地事务。

订单 `CANCELLED` 且库存动作 `SUCCEEDED` 后，取消台账 `COMPLETED` 与 Pay `FAILED` 在同一短事务提交；未知结果返回 202。已有资金执行不进入此取消台账；未决执行请求回滚见16K，已回滚失败收尾见16F。台账在 Pay 启动时幂等建表，关闭取消开关仍保留 execution/bridge/receipt 的永久阻断检查，不能靠关闭功能重启扣款。

恢复器每轮最多 20 条、10 秒启动预算，60 秒随机 token lease，失败退避 5–300 秒。原订单载荷矛盾、已支付或库存冲突停止自动取消并记录 `CONFLICT`。新旧数据库均提供幂等迁移：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_pay_tcc_cancel.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
```

### 已回滚 TCC 的失败入口收尾（CM-18 第 16 轮 F）

原 execution 已为 `ROLLEDBACK` 时，`mock-fail` 和失败 callback 复用成功入口的双端回滚证明：先在 Pay 本地快照核对原支付、execution、receipt/Fence 与 dispatch，再在事务外读取 User 当前 `CANCELLED`、无扣款账本及原 XID/branch/完整载荷。证明齐全后，短事务将 Pay 投影为 FAILED，失败入口返回 200，失败 callback 才完成日志。

此路径不依赖取消开关，不创建取消台账、不调用 coordinator/recover、TC 或 Order，不改变原 decision；User 证明不足继续 202，原有持久冲突错误保持不变。无 execution 仍按 16E 开关处理。16G 补充下述后台自动失败投影；默认新支付模式仍为 LEGACY。

### TCC 回滚结果后台投影（CM-18 第 16 轮 G）

复用 `cloudmall.pay.tcc-recovery.enabled` 与 execution 的 recovery 元数据，不新增表或开关。原 decision recovery 与 ROLLEDBACK 投影分阶段查询，共享每轮最多20条、10秒启动预算；首轮旧恢复优先，之后轮换优先级。后台不依赖公开 entry Bean，启用原 recovery 及其 coordinator/receipt 依赖即可运行。

只有原支付为 TCC 且仍 PENDING/ORDER_SYNC_UNKNOWN、原 execution 已 ROLLEDBACK、完整派发/receipt 存在、无 cancel/Legacy 意图的记录才参与候选。读取 User 当前同分支 CANCELLED/no-ledger 证明后，最终短事务按 Pay→execution 锁顺序再次验证原载荷和未过期租约，才写 Pay FAILED。旧 token 不能写终态或释放新租约；查询未知继续按5–300秒退避。

本阶段不调用 TC、Order 或 User 写入，不改变 execution state/decision，不自动完成 callback 日志；回调重放仍按原入口核验。缺失/矛盾事实继续保留待核验，不能由超时推断回滚。

### 已提交 TCC 的退款中响应（CM-18 第16轮H）

当 execution 已 COMMITTED、Pay 已处于 REFUND_PENDING 时，`mock-fail` 和失败 callback 返回 `PAY_REFUND_PENDING`/202，明确余额退款处理中。重复请求不触发 delivery、Order cancel/paid、TC 或 User refund，失败 callback 保持 RECEIVED_FAILURE，不能提前伪造 FAILED。

COMMITTED 但 Pay 仍 PENDING/ORDER_SYNC_UNKNOWN 时继续返回普通未知202；SUCCESS/REFUNDED 的409和FAILED幂等保持不变。此修复只校正结果分类，不提供已提交资金的主动取消能力。

### Legacy 失败与退款结果（第16轮I）

Legacy `mock-fail` 和失败 callback 共用持久状态分类：REFUND_PENDING 返回 `PAY_REFUND_PENDING`/202，SUCCESS/REFUNDED 返回409；FAILED在无意图或完整匹配FAILED意图时幂等返回200，不重复Order.cancel。存在意图时必须匹配原支付号、用户、订单、金额和Legacy模式，矛盾时503，未知结果保持202。

PENDING且无意图或已有FAILED意图保留原取消路径；ORDER_SYNC_UNKNOWN只有完整匹配FAILED意图才能继续取消，其余保持未知。失败callback只在Pay实际FAILED后完成日志；退款中和未知状态不调用complete。本轮不改变TCC，也不增加资金补偿。

### Legacy 首次意图与失败请求互斥（第16轮J）

首次扣款意图与失败请求统一先锁 Pay、再锁定读取 intent。只有当前 Pay 为 PENDING 且无意图时才能新建 INIT；已有意图只完整核对并重放，不能由旧 PENDING 视图重新发起已失败支付。

`mock-fail` 外层只做鉴权和路由，Legacy 失败服务在独立事务内重新核对当前事实、执行原 Order.cancel 和 FAILED CAS，并返回最新支付视图；失败 callback 也委托同一服务。意图先提交时失败请求保持202、不取消；失败先完成时旧成功请求不能新建意图。TCC 路径保持不变。

本轮没有新增取消补偿或修改资金恢复流程。Order RPC 仍在 Legacy 失败事务内，保留原3/5秒Feign超时和异常语义；数据库锁等待使用既有数据库/事务机制，不承诺消除所有恢复场景的锁竞争。

### TCC 已受理、未决议时请求回滚（第16轮K）

复用默认关闭的 `cloudmall.pay.tcc-cancel.enabled`。当原execution已有XID、decision仍NONE，且Pay receipt/Fence与User当前事实均证明同一双分支TRYING、无扣款账本时，mock-fail/失败callback可持久选择原ROLLBACK决议。先COMMIT者不会被改向；回滚选择胜出时，迟到COMMIT不能覆盖。

User查询位于两段短事务之间，最终按Pay→execution锁序重新校验原载荷与分支。只将decision/state推进为ROLLBACK/ROLLBACK_REQUESTED并提前恢复时间，不改原租约、不新建XID/分支/Try，不直接调用TC、Order或退款。选择成功后返回202，表示请求已持久化但尚未证明回滚完成；证据不足时也返回202，不能仅凭HTTP状态判断请求已受理。既有恢复器完成清算后，仍须16F/G的双端CANCELLED/no-ledger证明才写Pay FAILED。

无XID、分支证据不足或已COMMIT仍保持原边界；已提交资金的订单取消不属于该功能。支付失败不替代订单/库存自身的生命周期处理。

### TCC 交付扫描与租约保护（第16轮L）

交付种子扫描在LIMIT前排除NULL、空串及仅含空格的XID，避免不可处理的旧记录占满批次。异常记录保留待核验，不补造XID。

交付状态写入及重试元数据更新均要求原token精确匹配且租约尚未到期；过期但尚未被接手的执行者也不能写本地状态或延后重试。Pay与delivery状态仍在同一事务提交。已发出的远程请求不能撤回，订单和退款继续依赖原幂等键与事实核验。

本轮不增加表或开关，不改变COMMITTED后主动取消的既有边界。隔离MySQL回归命令（不注册TC分支）：

```powershell
mvn -pl backend/cloud-mall-pay -am test '-Dtest=PayTccDeliveryRecoveryMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_pay?allowPublicKeyRetrieval=true'
```

### TCC 取消恢复租约保护（第16轮M）

取消台账与支付失败投影要求非空白token精确匹配、租约尚未到期；SQL状态更新与结束本次尝试也验证有效租约。过期执行者即使尚未被接手，也不能释放租约或修改退避时间；新执行者仍可认领到期任务。Pay FAILED与取消COMPLETED保持同一事务。

不改变16E的资金互斥与Order/Stock事实核验，不新增表、开关或远程调用。隔离MySQL回归命令：

```powershell
mvn -pl backend/cloud-mall-pay -am test '-Dtest=PayTccCancelLeaseMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_pay?allowPublicKeyRetrieval=true'
```

### TCC 交付恢复阶段公平调度（第16轮N）

创建交付任务与处理到期任务两阶段交替优先，首轮仍先创建任务。两阶段各最多20条，共享从轮次开始计算的10秒单调时钟预算；查询或单条任务异常仅影响当前工作，剩余预算允许时继续另一阶段。

预算耗尽后不再启动查询或新任务，不强杀在途请求。重入调用直接跳过、不改变下一轮优先级。原delivery证据核验、租约和退款键保持不变；本轮不新增开关。

## 验证命令

```bash
cd frontend
npm run lint
npm test -- --run
npm run build
```

后端可使用 Maven 执行：

```bash
mvn test
```

支付恢复扫描的 MySQL 集成测试需显式运行（先启动本地 MySQL 并完成支付模式迁移）：

```powershell
mvn -pl backend/cloud-mall-pay -am test `
  '-Dtest=PayRecoveryScanMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_pay?serverTimezone=Asia/Shanghai'
```

该测试默认使用本地 `root/root`，可通过 `cloudmall.mysql.test.username/password` 覆盖。它复制现有表结构到当前连接的临时表，覆盖混合隔离记录、跨批恢复、到期/租约边界和领取前模式变化；连接关闭即清理，不写入持久化业务表。默认 `mvn test` 不运行 `*IT`，避免普通单元测试依赖本地 MySQL。

User 本地资金事务集成测试使用真实 MyBatis 和 Spring 事务代理：

```powershell
mvn -pl backend/cloud-mall-user -am test `
  '-Dtest=BalanceTccStateMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_user?serverTimezone=Asia/Shanghai'
```

该测试需要本地账号的建库权限：读取现有表结构，在随机命名的 `cm14_tcc_it_*` 独立库执行迁移、并发和事务回滚验证，结束后精确删除该测试库，不写入原业务数据。若测试进程被强行终止，可先核对实际遗留的该前缀库名，再清理测试库。

真实 TC/RM 验收需同时启动本地 MySQL 和 Seata，并已完成第 14 轮用户表迁移：

```powershell
mvn -pl backend/cloud-mall-user -am test `
  '-Dtest=BalanceTccFenceSeataIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_user?serverTimezone=Asia/Shanghai'
```

该测试在测试 JVM 中启动实际 User Spring Boot RM，通过网络连接真实 TC，使用独立 `cm15_tcc_it_*` 数据库。TC 默认地址为 `127.0.0.1:8091`，可用 `cloudmall.seata.test.tc` 覆盖。只有资金及 TC 事务均结束才清理数据库；若失败后保留测试库，应先恢复该测试事务，再清理，避免丢失仍在重试的分支事实。此测试不等同于进程中断或 Pay 全链路验收。

15B 独立进程故障验收需单独运行此测试类，避免 Seata SDK 的 JVM 静态资源缓存混入其他 RM：

```powershell
mvn -pl backend/cloud-mall-user -am test `
  '-Dtest=BalanceTccRmFailureIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_user?serverTimezone=Asia/Shanghai'
```

测试自动启动、终止并重启自己创建的 RM 子 JVM，使用 `cm15b_rm_it_*` 隔离库和稳定 appId。父 TM 不注册资金 RM；子进程通过专用 loopback TCP 代理连接真实 TC，断连仅影响该测试客户端，不停止 Seata 容器。故障 barrier 同时记录事务是否活跃、XID/branchId，并核对外部可见资金事实；所有控制接口和故障装饰器均位于测试源码，不进入业务 Jar。

验收日志、JVM 参数和资金快照保存在 User 模块 `target/cm15b_rm_it_*/`。只有 TC 已结束且资金终态核对通过才删除测试库；未决时保留库和恢复元数据。清理此类现场前应先用原 appId/XID/branch 恢复，不能通过删除 Fence、换键重试或手工改余额绕过。

16A 跨进程桥验收需本地 MySQL、Nacos、Redis 与 Seata，先构建 User 启动包，再单独执行：

```powershell
mvn -pl backend/cloud-mall-user -am package -DskipTests
mvn -pl backend/cloud-mall-pay -am test `
  '-Dtest=PayUserTccBridgeIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_user?serverTimezone=Asia/Shanghai'
```

该测试启动实际 Pay Spring 上下文和两个独立 User JVM，使用随机 `cm16_*` 数据库/Nacos 分组，不覆盖默认服务实例。九项验收覆盖提交、取消、响应截断、余额不足、并发入站、认证、载荷冲突、并发派发、User 重启及服务重选；同时核对一次 POST、唯一分支、冻结、账本和 TC 终态。原业务表 checksum 必须不变。日志与每笔事实保存在 Pay 模块 `target/cm16-bridge/`；未决时保留隔离库，先恢复真实事务后再清理。

16B1 双资源验收使用同样的基础设施和 User 启动包；另开 Maven 测试 JVM，避免 Seata 静态资源跨测试类复用：

```powershell
mvn -pl backend/cloud-mall-pay -am test `
  '-Dtest=PayReceiptTccIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_user?serverTimezone=Asia/Shanghai'
```

11 项验收覆盖双资源提交/取消、余额不足、空 Cancel、Pay Try/Confirm/Cancel 数据库故障、并发认领、User 响应丢失、载荷冲突和被拒第二分支安全回滚。日志与每笔 Pay/User 事实位于 `target/cm16b1-receipt/`；只在 TC 已结束且资金/receipt 已核对后清理随机 `cm16b1_*` 测试库，不改原业务数据。Pay 进程中断和自动主编排恢复不属于本次矩阵。

16B2 coordinator 验收同样需要上述中间件和 User 启动包，单独启动测试 JVM：

```powershell
mvn -pl backend/cloud-mall-pay -am test `
  '-Dtest=PayTccCoordinatorIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_user?serverTimezone=Asia/Shanghai'
```

13 项测试覆盖自动 begin/双资源决议、并发执行、User 响应丢失、commit/rollback 请求丢失、commit ACK 丢失、begin 响应未知、决议前中断、上下文/载荷冲突及 begin 等待期间的并发观察。另启动真实 Pay 子 JVM，在决议已落库或仅 XID 已落库的 barrier 终止进程，再以同 appId 和数据库重启：前者沿原 COMMIT 收敛，后者保留 UNKNOWN，不 begin/不 Try/不新增 decision。

测试日志、原 XID、进程参数与两端快照保存在 `target/cm16b2-coordinator/`。测试注入器捕获的原 XID 只用于真实 TC 终态核验和现场清理，不会回填 execution 来伪造业务恢复；begin 未知或缺少分支事实的业务结果仍保持 UNKNOWN。所有测试库只在原 TC 已结束、冻结归零且无 TRYING receipt 后精确清理。真实环境缺少这些证据时必须保留原事实。

16B3 的恢复 SQL/租约验证和真实自动恢复分别在独立 JVM 运行：

```powershell
mvn -pl backend/cloud-mall-pay -am test `
  '-Dtest=PayTccRecoveryScanMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_pay?serverTimezone=Asia/Shanghai'
mvn -pl backend/cloud-mall-pay -am test `
  '-Dtest=PayTccRecoveryAutomaticIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_user?serverTimezone=Asia/Shanghai'
```

前者在关闭 Seata 的随机 `cm16b3_scan_*` 库验证 8 项 SQL、租约竞争和旧数据迁移；后者用真实 Pay/User/TC 验证 5 项自动提交/取消、NONE 只读、未知 begin 不占窗口，以及 Pay 重启后的定时恢复，日志位于 `target/cm16b3-automatic/`。恢复 claim/RPC/release 各窗口的独立进程中断没有逐一演练；token 过期与迟到完成保护已有确定性 MySQL 验证。

16C 四服务交付验收先构建服务 Jar，需要 Docker、本地中间件、`redis:7.0` 镜像及 RabbitMQ management（15674）：

```powershell
mvn -pl backend/cloud-mall-user,backend/cloud-mall-pay,backend/cloud-mall-order,backend/cloud-mall-stock -am package -DskipTests
mvn -pl backend/cloud-mall-pay -am test `
  '-Dtest=PayTccDeliveryIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED' `
  '-Dcloudmall.mysql.test.url=jdbc:mysql://localhost:13307/cloudmall_user?serverTimezone=Asia/Shanghai'
```

8 项真实验收使用 `cm16c_*` 四个独立数据库、临时 Redis 容器、RabbitMQ vhost 和 Nacos 组，覆盖自动订单/库存交付、库存未回滚时禁止退款、禁用用户退款、退款响应丢失后新 Pay 进程恢复、paid 响应丢失、已回滚 execution 隔离、并发租约、本地状态事务失败回滚，以及创建任务查询故障时已有交付继续完成。测试只在资金/库存/TC 已核对后清理自身资源；未决现场保留，证据位于 `target/cm16c-delivery/`。未覆盖所有网络故障组合或生产容量。

Order→Stock 的响应丢失演练可使用 `backend/loadtest/stock-fault-proxy.py`。代理只监听本机地址，默认从 18095 转发到 18085；在独立 Nacos Group 中将测试 Stock 实例指向代理，并使用专用 RabbitMQ vhost 和精确可清理的测试订单。`POST /__fault` 可设置 `path/mode/delay_ms/remaining`，模式包括 `reject_before`、`delay_after`、`drop_after` 和 `pass`；`GET /__stats` 核对真正发往 Stock 的请求数，不记录请求体或 Token。延迟响应和丢弃响应都在真实 Stock 返回后执行，用来验证已提交库存事实的结果未知恢复。

秒杀并发复核可使用 [JMeter 测试计划](backend/loadtest/seckill-ladder.jmx)。先准备有效 Token、收货地址和独立的已开始活动；每个并发档位使用新活动，避免前一档消耗库存。PowerShell 示例：

```powershell
jmeter -n -t backend/loadtest/seckill-ladder.jmx `
  "-Jcloudmall.token=$token" "-Jcloudmall.activityId=$activityId" `
  "-Jcloudmall.skuId=$skuId" "-Jcloudmall.addressId=$addressId" `
  -Jcloudmall.threads=20 -Jcloudmall.loops=1 -l results.jtl
```

测试后同时核对 HTTP 受理、MySQL 秒杀受理/outbox/订单、Redis 配额及 RabbitMQ 死信；`409/429` 业务拒绝应与系统错误分开统计。

部分旧测试依赖的 Mockito/Byte Buddy 版本不支持 JDK 21，推荐使用 JDK 17 运行完整测试集。

16D/16F/16G 公开入口、回滚失败收尾及后台投影验收沿用上述中间件环境，额外启动隔离 Gateway，以真实 Redis Bearer token 调用公开接口。先构建全仓 Jar，再运行：

```powershell
mvn -pl backend/cloud-mall-pay -am test '-Dtest=PayTccPublicEntryIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_user?allowPublicKeyRetrieval=true'
```

该测试创建独立数据库、Redis、Rabbit vhost 和发现组；核对原库校验和、TC 终态、资金与库存后清理，存在未决事实时保留现场。

16E/16K 取消验收使用相同隔离夹具，覆盖取消响应丢失、库存延迟、并发首次支付、独立Pay进程接手、租约/原子写入失败，以及原NONE决议的COMMIT/ROLLBACK竞争：

```powershell
mvn -pl backend/cloud-mall-pay -am test '-Dtest=PayTccCancelIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_user?allowPublicKeyRetrieval=true'
```

## 项目结构

```text
backend/        Spring Cloud 微服务和数据库脚本
frontend/       React/Vite 前端
deploy/         Nginx、Prometheus 配置
docker-compose.yml
pom.xml
README.md
```

项目规格、调试记录、性能报告和 Agent 协作状态属于本地开发资料，默认不纳入公开仓库。

## 项目范围

CloudMall 是个人学习型项目，不承诺生产级高可用、生产安全或真实第三方支付能力。当前支付使用本地余额模型，收藏、评价、优惠券、物流和售后等能力暂未纳入业务契约。
