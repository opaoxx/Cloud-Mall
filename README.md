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

Nginx 宿主机入口为 `18088`（容器内 `80`），Seata TC 宿主机入口为 `18091`
（容器内 `8091`）。四个 Seata 客户端默认连接 `127.0.0.1:18091`，可用环境变量
`CLOUDMALL_SEATA_TC` 覆盖；容器之间仍使用内部服务名和端口。

Windows 出现 `ports are not available` / `forbidden by its access permissions` 时，
即使没有进程监听，也可能被系统 TCP 排除范围阻挡。启动前可执行只读诊断：

```powershell
.\deploy\check-local-ports.ps1
# 同时检查 IDEA 业务服务固定端口和 Vite 端口：
.\deploy\check-local-ports.ps1 -IncludeBusinessPorts
netsh interface ipv4 show excludedportrange protocol=tcp
netsh interface ipv6 show excludedportrange protocol=tcp
```

脚本报告排除范围或监听 PID，允许本项目运行中容器自身的 Docker/WSL 转发监听；
不会杀进程或修改系统设置。2026-10-08 本机排除范围覆盖 8088/8091，且无残留
Java/javaw 进程，因此本次映射改为上述高位端口。业务固定 8080–8086 当时也在
排除范围内；中间件启动成功不代表这些 IDEA 端口已经可绑定，启动业务前须另行
解决 Windows 的端口保留问题，并保持项目业务端口契约。

Sentinel Dashboard 的宿主入口为 `http://localhost:18858`，登录使用 `root/root`；
容器内仍监听 `8858`，Docker 网络内的业务服务使用 `sentinel-dashboard:8858`。
Gateway、Order、Stock 本地默认 Dashboard 地址为 `localhost:18858`，可用环境变量
`CLOUDMALL_SENTINEL_DASHBOARD` 覆盖完整 `host:port`。2026-10-10 重启 Docker Desktop 后，
8858 无进程监听却落入 Windows 排除范围 8773–8872，实际 start 报 forbidden，因此只调整
宿主映射和本地客户端默认地址。高位端口仍需启动前诊断，不保证系统重启后永久可用；
容器内端口、认证、健康检查和限流规则不变。已创建的容器必须重新创建才能更新映射：

```powershell
docker compose up -d --no-deps sentinel-dashboard
```

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

已有数据库在启动订单消费者前执行冲突证据增列迁移（可重复执行）：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_seckill_conflict_raw_payload.sql |
  docker exec -i cloud-mall-mysql mysql --default-character-set=utf8mb4 -uroot -proot cloudmall_order
```

先备份冲突表。迁移仅增加可空 `raw_payload`，不回填历史记录；订单初始化也会检查并补充该列，结构不兼容时启动失败。
取证优先读取非空 `raw_payload`（当前 Map 序列化后的完整文本）；历史 `NULL` 只能使用旧 `payload JSON` 视图，大数精度可能已经丢失。
回退代码时保留新列和已写证据，不删除证据数据。该文本不代表 RabbitMQ 原始消息字节，也不能恢复接收前已丢失的数值精度。

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

浏览器访问 <http://localhost:5173>；使用 Nginx 部署时访问 <http://localhost:18088>。

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

### CM-18 本地只读取证（16T1）

取证入口默认关闭：`cloudmall.pay.tcc-reconciliation.enabled=false`。确认已有 Pay
TCC 表和迁移结构后，可在需要检查的 Pay 实例显式开启此诊断开关；不需要开启资金
TCC entry、coordinator 或 recovery，也不会替你建表、重试交易或改写历史记录。

直接访问 Pay 的内部接口（固定本地端口 8086），不要通过 Gateway：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation
X-Internal-Service: cm18-reconciliation
X-Internal-Callback-Token: <该实例配置的内部 callback token>
```

仅接受 1–64 位 ASCII 字母、数字、下划线或连字符的支付号。关闭为 503，凭证/调用者
错误为 403，非法支付号为 400；已有本地事务或 XID 上下文为 409。支付不存在为 404，
排序规则别名/非唯一候选为 409；缺表或数据库不可用为固定 503，不泄露底层错误。
授权与参数校验先于连接/事务。网关会在查 Redis Token 前拒绝该路径及发现前缀/编码变体。

响应固定标记 `PAY_LOCAL_ONLY` 和 `automaticActionsAllowed=false`，包含上海采集时间、
白名单状态、错误存在性和 XID/branch/操作键/账本键的 SHA-256 指纹。它只读取 Pay 六类
本地来源，不查询 User、Order、Stock、TC 或 MQ；即使本地记录已 COMMITTED/ROLLEDBACK，
也只能报告外部证据尚未查询，不能据此再次扣款、退款、改决议或结束未知交易。

`BEGIN_UNKNOWN`/缺 XID 保留原事实；缺本地来源列出固定 reason code；载荷/模式/分支/Fence
矛盾按 LOCAL_CONFLICT 阻塞。指纹不能用于重放或推导原 XID。关闭诊断开关不改变任何
LEGACY/TCC 交易模式或恢复能力。后续仍需外部完整取证、人工处置和四服务发布/回退验收。

独立 MySQL 验证不启动 Pay 应用或 TC，使用自有随机数据库和最小 MyBatis/只读 RR 代理：

```powershell
mvn -pl backend/cloud-mall-pay -am test '-Dtest=PayTccReconciliationMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_pay?allowPublicKeyRetrieval=true'
```

### CM-18 Pay/User 只读取证（16T2）

另有默认关闭的 `cloudmall.pay.tcc-user-evidence.enabled=false`。同时开启 16T1 诊断开关后，
使用相同内部请求头直接调用 Pay 的 `GET /api/internal/payments/{payNo}/tcc-reconciliation/user-evidence`。
它先在 Pay 只读 RR 快照内取得可信原 XID 和两端分支，事务结束后复用 User 的现有 state GET，
最后重新读取 Pay；前后摘要或锚点变化返回 `LOCAL_EVIDENCE_CHANGED`。没有可信锚点时不查询 User。

User 必须已有其 TCC HTTP 查询入口；本工具不替你开启资金开关。其查询超时沿用 Feign 3s/5s，
只读 GET 最多额外重试一次，永不 POST。User 不可用、404、null body 为阻塞，未知状态、缺分支
或缺显式账本字段为缺证；原身份、金额、分支或明确相反终态为冲突，不修改任何资金记录。
Pay 已终态但 User 仍 TRYING 时也按缺证阻塞；GET 窗口隔离并恢复调用方用户上下文，
避免全局 Feign 拦截器追加其他用户身份或角色。

响应固定 `PAY_USER_READ_ONLY` 和 `automaticActionsAllowed=false`。Pay/User 一致的最高分类为
`BLOCKED_REMAINING_EVIDENCE`：Order、Stock、TC、MQ 仍未验证，User GET 也不包含账户余额/冻结
汇总，不能据此判定全链结束或授权扣款/退款。原 XID、分支、操作键、凭证和底层错误不会进入响应。

隔离验收先通过标准 Maven 构建 User classes，再用真实 User 只读链、Feign 和两个随机测试库验证；
只监听临时 loopback 端口，结束后关闭，原 Pay/User 库仅核对表清单/校验和：

```powershell
mvn -pl backend/cloud-mall-user,backend/cloud-mall-pay -am test '-Dtest=PayTccUserEvidenceMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_pay?allowPublicKeyRetrieval=true'
```

### CM-18 Pay/Order 基本事实只读取证（16T3）

同时开启 `cloudmall.pay.tcc-reconciliation.enabled` 和默认关闭的
`cloudmall.pay.tcc-order-evidence.enabled` 后，以16T1相同内部请求头直连 Pay：
`GET /api/internal/payments/{payNo}/tcc-reconciliation/order-evidence`。
该入口独立于 User 诊断开关：原 Pay RR anchor → 事务外既有 Order 详情 GET → 再次 Pay RR；
无可信原锚点不发请求，本地变化优先 `LOCAL_EVIDENCE_CHANGED`。Feign 用户头来自原 anchor，
调用方 AuthContext 在 GET 窗口隔离并在 finally 恢复；沿用 Order 3s/5s 超时和默认不重试。

只核对订单主行的订单号、用户、应付金额（BigDecimal）和四种合法状态。Order 现有 GET
不是 RR 完整快照，明细、地址、库存动作、当前 payNo 付款归属和退款均不构成本报告的证据。
不调用会间接查 Stock 的 paymentState，不发送 paid/cancel。包括 Order 已取消而 Pay 已提交，
只要基本身份与金额匹配，最高仍是 `BLOCKED_REMAINING_EVIDENCE`，不能据此判断库存或退款。
响应固定 `PAY_ORDER_BASIC_ONLY`、`automaticActionsAllowed=false`；User、Stock、TC、MQ 未查询。
404/403/5xx/null/error/数据库不可用按不可用阻塞，缺字段/未知状态按缺证，错身份/金额按冲突；
报告不回显错误订单身份、原资金标识、地址、明细或底层错误。

隔离验收先构建标准 Order classes/Boot jar，然后运行 Pay IT。仅为加载真实 Order 实现而从
其已有 Boot jar 读取固定栈的 Sentinel core 到 owned 临时文件，结束后关闭加载器并删除文件；
不新增生产依赖，不启动业务应用、TC、固定端口或调度器：

```powershell
mvn -pl backend/cloud-mall-order -am package -DskipTests
mvn -pl backend/cloud-mall-pay -am test '-Dtest=PayTccOrderEvidenceMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_pay?allowPublicKeyRetrieval=true'
```

### CM-18 普通库存预扣查询的终态判据（16T4）

现有 `GET /api/stock/reservations/{orderNo}` 的普通非空流水分支，只有全部已观察 SKU
完整同向确认、完整同向回滚或全部未结算，才分别报告 `CONFIRMED/ROLLED_BACK/RESERVED`。
部分结算、确认与回滚混合、非法/缺失/超量数量、容量溢出或请求状态与流水矛盾，均返回
`UNKNOWN` 和空明细；请求行不能覆盖不完整流水。合法数量使用精确整数验证，不截断或补零。
旧无库存动作订单据此保持待核验，不把部分 SKU 结清当成整单库存动作成功。

这是对已观察流水数量/方向与 request.status 的检查，不证明原订单完整 SKU 集合、请求
快照/哈希、库存余额或秒杀身份；仍需 CM-18 后续完整取证。GET 没有新增 RR/跨查询原子性，
并发读取不一致可保守返回 UNKNOWN，按原订单号重查。无流水的原 NOT_FOUND/request 分支、
秒杀、库存写状态机、Redis 和消息行为保持。查询不会自动修复历史流水或改库存数量。

隔离验证先构建标准 Order classes/Boot jar，然后用真实 Stock SQL 和实际 Order fallback
消费结果；Order 行/动作 Mapper 为替身，非 HTTP/TC/真实资金全链。只为加载 Order 已有的
Feign 类型，从其 Boot jar 提取依赖至临时文件，结束后删除，不新增生产依赖：

```powershell
mvn -pl backend/cloud-mall-order -am package -DskipTests
mvn -pl backend/cloud-mall-stock -am test '-Dtest=StockReservationTerminalProofMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_stock?allowPublicKeyRetrieval=true'
```

### CM-18 普通库存请求原载荷与重放校验（16T5）

普通非空流水存在请求行时，GET还核对原订单号、NORMAL场景、完整SKU/数量快照与hash。
原快照须为升序唯一的正SKU/正整数数量数组；缺/多/重复SKU、错量、坏hash或载荷不符，
均为UNKNOWN/空明细。独立JSON解析拒绝缺字段、额外字段、非原生整数、重复键与尾随JSON；
不改变全局ObjectMapper。无请求历史、无流水外层分支、秒杀和库存写状态机保持。

哈希由原StockLineDTO写入规则重建规范JSON再计算，兼容MySQL JSON空白/key顺序规范化，
不直接hash读取字符串。原始2.0若已被数据库规范成2，只能核验当前持久整数与规范hash；
已丢失的原token类型/重复键/前置语法不能重构。本校验也不证明原Order完整SKU、库存余额、
秒杀身份或跨SELECT原子性，仍需后续CM-18完整取证。

预扣重放只在读取结果确实为RESERVED时返回ALREADY_RESERVED。UNKNOWN、缺data、读取失败
或其他状态返回固定503/STOCK_RESERVATION_PROOF_UNAVAILABLE，避免把证据不足强制ACK；
不自动修复或改写历史数量/状态。上述StockReservationTerminalProofMySqlIT已增加实际JSON
规范化、原请求与流水完整对应、Order fallback及重放响应门回归，可沿同一命令运行。

### CM-18 普通流水原订单来源与原行校验（16T6）

普通库存GET的原数量汇总同时保留原订单号、不同字节订单身份数和非法原行计数。
只有原订单号精确等于查询号、byte identity唯一且非法计数为零，才继续16T4/16T5的数量、
完整SKU快照与hash判据。大小写/尾随空格/accent别名、同SKU或跨SKU混合订单标识，
负数被正数抵消、零数量、未知或非规范flow_type、遗留NULL原行均返回UNKNOWN/空明细；
异常来源也不能在重放中被ACK，继续使用固定503门。

WHERE保留原候选，异常行不会被过滤后伪装NOT_FOUND；单次只读SQL额外投影来源事实，
不改表/索引/写状态/其他Mapper数量语义。未知结果保留原流水供核查，未自动修历史。
本规则仍不证明库存余额、原Order完整SKU、秒杀或跨服务事务/MQ完成；不新增跨查询RR。
上述StockReservationTerminalProofMySqlIT已补实际SQL来源投影、异常旧数据、Order fallback
和重放回归，可沿同一隔离验证命令执行。

### CM-18 Pay直接Stock只读观察（16T7）

同时开启本地 `cloudmall.pay.tcc-reconciliation.enabled` 和独立默认关闭的
`cloudmall.pay.tcc-stock-evidence.enabled` 后，使用相同内部运维请求头直连 Pay：
`GET /api/internal/payments/{payNo}/tcc-reconciliation/stock-evidence`。
不依赖User/Order诊断开关。第一次Pay RR可信原锚点提供orderNo，事务结束后新GET-only Feign
查询现有Stock预扣GET，再次Pay RR核对；本地变化优先LOCAL_EVIDENCE_CHANGED。无可信锚点
不发Stock请求。GET隔离并恢复调用方AuthContext，不传callback/user/role/XID给Stock，
沿用3s/5s和默认不重试，没有写接口。

响应固定PAY_STOCK_OBSERVED_ONLY/automaticActionsAllowed=false，只输出白名单状态、
观察数组长度及完全校验后按SKU升序编码的向量指纹。原生文本订单号须匹配，item必须是原生
正Long/int且唯一、与aggregate状态一致；不强转字符串或截断小数。UNKNOWN/NOT_FOUND/
REJECTED/INIT、缺明细或坏值均缺证；重复/相反状态/错单冲突；HTTP/DB/解析不可用均阻塞。
最高BLOCKED_REMAINING_EVIDENCE；合法Stock ROLLED_BACK和Pay COMMITTED不据此猜退款。

报告不暴露原SKU明细、异常、XID、branch或凭证；User/Order/TC/MQ未查询。Stock现GET不是
跨表RR，本切片不证明完整Order SKU、scene/user/payNo归属、Stock余额/raw flow、跨库原子性
或全链资金终态。查询后Stock仍可能变化，完成CM18还需要完整统一取证和后续主项验收。

真实隔离验收先构建Stock标准classes/Boot jar，再使用owned Pay/Stock库和随机loopback HTTP、
实际Stock Controller/Mapper及Feign；仅从Stock Boot jar加载其已有Sentinel/Redis类型供测试反射，
不新增生产依赖、不连接Redis/Rabbit/TC，不启动业务App或固定端口。finally清理全部自有资源：

```powershell
mvn -pl backend/cloud-mall-stock -am package -DskipTests
mvn -pl backend/cloud-mall-pay -am test '-Dtest=PayTccStockEvidenceMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_pay?allowPublicKeyRetrieval=true'
```

### CM-18 Pay交付、退款与扣款前取消只读取证（16T8）

同时开启 `cloudmall.pay.tcc-reconciliation.enabled` 和默认关闭的
`cloudmall.pay.tcc-delivery-evidence.enabled`，使用16T1相同内部运维请求头直连Pay：
`GET /api/internal/payments/{payNo}/tcc-reconciliation/delivery-evidence`。
入口在连接前验证开关、凭证、调用者、支付号以及无本地事务/Seata上下文；Gateway禁止该入口，
响应 `Cache-Control: no-store`，客户端参数不能覆盖原支付归属。

原六来源capture与已有delivery/cancel纯SELECT共享一个本地只读RR事务；完整样本为一个连接、
八次SELECT。核验原payNo/userId/orderNo/金额、delivery原XID指纹与固定退款键、白名单状态、
Pay状态映射及取消与资金事实的互斥。已提交而缺delivery只报告缺证，不创建台账；BEGIN_UNKNOWN
不猜提交。合法COMMITTED后的REFUNDED不会误判，cancel已记录CONFLICT明确报告本地冲突。

输出固定 `PAY_LOCAL_DELIVERY_CANCEL_ONLY` 与 `automaticActionsAllowed=false`，包含原报告与
不可变台账摘要。XID/退款键只有SHA256；错误、租约owner和凭证不返回。重试次数、时间、租约
存在性仅供观察，不授予恢复权。原缺证保留；即使本地一致，最高仍为BLOCKED_EXTERNAL_EVIDENCE。
该入口不执行写入、锁、建表、Store、远端请求、TC或MQ，不代表CM-18资金主项闭环。

隔离真实MySQL验收只写随机自有测试库，前后核原Pay库表清单和校验和，结束删除自有库；
不启动应用、调度器或固定端口：

```powershell
mvn -pl backend/cloud-mall-pay -am test '-Dtest=PayTccDeliveryEvidenceMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_pay?allowPublicKeyRetrieval=true'
```

### CM-18 User账户、冻结总额与固定退款键只读观察（16T9）

独立默认关闭的 `cloudmall.user.tcc-reconciliation.enabled` 启用后，用内部运维请求头
`X-Internal-Service: cm18-reconciliation` 与已有 callback token 直连 User：
`GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}`。
不需要开启 TCC HTTP/RM 或资金写入开关；Gateway 禁止该入口。入口先验证开关、凭证、正用户ID、
ASCII支付号及无本地事务/Seata XID，再进入只读RR；成功和已知错误均禁止缓存。

原数据库receive提供原XID和键，现有UserTccStateQuery在同一个外层RR中校验原资金事实；
再读取账户资金部分投影、固定userId的TRYING金额聚合与 `refund:<payNo>` 账本。
完整样本单连接九次SELECT，账户查询不读取密码、电话或角色。聚合保留非法金额/状态原行计数，
合法原TRYING总额须等于账户frozen_balance；其他交易的非零冻结合法，不要求终态冻结为零。

固定退款键存在时核原身份、DEBIT证明和退款前后余额增量；合法部分退款按缺证观察，不补退。
固定键缺行不表示没有其他退款；报告显式 `otherRefundsNotInspected=true`。当前账户余额也
不必等于历史扣款或退款后的余额。报告仅输出不可变资金摘要、白名单状态和原标识SHA256，
不输出原XID/branch/操作键、敏感账户字段、凭证或异常。

响应固定 `USER_ACCOUNT_FROZEN_REFUND_OBSERVED_ONLY` 与 `automaticActionsAllowed=false`，
最高 `BLOCKED_EXTERNAL_EVIDENCE`；Pay/Order/Stock/TC/MQ未查询，不是全链资金终态。
本批不验证所有冻结行的attempt关联或全部退款键，也不代替统一证据包与CM-18主项验收。

隔离验收使用真实Mapper/RR和随机自有User测试库，原库仅核表清单/checksum；最终删除自有库，
不启动应用、RM/TC、调度器或端口：

```powershell
mvn -pl backend/cloud-mall-user -am test '-Dtest=UserTccReconciliationMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_user?allowPublicKeyRetrieval=true'
```

