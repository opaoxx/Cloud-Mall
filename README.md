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

### 扩充本地商品目录

需要使用 `F:\hmall\sql\hm-item.sql` 的完整商品数据时，在 MySQL 容器启动后执行：

```powershell
.\backend\database\import_hmall_catalog.ps1
```

脚本会先导入 hmall 商品源表，再将每条商品转换为 CloudMall 商品、SKU、库存、分类和热度数据，并校验迁移行数。它只重建商品和库存演示目录，不迁移 hmall 的订单或支付数据；由于旧购物车中的 SKU 会失效，脚本会清理 CloudMall 的 `cart:*` Redis 缓存。执行前应保留数据库备份。

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
