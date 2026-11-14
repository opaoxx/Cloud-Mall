# CloudMall

基于 Spring Cloud 和 React 的微服务商城学习项目，包含商品检索、购物车、订单、库存、余额支付和秒杀业务。

## 技术栈

- 后端采用 Java 17、Spring Boot 2.7.18、Spring Cloud 2021.0.5、Spring Cloud Alibaba 2021.0.5.0、MyBatis-Plus。
- 业务组件包括 Nacos、Sentinel、Seata、RabbitMQ、Redis、MySQL、Elasticsearch 和 Sharding-JDBC。
- 前端采用 React、TypeScript、Vite 和 React Router。
- 本地部署采用 Docker Compose、Nginx，监控组件包括 Prometheus、Grafana 和 Zipkin。

## 项目结构

```text
backend/
  cloud-mall-common/    公共类型与基础能力
  cloud-mall-gateway/   网关、路由与鉴权
  cloud-mall-user/      用户、地址与余额
  cloud-mall-product/   商品、分类与搜索
  cloud-mall-cart/      Redis 购物车
  cloud-mall-order/     订单与状态管理
  cloud-mall-stock/     库存预扣、确认与回滚
  cloud-mall-pay/       模拟支付与结果恢复
  database/            初始化 SQL、迁移和数据导入脚本
deploy/                中间件配置与部署辅助脚本
frontend/              React 前端
pom.xml                Maven 聚合工程
docker-compose.yml     本地中间件与 Nginx
```

## 本地运行

准备 JDK 17、Maven 3.9+、Node.js 22.12+ 和 Docker Compose。IntelliJ IDEA 可直接导入根目录的 `pom.xml`。

### 构建前后端

在仓库根目录执行后端打包。

```bash
mvn -Dmaven.test.skip=true package
```

构建前端静态资源，供 Compose 中的 Nginx 使用。

```bash
cd frontend
npm ci
npm run build
cd ..
```

### 启动中间件

```bash
docker compose up -d
```

首次使用新 MySQL 数据卷时，会执行 `backend/database/schema.sql`，创建数据库、表和演示商品。已有数据库的升级脚本保存在 `backend/database/`。

本地开发的 MySQL、Redis、RabbitMQ、Nacos 和 Grafana 使用 `root` 账号或 `root` 密码，具体连接参数见各服务的 `src/main/resources/application.yml`。配置用于本地学习环境。

| 组件 | 宿主机端口 |
| --- | --- |
| MySQL / Redis | 13307 / 16379 |
| RabbitMQ / 管理界面 | 15673 / 15674 |
| Nacos | 18848 |
| Seata | 18091 |
| Elasticsearch / Kibana | 9200 / 15675 |
| Sentinel | 18858 |
| Prometheus / Grafana / Zipkin | 9090 / 3000 / 19411 |
| Nginx | 18088 |

需要发布默认 Sentinel 规则时，在 PowerShell 7 中执行以下命令。

```powershell
pwsh -File deploy/nacos/publish-sentinel-rules.ps1
```

### 启动业务服务

等待中间件就绪后，在 IDEA 中分别运行七个服务的 `*Application`，或在独立终端中运行各服务的 JAR。以用户服务为例。

```bash
java -jar backend/cloud-mall-user/target/cloud-mall-user-0.1.0-SNAPSHOT.jar
```

| 服务 | 端口 |
| --- | --- |
| Gateway | 8080 |
| User | 8081 |
| Product | 8082 |
| Cart | 8083 |
| Order | 8084 |
| Stock | 8085 |
| Pay | 8086 |

启动开发前端后，通过 `http://localhost:5173` 访问，`/api` 请求代理到网关。

```bash
cd frontend
npm run dev
```

也可通过 `http://localhost:18088` 访问已构建的静态页面，Nginx 将 API 请求转发到宿主机的 8080 端口。前端修改后，重新执行 `npm run build` 更新静态资源。

## 业务约定

- API 使用 Bearer Token，登录态存储在 Redis，默认有效期为两小时。
- 金额通过 JSON 字符串传输，Java 使用 `BigDecimal`。
- 业务时间采用 `Asia/Shanghai`，接口时间带 `+08:00` 偏移。
- 订单、支付等写入入口按接口约定传递 `Idempotency-Key`。