### CM-18 Pay消费User账户与固定退款观察（16T10）

开启Pay本地 `cloudmall.pay.tcc-reconciliation.enabled` 与独立默认关闭的
`cloudmall.pay.tcc-user-account-evidence.enabled`，使用相同内部运维凭证直连Pay：
`GET /api/internal/payments/{payNo}/tcc-reconciliation/user-account-evidence`。
User需要开启16T9的独立诊断开关，不需要开启资金HTTP/RM或其他Pay采集开关。

可信Pay原锚点 → 事务外GET-only User16T9报告 → 再Pay只读RR；无锚点不GET，Pay变化优先阻塞。
路径只用原userId/payNo，User认证头固定cm18-reconciliation与实例callback token；GET清理并在
finally恢复调用方AuthContext，不透传用户、角色或XID。沿3s/5s及默认不重试，永不POST。

保留User13个字段的原生JSON token，不把数字或字符串强转为布尔/身份/金额。先校验范围与
显式安全标志；User自报本地冲突/缺证直接保留阻塞且不解析回显坏财务字段。完整源报告才核原
身份、金额、四个标识指纹、状态/账本、账户冻结投影及固定退款身份/金额/余额增量。
部分退款或Pay已REFUNDED但固定全额退款缺证都阻塞；固定键缺行不表示没有其他退款。
合法COMMITTED后退款、其他交易冻结、当前余额异于历史退款后余额不被误判。

响应固定 `PAY_USER_ACCOUNT_OBSERVED_ONLY`、`automaticActionsAllowed=false`、
`otherRefundsNotInspected=true`；最高 `BLOCKED_REMAINING_EVIDENCE`。Order/Stock/TC/MQ
未查询，User查询后也可能变化，没有跨库原子快照；全部退款/所有冻结归属、统一包及主项验收
仍待后续。成功与已知错误no-store，Gateway禁止访问；不输出原User body/error/key/XID/凭证。

真实隔离验收使用标准User classes、owned Pay/User库及随机loopback HTTP/实际Feign，完整样本
Pay两次6SELECT加User9SELECT。finally关闭监听/执行器/加载器和删除两库，原库仅核checksum：

```powershell
mvn -pl backend/cloud-mall-user,backend/cloud-mall-pay -am test '-Dtest=PayTccUserAccountEvidenceMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_pay?allowPublicKeyRetrieval=true'
```

### CM-18 User原扣款关联退款候选观察（16T11）

同时开启User本地 `cloudmall.user.tcc-reconciliation.enabled` 与独立默认关闭的
`cloudmall.user.tcc-refund-evidence.enabled`，以相同运维凭证直连User：
`GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/refund-evidence`。
Gate先于连接，成功/已知错误均no-store，Gateway禁止；不接收客户端原操作键/XID/金额覆盖。

同一只读RR复用16T9基线，按原 `original_operation_key=payNo` 索引查CI候选，不预过滤坏行；
100条加一哨兵，超限不输出总额或指纹、不继续逐键查询。每行核原身份/类型/状态/金额/退款
余额增量，并用有界操作键点查询确认全局CI候选恰好一条；重复/别名、累计超原款均冲突。
合法多键部分退款保留，当前范围全部核验后可输出部分总额和指纹，仍不授权自动补退。

只有原固定键partial这一缺口可由完整关联候选全额证明补齐；其他原缺证/冲突保留。原金额
缺失/非法不查候选，不抛意外500。完整基线+N合法候选为9+1+N次只读SELECT、同一连接。
摘要固定 `USER_LINKED_REFUNDS_OBSERVED_ONLY`、`automaticActionsAllowed=false`、
`unlinkedLedgerRowsNotInspected=true`，仅当前原账本关联候选完整性，不是财务结清。
0候选不说明不存在错link/未关联退款；Pay/Order/Stock/TC/MQ与统一包仍待后续。

候选行和原键不输出；向量按键SHA256升序编码金额/余额增量字段后再次SHA256。原16T9报告
保留在baseEvidence中。隔离验收使用随机owned User库、实际RR/Mapper，最终清理，原库仅核checksum：

```powershell
mvn -pl backend/cloud-mall-user -am test '-Dtest=UserLinkedRefundEvidenceMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_user?allowPublicKeyRetrieval=true'
```

### CM-18 User当前TRYING冻结归属观察（16T12）

同时开启 `cloudmall.user.tcc-reconciliation.enabled` 与独立默认关闭的
`cloudmall.user.tcc-frozen-ownership-evidence.enabled`，以相同内部运维凭证直连User：
`GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/frozen-ownership-evidence`。
共享gate先于连接，成功/已知错误no-store，Gateway禁止，不接收HTTP覆盖原资金参数。

同一只读RR先读16T9基线，再按userId/TRYING现有索引读100加一哨兵候选。超过100不继续查peer，
不输出总额/指纹。逐行核原身份/op/XID/branch/状态/金额与before-afterTry增量，复用原
UserTccStateQuery核receive、TCC attempt、冻结行、无DEBIT及TRYING Fence；缺原请求为缺证，
已知持久冲突保留，DB错误仍固定503，不伪装为无冻结。

全部候选归属完成后合计须同时匹配账户frozen_balance与原TRYING总额。其他交易的非零冻结
可合法证明，不能用合计相等替代原资金归属。源其他缺证（包括退款partial）保留，历史终态行
和全账户财务因果未核验。完整基线加N peer为9+1+5N次SELECT、同一readOnly RR连接。

报告固定 `USER_TRYING_FROZEN_OWNERSHIP_OBSERVED_ONLY`、`automaticActionsAllowed=false`、
`terminalFrozenRowsNotInspected=true`；最高 `BLOCKED_EXTERNAL_EVIDENCE`。只输出核验合计与
按peer支付号指纹升序的归属/金额向量SHA256，其他原payNo/XID/branch/key/错误不返回。
0候选不说明全局无资金行为；Pay/Order/Stock/TC/MQ及统一证据包仍待后续。

隔离验收使用随机owned User库、真实原校验器/Mapper/RR，原库只核checksum，最终清理：

```powershell
mvn -pl backend/cloud-mall-user -am test '-Dtest=UserFrozenOwnershipEvidenceMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_user?allowPublicKeyRetrieval=true'
```

### CM-18 User原资金状态金额证据门（16T13）

原User TCC只读状态查询现在对receive、attempt、冻结和已确认DEBIT账本的金额逐一核验：
必须存在、为正、不超过DECIMAL(18,2)容量且能无损表示为两位小数。无损零尾数如`1.000`
仍合法；缺失、零、负数、超精度或超容量统一返回既有`USER_TCC_STATE_CONFLICT`/409，
不再因NULL变成500，也不以四来源数值相等替代合法性证明。

查询参数/JSON字段、原SELECT数量/readOnly RR及合法状态/Fence过期语义保持。
16T9已先核receive金额，本次补足其下游原amount比较门；16T11/12沿原409冲突链阻塞。
其他TRYING的NULL原receive现在由16T12报告明确持久冲突，未观察到receive仍为缺证。
诊断GET继续no-store，原资金state GET缓存契约保持；本批不改变任何资金写入或历史记录，
不证明历史余额增量、全账户因果、统一证据包或CM-18整体完成。

### CM-18 User原冻结与扣款余额快照门（16T14）

原User TCC状态查询进一步核验真实Try写入的`balanceBefore/balanceAfterTry`：两值必须存在、
非负且符合DECIMAL(18,2)，并满足`before-afterTry=amount`。CONFIRMED的DEBIT账本也必须
满足余额差额，且before/after分别数值等于原冻结快照；两组各自差额正确但快照不同仍冲突。
NULL、非法余额或不一致快照沿原`USER_TCC_STATE_CONFLICT`/409拒绝，不输出已扣款证明。

零余额合法（before=amount、after=0），无损尾零及终态Fence过期语义保留；空取消没有冻结行
时不要求虚构快照。合法历史账本不必等于当前账户余额。原读取数量/RR/API、资金写入及历史
数据保持；该校验只证明可见原冻结/扣款快照的一致性，不证明全账户资金因果或CM-18闭环。

### CM-18 User返回Fence的精确来源校验（16T15）

原User TCC状态查询现在逐项核对返回Fence的原XID和分支，再核官方action/status。
现有CI查询条件保留，返回的大小写/重音别名、NULL或其他分支不会充当原Fence证据，
统一沿原`USER_TCC_STATE_CONFLICT`/409拒绝；不以BINARY WHERE隐藏异常候选。

原库XID使用`utf8mb4_0900_ai_ci`（NO PAD）；尾空格候选只在自有测试库的旧PAD SPACE
比较规则下验收，不修改原库collation。只有实际返回的行核身份：TRYING缺Fence仍冲突，
终态缺Fence保留原过期兼容语义。原SELECT/readOnly RR、缓存禁用、API及资金写入保持。
原state GET缓存契约保持，三个诊断GET错误仍no-store；本批不证明TC决议或全链资金终态。

### CM-18 User原接收事实契约校验（16T16）

原User状态查询在后续资金查询前校验接收事实：`CLAIMED`、正用户ID、ASCII支付号，
`operationKey=tcc-debit:<payNo>`、`ledgerKey=payNo`，以及订单/XID范围和可空正分支。
损坏标记或多份相互一致的错误固定键沿原`USER_TCC_STATE_CONFLICT`/409拒绝，
不能以UNKNOWN或已知资金状态掩盖源契约损坏；坏接收仅执行原接收一条SELECT。

`CLAIMED + branchId=null`是尚未bind的正常事实，保留原UNKNOWN和已知状态观察。
T9已有接收保护与错误码保持，T12其他peer原接收标记异常按原持久冲突阻塞。
不改SQL/readOnly RR/API/flags、claim/bind或资金写，也不修改历史接收记录；
原state缓存契约和三个诊断no-store保持，不代表完整User资金因果或CM-18闭环。

### CM-18 User接收重放与分支绑定保护（16T17）

接收claim重放遇到旧NULL金额时返回原`IDEMPOTENCY_CONFLICT`/409，不再变成500；
不会继续派发资源或查询资金。正常唯一winner/replay和非null金额比较保持。
分支绑定拒绝非正branch，null原noop保留；原CI UPDATE后核readback精确payNo/XID/branch，
支付号别名冲突在原REQUIRES_NEW中回滚整行，包括branch和updated_at。

原INSERT IGNORE/UPDATE/SELECT FOR UPDATE及事务传播不变，非法分支无prepared SQL，
事务代理仍可能已创建连接。正常正分支首次/同分支幂等与错误rebind不覆盖保持；
不改变余额写入、RM/TC、flags/API或生产DDL，不自动修复历史接收记录。
隔离SQL验收使用owned单表验证实际可写事务、回滚与独立提交；原库只核checksum并清理owned，
不启动应用、RM或固定端口，也不代表CM-18资金主项闭环。

### CM-18 User资金入口缺失原金额保护（16T18）

本地TCC资金入口在比较原归属、冻结及已确认DEBIT账本的金额前拒绝NULL：
归属缺金额沿原`IDEMPOTENCY_CONFLICT`/409，冻结或账本缺金额沿原
`BALANCE_TCC_UNKNOWN`/409，不再在重放/清算时产生空指针500。
正常非null金额比较、请求校验、锁序、状态CAS和资金写步骤保持。

只阻塞缺失原金额，不补零、不修历史、不猜资金终态，也不新增金额全规则或余额快照校验。
真实资金验收复用owned事务库，验证坏事实后资金四表完整不变，并回归原23项金融/并发/
初始化/迁移场景；原库仅核checksum。默认flags/RM/API/DDL不变，不启动真实TC或业务端口，
不代表CM-18主项闭环。

### CM-18 User实际资金写前账户金额校验（16T19）

fresh Try取得新归属/冻结前、首次Confirm清算前及已有冻结Cancel释放前，校验当前
balance/frozenBalance存在、非负且可无损表示为DECIMAL(18,2)。NULL、负数、非法精度或
超容量沿原`BALANCE_TCC_UNKNOWN`/409阻塞，防止提交无效冻结或返还投影。

零余额/无损尾零合法；只在实际资金写点核验，已终态replay和空取消保持原无资金写协议。
不在统一用户行锁入口加门，不比较旧快照/冻结聚合，不补0、修历史或证明算术溢出。
原SQL/锁序/条件更新/API/flags/RM、disabled清算与余额不足响应保持；真实owned演练
验证坏账户后三路径四资金表完整不变，不代表全账户因果或CM-18主项闭环。

### CM-18 User原冻结到确认账本的来源校验（16T20）

原资金Service进一步核验冻结before/afterTry合法非负18.2且差额等于amount。
确认账本before/after也必须合法、差额正确，逐项数值等于原冻结快照；返回账本operationKey
必须精确匹配原ledgerKey，CI别名不能充当原已扣款证明。坏原事实沿
`BALANCE_TCC_UNKNOWN`/409拒绝，重放也不能把坏快照认作已成功。

不新增SQL或锁，不改变正常冻结/清算/释放、T19当前钱包写前门及空取消无冻结协议；
合法零/无损尾零和不同scale的数值等值保持。仅私有ledger校验传入原debit，不变更API/flags/RM
或生产DDL，不比较历史到当前余额、不修历史。owned事务验收四表完整不变及原回归保持，
不代表全账户资金因果或CM-18闭环。

### CM-18 User实际资金变更的派生金额边界（16T21）

T19单值合法后，fresh Try在原余额不足判断之后、claim前要求`frozen+amount<=MAX`；
首次Confirm要求`frozen>=amount`，已有冻结Cancel要求`frozen>=amount`与`balance+amount<=MAX`。
越界或不足沿原`BALANCE_TCC_UNKNOWN`/409拒绝，避免SQL越界或条件更新0变成500。
使用BigDecimal，不截断、不补0；等于MAX、frozen等于amount、零和无损尾零保持合法。

NoOp/replay/空取消不计算未发生的加法；disabled用户实际清算仍经过相同派生门。
原余额不足`PAYMENT_FAILED`优先、条件UPDATE/requireOne回滚、SQL/锁序/状态/RM/API/flags保持。
仅本地TCC路径，不涵盖Legacy/refund整体金额边界，也不证明全账户因果或CM-18闭环。

### CM-18 User已取消重放的账本冲突核验（16T22）

Try/Cancel重放已取消结果时，空取消attempt摘要和已释放debit摘要均先复用原`requireNoLedger`：
同支付号存在任意状态DEBIT，或固定`ledgerKey`已被账本占用，返回原`IDEMPOTENCY_CONFLICT`/409，
不将矛盾记录报告为已取消。保留原CI查询命中及短路，不隐藏别名或改写账本。

正常无目标账本的取消仍幂等，不增加当前钱包金额门；禁用用户/关闭Try开关仍可Cancel重放。
Try的开关及用户状态优先级、CONFIRMED和反方向冲突、首次空取消、SQL/锁/CAS/RM/Fence保持。
仅本地资金服务的取消摘要核验，不涵盖全部历史账本/退款归属、全账户因果或CM-18闭环。

### CM-18 User冻结重放与缺失状态保护（16T23）

Try已有`TRYING`时，返回原冻结摘要前同样复用`requireNoLedger`：同pay任意DEBIT或固定
`ledgerKey`占用沿原`IDEMPOTENCY_CONFLICT`/409阻塞；原`CANCELLED`门和`CONFIRMED`账本证明保持。
原SQL的CI候选不以大小写、重音或尾空格别名隐藏，不改查询、短路或账本。

`requireConsistent`显式拒绝NULL debit状态，沿原`BALANCE_TCC_UNKNOWN`/409，避免状态集合检查NPE。
当前生产列仍为NOT NULL；nullable遗留缺证只在owned库模拟。未知非NULL状态仍按原409拒绝，
不改状态机/DDL/RM/Fence/API/flags。合法TRYING无账本重放仍不写资金、不增加当前钱包金额门，
原开关/status优先保持。本切片不证明全账户因果或CM-18闭环。

### CM-18 User历史终态冻结归属只读核验（16T24）

独立开关`cloudmall.user.tcc-terminal-frozen-ownership-evidence.enabled=false`默认关闭；入口为
`GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/terminal-frozen-ownership-evidence`。
复用原User取证开关、内部凭证和noTx/noXID门；响应no-store，Gateway禁止公开访问。

原基线、CONFIRMED/CANCELLED的CI候选及逐笔receive/attempt/TCC/账本/Fence证明共用一个只读RR。
最多核100条，101哨兵立即阻塞。精确核状态、原tuple、金额/冻结快照；确认还核同pay原DEBIT复制关系，
取消不允许存在任意状态DEBIT。终态Fence已清理时保留原TTL语义。只有原基线与所有候选都通过，
才输出确认/取消行数及含精确状态的脱敏向量；不计算历史金额总额，不比较当前钱包，不执行资金动作。

最高仍为`BLOCKED_EXTERNAL_EVIDENCE`。候选完整仅指本次两known terminal CI范围，未知态、仅attempt空取消、
超限历史、全账户因果以及Pay/Order/Stock/TC/MQ仍未闭环；旧活跃TRYING报告和生产DDL/写状态机不变。

### CM-18 User同支付号账本只读核验（16T25）

独立开关`cloudmall.user.tcc-payment-ledger-evidence.enabled=false`默认关闭，入口为
`GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/payment-ledger-evidence`。
复用T9认证/local开关及T11只读代理，不要求旧退款入口flag；无事务/XID上下文门、no-store和Gateway隔离保持。

原关联范围与同pay全部类型/状态/原关联键候选在一个只读RR里核验。新候选最多100行，包含原DEBIT；
101立即缺证。逐行核精确身份、APPLIED、正金额/delta和全局操作键唯一性：原DEBIT金额单独等原金额，
仅REFUND金额求和并与原关联范围的退款数/合计交叉核对。NULL/错误原关联只报`PAYMENT_REFUND_ORIGIN_UNPROVEN`，
不猜原扣款、不自动补链或补退。完整范围才输出计数/退款合计/脱敏向量，合法partial仍保留缺证分类。

最高仍`BLOCKED_EXTERNAL_EVIDENCE`，不证明其它pay或全账户因果；旧T11嵌套报告保留原scope。
实际100行（1原扣款+99退款）为210纯SELECT同连接，101行不执行新范围逐key COUNT。
生产DDL/index/资金状态机/旧APIflags不变，CM-18其它证据及发布/真实故障验收仍待完成。

### CM-18 Pay消费User活跃冻结归属（16T26）

独立开关`cloudmall.pay.tcc-user-frozen-ownership-evidence.enabled=false`默认关闭，入口为
`GET /api/internal/payments/{payNo}/tcc-reconciliation/user-frozen-ownership-evidence`。
复用Pay取证共享门，通过原持久锚点userId/payNo GET既有User16T12入口；远端User的T9/T12启用门仍生效。

第一次Pay RR结束后隔离AuthContext并使用实例凭证GET，不透调用方用户/角色或XID。
无论GET成功/失败都再次Pay RR，完整本地摘要变化优先。外层proof保原生JSON类型，嵌套T9报告先复用原
身份/四指纹/状态/账本/账户/固定退款门，必须完整MATCHED；再核100上限、完整候选、金额与两个冻结投影相等、
SHA向量，空候选须零金额/空向量指纹，正候选须正金额。User冲突/部分退款/缺证/超限不抬高，不回显原body。

最高仍`BLOCKED_REMAINING_EVIDENCE`，禁止自动资金动作；历史终态冻结、其它退款和Order/Stock/TC/MQ仍未闭环。
实际Pay两次6SELECT/User一次10+5N SELECT，503不重试，原库/资金事实不改。原T10校验仅包内复用，旧合同保持。

### CM-18 Pay消费User终态冻结归属（16T27）

独立开关`cloudmall.pay.tcc-user-terminal-frozen-ownership-evidence.enabled=false`默认关闭，入口为
`GET /api/internal/payments/{payNo}/tcc-reconciliation/user-terminal-frozen-ownership-evidence`。
按原Pay锚点GET既有User16T24，远端T9/T24启用门仍生效。继承身份隔离、实例凭证、双Pay RR变化优先、
no-store/Gateway隔离；GET不可用也读第二次Pay，不重试或执行资金动作。

