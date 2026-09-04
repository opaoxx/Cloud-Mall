# CloudMall Bug 修复经验记录

> 本文件记录每批实际遇到的问题、修复方案和验证结果；原始参考文档保持只读。

## 2026-09-05：IDEA 启动与 Docker Compose 第一批问题

### 发现的问题

1. Nacos 控制台登录后提示权限认证失败，账号没有 `public` 命名空间访问权限。
2. Docker Compose 中 RabbitMQ、Kibana 启动/访问出现 `HTTP 500: Internal Server Error`；Grafana 无法正常启动，初步怀疑存在端口冲突，待结合容器状态、日志和宿主机端口确认。
3. Sentinel Dashboard 使用 `root` 登录提示 `Invalid username or password`。
4. IDEA 启动后端服务失败：
   - cart：Feign 报 `No Feign Client for loadBalancing defined`，提示缺少 `spring-cloud-starter-loadbalancer`；
   - gateway：Gateway predicates 使用了不能被 Spring Cloud Gateway 解析的裸路径，报 `must be of the form name=value`；
   - user/product/order/pay：`application.yml` 中 JDBC URL 含 `?serverTimezone=Asia/Shanghai`，未加引号导致 SnakeYAML flow mapping 解析失败；
   - stock：缺少可用数据源 URL/数据库驱动，报 `Failed to determine a suitable driver class`。

### 修复后新增的启动复现问题

5. 按 IDEA 等价方式使用 JDK 21 启动后，user/product/cart/gateway 已成功注册 Nacos；order/pay 仍因旧版 Seata/Sharding 依赖触发 JDK 模块访问限制（CGLIB `InaccessibleObjectException`）启动失败。
6. stock 的 datasource 配置已补齐，但启动仍有新的运行时异常，需读取完整启动日志后定位。

### 第二次启动复现（配置修复后的残余问题）

7. user/product/cart/gateway 已使用等价 IDEA 运行方式成功启动并注册 Nacos；order/pay 在 JDK 21 下因 Seata 1.5.2 内置 CGLIB 访问 `java.lang.ClassLoader#defineClass` 被 Java 模块系统拒绝，报 `InaccessibleObjectException`。
8. stock 的 MySQL 数据源已初始化，但启动阶段 Redis 连接报 `NOAUTH HELLO must be called with the client already authenticated`，说明 Redis 密码属性未被 Boot 2.7 的实际配置键正确绑定。

### 第三次启动复现（残余环境/兼容问题）

9. 统一改用 `spring.redis.*` 后，stock 仍连接 `localhost:6379` 并收到 `WRONGPASS`；容器内 `redis-cli -a root ping` 正常，说明 Windows/IPv6 localhost 路径可能命中了其他 Redis 监听端点。需要统一使用 `127.0.0.1:6379` 做 IDEA 本地连接并重新验证。
10. order/pay 使用 JDK 21 + `--add-opens java.base/java.lang=ALL-UNNAMED` 后能够启动并注册 Nacos，但 Seata 仍打印 `default.grouplist is required`，需确认事务组配置，不将该警告等同于事务链路已通过。

11. 重新打包时发现旧的 CloudMall JAR 进程仍在运行并占用 `cloud-mall-gateway-0.1.0-SNAPSHOT.jar`，Maven `spring-boot:repackage` 无法重命名为 `.original`；需要先停止本项目旧进程再构建。

12. 按 `spring.redis.*` 和 `127.0.0.1:6379` 修正后，stock 仍收到 Redis `WRONGPASS`；宿主机 6379 同时存在 Docker backend 与 WSL relay 监听路径，无法稳定命中 CloudMall Redis。需要改用独立宿主端口并同步 IDEA 配置。

### 第四次在线启动回归（业务发现与接口错误码）

13. user/product 服务虽能启动 HTTP，但 POM 缺少 Nacos Discovery 依赖，未注册实例；Gateway 对商品/用户路由返回 503。
14. 订单创建缺少 `Idempotency-Key` 时返回 500，API 契约要求返回 400 参数错误。

### 第五次在线回归（服务已注册后的 Gateway 上游问题）

15. user/product 已补 Nacos Discovery 并成功注册，7 个服务健康端点均为 200；但 Gateway `8080/api/products` 和 Nginx `8088/api/products` 仍返回 503，product 直连 `8082` 返回 200。需要区分 Gateway 服务发现缓存、注册 IP 可达性和路由上游连接配置。

