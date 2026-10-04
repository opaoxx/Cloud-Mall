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

Gateway 与订单服务启动时自带秒杀限流默认规则。Nacos 可持久保存开发环境规则；首次启动中间件后执行：

```powershell
.\deploy\nacos\publish-sentinel-rules.ps1
```

脚本只创建缺失的 `CLOUDMALL_DEV` 规则。需要用仓库中的规则覆盖现有 Nacos 配置时，显式添加 `-Force`。Sentinel Dashboard 的临时调整不会自动写回 Nacos；需要长期保留的调整应更新 `deploy/nacos/sentinel/` 的 JSON 并发布。

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

### 商品热度历史来源标记（CM-21 第一阶段）

现有 `sales` 和 `hot` 排序使用导入目录的历史快照，尚无实时浏览、搜索或成交事件链路。已有 MySQL 数据卷先运行可重复迁移，再按上一节的步骤扩展 ES mapping、关闭搜索开关并重新运行完整商品索引回填，确认每条历史热度记录和 ES 商品文档都有来源字段后再开启搜索：

```powershell
Get-Content -Raw -Encoding UTF8 backend/database/migrate_product_hot_source.sql |
  docker exec -i cloud-mall-mysql mysql -uroot -proot --default-character-set=utf8mb4
.\deploy\elasticsearch\extend-product-search-index.ps1
```

迁移只给仍与 `hm-item.item` 源商品数值逐行一致、来源仍为 `UNKNOWN` 的记录标记 `LEGACY_IMPORT/hm-item.item`；无法确认来源的行保持 `UNKNOWN` 并由只读审计告警。`GET /api/products/{id}/hot-stat` 的 `sourceType/sourceRef` 会说明来源。指标新鲜度审计每 5 分钟只读核对 MySQL 和 ES，历史快照超过 24 小时会告警；它不会自行增加浏览、搜索或销量。

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

秒杀并发复核可使用 [JMeter 测试计划](backend/loadtest/seckill-ladder.jmx)。先准备有效 Token、收货地址和独立的已开始活动；每个并发档位使用新活动，避免前一档消耗库存。PowerShell 示例：

```powershell
jmeter -n -t backend/loadtest/seckill-ladder.jmx `
  "-Jcloudmall.token=$token" "-Jcloudmall.activityId=$activityId" `
  "-Jcloudmall.skuId=$skuId" "-Jcloudmall.addressId=$addressId" `
  -Jcloudmall.threads=20 -Jcloudmall.loops=1 -l results.jtl
```

测试后同时核对 HTTP 受理、MySQL 秒杀受理/outbox/订单、Redis 配额及 RabbitMQ 死信；`409/429` 业务拒绝应与系统错误分开统计。

部分旧测试依赖的 Mockito/Byte Buddy 版本不支持 JDK 21，推荐使用 JDK 17 运行完整测试集。

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