先复用原T10完整账户/身份/指纹/状态/固定退款门，再核User终态scope、原生元数据和0..100计数，
确认数+取消数必须等候选数，空范围必须空向量指纹，已CONFIRMED基线至少有一条确认行。
仅attempt空取消可为零候选，仍标记不在冻结行范围；终态Fence缺失保留原TTL语义。
完整匹配才给计数/脱敏指纹与账户摘要，不计算历史金额、不比当前钱包，不回显原响应或未核统计。

最高仍`BLOCKED_REMAINING_EVIDENCE`；未知态/空取消全覆盖、全账户因果、其它退款、外部系统和统一包仍未闭环。
实际Pay12SELECT，User10+6N单只读RR，100=610/101=10；旧T10/T26/资金接口及flags保持。

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

TCC `mock-fail` 和失败 callback 对未决资金执行返回 202，取消开关关闭时不改变全局决议。16E支持尚无资金执行的主动取消；16F支持已回滚且证明完整的失败收尾；16K支持完整双Try且尚未决议时请求回滚。16O/16P/16Q支持COMMITTED但尚未取得paid许可的严格取消，16R支持ORDER_SYNC_UNKNOWN下的取消待核验，均保留原lease。未知资金证据及售后取消仍未扩展，CM-18未整体闭环。callbackId 在业务处理前独立绑定支付号、用户、金额及方向；`RECEIVED_*`只表示收到，不能作为取消或退款已完成的证明。

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

COMMITTED但Pay仍PENDING/ORDER_SYNC_UNKNOWN时返回202；独立取消开关开启时按16O–16R的交付阶段持久接受严格取消或取消待核验。SUCCESS/REFUNDED的409和FAILED幂等保持不变，16H本身只校正退款中结果分类。

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

无XID或分支证据不足仍保持原边界；16K不反转COMMIT，已提交资金的有限取消窗口见16O。支付失败不替代订单/库存自身的生命周期处理。

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

### COMMITTED 后尚未取得订单支付许可时取消（第16轮O/16P/16Q）

复用默认关闭的 `cloudmall.pay.tcc-cancel.enabled`。只有Pay为TCC且PENDING/ORDER_SYNC_UNKNOWN、原资金COMMITTED证据匹配、无16E/Legacy冲突意图，并且delivery不存在或为READY且租约字段完整，失败请求才可持久写入 `CANCEL_REQUESTED`。owner/lease需同时为空，或非空白owner与到期时间同时存在；16Q允许原租约仍有效，但不修改它。尝试次数必须非负，部分租约字段继续拒绝。入口只返回202，不调用远程写接口、不写FAILED、不反转COMMIT。

取消请求与delivery claim、paid前状态更新共用Pay→delivery锁序。所有paid请求必须先提交ORDER_SYNC_UNKNOWN，该状态须按16R独立核验，不能当作严格取消。取消先于READY写许可提交时，旧worker不能再授权paid；它仍可释放自己的租约，或在已证实订单取消/库存成功时同向退款。新worker必须等待原租约释放或过期才能认领，不承诺立即退款。双端CONFIRMED及原扣款账本核验、Order CANCELLED和库存SUCCEEDED全部满足后，才沿原REFUND_PENDING→REFUNDED及固定 `refund:<payNo>` 清算。

查询、取消或库存结果未知时保留CANCEL_REQUESTED重试；订单已支付或库存冲突时保留冲突供核验，不paid、不猜退款。持久取消不因关闭取消开关而失效，已启用的delivery恢复器仍继续清算。回调保留RECEIVED_FAILURE，退款完成后失败回调重放仍按原约定409，不伪造FAILED。

16P/16Q接受改向时保留尝试次数、旧owner和原到期时间，只把next_retry_at按LEAST提前；已接受取消的重放不修改元数据。Java和SQL使用同一次毫秒精度时间更新。

无表结构迁移；新增状态记录应使用支持16O及以上的Pay版本恢复。该功能只覆盖未取得paid许可的窗口，不能替代完整售后退款。数据库锁/CAS验收（独立库，不注册TC分支）：

```powershell
mvn -pl backend/cloud-mall-pay -am test '-Dtest=PayTccCommittedCancelMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_pay?allowPublicKeyRetrieval=true'
```

### 订单支付结果未知时请求取消（第16轮R）

取消开关开启且原资金COMMITTED证明完整时，delivery的ORDER_SYNC_UNKNOWN可持久转为 `CANCEL_RECONCILING`，返回202只表示受理，不保证取消胜出。原paid请求可能仍在途；新worker不再发paid，待支付订单只尝试cancel，再读取订单与库存最终结果。

Order CANCELLED且库存回滚成功后使用原唯一退款键结清；Order PAID/COMPLETED且库存确认成功、没有退款事实时转为SUCCESS，表示原支付先赢。查询未知、库存未完成/冲突或资金证据不足时保留待核验状态，不能猜退款。严格CANCEL_REQUESTED的PAID冲突语义保持不变。

租约/attempts/原COMMIT保持不变，关闭取消开关后已有意图仍由delivery恢复。callback保持RECEIVED_FAILURE，最终SUCCESS/REFUNDED的失败回调仍返回既有409。该能力不包含已完成支付的售后退款；新状态须由支持16R的Pay实例恢复。

### execution 恢复完成时的租约校验（第16轮S）

原资金恢复和回滚结果投影共用的finishRecovery，只有支付号/token精确匹配且租约仍有效时才能清除租约、更新退避时间。过期但尚未被接手的旧执行者也不能延后重试；新执行者仍可按原规则认领。时间由Store按上海毫秒精度采样，四参数调用接口保持不变。

这只保护恢复元数据，不改变execution资金状态、决议或XID。缺少原XID的BEGIN_UNKNOWN仍保留待核验，不能通过重新begin/Try或猜回滚处理。

## 订单路由模式审计（CM-20 第17轮A）

订单默认使用 `cloudmall.order.routing.mode=dynamic-monthly`：由现有OrderSqlMapper按物理月表读写。当前Sharding-JDBC没有接管主链路；激活 `sharding` profile（含默认profile）或配置未支持的routing mode会以 `ORDER_ROUTING_UNSUPPORTED` 在数据源/建表初始化前失败。保留默认模式即可沿用当前行为。

启动时还会核对选定DataSource、JdbcTemplate与本地事务管理器是否引用同一对象，并拒绝实际Sharding数据源链。合法的同源Seata代理允许使用；数据源绑定不一致会在订单DDL前失败。

现有 `/actuator/info` 增加 `orderRouting`：只包含模式、清洗后的profile、数据源/事务管理器简单类型和上海时区的 `expectedMonths`。月份表示上月、本月及未来两个月的建表目标，不是表存在性证明；审计不会查询订单数据，也不输出连接URL、账号或密码。

本阶段未切换SQL、事务或ShardingDataSource，CM-20仍待后续影子路由与迁移验证。真实启动验收先构建Jar，再用隔离库/Redis/Rabbit vhost执行：