16. 复核发现 Gateway 使用 `lb://cloud-mall-*` 路由，但 Gateway POM 缺少 `spring-cloud-starter-loadbalancer`；user/product 注册正常且注册 IP 直连可达，Gateway 仍返回 503。需要补齐 Gateway 负载均衡客户端依赖并重新启动验证。

### 第六次在线回归（鉴权响应与 Prometheus 监控）

17. 有效 Bearer 请求经 Gateway 返回 HTTP 200 但响应体为空；直连 user/cart 返回正常 JSON，问题集中在 Gateway 转发/响应处理链路，影响所有登录态前端接口。
18. 7 个业务服务的 `/actuator/prometheus` 均返回 404，Prometheus targets 全部 down；需要补齐 Micrometer Prometheus registry/endpoint 依赖与配置。

### 第七次在线回归（登录态转发与监控端点）

19. 业务服务均已启动并注册 Nacos，但带登录态访问 Gateway 的用户接口出现异常：一次复现为 HTTP 200、业务体显示未登录；需核对登录 Token、Redis `auth:token:*` 和 Gateway Reactive Redis 鉴权过滤器的完整链路。
20. Prometheus 采集目标的 `/actuator/prometheus` 仍全部 404，需为 7 个业务服务补齐 Micrometer Prometheus registry 依赖并验证端点。

### 修复记录

#### 已完成修复方案

- Nacos：通过 bootstrap 创建/绑定 `cloudmall_admin`，授予 public 资源 `:*:*` 的 `rw` 权限；登录账号统一为 `root/root`，并已验证 public namespace API 可访问。
- Sentinel：通过 Compose 的 `JAVA_TOOL_OPTIONS` 覆盖镜像默认 `sentinel/sentinel` 为 `root/root`，保留登录认证。
- Docker 端口：Windows 保留端口导致 3307、5672、5601 绑定失败，分别调整为宿主机 `13307:3306`、`15673:5672`、`15674:15672`、`15675:5601`；Redis 因 6379 存在 Docker/WSL 双路径改为 `16379:6379`。容器内标准端口不变。
- Docker 容器：补齐 Seata、Prometheus、Grafana、Sentinel healthcheck；RabbitMQ/Kibana/Grafana/Nginx 通过 Compose 重新创建并恢复。
- backend 启动：Feign 服务补 `spring-cloud-starter-loadbalancer`；Gateway predicates 改为 `Path=...`；JDBC URL 加引号；stock 补 MySQL datasource/driver；Redis 改用 Boot 2.7 的 `spring.redis.*` 和 `127.0.0.1:16379`；order/pay 补 Seata `127.0.0.1:8091`；backend README 记录 Java 17 及 JDK21 `--add-opens`。

#### 验证状态

- Docker Compose 及中间件验证已完成；7 个业务 JAR 等价 IDEA 启动验证已完成，8080–8086 全部监听并注册 Nacos。
- 真实业务 API、跨服务 Feign/Seata/RabbitMQ/ES 和浏览器链路由 QA 继续回归。

#### 本批最终修复补充

- user/product POM 增加 Nacos Discovery，Gateway POM 增加 LoadBalancer；7 个服务均可注册到 public namespace。
- Gateway 鉴权过滤器修正 Reactor 空流处理，避免下游响应体被错误吞掉；Prometheus registry 已加入 7 个服务。
- Windows 端口隔离最终采用 MySQL `13307`、Redis `16379`、RabbitMQ `15673/15674`、Kibana `15675`；容器内部端口保持标准值。
- Nacos bootstrap 固化 `root` 用户、`cloudmall_admin` 角色及 public `:*:*` `rw` 权限；Sentinel 登录覆盖为 `root/root`。

#### 当前验证结果

- Docker Compose 配置通过；12 个常驻 CloudMall 容器运行且 healthcheck 正常。
- 7 个 Spring Boot JAR 在 JDK21 等价 IDEA 运行方式下均监听 8080-8086 并注册 Nacos；order/pay 使用 `--add-opens java.base/java.lang=ALL-UNNAMED`。
- Gateway `/api/products` 返回 200；有效 Bearer `/api/users/me` 返回 JSON；无 token `/api/cart` 返回 401。
- 7 个 `/actuator/prometheus` 返回指标，Prometheus targets 全部 up。
- Docker Maven `mvn -B -ntp test` 与 `package -DskipTests` 主流程均 `BUILD SUCCESS`；最新配置回归后需以最终 QA 报告为准。

