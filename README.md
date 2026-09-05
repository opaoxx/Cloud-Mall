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