```powershell
mvn -pl backend/cloud-mall-order -am package -DskipTests
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderRoutingAuditIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

### 月分片算法隔离验证（CM-20 第17轮B）

月分片算法已补齐精确与有界范围路由。第17轮B修正了原 YAML 算法类名，第17轮C进一步移除静态分片声明，改由隔离规则工厂使用算法对象。只接受上海本地语义的 `LocalDateTime` 或 JDBC 已物化的 `Timestamp`，时间精度必须符合 `DATETIME(3)`；目标表必须属于当前逻辑表。范围按毫秒处理开闭边界，缺少任一所需月份或输入无界时明确失败。

Sharding-JDBC 4.1.1 不接受空路由集合；空区间直接抛出 `ORDER_SHARD_ROUTE_EMPTY`，未来查询适配层再评审如何转换为空页。当前没有业务接口接入该算法；缺少分片条件的 SQL 也需在后续接入层约束，不能仅靠算法阻止框架广播。

隔离测试通过程序式 Sharding-JDBC 4.1.1 数据源读取随机 `cm17b_*` 库，与直接物理表查询对照；测试库中的表和种子由夹具创建，结束后删除。执行前启动 MySQL，测试固定 JVM/JDBC/session 上海时间语义：

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=MonthlyOrderShardingAlgorithmTest,OrderRoutingAuditTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl backend/cloud-mall-order -am test '-Dtest=MonthlyOrderShardingMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

本轮仍保留第17轮A的启动限制：默认订单链路使用动态物理月表，`sharding` profile 不可启用。滚动节点发布、跨月分页、恢复查询与 Seata 事务组合仍待后续评审和验证。

### 节点清单与规则快照（CM-20 第17轮C）

`OrderShardingRuleFactory.create(currentMonth, physicalTables)` 根据明确的上海业务月份和三类已观测月表清单，生成 Sharding-JDBC 4.1.1 规则。三表月份集合必须相同、历史连续，并覆盖建表器同一规则计算的上月、本月及未来两个月；任何缺口返回 `ORDER_SHARD_INVENTORY_INVALID`。节点包含完整历史，不只保留四个月。

工厂是普通 Java 工具，不查询数据库、不创建数据源或物理表。它输出的是独立规则快照，不热更新已有数据源，也不能保证观测后的表一直存在。显式重新观测、校验并创建隔离数据源，才能路由新月份；实际业务发布和事务接入仍待评审。原 YAML 静态节点及数据源声明已移除，`sharding` profile 标记保留并继续被启动审计拒绝。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderShardingRuleFactoryTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl backend/cloud-mall-order -am test '-Dtest=MonthlyOrderShardingInventoryMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

集成测试只在自建 `cm17c_*` 库中操作表和种子，验证跨年窗口、旧规则与新规则、历史保留、缺伴表/缺月和观测后删表；结束后清理隔离库并核对原订单库 checksum。

### 订单列表查询时间精度（第17轮D）

`GET /api/orders` 继续使用 `[startTime,endTime)`，支持带不同 offset 的 ISO-8601 时间。查询先转换到项目约定的上海偏移，再将两端向上对齐至 `DATETIME(3)` 毫秒；SQL 参数和月份选择共用这一窗口，避免纳秒边界因 JDBC 精度变化漏单或多查。

原始起止时间不递增、格式错误或端点超出数据库年份范围返回400。合法纳秒区间若不包含任何可存储毫秒，返回 `total=0,items=[]`，不查询月表；非空区间缺表仍整体返回503。缺省起止时间从同一次时钟采样计算，订单写入和支付/库存时间逻辑保持原样。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderQueryWindowTest,OrderListFailureTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderQueryPrecisionMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

真实 MySQL 验证使用自建 `cm17d_*` 库，结束后清理；默认订单仍使用动态物理月表。

### 月表模板引导（第17轮E）

订单启动和定时建表优先使用 `mall_order_item_template`、`order_status_log_template`。正式模板存在时不依赖 `202608` 历史表；仅在模板缺失时，才从对应的 `mall_order_item_202608`、`order_status_log_202608` 兼容创建。

两种来源都缺失或同名对象不是物理表时，明确返回 `ORDER_TEMPLATE_UNAVAILABLE` 并中止当次维护。数据库访问异常直接传播；不会猜测其他月份或重造结构。主表继续使用原 `mall_order_template`，上月、本月及未来两个月的窗口不变。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderTemplateMapperTest,OrderTableInitializerTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderTableInitializerMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

测试只在自建 `cm17e_*` 库执行 DDL，核对跨年幂等、结构/数据保留后清理。真实启动回归 `OrderRoutingAuditIT` 还覆盖正式模板完整但两个历史来源已移除的场景。

### 订单创建时间精度（第17轮F）

普通订单和秒杀落单在写入前共用 `OrderCreationTime.normalize`：将创建时刻按同一瞬时转换为 `+08:00`，再向下截断到毫秒。普通订单用此值生成订单号月份和表名，订单、明细、幂等记录、初始预扣意图和超时 outbox 的创建字段沿用同一值。秒杀仍保留消息中的原订单号与幂等信息。

该处理避免月末亚毫秒值被 MySQL 舍入到下一月，以及 UTC 等事件时间被错误当作上海本地字段。查询边界仍使用第17轮D的向上对齐规则，保持半开区间语义。第17轮G继续修复 Stock 首次认领和投递事件时间；已经发出的历史缺时间消息及无法核验的旧记录仍需后续处理。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderCreationTimeTest,SeckillAddressPropagationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderCreationPrecisionMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

创建集成测试使用自建 `cm17f_*` 库、真实 Order Service/Mapper 和 Spring 事务代理，仅外部 Feign 服务使用替身；核对持久化时间、消息幂等、非法消息回滚和原库 checksum，结束后清理。

### 秒杀事件的持久创建时间（第17轮G）

Stock 新请求在生成候选订单号之前只采样一次上海毫秒时间，共用于订单号月份、首次认领时间和 `request_payload.occurredAt`。同一幂等键重试保留首次认领的原号、载荷和时间；outbox 行的接受、租约和重试调度时间仍按实际操作时间更新。

投递器从受理记录核对订单号、事件号、键、状态、用户/活动/SKU、数量、地址指纹及创建月份，再填写 envelope 时间。显式 `occurredAt` 必须与持久事实一致；旧载荷仅缺该字段时可以在发送 envelope 中补入，不改历史 JSON。缺证据或发生冲突时保留原事件并记录 `SECKILL_EVENT_TIME_UNVERIFIED`、按原机制退避，不用投递时的当前时间猜测。

```powershell
mvn -pl backend/cloud-mall-stock -am test '-Dtest=SeckillEventTimeResolverTest,SeckillReliabilityTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl backend/cloud-mall-stock -am test '-Dtest=SeckillEventTimeMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_stock?allowPublicKeyRetrieval=true'
```

测试使用自建 `cm17g_*` 库中的真实认领、outbox 和调度逻辑，Rabbit/Redis 为替身，验证 NACK/ACK 后的状态及重复 envelope；不等同于真实消息代理的网络故障验收。结束后清理隔离库并核对原 Stock 库 checksum。

### 秒杀消费与恢复的时间校验（第17轮H）

Order 首次消费秒杀事件必须有显式 ISO-8601 `occurredAt`，经上海毫秒规范化后，其月份必须与原订单号前缀一致。缺失、非法或错月事件以 `ORDER_SECKILL_TIME_UNVERIFIED` 失败，事务回滚，沿原重试/DLQ 路径保留；校验通过前不调用 Product，也不写订单、明细或超时事件。

已有同用户/键、同 hash/orderNo 且已提交的重复事件仍先走原幂等分支，不因历史消息缺时间再次落单或报错。恢复任务在 Stock 验真和 Rabbit 重发前复用时间校验；无效时间进入 `QUARANTINED`、按原五分钟退避并释放租约，保留持久化 payload 和原键，不用当前时间补值。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderCreationTimeTest,SeckillMessageRecoveryTest,SeckillMessageRecoveryAckTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderCreationPrecisionMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

集成测试沿用 `cm17f_*` 隔离库，覆盖创建、回滚、重复事件、DLQ 持久化及恢复隔离；Rabbit/Feign 为替身。该防护不会重建历史错月订单或自动补写异常消息的时间。

### 死信 JSON 幂等比较（第17轮I）

同一事件重复落入恢复台账时，`request_hash` 仍按字符串精确比较，payload 则使用 MySQL 原生 JSON 值比较。对象键顺序、空白和等价数字表示不会再触发伪冲突；数字与字符串、数组顺序、JSON null 与缺字段等真实差异仍标记 `CONFLICT`，并保留首载荷。

等价重复直接返回，不修改已有状态、尝试次数、租约、退避或时间字段。非法 JSON 和数据库错误仍沿原保存失败路径处理。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=SeckillRecoveryJsonMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

测试在自建 `cm17i_*` 库验证 JSON 比较矩阵、并发重复和连续死信处理；确认两次 ACK 前都有持久记录，结束后清理。Rabbit Channel 使用替身，不等同于真实网络故障验收。

### 恢复完成写的租约保护（第17轮J）

恢复任务写入重放或验真结果时，必须仍持有尚未到期的租约、owner 字节精确匹配，且记录处于原可恢复状态。Mapper 每次完成调用重新采样上海毫秒时间，用同一次采样更新 `updated_at` 并检查 `lease_until > now`。过期、到期边界、空租约、被新 owner 接管或已为 `CONFLICT/RESOLVED` 时，不修改任何字段。

验真目标状态仅允许原有 `QUARANTINED/VERIFYING/CONFLICT`。迟到 worker 不能将已确认的 DLQ 冲突重新改成 `REPLAYED`；有效 owner 仍按原流程完成、释放自己的租约和安排重试。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=SeckillRecoveryLeaseMySqlIT,SeckillRecoveryJsonMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

新测试使用自建 `cm17j_*` 库验证边界、owner 接管、冲突竞态和整行保持，结束后清理。第17轮J仅保护完成写，发送前检查及批次时钟由第17轮K继续处理；未引入续期，也不保证撤回已经发出的消息。

### 恢复发送前租约检查与逐条时钟（第17轮K）

恢复扫描仍限定每批50条，但每条记录认领时重新采样上海毫秒时间，授予本次认领起算的30秒租约。验真、确认或失败后的退避计划也从实际处理时刻计算。租约等于当前毫秒时可再次认领，与第17轮J的严格有效期条件一致。

Stock 验真之前和 Rabbit 发送紧前均只读检查精确 owner、有效期及可恢复状态；数据库查询返回后再检查是否已跨过截止时间。失效或数据库读取异常时停止本次处理，不发消息、不写完成，也不清理他人的租约。后置检查失败时，已经发生的 Stock 只读查询无法撤回。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=SeckillRecoveryDispatchMySqlIT,SeckillRecoveryLeaseMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

测试使用 `cm17k_*` 自建库、可变时钟和 Stock/Rabbit 替身验证慢调用、接管、终态变化、查询异常及重试时间。检查与网络发送不是原子操作，已开始发送的消息不能撤回；原幂等机制及完成写 CAS 继续负责后续收敛。第17轮K不自动续租，尝试次数由第17轮L继续修复。

### 恢复认领后的最新快照与计数（第17轮L）

扫描结果只用于找到候选 ID。认领成功后，恢复器按当前 owner 和有效租约读取最新持久快照，再进行验真、发送和尝试次数计算，避免其他 worker 已完成后仍使用旧计数。快照查询失败或返回时租约已失效，直接跳过，不回退到扫描旧值。

计数仍在有效完成时回写，claim 不递增。`attempts+1` 使用 long 计算并在 `Integer.MAX_VALUE` 饱和；负数或非法计数保留事实，不猜测归零、不外发。完成 SQL 额外阻止计数降低；退避仍采用原 `2^min(n,8)` 规则，实际最大间隔256秒。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=SeckillRecoveryDispatchMySqlIT,SeckillRecoveryLeaseMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

扩展测试覆盖真实数据库中的扫描/完成交错、计数溢出与回退；继续验证读取异常、到期和发送守卫。Stock/Rabbit 为替身，本轮未新增自动续租或网络原子性保证。

### 已提交消费结果的身份核验与恢复收尾（第17轮M）

首次成功落单或确认同一业务已 COMMITTED 的重复消费，按原 eventId、orderNo、userId、idempotencyKey 和请求指纹核验恢复台账。仅完整匹配的 `REPLAY_PENDING/REPLAYED/QUARANTINED/VERIFYING` 可转为 `RESOLVED` 并清理租约；原 payload、次数、退避和错误记录保持不变。消费者的已提交事实不依赖 recovery worker 租约，旧 worker 的迟到完成仍受完成写 CAS 保护。

eventId 和业务字符串按字节匹配，防止数据库大小写或尾空格等价比较误命中。JSON 用户 ID 必须是与 Java long 值相同的整数（包括 MySQL `UNSIGNED INTEGER`），其他身份字段必须为字符串；合法64位十六进制指纹兼容大小写。历史字符串型用户 ID、字段缺失或身份冲突不自动修复，`CONFLICT/RESOLVED` 及未知状态保持原样。

```powershell
mvn -pl backend/cloud-mall-order -am test '-Dtest=OrderCreationPrecisionMySqlIT,SeckillRecoveryDispatchMySqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai' '-Dcloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:13307/cloudmall_order?allowPublicKeyRetrieval=true'
```

测试沿用隔离 `cm17f_*` / `cm17k_*` 库，覆盖真实 consumer 事务、两条成功路径、身份/JSON 类型差异、终态保护与迟到完成；核对原库校验和后清理。Feign 和 Rabbit 为替身，不代表跨服务网络故障验收。

### 死信持久化时间与恢复扫描一致（第17轮N）

死信入库复用恢复器的上海（`+08:00`）毫秒时钟，单次采样用于创建、更新和首次重试时间，避免 JVM 默认时区造成调度偏移或亚毫秒舍入跨月。该值是接收/调度时间，事件的 `occurredAt` 仍按原规则独立核验。

使用上述 `SeckillRecoveryJsonMySqlIT` 命令可验证 UTC/GMT+14 默认时区、月末毫秒边界、合法/非法及旧格式死信、冲突更新和等价重复保持。测试使用隔离 MySQL 库及 Channel 替身，不自动修正历史时间，也不改变 ACK/nack、租约或消息拓扑。

### 库存验真证据暂缺时保持可重试（第17轮O）

恢复器先核对原事件，再区分 Stock 响应：未知/缺失状态或已受理但核心字段、指纹缺失时进入 `VERIFYING`，30秒后重新查询；`NOT_FOUND/REJECTED` 仍隔离5分钟。只有完整事实与原事件确实不一致才进入 `CONFLICT`，完整匹配且处于 `ACCEPTED/PUBLISHED/ORDER_CREATED` 才允许重发。

沿用 `SeckillRecoveryDispatchMySqlIT` / `SeckillRecoveryLeaseMySqlIT` 命令验证“证据暂缺→补齐后重放”、明确冲突、原事件无效及租约保护。Stock/Rabbit为替身，真实MySQL台账保留原载荷；不会自动重分类历史 `CONFLICT`。

### 死信保存拒绝静默截断（第17轮P）

死信 Mapper 在写入前核验 eventId、请求指纹、状态的列宽及必需值，按 Unicode 字符数计数，避免误拒绝合法非 BMP 字符。保存改用普通 `INSERT`，仅唯一键重复进入原 JSON/hash 幂等核验；字段或数据库错误直接交给既有重投流程，不再由 `INSERT IGNORE` 降为警告后确认成功。

`SeckillRecoveryJsonMySqlIT` 覆盖严格/非严格 SQL 模式、超长及 null 输入、主键碰撞、Unicode 列宽边界、同事件并发和原字节重投。只有重投得到 publisher confirm 后才 ACK 原消息，否则请求重新投递；消息通道为替身。列宽、历史数据和消息拓扑保持不变。

### 死信重复保存精确核验事件号（第17轮Q）

数据库唯一键可能忽略 eventId 的大小写、重音或尾空格。重复保存先读取原行并精确核对事件号；不同身份不得返回幂等成功或修改原记录，而是保留原消息进入既有重投流程。只有完全相同的事件号才继续 JSON/hash 比较，真实载荷冲突按原主键和二进制事件号条件更新。

`SeckillRecoveryJsonMySqlIT` 覆盖三类排序规则别名、原行完整保持及重投确认后 ACK。现有唯一键保持不变，别名事件仍可能无法同时入库，需后续核验或单独评审迁移；不会自动合并、改号或修复历史记录。Rabbit 为替身。

### 死信确认异常不再触发重复发布（第17轮R）

持久化失败才进入延迟重投，重投失败才请求一次 requeue NACK；持久化或重投确认成功后只尝试一次 ACK。ACK 异常保留原始原因向容器抛出，不再被当作数据库或重投失败，也不追加 ACK/NACK/发布操作。

`SeckillMessageRecoveryAckTest` 注入 Channel 异常验证调用次数、顺序和异常原因，`SeckillRecoveryJsonMySqlIT` 回归真实入库及重投路径。结果未知仍依赖容器/连接恢复和原消息幂等；这些替身测试不代表真实 broker 断连恢复或 exactly-once 保证。

### 恢复线程保留中断并停止当前批次（第17轮S）

恢复扫描入口及每条认领前检查线程中断。等待 Rabbit 确认被中断时，恢复中断标志并结束本批，不写 `REPLAYED`、不继续认领，原租约自然到期；已发送消息仍可能到达，继续依赖原幂等机制。死信延迟重投的确认等待中断时，先尝试原有的一次 requeue NACK，再在 finally 恢复中断标志，NACK 抛错也不丢失停机信号。

`SeckillRecoveryDispatchMySqlIT` 和 `SeckillMessageRecoveryAckTest` 覆盖预中断、两条任务之间中断、确认等待中断及 NACK 失败。检查与网络发送不是原子操作，本轮不提供全链路取消或消息撤回；Rabbit/Channel 使用替身。

### 非法 UTF-8 死信保留原始字节（第17轮T）

死信入口使用严格 UTF-8 解码，非法字节不再被替换后参与 JSON 解析或生成可重放事件。此类消息按原字节 SHA-256 生成 `legacy-seckill:bytes:` 身份，以 `rawPayloadBase64/rawEncoding/reason` JSON 证据保存为 `CONFLICT`，成功持久化后才按既有流程确认；同字节重复保持原行，不同字节分别保存。

`SeckillRecoveryJsonMySqlIT` 验证 Base64 逐字节还原、重复保持、非法字节嵌入 JSON 时隔离，以及合法 U+FFFD、中文、emoji 兼容。正常 UTF-8 和旧格式解析规则保持不变；历史已丢失字节无法自动还原，消息通道仍使用替身验证。

### 死信 JSON 拒绝重复字段和尾随内容（第17轮U）

严格 UTF-8 解码后，死信入口使用局部严格 JSON reader：拒绝根/嵌套重复字段，以及根对象之后的第二个 JSON 值或垃圾内容。解析失败按完整原文生成指纹，将 `rawPayload` 原文保存为 `CONFLICT`，避免后值覆盖或忽略尾随内容后生成可重放事件。

`SeckillRecoveryJsonMySqlIT` 覆盖重复字段、尾随对象/数组/标量/null/垃圾、非对象 JSON、完整原文和重复保持。合法首尾空白、Unicode 与旧格式仍按原流程处理；不修改共享 ObjectMapper/Factory、其他消费入口或历史记录。

### 业务指纹失败不能由存证哈希替代（第17轮V）

恢复器仅把活动、SKU、用户和地址均可按既有规则计算出的值视为业务指纹。缺失、非正数、非整数、溢出或非法地址导致计算失败时，死信进入 `CONFLICT`；原文 SHA-256 仍可用于存证，但不能用于通过库存验真或发送前自校验。已带 suppliedHash 的消息保留该值作为原证据。

`SeckillRecoveryJsonMySqlIT` 和 `SeckillRecoveryDispatchMySqlIT` 覆盖非法维度、坏地址、空文本哈希绕过及合法数值字符串兼容。合法业务哈希算法、原消息字段和外部 API 不变；历史错误哈希不批量修复，Stock/Rabbit 为替身验证。

### 地址快照的共享指纹严格校验（第17轮W）

`SeckillRequestFingerprint.canonicalAddress` 使用局部严格 reader，拒绝地址字符串中的重复字段、尾随 JSON/垃圾以及非对象值。合法地址的对象排序、十进制规范化、数组顺序和 v1 SHA-256 输出保持不变。Stock 在认领/Redis/受理前返回原参数错误；Order 消费沿原异常事务退出，恢复记录沿原业务指纹失败路径隔离。

`SeckillRequestFingerprintTest` 固定合法输出并覆盖非法地址；`SeckillEventTimeMySqlIT`、`OrderCreationPrecisionMySqlIT` 及恢复 JSON/Dispatch IT 验证业务边界。修改 common 后需重新构建并重启依赖服务才生效；本轮完成构建和隔离验证，未重启现有业务进程，也不迁移历史歧义地址或指纹。

### 地址 Unicode 字符完整性（第17轮X）

地址规范化递归校验字段名和字符串值中的 UTF-16 代理对，拒绝孤立高/低代理字符，避免转成 UTF-8 时被问号替换而产生相同业务指纹。合法 emoji、中文、U+FFFD、问号及等价转义保持原值和 v1 哈希；不做 Unicode 归一化。

沿用 common 指纹测试及 Stock/Order 地址 MySQL 回归验证：非法字符在 Stock 认领前拒绝，Order 不落单、恢复不重放并保留证据。现有业务进程未重启，新构建需部署并重启后生效；历史地址和指纹不自动修复。

### 恢复批次隔离单条数据库失败（第17轮Y）

候选记录的认领或状态写发生 `DataAccessException` 时，只记录该条失败并继续后续候选，保留原事实和可能存在的租约。发送/确认与完成写分开，`markReplayed` 最多尝试一次；验真状态写失败也不再追加一次 `VERIFYING` 写入。扫描查询失败仍向外传播，中断仍停止批次。

`SeckillRecoveryDispatchMySqlIT` 在自有 MySQL 库以触发器注入首行三类更新失败，验证第二行可继续、失败行未被猜测修复、无重复完成写。原50条上限、退避和租约规则保持；这不保证数据库整体不可用时后续记录成功，消息/Feign仍为替身。

### 恢复发送前核对台账与消息体身份（第17轮Z）

原时间校验通过后，恢复器在查询 Stock 前核对台账与消息体：eventId 必须为非空字符串且精确相同，双方 requestHash 必须为字符串并与实际业务指纹一致（兼容合法十六进制大小写）。缺失、类型错误或不一致进入原 `CONFLICT`/5分钟退避，保留原事件号、哈希和载荷，不猜值修补。

`SeckillRecoveryDispatchMySqlIT` 覆盖元数据大小写/尾空格/不同事件号、缺失或数值型内部身份、哈希冲突及合法旧格式重放。无需 Stock 提供 eventId，原 legacy 兼容与租约保护保持；历史异常记录不批量改写，Stock/Rabbit 为替身。

### Stock marker 宽限期支持真实 DATETIME 类型（第17轮AA）

缺少 Redis marker 时，宽限期判断现在支持 MySQL 返回的 `LocalDateTime`，并将其及 `Timestamp` 的本地字段按项目固定 `+08:00` 解释；`OffsetDateTime` 保留同一瞬时。只有严格超过30秒才沿原逻辑转 `UNKNOWN`，恰好30秒、未来时间或无法识别的时间保持原状态。

`SeckillEventTimeMySqlIT` 验证真实 DATETIME、边界和零受理/outbox；`SeckillOutboxRecoveryRaceTest` 覆盖 UTC/GMT+14 默认时区及三种时间类型。Redis/Rabbit为替身。扫描排序、UNKNOWN 后续处理和 outbox 租约未变，UNKNOWN 长期占窗口的问题仍需单独核验。

### Stock marker 恢复使用有界循环扫描（第17轮AB）

marker 候选按主键递增扫描，实例内游标在成功读取一批后前移；后段为空时，同轮最多回绕一次首段。每轮仍不超过 `batch-size`，短批次不补满，查询失败不前移。旧 `UNKNOWN` 不再因时间戳不变而固定占满窗口，后续 marker 到达时也能在回绕后重新检查。

`SeckillEventTimeMySqlIT` 覆盖旧前缀、后续 PENDING、UNKNOWN 再检查、低主键插入、游标记录删除、短批次和查询/受理异常。扫描不改业务时间、状态、payload/hash，受理调用仍沿原逻辑。游标不持久化，重启从头；不保证频繁重启、无限流入或多实例下的全局公平，Redis/受理调用/Rabbit 为替身。

### Redis marker 读取故障不阻断已提交消息（第17轮AC）

单条 marker GET 的 Redis 数据访问异常或既有 `REDIS_SERVICE_UNAVAILABLE`/503 只跳过该条。读取失败不等于 marker 缺失，不触发 UNKNOWN、受理或配额操作；同批后续记录以及已提交 MySQL outbox 的原投递流程仍可继续。其他业务异常、程序错误和扫描 SQL 异常保持传播，不新增重试或重复熔断计数。

`SeckillEventTimeMySqlIT` 验证原生 Redis 异常、guard 503 和全部 marker 读取失败时，失败行保持不变、已提交 outbox 仍可发布；`SeckillOutboxRecoveryRaceTest` 验证异常分类边界。Redis/受理/Rabbit 为替身，不代表共享 Redis 断连或真实网络恢复验收。

### Stock outbox 完成写校验租约（第17轮AD）

成功发布和失败退避的完成写均要求：事件号与 owner 字节精确匹配、租约非空且尚未到期、源状态为 `PENDING/UNKNOWN/DEAD`。过期、恰好到期、owner 被替换或已结束时返回0，不清理他人的租约，也不覆盖已发布事实。

调用方仍即时提供完成时间，Mapper 将其按同一瞬时转为固定 `+08:00` 并截断至毫秒，共用于完成时间和租约判断；退避计划保持原参数。`SeckillEventTimeMySqlIT` 验证身份/状态/时间边界和迟到失败保护。本轮仅保护完成写，未改变认领、发送前检查或批次时钟，也不保证 SQL 等锁期间的墙钟变化被再次核验。

### Stock outbox 逐条时间与发送前租约检查（第17轮AE）

outbox 扫描只用扫描时刻选候选，每条认领重新采样上海毫秒时间并取得30秒租约。读取受理事实前、Rabbit 发送紧前分别只读核验精确 owner、有效租约及可重试状态；查询返回后再次检查截止时间。失效或查询异常时直接跳过，不发布、不写失败、不清租约。认领在租约恰好到期时即可接手。

`SeckillEventTimeMySqlIT` 验证慢首行、接管/终态、查询异常及跨截止返回、精确到期接手和在途发送后的完成写保护。outbox 使用独立时钟，marker 宽限期不变；失败仍退避10秒。不自动续租，检查与网络发送不是原子操作，已发消息不能撤回，Rabbit 使用替身。

### Stock outbox 重试计数边界（第17轮AF）

认领仍原子递增 `attempts`，达到 `Integer.MAX_VALUE` 后保持饱和，避免 SQL 越界中断批次。负计数在扫描的 LIMIT 前排除，认领、发送前租约检查和完成写也拒绝负值；保留原记录和租约，不自动归零或修复。

`SeckillEventTimeMySqlIT` 验证最大值反复认领、负值不占正常候选窗口及零更新，以及首条最大计数 NACK 后仍能处理下一条。正常计数时点、DEAD 重试和10秒退避保持不变；历史负值需独立核验，Rabbit 为替身。

### Stock outbox 数据库失败不再误判为发送失败（第17轮AG）

每条 outbox 的认领、受理事实读取及状态写发生 `DataAccessException` 时，保留当前事实和租约并继续后续候选。扫描级失败仍传播。发送/confirm 与完成写分开：成功后只尝试一次 `PUBLISHED` 写入，其数据库异常不会再触发失败回写；发送失败的原退避写也只调用一次。

`SeckillEventTimeMySqlIT` 用隔离库触发器验证认领、发布完成和失败回写异常，另验证事实读取失败及异常分类。claim/完成写的其他程序错误保持传播；原状态、计数、租约和10秒退避不变。数据库整体不可用时不保证后续成功，Redis/受理/Rabbit为替身。

### marker 转 UNKNOWN 的数据库故障隔离（第17轮AH）

缺 marker 且超过宽限期时，单次 `markSeckillUnknown` 数据库异常仅记录并跳过该条，不重试写入、不猜测是否提交，也不转而受理。后续 marker 和独立 outbox 继续处理；返回0时仍执行原竞争事实核验，非数据库错误和扫描异常保持传播。

`SeckillEventTimeMySqlIT` 验证触发器拒绝更新、实际更新提交后注入响应丢失两类场景，失败记录保持数据库真实事实，已提交 outbox 仍能发布。Redis/受理/Rabbit 为替身，未新增补偿或真实网络故障演练。

### Stock 恢复任务响应线程中断（第17轮AI）

调度入口、marker/outbox 阶段切换及认领、marker 结果处理、租约查询和完成写前检查中断。发布确认等待被中断时恢复中断标志并停止本批，不写失败或完成状态。已推进游标、已提交 marker 状态、认领次数和租约保持原事实，后续按原机制恢复。

`SeckillEventTimeMySqlIT` 验证预中断零扫描、marker 读取/提交、claim、租约查询和 confirm 的中断边界；`SeckillOutboxRecoveryRaceTest` 验证中断后不追加受理竞争查询。Redis/Rabbit 为替身，不保证全链路取消或撤回在途消息，也不从异常 cause 猜测中断。

### Stock payload 已有身份字段必须匹配（第17轮AJ）

outbox payload 若已包含 `eventId/requestHash`，投递前必须核验：事件号为非空字符串且与台账精确一致，请求指纹为非空字符串且与已验证的台账指纹一致（允许合法十六进制大小写差异）。null、类型错误或冲突不能被 envelope 覆盖后掩盖，沿原失败退避保留原载荷，不发布。

新旧 payload 完全缺失这两个字段时，仍由原 envelope 逻辑补全，不回写存储。`SeckillEventTimeResolverTest` 和 `SeckillEventTimeMySqlIT` 覆盖显式冲突、合法字段与缺失兼容；eventType/version 的既有行为不变，Rabbit为替身，历史数据不自动修复。

### Stock 请求认领精确核对幂等键（第17轮AK）

认领事务锁定用户幂等记录后，必须核对返回键与原始输入精确相等。大小写、重音或尾空格差异不能被 MySQL 排序规则当作同一请求；不一致返回已有 `SECKILL_IDEMPOTENCY_CONFLICT`/409，在 Redis/受理之前退出，保留原订单和载荷。

`SeckillEventTimeMySqlIT` 验证三类别名冲突、精确重试、不同用户独立，以及 metadata 缺失后的重试调用边界。现有唯一索引未迁移，别名键仍不能保证共存，不自动改键或迁移历史记录；Redis 为替身。

### 秒杀认领严格插入与幂等键长度（第17轮AL）

Order 与 Stock 秒杀入口按 Unicode code point 数限制幂等键最多128个字符，空白或超长输入在地址查询、认领及 Redis 操作前返回原参数错误400。Stock 认领 Mapper 同时校验订单号/键/指纹的列宽，普通 INSERT 仅对真正 DuplicateKey 返回重复结果，其他 SQL 错误传播，避免 INSERT IGNORE 静默截断首次事实。

`SeckillEventTimeMySqlIT` 验证严格/非严格 SQL 模式、128 emoji 键、nullable 历史列、精确重放与并发首事实；`SeckillSentinelFeignMappingTest` 验证公开入口提前拒绝与合法键原样传递。原列结构、历史数据及错误响应转换不变；本轮完成构建和隔离验证，现有服务尚未重启。

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

该测试在测试 JVM 中启动实际 User Spring Boot RM，通过网络连接真实 TC，使用独立 `cm15_tcc_it_*` 数据库。TC 默认地址为 `127.0.0.1:18091`，可用 `cloudmall.seata.test.tc` 覆盖。只有资金及 TC 事务均结束才清理数据库；若失败后保留测试库，应先恢复该测试事务，再清理，避免丢失仍在重试的分支事实。此测试不等同于进程中断或 Pay 全链路验收。

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

前者在关闭 Seata 的随机 `cm16b3_scan_*` 库验证14项SQL、租约竞争、过期完成拒绝和旧数据迁移；后者用真实Pay/User/TC验证5项自动提交/取消、NONE只读、未知begin不占窗口，以及Pay重启后的定时恢复，日志位于 `target/cm16b3-automatic/`。恢复claim/RPC/release各窗口的独立进程中断没有逐一演练；token过期与迟到完成保护已有确定性MySQL验证。

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

16E至16R的取消验收使用相同隔离夹具，覆盖响应丢失、库存延迟、首次支付竞争、独立Pay进程接手、租约/原子写入失败、原NONE决议竞争、READY严格取消，以及ORDER_SYNC_UNKNOWN下paid/cancel两种胜出结果和唯一退款：

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


### CM-18 Pay消费User关联退款集合（16T28）

独立开关`cloudmall.pay.tcc-user-linked-refund-evidence.enabled=false`默认关闭，入口为
`GET /api/internal/payments/{payNo}/tcc-reconciliation/user-linked-refund-evidence`。
消费既有User16T11 `refund-evidence`，保持可信原Pay锚点、事务外GET/AuthContext隔离和双Pay RR变更优先。
原生范围/标志/计数/金额/向量与原账户全部门通过才给有限摘要；正数候选必须合计原金额。
完整全额集合仅解除原固定退款部分覆盖缺口及REFUNDED无固定键缺口；固定退款身份、金额、账本、
四hash、状态与账户投影继续核验，旧T10/T26/T27行为保持。空集合不能证明Pay已退款。
范围`PAY_USER_LINKED_REFUNDS_OBSERVED_ONLY`，明确未关联账本未检查、全账户因果未证明、禁止自动动作。
最高`BLOCKED_REMAINING_EVIDENCE`；source partial/缺证/冲突/超限不输出账户或新集合统计。
不补退、不补链、不改资金事实。T25同pay未关联退款、全历史/统一证据包及M2–M4继续保留。


### CM-18 Pay消费User同支付号账本（16T29）

独立开关`cloudmall.pay.tcc-user-payment-ledger-evidence.enabled=false`默认关闭，入口为
`GET /api/internal/payments/{payNo}/tcc-reconciliation/user-payment-ledger-evidence`。
消费User16T25 `payment-ledger-evidence`，复用T28完整关联退款/原账户证明，保可信anchor、事务外GET、
AuthContext隔离及双Pay RR本地变化优先；不依赖其它Pay诊断flag或远端UserT11入口flag。
原生scope/标志/limit100、三计数、退款合计与向量均核验；同pay范围包含原DEBIT，CONFIRMED恰好一条，
退款数/合计与已核关联集合一致，取消/冻结无账本为空范围。partial、错/缺origin、冲突、超限及嵌套缺证
不输出账户/关联或账本统计。范围`PAY_USER_PAYMENT_LEDGER_OBSERVED_ONLY`、其它pay未检、全账户因果未证明，
最高`BLOCKED_REMAINING_EVIDENCE`，不补链、不补退、不改资金。统一证据包及CM18其它M1–M4仍待完成。


### CM-18 Order基本证据原生字段保护（16T30）

既有Pay `order-evidence`独立默认关闭入口改用GET-only `OrderBasicEvidenceClient`，保原JSON token，
不改变业务`OrderClient`、远端公开详情API或全局Jackson规则。用户必须原生正整数long；订单号/状态
必须原生文本；金额必须原生、有限十进制文本且无损18.2，拒绝字符串/小数ID和number金额的隐式强转。
`10.0000`等尾零数值等值及四状态（含CANCELLED+PayCOMMITTED）仍可基本匹配；合法字段矛盾仍冲突。
非法字段对应指纹null、最高剩余缺证，scope仍`PAY_ORDER_BASIC_ONLY`；双Pay RR/身份隔离/唯一GET保持。
不扩充SKU/库存动作或资金终态证明，不新增写/自动动作，CM18其它M1–M4仍保留。


### CM-18 秒杀库存GET原订单归属保护（16T31）

秒杀settlement及legacy受理查询仍沿原`WHERE order_no=?`返回CI候选；GET以原行orderNo精确核请求身份，
不把大小写/重音/尾空格别名、错单或缺身份包装为当前单的RESERVED/CONFIRMED/ROLLED_BACK。
三个只读分支（主settlement、主legacy ACCEPTED、Stock fallback legacy）身份失败均返回请求订单号、
`UNKNOWN/items=[]`，不伪装missing、不落普通路径或使Order旧fallback误判SUCCEEDED。
精确合法记录的原状态/数量保留，非ACCEPTED主legacy仍沿原null、fallback REJECTED/PENDING原映射保持。
不新增SQL/BINARY过滤/事务，原GET仍非RR；不改settle/迁移/恢复/CAS/Redis/MQ/DDL或API。
完整秒杀原tuple、余额、Order SKU/动作、TC/MQ/统一包及CM18其它M1–M4仍待完成。


### CM-18 秒杀结算写前原订单归属保护（16T32）

确认/回滚沿原INSERT OPEN→SELECT FOR UPDATE锁序；锁定行必须以原orderNo精确绑定动作请求，
在维度/操作键/终态幂等和CAS前拒绝CI别名、错单、缺身份，返回原`SECKILL_SETTLEMENT_CONFLICT`/409。
既有REQUIRED事务保证临时错误OPEN INSERT随异常完整回滚，原ACCEPTED及普通库存事实不动，
错误身份不能走终态noop成功或普通库存路径。精确原订单的状态、revision、操作键及重试语义保持。
不新增SQL/查询/BINARY过滤/锁/事务，不改GET16T31、迁移、Redis/MQ/恢复或API。
完整settlement tuple/其它维度、Order SKU/动作、TC/MQ/统一包及CM18其它M1–M4仍待完成。


### CM-18 旧秒杀SKU无损读取保护（16T33）

旧受理台账GET只接受整数/DECIMAL数学整数且为正long，拒绝`longValue()`小数截断、大数回绕和不可信
Float/Double、文本、缺失、零/负值。主legacy ACCEPTED保原`SECKILL_SETTLEMENT_CORRUPT`/500；
fallback坏SKU返回`UNKNOWN/items=[]`，不把非法REJECTED明细映成Order冲突或落入普通路径。
T31原订单身份仍先核，合法BigDecimal尾零、Long.MAX_VALUE及原状态/数量保持。
正常生产列仍signed BIGINT；小数/大数/浮点仅owned遗留列验证，不修改生产DDL或猜恢复已丢精度。
无新增SQL/事务/写，T32写门、settlement/迁移/恢复/Redis/MQ和普通流程保持，CM18其余M1–M4保留。


### CM-18 秒杀终态原操作键保护（16T34）

秒杀结算只有原订单/既有维度和原操作键均通过，才能将CONFIRMED/RELEASED作为完成证据。
原键必须精确为原orderNo加`:CONFIRM`/`:ROLLBACK`，拒绝缺失、别单、反方向及大小写/重音/尾空格别名。
GET缺证返回`UNKNOWN/items=[]`、不落普通库存或使Order fallback误报完成；写前在noop/CAS前返回
原`SECKILL_SETTLEMENT_CORRUPT`/500，消息不回显原键，既有REQUIRED事务完整回滚。
合法终态同向幂等、反向409保持，原身份与维度错误优先；OPEN/pending/blocked/其它状态不新增规则。
无SQL/DDL/锁序/事务/Redis/MQ/恢复/API变更；完整tuple、其它状态原操作键及CM18其余M1–M4仍待完成。


### CM-18 秒杀非终态原操作键保护（16T35）

在16T34两终态校验基础上，OPEN只允许NULL或原订单的exact CONFIRM/ROLLBACK键，
RELEASE_PENDING只允许exact ROLLBACK，PROJECTION_BLOCKED保持两种exact方向的既有兼容。
拒绝大小写/重音/尾空格等别名通过MySQL CI比较进入CAS；GET缺证为`UNKNOWN/items=[]`，
write在原identity/维度门后、派生/noop/CAS前沿原CORRUPT500固定消息拒绝。
合法OPEN跨向仍由原CAS不命中返回503，pending/blocked与终态的合法协议保持。
SQL/DDL/锁序/事务/恢复/Redis/MQ/API不变；未知/CONFLICT状态、完整tuple及CM18其余M1–M4保留。


### CM-18 秒杀结算原受理幂等键保护（16T36）

结算原idempotency_key沿受理入口范围校验：拒绝NULL、精确字面值`null`、blank和超过128 Unicode
code points；不trim/casefold，合法包围空格、`NULL`、128 ASCII/非BMP字符保持原字段。
GET/write沿既有维度`SECKILL_SETTLEMENT_CORRUPT`/500固定消息，原订单身份仍先核，
操作键与状态/幂等/CAS在维度门之后，坏事实不落普通库存或参与状态写。
正常VARCHAR128/NOTNULL不改；缺失/超限仅unit验证，真实SQL只写可保存的blank/literal null和合法边界。
SQL/DDL/锁序/事务/Redis/MQ/恢复/legacy/API不变；完整tuple/原来源及CM18其余M1–M4仍保留。


### CM-18 秒杀恢复候选事实保护（16T37）

独立释放恢复入口在claim/租约owner/Redis key/Lua前核原候选：原orderNo有效且不超过64 code points、
状态精确pending/blocked、原orderNo:ROLLBACK、revision2/quantity1、活动/SKU/用户正long和T36幂等键范围。
坏候选返回false，零claim/Redis/状态写；合法blocked-confirm继续跳过，健康rollback沿原Lua/租约/回执重试。
真实MySQL与Redis Lua覆盖损坏维度、大小写/外单原键及释放回执重放；保原用户计数不减少。
SQL/DDL/Lua/批50/租约/GET/settle/API不改；claim后TOCTOU、CAS readback及完整tuple/CM18其余M1–M4保留。


### CM-18 秒杀释放CAS读回证明（16T38）

markReleased未命中时，只有读回exact RELEASED、同一行ID/原始tuple/操作键、非NULL原createdAt，
且redisRevision匹配本次Lua回执，才能计为handled。大小写/重音/尾空格别名或错误原字段均不能借终态标签成功。
缺证沿原CAS_UNKNOWN/retry原候选，读回坏终态不修补、不重复Lua；markReleased1仍直接成功。
忽略updatedAt/lease/lastError等可变诊断字段，允许合法writer已完成同一释放的读回。
SQL/DDL/Lua/租约/批50/GET/settle/API不变；扫描窗口、claim后TOCTOU及完整source/CM18其余M1–M4保留。


### CM-18 秒杀恢复有界环形扫描（16T39）

恢复器保单轮最多50行与同一now，用进程内id游标跨轮推进；坏候选、blocked-confirm、租约失败也推进。
首段沿原findDue，后段findDueAfter只新增id>cursor，后段不足时以剩余预算回首段并避免重复。
查询异常保进度，空/不可靠id回首段；游标CAS避免慢轮覆盖并发进度，不表示释放成功，不持久化或输出API。
坏行不延期/repair，原T37候选、T38读回、claim/Lua/receipt/lease保持；DDL/索引/原SQL/状态机不改。
有限稳定候选跨轮覆盖，重启从首段；持续无限新增/频繁重启/多实例不承诺强公平，claim后TOCTOU等仍保留。


### CM-18 秒杀恢复claim后事实与租约保护（16T40）

claim成功后、Lua前沿原findSettlement点读当前行，核同一immutable原行/tuple/创建时间、仍为合法rollback、
租约owner精确本次owner及leaseUntil晚于点读后的fresh now；已观察漂移/失租/过期/null返回false。
拒绝路径不Lua/mark/retry或repair，查询异常仍原catch/retry且不Lua；允许pending/blocked及诊断字段变化。
T38 CAS读回仍独立，T39扫描预算不包括新点读；SQL/DDL/事务/Lua/API和其它协议保持。
只覆盖本次观察到的claim后漂移，不声称读回到Lua之间窗口已消失或实现跨SQL/Redis fencing/fullsource证明。


### CM-18 秒杀恢复状态写身份/有效租约保护（16T41）

markReleased/markProjectionBlocked/retry保原revision/候选SQL与SET，只追加BINARY精确order/op/owner/status，
且leaseUntil非NULL晚于数据库语句NOW(3)。别名身份、过期/缺失lease返回0，不标记或清除失效租约。
健康pending/blocked写入与原影响行数0处理保持；T40点读、T38 CAS证明、T39扫描及Lua不改。
真实Lua后lease漂移覆盖release/block/丢响应retry，已执行Redis变化如实保留，不能假装跨库回滚。
本门未绑定全部immutable tuple，不是跨SQLRedis原子/完整fencing；readback→Lua等窗口仍待后续处理。


### CM-18 秒杀恢复状态写原快照绑定（16T42）

三类恢复UPDATE唯一追加原扫描PO快照，除T41身份/有效lease之外，SQL绑定原id、活动/SKU/用户、
幂等键、quantity、revision、createdAt及精确order/op；任何字段漂移、缺snapshot或scalar矛盾均affected0。
正常/异常retry始终传原candidate，不从readback重建参数，无缺快照旧签名旁路；原SET/可变诊断保持。
不新增查询/事务/DDL/公开API，T37–41/Lua/扫描保持；Redis已执行效果不伪回滚，完整来源与跨库窗口仍保留。


### CM-18 秒杀释放终态持久回执保护（16T43）

RELEASED 在原订单、维度和操作键核验后，要求持久 redisRevision 属于原释放 Lua 的 1..1000000000000 回执范围。
缺失或越界时 GET 返回 UNKNOWN/空明细，写前返回固定 CORRUPT500；合法回执保留回滚重放及反向409。
其它状态不新增回执限制，不增加 Redis GET 或改动 SQL/事务；本门不证明原 APPLIED 来源或跨存储原子性。


### CM-18 秒杀恢复领取的原受理来源绑定（16T44）

claim 在原租约/版本谓词上追加同一 SQL 的受理来源 EXISTS，核精确原订单和活动/SKU/用户/幂等键。
保留原 ACCEPTED/PUBLISHED/ORDER_CREATED 三种受理态；缺失或错来源不领取租约、不执行释放 Lua。
原 SET/参数、恢复点读/回执/扫描协议保持，无新增查询/DDL/API；关联读取可能改变 InnoDB 锁足迹。
本门只核领取时当前同 tuple 来源，不证明请求/订单全因果、原 APPLIED 来源或关闭 claim 后跨存储窗口。


### CM-18 秒杀状态变更的原受理来源绑定（16T45）

OPEN确认、OPEN待释放、blocked确认三个CAS在同一UPDATE中核原订单/活动/SKU/用户/幂等受理来源，
允许原ACCEPTED/PUBLISHED/ORDER_CREATED三态；缺失或错来源影响行数0，沿原UNKNOWN503，不改事实。
原SET/参数、INSERT→FOR UPDATE事务顺序、终态noop及claim/Lua保持；无额外查询/DDL/API。
关联读取可能改变InnoDB锁足迹；GET/noop来源、请求/订单完整因果及跨存储窗口仍待处理。


### CM-18 秒杀终态查询的受理来源观察（16T46）

CONFIRMED/RELEASED GET通过原身份/维度/操作键/回执门后，再按原快照只读匹配受理订单/活动/SKU/用户/幂等键，
允许原三受理态；缺证返回UNKNOWN/空明细并阻断普通fallback，查询异常保留，不修事实。
合法终态查询由1次增至2次，两个独立点读不构成RR/原子来源证明；其它GET、legacy、settle/noop及恢复保持。
终态写noop来源、请求/订单完整因果、APPLIED及跨存储窗口仍待处理。


### CM-18 秒杀终态同向写重试的受理来源观察（16T47）

CONFIRMED+CONFIRM、RELEASED+ROLLBACK在原proof通过后复用只读来源查询，缺/错来源沿原UNKNOWN503，
来源查询异常保留；合法三受理态仍幂等成功。同向noop原2变3SQL，反向409/2SQL及其它路径保持。
不新增表/查询方法/锁/事务或Redis操作；同事务非锁source观察不证明源冻结、完整因果或跨存储fencing。


### CM-18 秒杀非终态查询的受理来源观察（16T48）

OPEN/RELEASE_PENDING/PROJECTION_BLOCKED也在原proof后复用受理来源只读查询，缺/错来源返回UNKNOWN/空明细，
合法来源保原RESERVED或UNKNOWN及原明细；这三态由1次增至2次SELECT，终态规则保持。
不改源SQL/写入/恢复/legacy/未识别状态；两个非锁点读不证明RR、来源冻结、完整因果或跨存储fencing。


### CM-18 历史秒杀GET的原上下文维度（16T49）

legacy两条只读路径在原order/SKU核验后检查activity/user无损正long与原生合法幂等键，保整数DECIMAL/long上界与Unicode128边界。
main ACCEPTED损坏上下文返回固定CORRUPT500，outer各状态损坏返回UNKNOWN/空明细；原SKU与身份错误优先级保持。
共享纯字段validator不改Map/SQL/DDL/事务/Redis；此门只核基本上下文，不证明原请求/hash/订单完整因果。


### CM-18 APPLIED释放重放的受理marker校验（16T50）

释放Lua在APPLIED early return前核当前accepted marker为string且精确原order，缺失/错类型/别单/别名返回0，
恢复沿原投影阻塞处理，不改Redis内容或造完成；合法witness保持正回执与无二次INCR，允许其它projection已丢失。
当前受理marker无TTL保留至审计归档，witness丢失/过期/归档后保留缺证；不自动补marker，不证明完整APPLIED tuple或跨存储fencing。


### CM-18 已存在待释放重试的受理来源观察（16T51）

RELEASE_PENDING/rollback方向PROJECTION_BLOCKED同向重试在原方向proof后复用source getter，缺/错来源沿原UNKNOWN503，
来源查询故障保留；合法来源继续原pending/false/UNKNOWN响应，不改租约诊断。同向2变3SQL，反向与初次CAS保持。
不新增SQL/DDL/API字段/锁/事务或Redis操作；该非锁来源观察不证明源冻结、完整因果或跨存储fencing。


### CM-18 秒杀恢复后续写入的受理来源约束（16T52）

markReleased/markProjectionBlocked/retry保原lease/不可变snapshot谓词及SET，追加同语句精确受理tuple/合法三态EXISTS。
claim/Lua后source删/变时UPDATE0不清租约、不造完成或改诊断；Java恢复、参数/查询/API/DDL/Lua保持。
若Redis已实际释放而后SQL拒绝，其效果保留，不伪回滚；关联读取锁足迹可能改变，跨存储原子/fencing及完整来源仍待处理。

### CM-18 恢复CAS完成读回的受理来源观察（16T53）

markReleased未命中后，先核同一不可变结算行和本次Lua回执，再复查原受理tuple的合法三态来源，来源存在才计为完成。
来源缺失沿原未知重试，查询故障沿原异常路径；普通CAS成功不增加查询，坏读回先短路，SQL/Lua/协议保持。
这是非锁两点观察，已发生Redis效果保留，不证明源冻结、完整受理因果或跨存储fencing。

### CM-18 秒杀恢复Lua前的受理来源观察（16T54）

claim后原行/租约证明通过，恢复器在Lua前复查原受理tuple来源；缺来源直接停止，不释放Redis配额、不清租约或补偿写。
来源查询故障保留原重试路径；合法来源继续原释放流程，CAS完成读回仍独立复核来源。
SQL/Lua/接口协议保持；查询后变化、查询耗时后的租约窗口和跨存储fencing仍待处理。

### CM-18 秒杀恢复Lua前的租约到期复核（16T55）

受理来源查询成功后，恢复器再次采样上海时间；原租约恰好到期或已过期时停止，不调用Lua或补偿写，也不清租约。
正常未到期流程保持，无新增SQL或接口字段。私有Clock默认使用系统瞬时并转换上海时间，便于确定性验证查询耗时。
这一步不关闭最后检查到Lua的窗口，不证明数据库与应用时钟同步或跨存储fencing。

### CM-18 未识别结算状态的空明细查询（16T56）

结算GET只对五个协议状态核来源并输出原明细；CONFLICT、未知或错误拼写/空白状态返回UNKNOWN和空明细。
未知状态仍是非null候选，不查受理来源，不落普通库存，也不进行GET迁移；原身份/维度/键/回执校验优先级保持。
合法五状态、历史受理查询及写协议保持，Order fallback仍UNKNOWN，不将旧路径描述为已经错误完成。

### CM-18 历史受理未知状态的空明细查询（16T57）

无结算行的历史兼容查询仅保留ACCEPTED/REJECTED原状态与明细；PENDING等其它状态返回UNKNOWN和空明细。
原身份、SKU和上下文校验优先，未知结果不落普通库存；主查询、SQL及写状态保持，不扩展PUBLISHED/ORDER_CREATED映射。
此门只收窄未证明的明细输出，不证明原请求/hash/订单完整因果，Order fallback继续保持UNKNOWN。

### CM-18 启动补建结算行的精确受理状态（16T58）

启动恢复补建OPEN时，在原ACCEPTED候选条件后核字节级原状态，大小写、重音和尾空格别名不再创建结算行。
精确ACCEPTED的字段、时间、默认值及幂等行为保持；PUBLISHED/ORDER_CREATED不扩展补建，既有错误行不自动删除。
两个启动入口复用同一Mapper条件；不证明原完整tuple/请求/hash因果，也不改变其它读写或Redis流程。

### CM-18 启动补建的原正数身份维度（16T59）

启动补建OPEN除精确ACCEPTED外，还要求原活动、SKU和用户的现行BIGINT值均大于0；零值/负值来源不再创建结算行。
合法1至Long.MAX的原字段、默认值、时间与幂等行为保持；不修补已有行，不改变其它读写或扩大历史DECIMAL/FLOAT迁移语义。
该门不证明幂等键、原请求/hash或完整Order因果。

### CM-18 启动补建的原幂等上下文（16T60）

启动补建OPEN时原幂等键须非空、非精确字面量null、非Java17全空白且长度1–128个码点，避免补建后才发现上下文损坏。
SQL判定空白与既有Java校验对齐，保留非空白字符、NULL/null加空格、包围空格及Unicode边界的原键，不trim或改写。
仅补建条件收紧，不删改旧行；原字段/时间/幂等/其它SQL、请求/hash/Order完整因果及Redis流程保持原范围。

### CM-18 启动补建的原订单号基本域（16T61）

启动补建OPEN时订单号须非空、非Java全空白、非精确字面量null且长度1–64个码点，避免创建恢复器无法处理或被截断的身份。
沿现有通用订单号规则检查，不强加年月格式、不trim/casefold，保留合法原字节；原CI候选/唯一约束、字段时间及幂等保持。
不证明原Order存在/完整tuple/hash因果，不自动删改旧错行或改变其它SQL与Redis流程。

### CM-18 配额释放计数的写前格式检查（16T62）

RESERVED释放分支的库存和活动版本文本只接收0或无前导零正整数，非法05/03等值在任何Redis写入前返回0，避免版本先递增后库存命令报错。
保留原数值边界、键/参数和写顺序；恢复器继续原投影阻塞处理，合法APPLIED重放仍只读回执、不重复INCR。
此门不新增跨Redis/SQL原子回滚或资源故障下的fencing保证。

### CM-18 秒杀预扣计数的写前格式检查（16T63）

预扣Lua对库存、用户累计数和活动版本只接收0或无前导零正整数；02/03等非法格式在任何扣减前返回原-1。
缺用户/版本仍默认0，原限购/时间/TTL/键参数/写顺序保持；已有accepted重放仍返回3，不重复扣减。
只阻断这类文本解析引起的部分写入，不新增资源故障回滚或跨Redis/SQL一致性保证。

### CM-18 秒杀预扣过期参数的写前校验（16T64）

预扣Lua在原时间、限购和库存拒绝之后、任何写入之前，要求过期秒数为无前导零的正整数且不超过1000000000000。
非法参数返回原-1，避免写后EXPIRE报错或零/负秒数删除用户累计计数；合法参数仍原文传入EXPIRE。
已有accepted重放3及原业务拒绝码优先级保持；本批不提供资源故障回滚或跨Redis/SQL一致性保证。

### CM-18 秒杀预扣时间与限额参数校验（16T65）

预扣Lua先核三个时间参数为规范整数、绝对值不超过1e12且结束晚于开始，再按原窗口返回4/5；通过窗口后，两个限额须为规范正int。
非法时间或限额返回原-1，避免脚本异常、错误窗口分类和小数限购额外放行。现有Java调用参数、计数/TTL检查和写顺序保持。
已有accepted重放仍最先返回3，合法未开始/结束仍优先于限额/TTL检查；本批不证明完整来源或跨存储一致性。

### CM-18 配额释放数值证据的规范文本（16T66）

释放Lua要求结算版本/数量参数原文分别为2/1，RESERVED/APPLIED版本和元数据限额为无前导零正整数，原1..1e12数值界保持。
损坏或数值别名返回0，不释放或伪造完成；恢复器沿原投影阻塞处理。合法APPLIED仍先核accepted，再只读原回执，不依赖后来损坏的当前计数/元数据。
不改变键、参数位置、写顺序、Java/SQL或时间字段；本批不证明完整回执发生因果或跨存储一致性。

### CM-18 User取消状态的同支付号扣款矛盾检查（16T67）

User TCC状态查询在CANCELLED原固定账本键无冲突后，增加一次既有全支付号DEBIT只读查询；其他操作键或未APPLIED的扣款候选也返回原409。
合法释放取消/空取消和Fence历史过期兼容保持，取消路径多1SELECT；TRYING、CONFIRMED、UNKNOWN不增加查询，写服务和Mapper SQL不变。
查询故障不伪装无扣款，诊断复用原冲突传播；这一检查不证明全部历史、退款或账户资金因果。

### CM-18 冻结/确认状态的完整支付号扣款范围（16T68）

TRYING查询在原固定键门后要求同支付号无任何DEBIT；CONFIRMED要求唯一范围候选与原固定账本的身份、金额和余额快照匹配。
别键扣款、缺失/错配证据和非唯一结果不得输出成功；数据库/多行异常沿原错误渠道，合法两个状态各多1SELECT。
取消16T67、UNKNOWN、原写端/Mapper SQL/接口和Fence历史TTL保持，不扩展为全历史、退款或账户资金因果证明。

### CM-18 原DEBIT账本的退款来源字段保护（16T69）

TCC确认查询的固定账本、全支付号范围候选，以及Try/Confirm已确认重放，均要求DEBIT的originalOperationKey为NULL。
空字符串、字面值null、自身或其他键均不视作NULL，沿原409/未知资金证据拒绝；正常NULL及原校验优先级保持。
不新增查询、资金写或修账动作，不改接口、SQL、锁或状态机；本批不证明完整退款来源及账户资金因果。

### CM-18 退款前的原扣款证明（16T70）

新退款及禁用用户的原TCC退款验证，要求锁定原行的键、类型、来源NULL及身份精确匹配，并证明合法18.2金额和before-after=amount。
active坏来源沿原退款无效409，disabled沿原用户不存在404；非法或缺失金额不再进入退款合计、归属比较或credit。
零余额/无损尾零、正常Legacy/TCC退款和已有重放边界保持；不新增查询或修改历史，不扩展到请求金额、当前钱包或全部退款因果证明。

### CM-18 Legacy请求金额的无损18.2域（16T71）

Legacy扣款、退款和兼容入口的共享金额校验，要求正数、无损有效小数不超过两位且不超DECIMAL(18,2)容量。
0.001/10.001及超容量请求在任何数据库或资金访问前返回原400；0.010/10.000、0.01与合法上限保数值等值，不舍入后接受。
调用者身份优先、已有幂等/余额不足、T70来源证明及TCC路径保持；不改DTO、SQL、资金状态或完整账户因果边界。

### CM-18 退款钱包边界与账本原子提交（16T72）

新退款在原来源/超额检查后，校验当前余额及退款后余额的18.2域；非法或溢出钱包返回原退款失败409，正常零余额/尾零/精确上限保持。
账本插入必须影响一行，否则抛可回滚异常，使已入账余额随原事务回滚，不返回APPLIED。派生余额只计算一次用于快照与响应。
已有退款重放、原SQL/锁序/事务注解和接口保持，不修历史或证明全账户资金因果。

### CM-18 已退款重放的原行证明（16T73）

已有退款重放必须精确匹配实际退款键、REFUND/APPLIED、身份和原扣款键，并核合法金额、非负余额快照及after-before=amount。
坏状态、CI别名、NULL/负数或错误差额沿原幂等冲突409，避免200或NPE；合法历史快照不和当前钱包比较。
active重放不增加原来源查询，disabled保留原扣款/TCC404优先；不新增资金写、SQL或历史修复。

### CM-18 禁用用户退款的原确认冻结证明（16T74）

禁用用户的TCC退款先验证归属金额，再读原冻结行，要求CONFIRMED、精确身份/操作键/XID/分支及金额/快照与已核原DEBIT一致。
缺失、别名或损坏冻结保持原404，不入账或插入退款；NULL归属金额不再NPE。active路径不增加冻结查询，disabled已有重放仍保原来源优先。
仅证明本地冻结，不读receive/Fence/TC，不改变SQL、锁、资金状态或完整跨服务因果边界。

### CM-18 新退款累计的关联记录证明（16T75）

新退款在原扣款证明后，复用现有关联候选查询，逐行核对精确身份、来源、REFUND/APPLIED、操作键及合法金额和余额差额，再计算退款累计。负数抵消、错关联和被旧SUM过滤的坏记录不能继续入账。

候选缺失或超过100条时返回退款无效409；健康部分退款及合计恰好达到原扣款继续成功，超额保持原409。数据库读取故障原样传播，已有退款重放不增加集合查询。原SQL、用户锁、禁用用户原来源404优先和钱包/账本原子门保持。

本批只证明当前原扣款键的有界关联集合；全账户资金因果、其它支付号、历史全局唯一性和外部来源变化仍需后续验证。

### CM-18 Legacy扣款的钱包与历史资金证明（16T76）

新Legacy扣款在认领归属前校验钱包金额的无损18.2域；非法钱包返回资金未知409。归属/账本金额为空或非法时按原幂等冲突拒绝，原扣款键及来源也须精确匹配。

已有APPLIED账本需证明before-after=amount；REJECTED需证明before=after且原余额不足。坏快照返回资金未知409，正常不足仍保留原持久决策。历史重放不把当前钱包与原快照作相等比较，钱包后来变化不导致二次扣款。

原SQL、用户锁、状态冲突优先、两种扣款入口、TCC/退款路径及资金写序保持；本批不修历史事实，也不证明全账户或跨服务资金因果。

### CM-18 Legacy支付退款终态的账本确认（16T77）

Legacy恢复流程在完成REFUNDED前，读取实际退款操作，核对精确退款键、REFUND/APPLIED及合法18.2的全额金额。扣款查询的退款标记和退款写接口的成功回执均需要这一步，部分退款不能被判为全额已退。

POST仍使用固定refund:<payNo>键并核对回执；历史marker允许合法自定义全额退款键。读取缺失、错误响应或金额不符时保持原恢复方向和重试，退款GET的404不按未扣款处理；不自动补差额或改变TCC路径。

本批只核Pay可见的单条退款字段；User原账本快照/来源、分笔退款全集、读取到终态的窗口及跨服务完整因果仍保留后续验证。

### CM-18 余额操作查询的账本与关联证据（16T78）

余额操作GET在同一个只读REPEATABLE_READ快照中核对实际操作键、已知类型/状态、金额和余额差额。退款查询还要证明原DEBIT来源，以及同原键的有界退款集合、累计上限和自身归属；DEBIT的退款标记继续使用原selector，但必须属于已证明集合。

主操作缺失或归属不符仍返回原404，坏字段/来源/集合统一返回账本待核对409，数据库故障原样传播。合法部分/全额退款、原余额不足记录及钱包后来变化的历史查询保持；查询不会修账、写缓存或改变扣款/退款资金动作。

该快照证明一次查询内的账本片段；全历史/账户因果、分笔退款结清及查询之后的外部变化/跨服务终态窗口仍需后续处理。

### CM-18 Legacy恢复每次领取的独立身份（16T79）

Pay Legacy恢复器每次领取任务生成新的UUID token，成功后沿全部私有更新、终态和重试路径传递。同一服务实例重领超时任务时，旧执行不能通过复用的owner覆盖新意图或清新租约。

领取SQL、30秒租约、状态与远端调用顺序保持。该批只隔离每次执行身份，租约期限、PayRecord跨表更新的完整fencing及条件写影响行检查仍需后续处理。

### CM-18 Legacy支付终态的领取资格（16T80）

Legacy的SUCCESS、FAILED、REFUNDED支付记录通过单条关联UPDATE核对当前领取token、未过期租约、两表LEGACY模式与原允许状态。支付记录更新成功后，意图也须由当前未过期owner完成；两步都必须恰好影响一行，不能用旧终态readback绕过资格。

旧Mapper方法继续用于其它调用者，TCC/API/RPC契约保持。这两个独立UPDATE尚不是跨表原子事务；中间状态、远端调用前检查和查询后来源变化仍保留后续处理。

### CM-18 Legacy中间状态与远端写前资格（16T81）

等待订单同步、等待退款及意图推进/重试使用当前未过期token和双表LEGACY模式条件。正常中间写必须恰好影响一行；异常清理失去资格时不回退普通payNo写。

User扣款、Order支付、User退款三个写RPC前再读取当前领取资格，明确失效时停止调用。成功路径和请求契约保持；资格读取与RPC发出之间仍有窗口，不能作为下游原子fencing或两表事务保证。

### CM-18 Legacy两表终态本地原子提交（16T82）

Legacy支付记录终态与意图完成在同一Pay数据源的REQUIRES_NEW短事务内提交；任一owned更新非一行或异常均回滚两表终态写，避免先落支付终态却留下未完成意图。

远端User/Order调用、claim、前置REFUND_PENDING、stage及retry保持在短事务之外。已有Seata XID时拒绝进入终态块，不解绑调用者上下文；本批只保证本地两表原子，不保证跨服务或完整租约fencing。

### CM-18 Legacy恢复入口事务隔离（16T83）

initiate、process、recoverDue在保存意图、读取或领取前拒绝已有Spring本地事务或Seata XID，抛出固定异常“Legacy支付恢复不能继承事务上下文”。拒绝不会结束调用者事务或解绑XID；正常无上下文恢复保持既有流程，终态短事务的后置XID保护继续保留。

该边界避免恢复的claim、stage及RPC继承调用者事务；仍不保证跨服务原子性、source读后稳定或完整租约fencing。

### CM-18 Legacy恢复的原支付载荷证明（16T84）

恢复器在领取前、领取失败重读及领取成功重读时，用原请求payNo查询原支付单，核对两表精确payNo/orderNo、正userId及数值相等的正DECIMAL(18,2)金额。协议或缺失原支付单仍保留503优先；载荷不符返回“支付意图与原支付记录不一致”409，不继续远端调用或状态写。

领取后才发现错误时，已发生的claim保持，租约按原期限到期；不会自动修历史事实。该校验不保证查询后的来源稳定或完整跨服务fencing。

### CM-18 Legacy恢复的远端订单号校验（16T85）

恢复器每次读取Order支付状态时，返回orderNo必须精确等于原支付意图的订单号；缺失、别单、大小写、重音或尾空格别名都不能用于支付成功、失败、新扣款或退款判断。用户归属和金额校验保持，身份不符沿原恢复方向记录重试。

订单写入后的第二次GET也受同一校验；已经发送的paid请求和前置状态不回滚。本批只核读取的订单号，不证明完整SKU/订单快照或查询之后的跨服务稳定性。

### CM-18 Legacy退款等待状态恢复（16T86）

原扣款已APPLIED、原订单明确取消且库存动作成功时，恢复器直接进入已有的退款恢复链，不再先写ORDER_SYNC_UNKNOWN/DEBITED。这样已处于REFUND_PENDING的支付单及部分成功的退款前置状态可以继续固定键退款和账本确认。

退款前两步owned更新、远端写前租约检查、固定refund:<payNo>键、全额账本确认及两表终态事务保持；故障或缺证仍等待重试。已有退款marker不再退款，无扣款取消仍走原失败路径，不放宽SQL状态或承诺跨服务原子性。

### CM-18 Legacy恢复有界主键轮转扫描（16T87）

定时恢复使用进程内主键游标读取符合原协议、到期和租约条件的候选，每轮最多处理50条；只有尾段为空时再从头读取，最多两次查询。即使本轮候选全被原载荷校验拒绝，扫描仍前进，避免坏队头长期阻塞健康后项。查询失败不推进；重启从头开始，游标不持久化，不代表处理成功。

恢复扫描改为到期资格过滤后的主键轮转次序，旧findDue及其时间优先排序继续供其它调用使用。坏事实不修改、claim/租约/业务门保持；不承诺跨实例强公平、物理扫描性能或完整fencing。

### CM-18 User已确认重放的同支付扣款范围（16T88）

TCC已CONFIRMED的Try/Confirm重放在原固定账本证明通过后，再读取同支付号的DEBIT候选（最多两行，不按状态过滤）。必须恰好一行，且id、精确身份、操作键、类型、状态、origin和数值快照都对应原账本；缺失、重复或错配沿原资金待核对409。

该查询保留CI别名候选，合法REFUND记录和后来钱包变化保持历史重放语义。首次资金处理、取消、SQL写、API及Fence保持，不修复历史或证明全账户/全部退款因果；数据库故障原样传播。

### CM-18 User无账本门的同支付全类型范围（16T89）

首次冻结、首次确认、取消和未确认重放在原DEBIT/固定键检查通过后，还会读取同支付号的全类型/全状态账本候选。只有可靠的空列表才能继续；任意记录都冲突，查询缺证或故障不按无账本处理。

此门也覆盖仅有attempt的空取消，保留原检查优先级、资金事务和空取消幂等行为。已确认重放继续使用独立扣款证明，合法退款历史保持；本批不扩为全账户查询或修复历史账本。

### CM-18 User未确认状态读的全类型无账本证明（16T90）

TRYING/CANCELLED状态读取在原固定键和DEBIT门通过后，再读取同支付号全类型/全状态账本候选。只有可靠empty才返回该无账本状态；孤立退款、未知类型、缺证及CI别名都不能被忽略，仍返回原state冲突409。读取保持同一只读RR，数据库故障不转换为空。

UNKNOWN（未观察到attempt）仍只是不确定，不提升为无资金证明；CONFIRMED及已扣款的合法退款仍用原独立范围。复用Query的诊断入口继承保护，不修改任何资金、API或SQL写。

### CM-18 User TCC回调的Fence精确来源（16T91）

本地TCC commit/rollback在读取到Fence候选后，先精确核对XID、branchId和actionName，再进入官方Fence处理器和资金回调。别名或错分支返回false并标记回滚；被拒分支的二次Fence读取也复用该来源检查。

没有Fence时的原终态证明、空回滚、官方处理器、资金规则和Seata上下文保持。测试在owned数据库中调用实际官方Fence JDBC及本地资金事务，不代表网络TC或完整跨服务fencing验收。

### CM-18 User已确认状态的有界扣款候选（16T92）

CONFIRMED状态查询在原固定账本证明通过后，读取同支付号DEBIT候选（最多两行、保留所有状态及CI候选），必须恰好一行并通过原身份/金额/快照检查。重复、缺失或坏候选返回原持久事实409，而不是单结果Mapper引起的500/503。

数据库本身故障仍原样传播；未确认状态、UNKNOWN、资金写、RR、查询计数及API字段保持。该门不修复重复历史，不扩为全账户或跨服务资金因果证明。

### CM-18 User未确认状态的有界扣款空范围（16T93）

TRYING/CANCELLED（含仅attempt空取消）使用有界DEBIT候选列表证明无扣款。只有可靠empty才继续全类型账本空范围检查；单条、重复或缺证均返回原状态冲突409，避免单结果查询将重复事实变成500/503。

查询次数一替一、RR与无写入语义保持，真正DAO故障仍原样传播；CONFIRMED和UNKNOWN独立语义、资金与TCC规则不变。

### CM-18 User资金入口的有界扣款空范围（16T94）

首次或未确认的Try/Confirm/Cancel在证明无账本时，先读取同支付号DEBIT候选（最多两行、所有状态及CI候选）。只有可靠空列表才能继续固定账本键与全类型账本检查；任何候选都返回原幂等冲突409，缺证保留资金待核对409，真实DAO故障原样传播。

查询次数一替一，原校验顺序、资金事务、空取消幂等及已确认重放保持。该保护不修复重复历史，也不证明全账户或查询后来源稳定性。

### CM-18 Legacy扣款的有界历史范围（16T95）

两个Legacy扣款入口读取同支付号DEBIT历史时，使用最多两条、全状态及CI候选。多行沿原支付号冲突409，缺列表或唯一空行返回扣款待核对409；可靠空或单行继续原固定键、状态和金额快照检查，避免重复历史被单行Mapper报成500。

原请求/用户/attempt校验优先、数据库故障传播、资金事务和缓存语义保持。一读替换一读，不修复历史、不扩展退款/TCC或全账户因果。

### CM-18 Legacy首次扣款的全类型账本空范围（16T96）

首次扣款在原归属、DEBIT历史和固定账本键检查通过后，再读取同支付号所有类型和状态的账本候选。只有可靠空列表才能检查钱包并认领新扣款；孤立退款、未知类型、别名或其它用户的同支付事实均返回409，缺列表保留待核对409，数据库故障原样传播。

仅首次路径增加一次SELECT，历史APPLIED/REJECTED及无attempt重放仍按原规则返回，合法退款历史不触发此空范围门。资金写、缓存、API和TCC/退款规则保持；不修复历史或证明全账户因果。

### CM-18 新退款的同支付唯一扣款来源（16T97）

新退款在原扣款和禁用用户来源检查通过后，读取同支付号的DEBIT候选（最多两条、全状态及CI候选）。必须恰好一条，并复制指定原扣款的主键、精确载荷与金额快照；缺证、重复或错配返回原退款无效409，数据库故障原样传播。

只在新退款路径增加一次SELECT，然后继续原关联退款累计和资金写入；已完成退款重放不查此范围，禁用用户原404检查优先保持。不修复历史，也不证明全账户或查询后来源稳定。

### CM-18 启用用户新退款的已观察TCC来源（16T98）

新退款在原DEBIT和唯一范围检查通过后，观察本地扣款归属。已观察TCC必须具备匹配的CONFIRMED归属与冻结快照，未知模式或矛盾事实返回原退款无效409；数据库故障原样传播。

仅启用用户的新退款增加一次归属读取，TCC再读取一次冻结；已完成退款重放和禁用用户原404检查保持。null归属或LEGACY保留历史兼容，仍不证明缺失归属的完整历史、全账户因果或查询后来源稳定性。

### CM-18 启用用户新退款的已观察Legacy来源（16T99）

已观察到LEGACY归属的新退款，必须核对归属为APPLIED，用户、支付号、订单号、操作键和账本键精确对应原扣款，金额合法且数值相等，XID和分支为空。矛盾事实返回原退款无效409，不新增数据库读取。

缺归属的历史兼容、TCC确认来源、禁用用户及已完成退款重放保持原规则；不修复历史，也不证明缺失归属、全账户或查询后来源稳定性。

### CM-18 缺归属新退款的冻结空范围（16T100）

启用用户的新退款未观察到扣款归属时，还需检查同支付号的TCC冻结。存在任何冻结记录都返回原退款无效409，包括异常状态、别名或其它用户的候选；只有未观察到冻结才保留历史Legacy兼容，查询故障原样传播。

仅缺归属的新退款增加一次冻结读取。已有归属、禁用用户和已完成退款重放保持原规则；不证明其它接收事实、全账户因果或查询后来源稳定性。

### CM-18 Legacy新退款的冻结空范围（16T101）

启用用户的新退款在Legacy归属字段检查通过后，同支付号也必须没有TCC冻结；存在任何冻结候选都返回原退款无效409。缺归属历史继续使用相同存在性检查，数据库故障原样传播。

有效Legacy新退款增加一次冻结读取；坏归属先拒绝，TCC、禁用用户和已完成退款重放的读取预算保持。不修复历史，不证明其它接收/Fence事实、全账户因果或来源变化窗口。

### CM-18 新退款的同支付集合一致性（16T102）

新退款先验证原扣款键关联的退款集合，再读取同支付号全部账本候选。必须唯一观察匹配的原DEBIT，其它记录均为精确关联的合法退款，并与第一集合的操作键、金额、余额快照和累计完全一致；未关联、错关联或异常记录返回原累计证据409，查询故障原样传播。

新增独立LIMIT102查询，保留原DEBIT加100条历史退款和超限哨兵，原LIMIT101接口不变。仅新退款增加一次读取，已有退款重放、归属检查和资金写入规则保持；不证明其它支付号、全账户或来源变化窗口。

### CM-18 余额操作查询的同支付集合一致性（16T103）

余额操作GET在主行、原扣款和关联退款证明通过后，再核对同支付号全账本集合。复用新退款的双集合检查，孤立、错关联、异常或缺证返回原账本待核对409，数据库故障原样传播。

正常APPLIED/REJECTED来源都须精确复制原状态；原主行404、退款标记、金额字段和REFUND的null语义保持。在同一只读RR事务中增加一次查询，不读取钱包或归属，不修改资金；不证明全账户或来源变化窗口。

### CM-18 首次Legacy扣款的冻结空范围（16T104）

首次Legacy扣款在原归属、历史DEBIT、固定键和全支付账本检查通过后，还须证明同支付号没有TCC冻结。存在任何冻结候选都返回支付号冲突409，避免再次认领、扣款或形成矛盾REJECTED记录；查询故障原样传播。

仅首次路径增加一次已有查询，已有APPLIED/REJECTED及历史重放保持原规则；TCC、退款和余额操作查询不变。不修改残留冻结，也不证明其它接收/Fence来源或完整资金因果。

### CM-18 已完成active退款重放的原扣款证明（16T105）

启用用户重放已完成退款时，先保留原退款行校验，再锁定读取指定原DEBIT，核对APPLIED、精确身份与合法金额快照，并确认单笔退款不超过原扣款。缺失或矛盾来源返回原退款无效409，数据库故障原样传播。

合法重放仍返回原历史余额，不和当前钱包比较，也不重复入账。禁用用户原TCC来源404、新退款和其它读取规则保持；不证明完整退款集合、归属/Fence或全账户因果。

### CM-18 禁用用户退款重放的单笔额度（16T106）

禁用用户重放已完成退款时，原扣款/TCC来源和退款行检查通过后，单笔退款也必须不超过原扣款金额。超额返回原退款无效409，等额、合法部分和数值尾零保持原历史结果。

复用已读取的原扣款，不增加查询或入账；原来源404、退款行错误优先、当前余额独立和其它资金路径保持。不证明完整退款累计、归属因果或来源变化窗口。

### CM-18 Legacy扣款重放的冻结空范围（16T107）

已有Legacy扣款的原载荷、状态和余额快照证明通过后，还需检查同支付号TCC冻结。存在任何冻结候选返回原扣款待核对409，避免报告正常成功、更新缓存或误标余额不足；查询故障原样传播。

仅历史重放增加一次已有查询，无冻结时仍返回原APPLIED/REJECTED结果，不和当前余额比较，不再次扣款。首次扣款、TCC、退款和操作查询保持；不修复历史或证明其它接收/Fence及完整资金因果。

### CM-18 已完成active退款重放的本地归属（16T108）

启用用户的退款重放在退款行、原DEBIT和单额检查通过后，还需复用新退款的归属/冻结校验。已观察TCC须确认归属和冻结，Legacy须精确APPLIED且无冻结，缺归属也须无冻结；矛盾来源返回原退款无效409，数据库故障原样传播。

合法重放仍返回历史余额、不再次入账；新退款原校验位置和查询数、禁用用户及其它资金路径保持。不证明完整退款集合、receive/Fence、全账户因果或来源变化窗口。


### CM-18 已退款重放的同支付唯一扣款来源（16T109）

已退款重放在原退款行、原扣款单额和归属检查通过后，还需复用新退款的有界同支付号DEBIT来源检查。候选必须唯一并完整复制已证明的原扣款；缺失、重复、别名或矛盾来源返回原退款无效409，数据库故障原样传播。

启用和禁用用户的合法重放各增加一次已有只读查询，不重复读取原扣款或再次入账，仍返回历史余额。新退款原位置和查询次数保持；不证明完整退款集合、全账户因果或来源变化窗口。

### CM-18 已退款重放的累计与同支付集合（16T110）

退款重放在原退款行、原扣款、归属和唯一来源检查通过后，还需核对原扣款关联退款集合与同支付号全账本集合。当前退款须完整包含，所有记录必须一致，累计不得超过原扣款；缺证或矛盾返回原退款累计证据409，查询故障原样传播。

当前退款已计入累计，重放不会再加请求金额或再次入账，仍返回历史余额。仅重放增加两次已有只读查询，新退款、操作查询和资金写入保持；不证明其它支付号、全账户或来源变化窗口。

### CM-18 新退款记录容量与恢复边界一致（16T111）

原扣款关联退款和同支付号集合证明、金额超额检查通过后，新增退款必须保证总记录数不超过100。已有100条时返回原退款无效409“退款记录数量已达上限”，避免创建无法通过历史证明的第101条；99条时仍可受理第100条。

容量检查复用已读集合，不增加数据库查询或改变金额超额优先级。合法100条的重放和操作查询保持，已有超限历史仍拒绝，不自动修复。

### CM-18 APPLIED余额操作查询的本地归属（16T112）

余额操作GET完成原主行、原扣款、双集合和标记校验后，对APPLIED原扣款复用退款的Legacy/TCC归属与冻结证明。矛盾来源返回原待核对409，数据库异常原样传播。REFUND使用原DEBIT来源，REJECTED读取预算保持。

APPLIED最多增加归属、冻结各一次点读，位于同一只读RR快照，不读取当前钱包或写资金。退款原规则和查询次数保持；不证明receive/Fence、全账户或来源变化窗口。

### CM-18 REJECTED余额操作查询的本地归属（16T113）

拒款账本通过原主行、空退款集合和标记证明后，已观察归属必须是同元组、同金额的LEGACY/REJECTED，且无XID/分支。有效归属或缺归属历史都须同支付号无冻结；矛盾来源返回原待核对409，查询故障原样传播。

REJECTED新增归属读取一次，通过后冻结读取一次，同一只读RR，不查询当前钱包或写资金。APPLIED与原资金写路径规则和次数保持；不证明receive/Fence、完整历史或来源变化窗口。

### CM-18 余额操作查询的已观察键全局唯一性（16T114）

余额操作GET先核主键全局候选数量：0保持404，1才读取原行，重复或缺证返回原待核对409。REFUND原扣款键也须唯一；原账本、集合、标记和归属证明完成后，已观察退款键逐一核全局唯一，避免其它支付号或错关联的同名记录被遗漏。

同一只读RR最多101次已有COUNT，成功证明的主行/来源键复用，不重复查询，不写资金。原资金接口不增加读取；只检查当前观察键，不修复历史或枚举整个账本。

### CM-18 退款已观察键的全局唯一性（16T115）

新退款和重放通过原来源、归属、双集合及金额/容量证明后，还须确认原扣款和每个关联退款操作键的全局候选数量恰为1。其它支付号或错关联下的同名记录不能被局部集合隐藏，缺证或重复返回原累计证据409，COUNT异常原样传播。

仅校验已读取的键，新退款最多100次、重放最多101次已有COUNT；新退款在余额写入前校验，重放不重复入账。原点读、锁、错误优先级和GET保持；不修历史，点读多结果分类和来源变化窗口仍待后续处理。

### CM-18 退款点读多结果的业务分类（16T116）

退款当前行及原DEBIT点读遇到历史重复记录时，直接MyBatis多结果异常或Spring对它的直接包装，统一返回原退款累计证据409，避免暴露为500。其它数据库异常保留原对象传播，不按异常消息或任意深层cause猜测。

查询、锁、读取次数和原null/单行逻辑保持，原权限/User404/退款行优先级与资金写入顺序保持。该检查只分类已经观察到的多结果，不修复历史或保证查询后的来源稳定性。

### CM-18 首次Legacy扣款的TCC接收互斥（16T117）

首次Legacy扣款通过原归属、账本和冻结空范围后，还须同支付号没有TCC接收凭据。任何已观察接收行均返回支付号冲突409“支付号已有 TCC 接收事实”，避免抢占已接收事务、再次扣款或写拒款记录；查询故障原样传播。

普通启动幂等准备已有接收表，HTTP关闭也可读残留事实。仅首次Legacy增加一次已有点读，历史重放、TCC、退款和GET不增加读取；不启动HTTP/RM，不证明查询到写入间的完整fencing或receive完整元数据。

### CM-18 Legacy扣款历史重放的TCC接收互斥（16T118）

历史Legacy扣款通过原载荷、状态、资金快照及冻结空范围校验后，还须同支付号没有TCC接收凭据。任何已观察接收行均返回待核对409“扣款来源存在 TCC 接收事实”，避免把矛盾历史缓存为成功或判断为余额不足；查询故障原样传播。

历史重放仅新增一次已有点读，接收缺失保持原APPLIED/REJECTED结果与缺归属历史兼容。首次扣款沿用16T117，TCC、退款和GET读取不变；不核接收完整元数据、不修历史或保证查询后的来源稳定性。

### CM-18 REJECTED余额操作查询的TCC接收互斥（16T119）

拒款账本通过原账本、退款集合、标记、归属和冻结空范围校验后，还须同支付号没有TCC接收凭据。任何已观察接收行均返回原待核对409“余额账本事实待核对”，接收缺失保持健康查询，查询故障原样传播。

仅REJECTED GET增加一次已有点读，与原冻结读取同只读RR事务；APPLIED GET、退款、扣款及TCC路径读取保持。不读当前钱包或写资金，不证明接收完整元数据、Fence、全账户因果或查询后的来源稳定性。

### CM-18 APPLIED Legacy来源的TCC接收互斥（16T120）

成功扣款来源的归属为Legacy或缺失历史归属时，原归属/冻结校验通过后还须同支付号无TCC接收凭据。任何接收行均阻断新退款和退款重放，返回原来源无效409；扣款/退款账本GET沿原映射返回待核对409。接收缺失保持原结果，查询故障原样传播。

四条入口共用来源校验，仅Legacy/null分支新增一次点读。TCC确认来源、禁用用户退款、拒款GET与扣款路径保持；GET仍同只读RR且不读钱包，退款保留原锁和写入链。不核接收完整元数据/Fence或保证查询后的来源稳定性。

### CM-18 接收凭据多结果的业务分类（16T121）

接收凭据保护门遇到遗留同支付号多行时，直接MyBatis多结果异常或Spring对它的直接包装视为已存在冲突，按各入口原业务错误返回409。其它数据库故障保留原对象，不按异常消息或深层cause判断。

首次/历史Legacy扣款、拒款查询及成功Legacy来源的查询/退款共用一次原点读，null和单行语义、校验顺序及查询预算保持；正常TCC、禁用用户退款预算保持。不改SQL、索引或全局异常处理，不修复历史重复记录或保证读取后的来源稳定性。

### CM-18 Legacy扣款操作键多结果的业务分类（16T122）

Legacy扣款同支付号校验通过后，操作键点读若遇到遗留重复账本，直接MyBatis多结果异常或Spring对它的直接包装返回幂等冲突409“扣款操作键存在重复账本记录”。其它数据库或深层包装故障保留原对象，不按异常消息判断。

两扣款接口的首次请求及成功/拒款重放保持原单次点读、校验顺序、null和单行语义；不加COUNT/SQL/锁，不改其它余额查询或退款入口，不修复历史索引或保证读取后的来源稳定性。

### CM-18 扣款归属多结果的业务分类（16T123）

扣款归属的同支付号点读遇到遗留多行时，直接MyBatis多结果异常或Spring直接包装按所在入口拒绝：Legacy扣款为幂等冲突409，成功来源查询/退款为原来源无效409（GET映射待核对），拒款GET为待核对409，禁用用户退款保留缺证404。其它数据库和深层包装异常原样传播。

四处调用共用一次原点读和各自错误工厂，null/单行、校验顺序、冻结/接收后置门与写入链保持；不加SQL/锁/COUNT，不修改TCC资金服务、RM或历史索引，不保证读取后的来源稳定性。

### CM-18 冻结凭据多结果的业务分类（16T124）

冻结点读遇到遗留同支付号多行时，直接MyBatis多结果异常或Spring直接包装在Legacy互斥门视为已有冻结，保持各入口原冲突409。TCC退款来源无法由多条记录证明唯一确认，沿原来源无效规则拒绝；账本GET映射待核对409，禁用用户退款保持缺证404。其它异常原样传播。

存在性与确认来源使用各自单次原查询，null/单行、金额/身份/状态证明及校验顺序保持。不伪造冻结记录，不增加SQL/锁/COUNT，不修改TCC资金服务、RM或历史索引，不保证读取后的来源稳定性。

### CM-18 本地TCC点读多结果的业务分类（16T125）

本地Try/Confirm/Cancel的归属初读、归属锁读、冻结和固定账本键查询遇到遗留多行时，直接MyBatis多结果异常或Spring直接包装返回原资金待核对409；无账本保护门的重复固定键按原已占用冲突409拒绝。其它数据库和深层包装异常原样传播。

七处点读共用精确分类器，原SQL、锁性质、读次数、null/单行、校验顺序和状态/资金写入规则保持。不开额外HTTP接口、不改变接收凭据或RM/Fence，不修复历史唯一索引或保证读取后的来源稳定性。

### CM-18 TCC状态查询点读多结果的业务分类（16T126）

只读TCC状态查询的接收、归属、冻结、账本和Fence点读遇到遗留多行时，直接MyBatis多结果异常或Spring直接包装返回原事实冲突409“TCC 持久事实不一致”，不能据此输出UNKNOWN或终态。其它数据库和深层包装异常原样传播，真正缺少接收凭据仍404。

五类点读保持原单次查询、顺序和只读RR；不修改接收/资金/Fence、不新增SQL/COUNT/锁，也不修复历史唯一索引或保证读取后的来源稳定性。

### CM-18 无归属UNKNOWN状态的账本范围（16T127）

仅有接收凭据、缺少扣款归属时，原冻结和固定账本为空后，还须同支付号的DEBIT范围与全类型账本范围均可靠为空，才返回UNKNOWN。其它键的扣款、退款、未知类型/状态、用户或大小写别名记录，以及查询缺证均返回原事实冲突409；数据库故障原样传播。

本分支新增两次现有有界读取（LIMIT2、LIMIT101），同只读RR；不读Fence或当前钱包，不写/修复历史。已知状态及旧固定来源错误优先级保持。UNKNOWN仍不能证明完整账户或读取后来源稳定。

### CM-18 TCC接收事务锁读多结果的业务分类（16T128）

接收认领和补绑定分支写后锁读遇到遗留同支付号多行时，直接MyBatis多结果异常或Spring直接包装返回原载荷冲突409“TCC 接收载荷不一致”。本次REQUIRES_NEW中的认领插入或分支更新仍全部回滚；其它数据库和深层包装异常原样传播。

原FOR UPDATE、读写顺序、分支null/非法前置校验、正常首次派发权及重放保持。不改变HTTP/RM/GET、事务传播或历史唯一索引，不合并旧接收记录。

### CM-18 禁用用户首次TCC接收回滚（16T129）

HTTP复用原一次用户读取，将已观察状态传给接收事务。仅首次INSERT winner在非启用状态时，原锁读和载荷证明成功后返回原“用户不可用”409，使本次REQUIRES_NEW认领回滚；不进入LocalTCC或消耗永久派发权。已有接收重放不受当前状态限制，仍只读原状态。

内部claim显式携带status，不保留绕过入口；没有新增SQL/锁、HTTP字段或表。认证/XID/用户404/坏载荷错误优先级和正常派发保持。用户状态在读取之后变化的窗口仍需后续处理。

### CM-18 关闭新Try模式的首次接收回滚（16T130）

HTTP 配置复用现有 `cloudmall.user.tcc-debit.enabled`，显式传入 HTTP 桥和接收事务。首次接收完成载荷和用户状态校验后，若模式关闭，返回原 `PAY_DEBIT_MODE_UNAVAILABLE`（503）并回滚本次认领，不消耗派发权。已有请求重放、GET 和旧冻结资金清算不受新 Try 开关限制。

内部 `claim` 使用四个参数，HTTP 桥构造器显式要求模式值，均不保留绕过校验的重载。SQL、锁、执行顺序和认证保持原约定，没有新增开关、表或 HTTP 字段；配置读取后变化的窗口仍需后续处理。

### CM-18 User 取证点读重复的错误分类（16T131）

基线取证读取原接收记录、账户资金投影和固定退款键时，明确多结果返回 `USER_TCC_RECONCILIATION_CONFLICT`（409），避免将持久数据冲突误报为临时不可用。仅处理直接 MyBatis 多结果异常及 Spring 的直接包装；其它数据库故障仍沿原 503 处理，深层包装和其它运行时异常保持原语义。

查询顺序、次数、只读 RR、HTTP `no-store` 和字段保持原约定。没有修改 SQL、索引或历史数据，不代表完整账户因果验证。

### CM-18 Legacy 支付恢复的订单状态校验（16T132）

恢复器读取订单后，仅接受精确的 `PENDING_PAYMENT`、`PAID`、`COMPLETED`、`CANCELLED`。未知、缺失或大小写/空白别名状态保留原意图并重试，避免在结果未知时发起扣款或把拒绝账本直接结为失败。

首次无效查询不发起资金写入或支付终态；支付同步后的再次查询无效时，保留此前已经发出的请求和恢复阶段，继续等待核对。正确状态仍按原库存动作完成条件处理，未改 API、SQL 或租约，也未覆盖查询后的来源变化。

### CM-18 Legacy 新扣款的来源与回执校验（16T133）

未读到当前支付号的扣款账本时，仅明确待支付的订单允许发起新扣款。订单已支付或已完成而原账本缺失时，保留同一支付号等待核对；未扣款的已取消订单保持原失败处理。

扣款回执必须同时为成功 APPLIED，且操作键精确等于原支付号。缺失、别笔或大小写/空白别名回执不能推进订单或支付终态。已发出的扣款请求无法据此回滚；后续读到正确账本时继续原恢复流程，不再次扣款。未修改 API、SQL、租约或 TCC，也不代表完整跨服务资金来源已证明。

### CM-18 已推进支付恢复阶段的缺账本保护（16T134）

支付意图已进入扣款、订单同步或退款恢复阶段时，查询不到原扣款账本不能解释为从未扣款。恢复器保留原等待方向，不因此新发扣款或将取消订单直接结为失败。

初始意图仍按原新扣款规则处理。后续读到正确原账本时，同一支付继续完成支付或退款。此保护只使用当前已读取的恢复阶段，没有增加 SQL、RPC、字段或租约协议；历史数据修复与跨服务完整资金来源仍需后续处理。

### CM-18 支付记录恢复阶段的缺账本保护（16T135）

意图仍为 INIT、支付记录已进入订单同步或退款等待时，恢复器使用认领后已校验的原支付记录保留等待方向。原扣款账本缺失不再触发新扣款或取消失败；查询异常也不能覆盖原退款等待方向。

支付记录来自原有查询，没有增加 SQL 或跨服务请求。原账本补齐后，同一支付继续成功或完成退款。原记录读取后的变化、历史错配处置及完整资金因果仍需后续处理。

### CM-18 扣款拒绝与恢复阶段的冲突保护（16T136）

扣款查询返回 REJECTED 时，只有初始意图、待处理支付记录且没有退款标记，才沿原规则结束为 FAILED。已推进的扣款/退款恢复阶段或退款标记与拒绝事实矛盾时，保留原等待方向，不自动改成失败。

正确初始拒绝仍可结束支付；修正原账本来源后，同一支付继续完成支付、退款或明确拒绝。原订单校验优先级、查询次数、SQL、租约及跨服务接口保持，历史数据修复与读取后变化仍需后续处理。

### CM-18 已记录退款方向的订单证据保护（16T137）

原扣款已 APPLIED、意图或支付记录已在退款等待时，必须确认订单已取消且库存回滚完成，才继续退款或已有退款标记的恢复。证据不完整时保留退款方向，不能再调用订单支付、改写正向阶段或结为 SUCCESS。

正确取消仍按原稳定键退款；已有退款标记仍只核验原账本。取消/回滚证据补齐后，同一支付可继续退款。未新增 SQL、RPC、字段或租约协议，不保证读取后来源稳定，也不替代跨服务完整资金核对。

### CM-18 Legacy 支付成功前的原扣款复核（16T138）

提交 SUCCESS 前，在本地终态事务外再查询一次原扣款账本，要求操作键、类型和金额匹配，状态为 APPLIED 且没有退款标记。缺失、矛盾或查询故障保留原支付号等待，不用较早读取或 POST 回执替代本次证据，也不猜测退款。

成功路径增加一次现有 User GET；退款和 FAILED 路径的读取次数保持。已发扣款、订单支付请求不回滚。该复核只保护这次观察，不能保证读取到终态之间来源不再变化。

### CM-18 Legacy 退款终态前的订单复核（16T139）

提交 REFUNDED 前，在原支付记录阶段写入及本地终态事务之前，再核对一次订单身份、金额、取消状态和库存回滚完成状态。缺失、冲突或查询故障沿原流程等待，不用退款调用之前的订单快照替代当前证据。

退款路径增加一次现有 Order GET；成功和失败路径不增加该读取。已发退款不撤回，后续读到正确取消证据与原退款标记可在同一支付继续恢复。该复核不保证读取到终态之间的来源稳定性。

### CM-18 Legacy 失败终态前的扣款复核（16T140）

提交 FAILED 前重新读取原扣款账本：无账本取消路径须仍未观察到原账本，拒绝路径须仍为相同操作键、类型和金额的 REJECTED，且没有退款标记。来源变化或查询故障保留原支付号等待，不猜测退款或结束资金恢复。

失败路径增加一次现有 User GET；其它终态的读取预算保持。正常缺失/拒绝证据仍可失败，来源修正后同一支付可继续退款或明确失败。读取到终态之间的来源变化仍需后续处理。

### CM-18 Legacy 终态事务的原载荷保护（16T141）

提交 SUCCESS、REFUNDED 或 FAILED 的本地短事务中，先核对支付记录与意图的原支付号、用户、订单、金额及当前领取资格，并锁定两行。缺行或载荷变化时拒绝终态写入；匹配后执行原两次更新，任一更新失败整体回滚，提交或回滚后释放锁。

远端查询和调用仍在该事务外。此保护覆盖本地终态事务中的两表载荷，不保证远端 User/Order 的来源稳定，也不阻止事务结束后的外部改写。CM-18 的完整跨服务恢复验收仍待后续完成。

### CM-18 Legacy 成功终态前的订单复核（16T142）

提交 SUCCESS 前，先复核原扣款，再查询一次订单，要求当前仍为 PAID 或 COMPLETED，且库存确认已成功。订单取消、库存确认未知、身份或金额冲突及查询故障均保留原支付号等待，不使用较早的订单快照结束支付。

成功路径增加一次现有 Order GET；退款和 FAILED 路径的预算保持。已发扣款和订单支付请求不撤回，补齐正确证据后同一支付可继续恢复。该复核只证明本次观察，不保证远端读取到本地提交之间的来源稳定。

### CM-18 Legacy 中间阶段的双表事务（16T143）

正向恢复阶段和退款准备阶段的支付记录、意图记录两次更新，现在分别在独立本地短事务中提交。任一更新未满足当前租约或发生异常时，两次更新整体回滚，随后沿原流程记录重试，避免留下半个阶段。

远端请求、认领、重试清理和后续终态事务仍各自执行。已提交的中间阶段不会随后续终态失败而回滚；该修复不保证远端原子性，也未覆盖重试清理或原载荷变化的全部窗口。

### CM-18 Legacy 中间阶段的原载荷保护（16T144）

正向恢复和退款准备的短事务中，先核对并锁定支付记录、意图记录的原支付号、用户、订单、金额及当前领取资格，再执行阶段更新。原字段变化或资格失效时，该阶段不写入，也不继续发起其后的订单支付或退款请求。

原重试清理仍执行，之前已发出的请求不撤回。该保护仅覆盖两组中间阶段的本地事务，不保证整个恢复流程或远端来源持续不变。

### CM-18 Legacy 重试清理的本地事务（16T145）

异常后的支付记录清理与意图重试信息更新，现在在独立本地短事务中执行。意图更新失去资格或发生数据库异常时，撤回本次支付记录清理；零行仍保留原等待结果，数据库异常按原方式传播，不改用无租约的方法补写。

阶段、终态和重试分别提交；原退避、错误截断及单意图重试方向保持。第一笔支付清理零行仍按原规则尝试意图更新。存在 Seata 上下文时跳过重试清理并保留调用者上下文。该修复未覆盖重试原始字段校验及跨服务竞争窗口。

### CM-18 Legacy 重试清理的原载荷保护（16T146）

重试清理的短事务中，先核对并锁定支付记录、意图记录的原支付号、用户、订单、金额及当前领取资格。字段变化、记录缺失或资格失效时，跳过本次清理，保留记录和租约，仍返回原等待方向。

匹配后继续原清理顺序及提交、回滚规则。之前已发出的请求不撤回；该保护仅覆盖本地重试事务，未解决整个恢复流程、远端变化或历史冲突数据的处理。

### CM-18 Legacy 退款收尾准备与终态的事务（16T147）

退款收尾的准备更新已并入原终态短事务：先锁定并核对两表原字段，再依次写退款等待、支付已退款和意图已退款。任一更新失效或发生异常时，三次写入整体回滚，避免留下单独的准备更新。

末次订单核对仍在事务外。退款请求之前的中间阶段继续独立提交，已发退款不撤回；本批未将整个退款流程合成一个事务，也不保证远端来源持续不变。

### CM-18 Legacy 重试首笔清理的影响行校验（16T148）

订单等待方向的支付清理必须恰好更新一行，才继续写意图重试信息。首笔被状态或资格条件拒绝时，本次清理回滚，不改意图、不释放租约，仍返回原等待结果，避免忽略支付记录已有的退款方向。

此规则取代 16T145 保留的首笔零行后继续写意图的行为。租约到期后的恢复继续按原记录核对，不自动猜测新状态；其它重试方向、阶段、终态及远端请求保持原流程。

### CM-18 Legacy 意图退款方向的写入保护（16T149）

意图已进入退款等待时，阶段更新和重试 SQL 只接受精确的退款等待目标，不能再改回扣款、初始或订单等待。较早读取之后出现的新退款方向，会在写入时被保护；被拒绝的阶段或重试写入沿原事务回滚和等待流程处理。

原退款方向的合法更新继续执行，未新增资金或订单请求，也未修改服务的状态计算。此保护不替代完整历史状态校验和跨服务核对。

### CM-18 Legacy 意图退款方向的终态保护（16T150）

已进入退款等待的意图，只能以精确的已退款目标完成，不能被较早观察后的成功或失败结果覆盖。终态写入被拒绝时，原本地事务回滚支付记录和支付时间，随后沿原流程保留退款等待。

正常退款完成继续执行；原接口、状态计算和远端调用不变。该保护不替代跨服务资金核对或完整历史状态处理。

### CM-18 Legacy 写请求前的原字段与阶段检查（16T151）

扣款、订单支付和退款请求前，先执行原租约检查，再只读核对两表原支付号、用户、订单、金额以及该操作所需的精确阶段。信息变化或查询失败时，不发出该写请求，沿原流程等待核对。

每个写请求增加一次本地 SQL 查询，原租约检查失败时不追加查询。检查不持有锁，已提交阶段和已发出的请求不撤回；检查到远端执行之间的变化窗口仍需后续处理。

### CM-18 Legacy 初始重试的阶段保护（16T152）

INIT 重试只能更新当前仍为初始状态的意图。较早读取之后已进入扣款、订单等待或退款等待的意图，不再被旧 INIT 目标重置，也不因此清除租约和重试证据。

被拒绝时仍返回原等待结果，租约到期后按原记录重新核对；其它合法等待、退款目标和远端请求保持原流程。此规则不替代完整状态迁移或跨服务核对。

### CM-18 Legacy 失败终态的当前初始阶段校验（16T153）

失败终态的支付首写只接受当前意图 INIT、支付记录 PENDING 的组合；意图完成为 FAILED 时也要求当前仍为 INIT。较早读取后的扣款或订单等待阶段变化，不再被旧失败结果覆盖。

不符合条件时沿原事务回滚并保留等待，补齐原账本后继续原恢复流程。成功、退款及接口保持原约定，不自动猜测资金决议。

### CM-18 Legacy 初始重试的两表阶段校验（16T154）

INIT 重试同时要求当前意图 INIT、支付记录 PENDING。较早读取后仅支付记录进入订单等待或退款等待时，也不能清除原租约或覆盖恢复证据。

拒绝写入后保持原等待结果，租约到期补齐账本与订单证据后继续原恢复流程。其它重试目标沿用原条件，此校验不替代完整状态迁移和远端核对。

### CM-18 Legacy 退款终态前的账本复核（16T155）

末次订单取消与库存回滚核对通过后，再读取同一退款键对应的全额退款账本，确认后才写 REFUNDED。历史自定义退款键和固定 POST 退款键沿用原约定；证据缺失或变化时保留等待，不新增退款。

健康退款流程增加一次事务外 User 查询。已发出的退款不撤销；这次读取只证明当前可见账本，完整资金来源和读取到终态写入之间的变化窗口仍需后续处理。

### CM-18 Legacy 退款响应的关联字段校验（16T156）

User 的 REFUND 账本响应必须按既有协议返回 null 关联退款键。空串、空白、自指或其它非 null 值均视为证据不一致，早期确认和终态复核都保留等待；外层 DEBIT 的历史自定义退款键仍可使用。

补齐同一退款键的正常账本后继续原恢复流程，不新增查询或资金请求。此校验只约束退款响应字段，完整资金来源和跨服务变化窗口仍需后续处理。

### CM-18 Legacy 失败终态前的订单复核（16T157）

末次未扣款或扣款拒绝证明通过后，再核对当前订单。未扣款路径仅取消订单可结为 FAILED；扣款已拒绝时，待支付或取消订单仍可失败，已支付和已完成订单保留等待。

失败来源证明不通过时不追加订单查询。此流程增加一次事务外 Order 读取，不要求库存成功、不写订单或资金；读取到终态写入之间的变化窗口仍需后续处理。

### CM-18 Legacy 恢复任务的支付状态资格（16T158）

候选扫描和原子领取同时要求支付记录仍为 PENDING、ORDER_SYNC_UNKNOWN 或 REFUND_PENDING。已终态、未知和空白状态不再占用扫描名额或改写意图租约、尝试次数；合法状态的既有列等价语义保持。

两种扫描都先过滤再执行原排序与 LIMIT，领取拒绝时保留原记录。此规则不解释或修复历史矛盾，也不保证领取后的阶段和远端来源持续不变。

### CM-18 Legacy 恢复任务的两表身份校验（16T159）

扫描和领取要求支付记录与意图的支付号、订单号精确一致，用户和金额相等；领取的请求支付号也须精确匹配。单边身份变化不再先改租约和尝试次数，错误配对先被过滤再执行扫描 LIMIT。

合法状态的列等价语义、数值金额、时间及排序保持。此条件只验证当前两表一致性，不绑定旧快照，也不判断双方相同的非法域值或保证领取后的来源稳定。

### CM-18 Legacy 恢复任务的正数值资格（16T160）

扫描和领取同时要求用户 ID、支付金额为正值，两表共同为零或负值的记录先被过滤。领取前双方数值变为非法值时，不再先修改租约和尝试次数，原校验错误保持。

合法 BIGINT 用户范围、DECIMAL(18,2) 正金额及尾零语义保持，扫描仍按原时间、主键和 LIMIT 执行。本条件只约束非正数值，其它历史域和领取后的变化窗口仍需后续处理。

### CM-18 Legacy 恢复任务的空白业务键资格（16T161）

扫描和领取要求支付号、订单号均包含至少一个非 Java 17 空白字符，空串及仅由空白构成的共同键先被过滤。SQL 与现有 isBlank 规则一致，NBSP 等非 blank 字符和混合键保持原语义，不修改键文本。

领取前共同订单号变为空白时不先改租约和尝试次数，原校验错误保持。扫描仍按原时间、主键和 LIMIT 执行；键长度、格式、其它历史域及领取后的变化窗口仍需后续处理。

### CM-18 Legacy 恢复尝试计数的上限饱和（16T162）

领取时尝试数正常递增，达到 INT 最大值后保持该值，继续更新本次租约和领取身份，避免计数溢出阻断恢复。计数不会减少或重置，也不参与资金决议。

原领取资格、租约、重试退避及状态流程保持；最大计数记录仍能按原账本恢复。非法历史计数、类型迁移和完整来源校验仍需后续处理。

### CM-18 Legacy 恢复尝试计数的非负资格（16T163）

扫描和领取要求尝试计数非负，负计数记录不再占用扫描名额、被逐次改写或触发远端恢复。坏计数保持原值，供后续核对处理；正常计数递增及 INT 上限饱和保持。

SQL NULL 比较不满足资格，但本批不修改空值映射或历史 schema。其余领取条件、重试和状态流程保持，领取后的元数据变化及类型迁移仍需后续处理。

### CM-18 Legacy 支付意图的 boxed NULL 读取（16T164）

支付意图的内部 ID、用户 ID、尝试次数使用 typed JDBC 读取，SQL NULL 保留为 null，真实零、负值和整数边界仍原样读取，避免用零掩盖缺失事实。

本批仅调整读取表达，不修改 SQL、恢复资格、日期金额、类型或表结构。非负计数资格仍由原 SQL 判断；空值 schema 迁移、时间 NULL 保护和历史数据处理仍需后续完成。

### CM-18 Legacy 支付意图的 NULL 时间读取（16T165）

下次重试、创建和更新时间遇到 SQL NULL 时保留为 null，读取不再触发 NPE，也不填当前时间或默认日期。非 NULL 仍使用原 Timestamp 转换，毫秒和时区语义保持。

本批仅调整读取表达，不修改生产 SQL、恢复资格或表结构。缺失时间不代表合法资金事实；历史迁移、业务时间域及完整来源校验仍需后续处理。

### CM-18 Legacy 恢复历史零日期过滤（16T166）

恢复扫描和领取要求下次重试、创建、更新时间的年不小于1000，月和日非零；租约仍允许NULL，否则满足同一条件。历史零日期在LIMIT和领取写入之前被过滤，避免JDBC读取失败阻断健康记录，也不自动覆盖坏时间或递增尝试数。

正常日期、到期边界和原扫描顺序保持。过滤仅检查非零年月日，月份天数、完整公历有效性、时间先后、时钟偏差和领取后的变化窗口仍需后续处理。

### CM-18 Legacy 恢复历史月日上界过滤（16T167）

恢复扫描和领取在非零年月日校验后，要求四个意图时间的日数不超过当月最后一天；租约继续允许NULL。非闰年2月29日、2月30/31日及30天月份31日等历史值在LIMIT和领取写入之前被过滤，原记录保留。

合法闰日、月末日期、年份边界及原到期规则保持。本批仅补月日上界，历史数据修复、1582日历切换、时区、时间先后和完整来源/fencing仍需后续处理。

### CM-18 Legacy 支付意图时间直接读取（16T168）

四个意图DATETIME字段直接读取为LocalDateTime，保留数据库日期组件、毫秒精度和SQL NULL，避免Timestamp混合历转换使合法1582年日期读取失败。合法历史日期继续按原资格进入恢复流程，原记录无需修写。

本批只调整共享读取映射，SQL、到期及领取规则保持。已回归独立UTC测试JVM；全服务时区策略、其它驱动、时间先后及完整资金来源/fencing仍需后续处理。