### 本批最终 QA 结果

- 7 个 Spring Boot 服务在 JDK21 等价 IDEA 方式下均启动成功并注册 Nacos；order/pay 使用 `--add-opens java.base/java.lang=ALL-UNNAMED`，Seata 已连接 `127.0.0.1:8091`。
- Gateway `/api/products` 返回 200；有效 Bearer 的 `/api/users/me` 返回非空 JSON；无 Token 的 `/api/cart` 返回 401；Nginx 8088 代理正常。
- 7 个 `/actuator/prometheus` 返回 200，Prometheus targets 7/7 up。
- Nacos `root/root` public namespace、Sentinel `root/root`、RabbitMQ `root/root`、Kibana、Grafana 均验证通过；RabbitMQ/Kibana/Grafana 的原始 500/启动问题未再现。
- 当前未确认代码 P1；订单支付、库存、秒杀、ES 真实业务链路因商品/SKU/活动测试数据为空暂未闭环，不将测试数据缺失误判为功能通过或失败。
- 主流程 Docker Maven `mvn -B -ntp test` 与 `package -DskipTests` 均 `BUILD SUCCESS`；当前 workspace 仍保留未提交变更，未自动清理或推送。

### 验证记录

> 详见“修复记录”和后续 QA 回归结果。

## 2026-09-05：当前补充批次——Seata 在 IDEA/JDK 21 下启动失败

### 发现的问题

21. order/pay 使用 IDEA 的 JDK 21 直接启动时，Seata 1.5.2 内置 CGLIB 反射访问 `java.lang.ClassLoader#defineClass`，触发 `InaccessibleObjectException`，导致 Spring Boot Application 启动失败。

### 处理边界

- 该问题首先按运行时兼容性问题诊断，不升级计划冻结的 Seata 1.5.2，不关闭 Seata，也不修改业务契约。
- backend 负责确认代码/配置是否已包含 Seata 地址和 JDK 21 兼容说明；IDEA 的 Project SDK、Maven Runner JRE 和 Run Configuration JRE 仍需由使用者按项目说明设置。

### 诊断、修复与验证结果

- backend 复核确认：order/pay 的 Seata `service.grouplist.default` 均为 `127.0.0.1:8091`，Seata 1.5.2 与 Java 17 编译目标均符合项目冻结约束；本轮无需修改 backend 文件。
- JDK 21 无 VM 参数时，order JAR 稳定复现 `module java.base does not "opens java.lang" to unnamed module`；加入 `--add-opens java.base/java.lang=ALL-UNNAMED` 后，order/pay 均启动成功、监听 8084/8086，并打印 `register TM success`。
- Docker Maven Temurin 17 执行 order/pay 及 common 的 `test package` 成功；common 20 项测试通过，order/pay 编译打包成功。
- 最终解决方案：IDEA 的 Project SDK、Maven Runner JRE、order/pay Run Configuration JRE 统一使用 JDK 17；若必须使用 JDK 21，将参数放入 VM options（不是 Program arguments）。
- 当前剩余风险：本机可见 JDK 为 21.0.10，若用户仍直接点击 IDEA Run 且未设置上述参数，order/pay 会再次复现同一启动失败；这是本地运行环境配置风险，不是代码修复项。

### QA 复核

- qa 只读检查：Compose 配置、Seata health、order/pay Seata 地址、Java 17/JDK 21 运行说明均通过。
- qa 检查活动源码/配置/部署文件未发现 `wire_api`、`javaagent`、`--add-exports`、`illegal-access` 等过时启动参数。
- qa 因宿主 PowerShell PATH 没有 `mvn` 将本机 Maven 项目标为环境阻塞；主 Agent 已使用 `maven:3.9.9-eclipse-temurin-17` Docker Maven 补跑全量 `backend` 测试，9 个模块 `BUILD SUCCESS`，common 20 项、product 16 项测试全部通过，其余模块无测试失败。
- 本批闭环结论：无新增代码级 bug；问题由 IDEA 使用 JDK 21 且未设置 VM options 引起，归属开发环境配置。
