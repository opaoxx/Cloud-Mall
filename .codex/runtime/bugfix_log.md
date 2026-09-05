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

## 2026-09-05：Stock/Pay 启动端口冲突

### 发现的问题

22. `StockApplication` 启动时报 Web server port `8085` already in use。
23. `PayApplication` 启动时报 Web server port `8086` already in use。

### 初步诊断

- 配置中的 Stock/Pay 端口仍分别为 8085/8086，未发现端口被错误改成相同值。
- 当前复核时 8085/8086 均无 LISTEN 进程，也没有发现对应的 CloudMall Java 残留进程；倾向于 IDEA 重复启动同一服务或启动时已有旧实例占用。
- 本批不改端口配置，先通过 PID/命令行确认占用者，避免掩盖重复启动问题。

### 复现与确认结果

- 直接运行 Stock JAR 在固定端口 8085 稳定复现 `PortInUseException`；将运行参数临时改为 `--server.address=127.0.0.1` 仍失败，排除 Spring Boot 默认全地址绑定导致的假象。
- Stock 临时使用 `--server.port=18085` 后完整启动并注册 Nacos；Pay 临时使用 `--server.port=18086` 后也完整启动并注册 Seata，证明两个服务自身正常。
- 端口占用证据：8085 对应 `svchost.exe` 的 `WpnService`，连接 `198.18.0.1:8085 -> 198.18.0.4:443`；8086 对应 `com.vortex.helper.exe`，连接 `192.168.1.4:8086 -> 4.145.79.82:443`。二者均不是 CloudMall Java 进程。
- 处理结论：停止/退出占用这些端口的外部网络程序后，再从 IDEA 启动 Stock/Pay；不修改项目端口，不停止未知系统进程，不调整网关路由。

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

## 2026-09-05：SKU DTO、订单月表与 RabbitMQ JSON 消息批次

### 发现的问题

24. 商品 SKU 接口返回对象型 `skuSnapshot`，cart/order Feign DTO 声明为 `String`，导致购物车新增、普通下单和秒杀异步消费出现 `DecodeException`/500。
25. 订单服务按当前月份动态访问物理表，但 schema 只预建到 202608；进入 202609 后订单表不存在。
26. RabbitTemplate 默认发送 Java serialized object，消费者使用 JSON converter，商品索引和秒杀订单消息出现消息转换失败并进入 DLX。
27. 修复过程中发现 order 秒杀消费者切换为 Map 参数后，catch 块变量名与消息变量冲突，导致编译失败。
28. 运行时测试夹具 SKU 900001 未初始化 Redis `stock:available:900001`，普通下单被错误判定为库存不足；该项属于现有库存初始化缺口，未并入本批修复。

### 修复方案与验证结果

- cart/order 的 SKU DTO 统一为 `Map<String,String>`；订单写入 JSON 快照，订单查询将 JSON 反序列化回结构化对象。
- 新增 `OrderTableInitializer`，启动时确保 order/item/status 模板及当前月前一月到未来两个月的物理表存在；schema 增加 item/status 模板表。
- product/stock/order 统一注册 `Jackson2JsonMessageConverter`；stock 秒杀生产者发送 Map envelope，order 消费者直接接收 `Map<String,Object>`。
- Docker Maven 全量 `test`：9 个模块 BUILD SUCCESS，common 20 项、product 16 项通过，其余模块无失败。
- 运行时回归：购物车新增成功；普通订单成功落到 `mall_order_202609`；支付成功后订单为 `PAID`，库存从 100→99 且预扣转已售。
- 分表初始化日志确认覆盖 2026-08 至 2026-11。秒杀入口在消费者参数修复前已验证 Redis 预扣正确；消费者参数修复后的秒杀消息回归待重新启动最新 order jar 后复测。

### 后续构建问题

29. order 进程仍运行旧 jar 时，Docker Maven 在 `spring-boot:repackage` 阶段无法把构建产物重命名为 `.original`，导致 order 定向打包失败。

30. RabbitMQ JSON converter 已能将秒杀消息转换为 Map，但秒杀消费者分支仍直接把 Map 传入 MySQL JSON 列，触发 `Cannot create a JSON value from a string with CHARACTER SET 'binary'`，消息再次进入 DLX。

### 修复完成补充

- order 秒杀消费者改为直接接收 `Map<String,Object>`，并统一将结构化 SKU 快照序列化为 JSON 字符串后写入 JSON 列。
- 停止占用 jar 的本轮 order 进程后重新打包，Docker Maven order/common `package` 成功；之前的变量名冲突和 jar 文件锁问题均已解除。
- 最新运行时验证：购物车新增成功；普通订单落库并完成支付；秒杀入口 accepted 后订单成功落库为 `PENDING_PAYMENT`，主队列为 0，未新增 DLX，order 日志无 DecodeException/DataIntegrityViolation。
- 第 28 项 RabbitMQ 消息转换问题已闭环；第 25 项分表问题由启动初始化器自动覆盖 2026-08 至 2026-11。
- 第 28 项之外的库存 Redis key 初始化缺口仍未纳入本批业务修复，测试时通过夹具显式初始化 `stock:available:900001`。

## 2026-09-05：扩大修复范围——库存缓存初始化与测试产物管理

### 发现的问题

31. stock 服务启动初始化逻辑只写死 `stock:available:1=100`，数据库中其他 SKU 没有 Redis 可售库存 key，导致普通下单被错误判定为库存不足。
32. JMeter 结果目录和本地服务日志未被 `.gitignore` 覆盖，容易把大体积、不可复用的运行产物误提交；JMX 计划和性能报告仍应纳入版本追踪。

### 本批修复方案（待验证）

- stock 启动时从 `stock_sku` 读取所有 SKU 的 `available_quantity`，对缺失 Redis key 执行初始化，不覆盖 Redis 中已有的运行态库存。
- `.gitignore` 忽略 `.codex/runtime/jmeter-*`、`.codex/runtime/*-logs/` 和 `.codex/runtime/*.jtl`，保留 `.jmx` 计划、Markdown 报告和 Bug 日志。

### 修复完成与验证结果

- stock 启动初始化改为查询 `stock_sku` 全量 SKU，对缺失的 `stock:available:{skuId}` 执行 `setIfAbsent`，不覆盖已有 Redis 运行态库存。
- Docker Maven stock/common `package` 成功；stock 模块无测试失败。
- 删除 Redis `stock:available:900001` 后重启新 stock jar，Redis 自动恢复为数据库可售库存 `99`；`GET /api/stock/skus/900001` 返回 `availableQuantity=99`。
- Git 忽略规则验证通过：JMeter 结果目录和本地日志不再出现在待提交列表；JMX 计划和 Markdown 报告仍可追踪。
- 本批闭环结论：库存缓存初始化问题已修复；库存服务仍未实现 Redis 与数据库在异常崩溃后的主动重建/校准，该项保留为后续独立问题。

## 2026-09-05：Redis 与数据库库存异常后的主动校准/重建——完成

### 修复结果

- 新增 `StockCacheReconciler`，stock 启动时从 `stock_sku.available_quantity` 全量重建普通库存 Redis key，覆盖 key 缺失和已有值漂移两种情况。
- 移除 `StockController` 中重复的库存初始化逻辑，避免多个启动钩子产生不同校准语义。
- Docker Maven stock/common `package` 成功。
- 将 `stock:available:900001` 人为改为 `1` 后重启 stock，Redis 恢复为数据库值 `99`；接口返回 `availableQuantity=99`；日志确认校准 1 个 SKU 且无启动异常。

### 遗留边界

- 本批只在 stock 启动期校准，不在运行中定时覆盖 Redis，避免与正常扣库存并发竞态。
- 秒杀库存使用独立 Redis key，未纳入普通库存校准；异常期间的 reservation 恢复和秒杀最终对账需另行设计。

## 2026-09-05：Redis 与数据库库存异常后的主动校准/重建

### 发现的问题

33. stock 服务此前只对缺失 key 做初始化，Redis 中 `stock:available:{skuId}` 已存在但数值错误时不会校准；Redis 数据整体丢失后也没有独立的全量重建入口。

### 本批修复方案（待验证）

- 新增 stock 启动期 `StockCacheReconciler`，以 MySQL `stock_sku.available_quantity` 为普通库存持久化基准，全量重建/校准 `stock:available:{skuId}`。
- 删除 controller 中只处理 SKU 1 的硬编码初始化逻辑；不触碰秒杀库存 key 和运行中的周期性扣库存流程。
