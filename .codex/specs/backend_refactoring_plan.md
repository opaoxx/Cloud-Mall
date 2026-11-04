# CloudMall 后端逐模块重构方案书

日期为 2026-11-04。方案责任人为 `pm_arch`，实施责任人为 `backend`，验证责任人为 `qa` 与主协调 Agent。

用户已确认采用中等深度重构，重点为规范命名、理顺分层、按业务职责组织代码，并在实施过程中通过 Git 保存可回退节点。当前已完成 Common 契约盘点、Cart 和 Gateway 重构，最新验证与回退信息见文末实施记录。

## 1. 使用方式与范围

本方案覆盖 Common、Gateway、User、Product、Cart、Order、Stock、Pay 八个后端 Maven 模块。采用现有技术栈、服务边界、标准 Maven 布局及项目分层约定，按模块完成可独立验证的结构调整。

实施时依次完成现状盘点、模块细化、基线验证、分步修改、专项回归、阶段提交和调用链复盘。每个模块的真实改名清单、移动清单及方法归属先写入本文实施记录，由 `pm_arch` 核对后交给 `backend`。本文中的候选类名用于指导细化，具体名称以实施前的实际职责和调用关系为依据。

功能扩展、技术升级、数据库迁移及新发现缺陷使用独立任务和提交。需要改变 API、Feign、核心表结构或跨服务架构时，先同步相关规格并完成 `pm_arch` 评审。Bug 按现有 `bugfix_log.md` 在修复前记录现象，修复后补充根因、方案和验证结果。

原收尾记录继续保存其历史结论。CM21 真实压测启动、guardian、授权与实验清理链中的遗留问题保持原归档状态。本方案的后端结构整理采用新的实施记录，未来测试按实际执行范围报告。

依据为 [项目规则](F:/Cloud-Mall/AGENTS.md)、[README](F:/Cloud-Mall/README.md)、[架构规格](F:/Cloud-Mall/.codex/specs/architecture.md)、[API 规格](F:/Cloud-Mall/.codex/specs/api_spec.md)、[数据库规格](F:/Cloud-Mall/.codex/specs/db_schema.md)、[产品规格](F:/Cloud-Mall/.codex/specs/prd.md) 和 [收尾说明](F:/Cloud-Mall/PROJECT_CLOSEOUT_2026-11-04.md)。

## 2. 方案形成时的现状

当前分支为 `codex/bugfix-batches`，检查时 HEAD 为 `12892854e45083ddee99d7a2afa393188c19e8f0`。检查时 `main` 指向 `d1877cf8`，未来重构分支从核定的当前 HEAD 建立。该值记录方案形成时的源码位置，实际重构基线在启动实施时重新确认。检查时已跟踪文件的工作区差异和暂存区差异为空，存在两个既有的未跟踪 `__pycache__` 目录，实施时按实际状态重新盘点。

下表来自生产 Java 源文件静态统计，包含 `package-info.java`，文件数量用于定位整理范围。

| 模块 | 主 Java 文件数 | 已观察到的结构问题或盘点重点 |
| --- | --- | --- |
| Common | 17 | 共享返回、异常、认证、序列化和消息公共能力的职责与依赖 |
| Gateway | 5 | 过滤、认证和配置之间的职责及路由隔离 |
| Cart | 18 | Controller 继承实现类，主 Service 接口为空，HTTP 与 Redis 编排集中 |
| Product | 83 | 主 ServiceImpl 约 902 行，检索、索引和热度相关类在同层较密集 |
| User | 82 | 主 ServiceImpl 约 1189 行，认证、资料、地址及资金处理集中 |
| Stock | 71 | 主 ServiceImpl 约 1034 行，普通库存、秒杀及恢复职责交织 |
| Order | 80 | 主 ServiceImpl 约 1319 行，创建、查询、状态变更及消息消费集中 |
| Pay | 163 | `service.impl` 有 43 个 Java 文件，TCC、交付、取消与证据读取需明确分组 |

Cart、User、Product、Order 的主 Controller 采用继承 ServiceImpl 的形式，对应主 Service 接口为空。Cart ServiceImpl 中可以直接看到 HTTP 映射、鉴权上下文读取、Feign 查询、Redis Lua 和序列化逻辑。以上为静态检查结果，方案阶段的运行测试状态为 `NOT_RUN`。

## 3. 目标分层与职责规则

业务模块保留 `controller`、`service`、`service.impl`、`mapper`、`domain.po`、`domain.dto`、`domain.vo`、`config`、`feign`、`util`。启动类位于服务根包，Gateway 按 `config`、`filter`、`util` 组织，Common 以已有公共能力包为基础整理。

```text
controller                 HTTP 映射、绑定、校验、认证上下文、响应装配
service                    有实际业务方法的稳定契约
service.impl               业务规则、事务编排、跨服务协作
mapper                     SQL、条件更新、幂等写入、持久化查询
domain.dto                 HTTP 请求及 Feign 传输对象
domain.vo                  对外响应对象
domain.po                  数据库、Redis、ES 持久化对象
config                     配置、Bean 注册、基础设施装配
feign                      服务间调用契约与必要的适配配置
util                       与单项业务流程解耦的工具
```

类较多的层可以按真实业务能力建立子包，例如 `service.impl.tcc`、`service.impl.recovery`、`mapper.tcc`。先以一层业务分组满足导航需要，新增一级包或调整整体分层时写明原因并评审。Consumer、Dispatcher、Scheduler 可以进入现有层的业务子包，其入口职责和事务职责分别记录。

Controller 通过构造器注入 Service，方法显式委托。HTTP 绑定、路径参数注解和响应装配回到 Controller，Service 接口声明具体参数与结果。迁移 `ApiResponse<?>` 时逐端点核对实际 JSON 和 HTTP 状态，保留异常携带的状态码、错误码、消息、响应头及 null 语义。

Controller 读取认证上下文后向业务方法传递明确的用户身份。Service 按操作语义核验资源归属和内部调用权限，异步恢复流程使用已持久化且可核验的身份来源。迁移时记录现有 AuthContext 的设置与清理时机，以及 Feign 转发身份的来源。

一个业务接口应覆盖一项可说明用途的业务能力。已有可复用实现优先保留，辅助类按变化原因和测试价值设置接口。拆分类别以职责、依赖和修改集中程度为依据，类行数作为排查线索。

数据库操作通过 Mapper 或 Mapper XML，PO 与表一一对应，保持 `@TableName`、`@TableId`、`@TableField`、`@Version` 等映射准确。Redis 和 ES 的 PO 保持现有持久化格式，Mapper 返回值与影响行数语义在 Service 中显式处理。

所有成员变量注明准确含义，public 方法、构造器、Feign 方法、Bean、消费者和定时任务补齐 JavaDoc。业务实现方法按实际流程使用 `// 1.`、`// 2.`、`// 3.` 注释，移除泛化占位说明。变量与参数表达真实含义，统一 import、缩进、括号和换行。

## 4. 命名与迁移登记

类名采用业务对象、动作和角色组合，方法名表达调用意图。`Service` 表示业务能力，`Mapper` 表示持久化访问，`Consumer` 表示消息接收，`Dispatcher` 表示待办派发，`Scheduler` 表示定时触发。DTO、VO、PO 后缀沿用项目约定，协议标识按兼容清单单独检查。

| 现有类 | 候选名称 | 迁移前核对 |
| --- | --- | --- |
| `Cm18SeataDemoService` | `SeataTransactionDemoService` | 全局事务代理、调用方、事务名称 |
| `Cm18SeataOrderBranchService` | `SeataOrderDemoBranchService` | 本地事务 Bean、传播行为、分支调用 |
| `Cm18SeataStockDemoMapper` | `SeataStockDemoMapper` | PO 表映射、Mapper 扫描、XML namespace |
| `Cm18SeataDemoController` | `SeataTransactionDemoController` | 演练入口映射、访问隔离、契约测试 |
| `Cm21JdbcAnchorObserver` | `JdbcTransactionAnchorObserver` | 测试辅助调用、外部运行入口、冻结材料引用 |
| `Cm21PhaseAnchorBridge` | `TransactionPhaseAnchorTestBridge` | 测试语义、包可见性、阶段观察与计时契约 |

业务代码、演练代码、测试辅助代码各自形成移动清单。演练相关类在既有层内按需使用 `demo` 子包，类名保留演练用途。CM 编号进入规格与变更追溯说明。CM21 测试工具先核对当前源码、测试及实验脚本依赖，再决定是否纳入当前模块改名切片。

重命名登记至少包含旧全限定名、新全限定名、旧文件、新文件、调用方、Bean 名称、限定符、反射字符串、Jackson 类型标识、RabbitMQ 类型头、Mapper XML、测试选择器和脚本入口。Java 类名变更与外部协议标识使用两列记录，逐项说明兼容方式。

历史冻结 runner、报告、原始证据与归档索引保留其原来源。新的执行入口如有需要，使用新版本引用和新记录，避免覆盖已有实验结论。

## 5. 实施顺序与提交节奏

推荐顺序为 Common 契约盘点 → Cart 样板 → Gateway → Product → User → Stock → Order → Pay → 全链路回归。Common 的实际改动随已评审需求分批完成，每次检查依赖方。

每个模块使用以下连续切片。可将较小且紧密关联的工作合为一个可验证提交，也可以继续细化复杂切片。

| 切片 | 交付内容 | 提交前检查 |
| --- | --- | --- |
| 准备 | 实际 HEAD、类与方法清单、调用链、接口与运行基线 | 改动范围和验证命令可定位 |
| 命名与归组 | 已确认的重命名、包移动、引用同步 | 生产及测试源码编译、相关契约检查 |
| HTTP 解耦 | Controller 委托、Service 契约、绑定迁移 | 路由、校验、鉴权、响应与异常检查 |
| 职责整理 | 拆分业务流程、复用已有逻辑、整理 Mapper 与数据对象 | 核心单测及事务、并发专项检查 |
| 验收 | 模块回归、核心代码逐行复盘、最终调用链、实施记录 | 通过范围、遗留项和回滚点明确 |

每次提交包含一个可解释的目的及其必要测试调整。只有通过该切片适用验证的提交才能记为可回退通过点。环境阻塞的工作记录实际状态，完成声明等待必需验证通过。

## 6. Cart 样板的具体方案

当前可见入口为 `all`、`add`、`update`、`delete`、`cleanupSnapshot`、`cleanupItem`、`checked`、`clearChecked`、`preview`。主流程依赖 `StringRedisTemplate`、`ObjectMapper` 和 `ProductClient`，当前不存在 `CartStoreService` 或其实现。

首个切片明确 `CartService` 的方法与返回类型，把 Controller 改为组合调用并迁移 HTTP 注解。初期业务计算可以继续集中在 `CartServiceImpl`，先使 normal HTTP 入口和现有测试均能覆盖真正的 Service Bean。

| 当前入口 | Service 候选业务方法 | 关键返回与行为 |
| --- | --- | --- |
| `all` | `listItems(userId)` | 当前购物车项数组，刷新时保留商品失效项可见性 |
| `add` | `addItem(userId, request)` | 同一次条件写成功的项，正数数量与合并溢出校验 |
| `update` | `updateItem(userId, skuId, request)` | 绝对数量，checked 缺失时保留原值 |
| `delete` | `deleteItem(userId, skuId)` | 原空响应语义 |
| `cleanupSnapshot` | `getCleanupSnapshot(userId, skuId)` | 原始 JSON 证明，目标缺失返回 null |
| `cleanupItem` | `cleanupItem(userId, skuId, snapshot)` | 已删除为 true，缺失或变化为 false |
| `checked` | `setItemChecked(userId, skuId, checked)` | 原 Boolean.TRUE 判定，缺失与 null 的 false 语义 |
| `clearChecked` | `clearCheckedItems(userId)` | 初读勾选且 JSON 相同的候选逐项清理 |
| `preview` | `previewSettlement(userId, selection)` | items、invalidItems、totalAmount、payAmount |

具体类型沿用已存在 DTO/VO 并按需要补齐独立对象。当前 Map 请求迁移为 DTO 时验证缺失字段、null、未知字段、重复 SKU 等兼容行为，预览金额继续以 BigDecimal 计算并输出原金额字符串。

第二个切片评估是否把 Redis 读写与原值条件操作提取为层内辅助实现，例如候选 `service.impl.CartStorage`。其职责可以包括完整 Hash 快照读取、JSON 编解码、原值条件写、原值条件删和 Redis 异常映射。只在这些操作具备独立职责与测试价值时提取，并复用现有算法。

商品事实获取与价格、SKU 身份核验可以先保留为清晰的私有方法。若购物车展示和结算预览形成独立变化原因，再评估候选 `CartSettlementService`。Feign 仍由现有 `ProductClient` 承担，额外接口需有真实调用与替换需要。

Cart 必须保留以下行为。

- Redis Key 为原用户的 `cart:{userId}`，Hash 字段与 PO 中 SKU 身份精确匹配。
- 完整 Hash 的任一损坏行会阻止后续写入或清理，原 JSON 字符串作为条件比较依据。
- 条件写与 30 天 TTL 更新由同一 Lua 原子执行。仅明确返回 0 时按原上限三轮重读，未知结果及依赖异常直接传播。
- 更新数量、勾选和商品摘要时保留其他并发字段，刷新冲突按原 409 处理。
- 清理快照使用原 JSON 精确匹配，保持一次条件删除、跳过变化项和成功前序操作的语义。
- 商品身份、状态与价格证明保持原校验顺序，依赖异常和刷新冲突按原错误分类传播。
- 结算预览保持显式 SKU 与 checked 选择规则、失效商品分类及原金额文本格式。

Cart 当前有 12 个 `*Test` 文件和 10 个 `*IT` 文件，数量表示文件数。基线审查后选择 `CartAddCompareWriteTest`、`CartQuantityMergeTest`、`CartPutCheckedCompareWriteTest`、`CartCheckedCompareDeleteTest`、`CartCleanupSnapshotTest`、`CartHashIdentityTest`、`CartProductIdentityTest`、`CartProductPriceTest`、`CartPreviewQuantityTest`、`CartRefreshCompareWriteTest`、`CartRedisOutageTest`、`CartItemCreationTest` 中适用的无外部资源测试。

Redis 专项覆盖并发数量合并、checked 与数量互不覆盖、条件清理、坏数据零写入、TTL、故障与未知执行结果。跨服务清理与 Order 的 `CartCleanupContractTest`、`OrderCartCommitBoundaryTest` 一起核对。每个测试先阅读 setup、连接及清理逻辑，再记录实际运行命令与资源范围。

## 7. 各模块的细化方向

### Common

盘点 ApiResponse、BizException、ErrorCodes、认证上下文、全局异常、时间金额及消息公共能力的引用。候选调整以现有公共包职责为单位，业务专属状态、业务表映射和业务判断回到所属服务。

保留统一响应、异常 HTTP 状态、认证上下文生命周期、公共指纹和消息字段语义。切片采用引用盘点、必要的命名整理、依赖方同步。已有测试包括 `CommonRulesTest`、`BackendP1RulesTest`、`GlobalExceptionHandlerCacheControlTest`、`SeckillRequestFingerprintTest`，运行前核对覆盖和资源使用。涉及公共类型时对八模块编译并选择依赖方回归。

### Gateway

按配置装配、身份验证过滤和请求工具整理现有五个文件，核对 filter 顺序、白名单、内部路由隔离、跨域和错误响应。候选命名应明确如身份验证过滤或路由配置，具体以现有职责命名。

切片采用命名与职责注释、过滤链整理、契约验收。保留 Token TTL 2 小时、Bearer 语义、Redis 失败响应、下游身份头和内部 TCC 路由屏蔽。已有 `AuthenticationRedisOutageTest`、`SeckillSentinelGatewayConfigTest`、`TccInternalRouteIsolationTest` 用于定向检查，必要时补充实际路由映射回归。

### Product

将分类、商品/SKU 维护、查询搜索、活动管理、索引同步、热度统计列为业务分组。主 Service 的候选接口为 `CategoryService`、`ProductQueryService`、`ProductManagementService`、`SeckillActivityService`，已有索引和指标类按职责复用与归组。

切片先完成 HTTP 解耦和分类/商品职责，再整理活动、索引和统计。保留商品快照字段、金额文本、ES 检索路由、索引版本、outbox 发布确认、消费幂等、批次与单条消费边界及热度模式。`ProductContractTest`、`ProductKeywordSearchRoutingTest`、`ProductIndexConsumerTest`、`ProductIndexDocumentLoaderTest`、`ProductIndexRebuildJobTest` 等作为候选，真实 MySQL、ES、RabbitMQ 验证单独记录。

### User

按认证、资料、地址、余额扣款退款、本地 TCC、只读证据分组。候选接口为 `AuthenticationService`、`UserProfileService`、`UserAddressService`、`BalanceDebitService`、`BalanceRefundService`，结合现有 Balance 服务确定最终方法归属。

切片先处理认证与资料/地址，再拆分资金入口与查询，最后整理 TCC 和证据读取。保留余额/冻结额/流水原子提交、operationKey/payNo 互斥、原资金证明、退款累计、Fence 与本地事务关系、回调身份和禁用用户语义。候选测试为 `AddressValidationTest`、`BalanceContractTest`、`BalanceLedgerServiceTest`、`BalanceTccDebitServiceTest`、`LoginRedisOutageTest`，并按实际改动显式运行 MySQL/TCC/Fence 专项。

### Stock

按普通库存查询、预扣确认回滚、秒杀配额结算、缓存核对、outbox 与恢复、Seata 演练分组。候选业务接口为 `StockQueryService`、`StockReservationService`，现有秒杀和核对服务优先复用。

切片先整理演练命名与普通库存，再整理秒杀及恢复。保留防超卖条件更新、流水与库存事务、Redis Lua 原子性、原操作键与受理身份、lease/token CAS、outbox 原事件和恢复扫描边界。候选覆盖 `SeckillQuotaSettlementServiceTest`、`SeckillQuotaReconciliationTest`、`SeckillRecoveryScanFairnessTest` 等现有测试，真实库存并发、Redis 与数据库一致性另列验证。

### Order

按订单创建、查询、状态流转、秒杀接收、超时消费、库存动作、分片与演练分组。候选接口为 `OrderCreationService`、`OrderQueryService`、`OrderStateService`、`SeckillOrderService`，消息入口委托真实业务方法。

切片先完成演练命名与 HTTP 委托，再逐项迁移创建、查询、状态流转，最后整理消息和恢复。保留已提交 INIT、远程预扣、RESERVED、订单与 outbox 提交的先后关系，PAID/CANCELLED CAS 与库存动作同事务，结果未知与恢复语义、月表路由及 DATETIME(3) 精度。

候选测试为 `NormalOrderIdempotencyKeyTest`、`OrderCreateCompensationProofTest`、`OrderCartCommitBoundaryTest`、`OrderCreationTimeTest`、`FeignTimeoutConfigurationTest` 及对应实际存在的分片和故障专项。`synchronized` 方法拆分时先记录当前实例及锁对象范围，评估并发调用路径后再移动，锁语义随业务职责一起验证。

### Pay

以现有实现为基础建立支付入口、Legacy、TCC bridge/coordinator/receipt、交付、取消退款、恢复调度、证据读取分组。已有 `PayConsistencyService`、`PayTccCoordinatorServiceImpl` 等保留其独立职责，减少一条业务链跨越无关包的查找成本。

切片先整理包与命名，再处理支付入口，随后按 Legacy、TCC 编排、交付取消、恢复与证据逐组完成。保留持久 debit_mode 路由、原 XID 和分支绑定、一次性 dispatch、单向决议、Fence、本地事务、远程写前证明、未知状态和固定退款键、lease/token 保护及回调幂等。

候选测试为 `BalancePaymentContractTest`、`PayDebitModeIsolationTest`、`PayConsistencyServiceTest`、`PayLegacyIntentClaimTest`、`PayLegacyFailureOutcomeTest`、`PayRecoveryScanBoundaryTest` 及实际涉及的 TCC 测试。MySQL、真实 RM/TC、回调丢失、响应未知、交付退款竞争按所改链路执行专项验收。

## 8. 兼容与事务核对矩阵

| 范围 | 每个相关切片需要核对的内容 |
| --- | --- |
| HTTP | URL、方法、请求头、权限、参数绑定、校验先后、状态码、错误码、响应头、JSON 字段与 null |
| Feign | 服务名、路径、方法、DTO、身份头、3 秒连接/5 秒读取超时、仅幂等 GET 的有限重试 |
| DB/PO | 表与列、主键生成、精度、乐观锁、影响行数、唯一约束、历史 null 与旧状态兼容 |
| Redis | Key、数据类型、字段名、JSON 原文、TTL、Lua 参数/返回值、原子性、故障与未知结果 |
| RabbitMQ | 交换机、队列、routing key、TTL+DLX、eventId、类型头、publisher confirm、ACK 与幂等 |
| Seata | XID、actionName、分支注册、Fence、事务传播、代理 Bean、回调身份和异常语义 |
| Sentinel | 资源名、规则数据源、热点参数位置、限流与降级返回 |
| Sharding-JDBC | 逻辑/物理表、分片键、月份与时区、动态装配、建表和跨月查询 |
| Elasticsearch | 索引、文档字段、版本、序列化与查询路由 |
| Spring | Bean 注册、默认 Bean 名、Qualifier、条件配置、扫描、AOP 切点、启动检查和调度开关 |
| 表示与历史数据 | 金额字符串与 BigDecimal、ISO-8601 +08:00、Asia/Shanghai DATETIME(3)、旧消息及旧数据 |

拆分事务方法前列出事务起止、传播级别、回滚异常、锁对象、远程调用位置、提交后动作和恢复入口。独立 Bean 的事务调用经过 Spring 代理，自调用语义按现有行为核对。新增接口或类可能影响 AOP 匹配、消息类型头和反射，均纳入迁移清单。

## 9. 验证方式与通过标准

构建入口为 `F:\Cloud-Mall\pom.xml`，项目目标 JDK 为 17。执行前确认实际 Java 与 Maven 路径、版本和现有依赖。方案检查时 `mvn` 未在 PATH，实际可用 Maven/JDK 17 路径留到实施启动时填入记录。

根与子 POM 未发现专门的格式化、Surefire 或 Failsafe 配置。按项目实际工具执行 Java 格式化，先记录工具路径、版本和确切命令。测试先审查源码和默认发现范围，再选定无外部资源测试，真实集成测试记录依赖、数据与清理范围。

下面为 PowerShell 命令模板，`$mavenExecutable`、`$javaExecutable`、`$testSelection` 在执行前填写为核实后的实际值。

```powershell
Set-Location F:\Cloud-Mall
& $javaExecutable -version
& $mavenExecutable -version
& $mavenExecutable -pl backend/cloud-mall-cart -am test-compile -DskipTests
& $mavenExecutable -pl backend/cloud-mall-cart -am test "-Dtest=$testSelection" '-Dsurefire.failIfNoSpecifiedTests=false'
git diff --check
```

以 Maven `-version` 实际输出中的 Java version 和 Java home 确认本次构建使用核定的 JDK 17。单独 Java 命令输出只说明该可执行文件的版本，Maven 的 JDK 来源另行核对。需要指定时，为本次 PowerShell 进程设置已核定的 `JAVA_HOME`，再检查 Maven 实际输出并将结果写入记录。

指定 `-am` 时 `surefire.failIfNoSpecifiedTests=false` 仅用于依赖模块没有同名测试的情形，目标模块的实际执行类与用例数仍需检查。`test-compile -DskipTests` 覆盖源码及测试编译，测试执行结果另行记录。默认 `mvn test` 的范围与 `*IT` 的显式选择分别说明，不能以源码编译代替运行结果。

每项记录命令、cwd、工具版本、开始结束时间、返回码、测试类与用例数、失败/错误/跳过、依赖和结果位置。使用 `PASS`、`FAIL`、`BLOCKED`、`NOT_RUN` 表示结果。基线已有失败先定位来源，比较相同条件下的前后结果，并说明与本次改动的关系。

新增测试优先补缺失的回归保护，例如 HTTP 继承改组合后的真实路由、事务拆分后的提交边界，以及改名后的序列化兼容。已有测试若耦合私有方法或旧构造器，先保留其业务断言，再迁移到真实入口。模块验收需要逐行阅读核心代码并记录最终调用链。

Common 的 `BackendP1RulesTest` 会跨模块读取源码并检查具体文件与字符串，其中包括 Product/Order ServiceImpl 中的 HTTP 注解、超时队列及月份解析位置。相关结构迁移时同步改为相应 HTTP、行为或契约断言，适合保留源码规则的断言更新到准确职责位置，保持原保障目的。测试修改与对应结构迁移在同一提交完成，验证选择包含 Common，即使 Common 生产代码在该切片没有改动。断言调整依据记录在实施清单中。[现有规则测试](F:/Cloud-Mall/backend/cloud-mall-common/src/test/java/com/cloudmall/common/BackendP1RulesTest.java)

## 10. Git 基线、提交和回滚

### 10.1 启动实施时建立基线

先检查工作区、暂存区、当前分支和实际 HEAD，逐项确认既有修改的归属。用户与其他任务的文件保留原状态。实际基线需要同时记录源码 SHA、已选测试结果、配置模式和必要的测试数据说明。

```powershell
Set-Location F:\Cloud-Mall
git status --short
git diff --stat
git diff --cached --stat
git branch --show-current
git rev-parse HEAD
git log -5 --oneline
```

重构启动前确认基线可复现，再创建专用分支 `codex/refactor-backend` 和唯一基线标签，例如 `refactor/backend-baseline-20261104`。若这些名称已存在，先检查其指向，使用新的带日期或序号名称。创建前的现有改动按归属处理并记录，避免将其混入基线说明。

```powershell
git branch --list codex/refactor-backend
git tag --list refactor/backend-baseline-20261104
git switch -c codex/refactor-backend
git tag -a refactor/backend-baseline-20261104 -m '后端重构基线，验证范围见方案书实施记录'
```

采用同一重构分支的线性阶段提交，每个模块完成后记录通过点，可增加唯一模块验收标签，并在标签说明与实施记录中附实际验证范围。共享分支采用追加提交与 revert 保存历史。需要发布或推送时按用户后续指令执行。

### 10.2 精确提交与方案书版本化

只暂存当前切片的明确文件，检查暂存差异后提交。提交前使用 `git diff --cached --name-only` 核对完整暂存清单等于本次允许路径，逐文件阅读暂存差异。既有其他任务的暂存内容先按归属隔离处理，保留其差异和原状态，避免通过整仓清空 index 处理。命名与移动提交尽量独立于业务拆分，格式化限制在本次处理文件与必要区域。提交消息写清模块与阶段目的，例如 `refactor(cart): use service delegation for HTTP endpoints`。

```powershell
git add -- backend/cloud-mall-cart/src/main/java/com/cloudmall/cart/controller/CartController.java backend/cloud-mall-cart/src/main/java/com/cloudmall/cart/service/CartService.java backend/cloud-mall-cart/src/main/java/com/cloudmall/cart/service/impl/CartServiceImpl.java
git diff --cached --name-only
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m 'refactor(cart): use service delegation for HTTP endpoints'
git rev-parse HEAD
```

当前 `.gitignore` 的 `*.md` 会忽略新建 Markdown，已被跟踪的规格文件仍正常受 Git 管理。本方案书首次纳入 Git 时可使用以下单独文档提交，后续更新按普通已跟踪文件处理。执行文档提交前同样检查完整暂存集，清单应只有本方案书，既有暂存内容按上一段的归属规则处理。方案书被纳入之前，其本地内容需要单独保管。

```powershell
git check-ignore -v .codex/specs/backend_refactoring_plan.md
git add -f -- .codex/specs/backend_refactoring_plan.md
git diff --cached --name-only
git diff --cached --check
git diff --cached -- .codex/specs/backend_refactoring_plan.md
git commit -m 'docs: record reviewed backend refactoring plan'
```

### 10.3 未提交修改的保存

回退前重新检查工作区。属于当前任务的已跟踪改动可以先保存 worktree 与 index 两份补丁，再验证文件存在和可读取。PowerShell 使用 Git 自身 `--output` 生成补丁，保存位置应选本次专属目录。`$ownedPaths` 填写当前任务拥有且已经核对的明确文件列表，以下以 Cart 三个切片文件示意。

```powershell
$ownedPaths = @(
    'backend/cloud-mall-cart/src/main/java/com/cloudmall/cart/controller/CartController.java'
    'backend/cloud-mall-cart/src/main/java/com/cloudmall/cart/service/CartService.java'
    'backend/cloud-mall-cart/src/main/java/com/cloudmall/cart/service/impl/CartServiceImpl.java'
)
git diff --binary --output=F:/Cloud-Mall-Refactor-Backup/worktree.patch -- $ownedPaths
git diff --cached --binary --output=F:/Cloud-Mall-Refactor-Backup/index.patch -- $ownedPaths
```

先创建并核对实际备份目录与文件名，避免覆盖以前备份。上述 diff 保存已跟踪文件差异，未跟踪与忽略文件需要按本次明确文件清单另行复制。也可以对当前任务拥有的明确路径使用 stash，使用 `-u` 才包含未跟踪文件，忽略文件另行备份。stash 前核对路径与归属，恢复时先 `apply` 并检查，确认完整恢复后再清理相应 stash。

`index.patch` 保存 HEAD 到暂存区的差异，`worktree.patch` 保存暂存区到工作区的差异。恢复到相同基线时，先核对路径、基线和补丁适用性，再还原 index 层及其暂存状态，随后还原 worktree 层并检查最终差异。目标工作区若已含部分修改，先核对重复内容与冲突，按实际状态选择恢复范围。

### 10.4 已提交阶段的撤销

单提交回退使用实际完整 SHA，先核对提交范围，再生成反向提交。下面的输入为执行者确认过的提交号。

```powershell
$badCommit = Read-Host '输入需要撤销的完整提交 SHA'
git show --stat $badCommit
git revert --no-edit $badCommit
git status --short
```

连续多个提交按从新到旧的顺序列出完整 SHA，逐项检查依赖关系、修改文件与目标通过点。一个逻辑切片的业务与配套测试一起撤销，避免留下依赖被撤销类型的调用。

```powershell
$commitsNewestFirst = (Read-Host '按从新到旧输入需撤销的完整 SHA，以空格分隔') -split ' '
git show --stat $commitsNewestFirst
git revert --no-edit $commitsNewestFirst
```

发生冲突时读取 `git status` 与冲突文件，按目标通过点解决，明确暂存已解决路径后执行 `git revert --continue`。若目标或影响范围需要重选，执行 `git revert --abort` 回到此次撤销开始前，再重新盘点。revert 前保存独立修改，使该操作边界清楚。

若误撤销了一个正确阶段，可检查该 revert 提交并对它执行新的 `git revert`，随后重新验证。工作流使用追加提交保存过程，硬重置、清理未跟踪文件和强制改写共享历史不属于本方案的常规回退步骤。

每次回退后重新执行目标模块编译、契约与适用专项测试，检查配置及运行入口，并在实施记录中写明撤销 SHA、新 revert SHA、原因和验证结果。涉及 Common 的回退检查依赖方，涉及跨服务 DTO/Feign 的回退检查双方。

Git 保存源码历史。数据库数据、Redis 内容、消息积压、Seata 事务、容器、运行中进程和被忽略的构建/实验产物需要各自的恢复依据。若切片涉及数据迁移或持久化协议变化，先单独评审数据兼容与恢复方案，再确定代码回滚是否仍可执行。

## 11. 模块验收清单

- [ ] 模块清单记录真实开始 SHA、实际改名和方法迁移关系。
- [ ] Controller 使用组合委托，HTTP 注解和绑定职责清楚，Service 接口具有真实方法。
- [ ] 业务能力、辅助实现和公共能力职责清楚，数据访问符合项目分层。
- [ ] Java 命名、JavaDoc、成员注释、流程注释和格式化检查完成。
- [ ] 相关兼容矩阵逐项检查，旧数据、旧消息和异常/null 语义有覆盖说明。
- [ ] 事务代理、传播、锁范围、幂等、租约、未知结果与恢复链路完成核对。
- [ ] 实际编译和适用测试通过，执行类与用例数可定位，专项环境限制明确记录。
- [ ] 核心实现逐行复盘，最终调用链及关键数据流写入实施记录。
- [ ] Git 阶段提交、最近通过点、精确回退范围及回退后验证方式可定位。
- [ ] 涉及缺陷的 `bugfix_log.md` 原条目已补根因、修复和回归结果。

## 12. 实施记录模板与当前评审

每个模块开始前在本节后追加一份记录，后续对话从最新记录继续。

| 字段 | 内容 |
| --- | --- |
| 模块与切片目的 | 待实施时填写 |
| 开始分支与完整 SHA | 待实施时读取 |
| 基线标签与通过范围 | 测试实际结果、已知失败和环境限制 |
| 文件归属与改动范围 | 明确路径及既有工作区修改 |
| 类名/包名/方法映射 | 旧名称、新名称、调用方、兼容标识 |
| 事务与并发边界 | 原边界、目标边界、代理与锁语义 |
| 适用兼容项与规格 | 对应矩阵及相关规格位置 |
| 验证命令和资源 | 实际工具、测试选择、配置、独立实验数据与清理 |
| 实际验证结果 | PASS / FAIL / BLOCKED / NOT_RUN，返回码及用例数 |
| 核心调用链与复盘 | HTTP/消息/任务入口到持久化与跨服务调用 |
| 提交与最近通过点 | 完整提交 SHA、标签、验证范围 |
| 回退记录 | 目标通过点、被撤销 SHA、revert SHA、复测结果 |
| 遗留项与下一切片 | 具体事项、归属和启动条件 |

2026-11-04 `pm_arch` 对本方案完成结构与可实施性评审。中等深度、逐模块推进、分层职责、兼容矩阵和 Git 回退流程可作为后续模块准备依据。Cart 已核对真实入口、Redis 原子操作及现有测试文件，其他模块为总体职责规划，详细类/方法归属在相应模块启动前补齐并评审。

方案初稿交付时运行验证为 `NOT_RUN`，源码改造待启动。此段记录当时的评审状态，最新实施结果以第 13 节及其后续记录为准。

新对话先读取本文、项目 AGENTS.md、README、bugfix_log.md 及当前模块相关规格，核对实际分支、HEAD 和最近通过点。恢复任务以最新实施记录和真实 Git 状态为依据。

## 13. 2026-11-04 实施启动与 Cart 迁移清单

本会话按用户要求由主 Agent 单独完成规格细化、实现和验证。此安排仅对当前会话有效，后续会话按当时的用户指令与项目规则执行。此前总体方案的 pm_arch 评审继续作为规划依据，本节细化由主 Agent 对照真实代码审阅。

起始源码为 `12892854e45083ddee99d7a2afa393188c19e8f0`。已跟踪工作区及暂存区为空，既有两个实验 `__pycache__` 目录按原归属保留。创建 `codex/refactor-backend` 分支，基线标签采用 `refactor/backend-baseline-20261104`，标签只声明已执行的验证范围。

实际工具为 `C:/Users/cyf34/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd`，Maven 3.9.16 实测使用 `C:/Users/cyf34/.jdks/ms-17.0.20` 的 Java 17.0.20。格式化工具为 `C:/Users/cyf34/.codex/tools/google-java-format-1.22.0-all-deps.jar`，实测版本 1.22.0。

Common 先完成契约与引用盘点。Cart 沿用现有 ApiResponse、BizException、AuthContext 与全局异常处理，Common 的生产类型本阶段无需调整。Cart 基线已执行 `-o -pl backend/cloud-mall-cart -am test -Dtest=Cart*Test,CommonRulesTest,BackendP1RulesTest,GlobalExceptionHandlerCacheControlTest,SeckillRequestFingerprintTest -Dsurefire.failIfNoSpecifiedTests=false`，构建通过，Cart 12 类 27 项全部通过，Common 4 类 25 项全部通过。运行日志位于 `F:/Cloud-Mall/target/backend-refactor-20261104/cart-baseline/maven.log`。

### Cart 第一切片

| 位置 | 精确迁移与验收 |
| --- | --- |
| CartController | 去除继承，通过构造器注入 CartService。保留九个 HTTP 入口的路径、方法和绑定类型，读取用户身份后调用业务方法并包装 ApiResponse。checked 与 preview 暂沿原 Map 绑定，保持原字段转换、缺失和 null 行为 |
| CartService | 声明 listItems、addItem、updateItem、deleteItem、getCleanupSnapshot、cleanupItem、setItemChecked、clearCheckedItems、previewSettlement，所有方法显式接收 userId |
| CartServiceImpl | 注册真实 Service Bean，移出 HTTP 映射和线程认证读取，保留原商品校验与条件更新顺序。业务方法返回具体数据或 void，Feign 返回仍使用原 ApiResponse 契约 |
| CartSettlementPreviewVO / CartInvalidItemVO | 将结算预览与失效项从无类型 Map 改为独立响应对象，保留 items、invalidItems、totalAmount、payAmount 及 skuId、code、message 的 JSON 与金额文本 |
| 现有 Cart 测试 | 原断言通过 Controller 组合真实 Service 的入口继续执行，HTTP 注解检查转到 Controller，预览 Java 返回类型的断言同步为 VO，保留所有原有业务断言 |
| 新增 HTTP 回归 | 使用真实 Spring MVC 映射、Controller、Service 和依赖替身覆盖九个路由、DTO 缺省值、Map 输入、JSON/null、业务错误与身份隔离，验证 Service Bean 可以按接口注入 |

原 Redis Lua 文本、参数顺序、原 JSON 比较、30 天 TTL、三轮确认冲突重读、未知结果不重试及一次条件清理保持为验收约束。接口改造后再评估 Redis 操作提取，按独立提交验证。测试中的直接 Java 调用签名跟随新的内部契约，HTTP/Feign 契约使用真实入口回归。

### Cart 第二切片

将完整 Hash 读取、JSON 编解码、条件写删、TTL 和 Redis 故障映射归入 `service.impl.CartStorage`。CartServiceImpl 负责商品事实、数量与勾选规则、条件冲突重试及结算计算。CartStorage 复用现有 Lua 和算法，以构造器注入共享 StringRedisTemplate 与 ObjectMapper，通过正常入口执行原测试验证。逐项删除与摘要刷新保持原部分成功语义。

新增辅助实现直接使用具体类注入，业务对外契约仍为 CartService。CartItemVO 与持久化对象的继承关系、DTO 的字段绑定收敛另按具体兼容验证结果决定迁移顺序。

Redis 专项使用 `deploy/experiments/compose.yml` 的新独立 Compose 项目和新卷，仅启动 Redis 服务。执行前读取十个 Redis IT 的连接和清理约定，按显式端口运行，归档结果后停止并移除本次实验容器，数据卷保留。此环境用于验证 Lua、并发和条件清理。

### Cart 第一切片验收

Controller 已组合注入真实 CartService，九个 HTTP 入口与 Service 业务方法分离，Service 显式接收 userId，结算预览与失效项使用独立 VO。所有原 Cart 业务断言保留，经 Controller 调用真实 Service 执行，HTTP 映射反射检查移动到 Controller。新增 CartHttpContractTest 六项通过真实 MVC、公共鉴权拦截器与真实业务 Bean 检查九个路由、金额/null/缺省值、原 JSON、异常响应与身份隔离。

验证通过 Common 25 项、Cart 33 项单测/MVC 与 42 项 Redis IT，以及 Order 的 CartCleanupContractTest 和 OrderCartCommitBoundaryTest 共 14 项。全部零失败、零错误、零跳过。日志目录为 `F:/Cloud-Mall/target/backend-refactor-20261104/cart-http`，初次测试发现的失效项 Map 强转遗漏已在原 bugfix_log 条目记录并修正，最终回归通过。Google Java Format 1.22.0 已处理本次 Java 文件，提交前执行 diff 检查。

Redis 实验默认地址池耗尽的启动问题通过本次局部 Compose 覆盖解决，新增 lab 子网为已核查未占用的 `10.254.240.0/24`，Redis 宿主端口为 `19923`，项目为 `cloud-mall-refactor-cart-20261104`。覆盖文件仅包含 `networks.lab.ipam.config`，位于本次 target 证据目录，后续复现前重新检查网段和端口。基线十类 Redis IT 共 42 项已通过。

核心调用链为 CartController → CartServiceImpl → 原 ProductClient 与 Redis 脚本。HTTP 成功响应在 Controller 装配，业务异常交原 GlobalExceptionHandler。原 JSON 字符串比较、脚本参数、未知结果处理及条件重读次数已逐段对照。

Cart 第二切片开始。第一切片提交为 `c1e34c4cf48cb23ccf56dda0aadda4b75645a680`，其验证范围为 Common25、Cart75、Order14。存储提取同时将 CartItemVO 改为独立响应类，逐字段保持原名称、默认值、金额与时间字符串，原继承依赖断言迁移为 VO。CartStorage 的写入/读取方法限本包调用，构造器用于 Spring 装配，业务服务仅依赖存储与商品客户端。

Cart 第二切片验收通过。CartStorage 承担完整 Hash 与原 JSON 读取、条件写删、TTL、证明解析和 Redis 故障映射，Service 保留商品事实、数量/勾选及结算编排。两个 Lua 初始化文本与第一切片逐字比对一致，CartItemVO 已独立于 PO 且字段和默认值完整保留。Common25、Cart33 单测/MVC与42 Redis IT 全部通过，零失败、零错误、零跳过。初次编译发现的测试持久化对象强转误改已修复并回归。记录位于 `F:/Cloud-Mall/target/backend-refactor-20261104/cart-storage`。调用链为 HTTP → CartService → CartStorage / ProductClient，包内存储方法通过真实业务入口验证。

### Cart 第三切片的请求与 Feign 对象细化

第二切片提交为 `a5d1020e622bd01fd599095c629a35bb9046b0dd`。继续按标准 DTO 分层收敛 checked、preview 的请求绑定，并将 ProductClient.SkuView 迁移为 `domain.dto.ProductSkuDTO`。SKU 五个字段、服务名、请求路径及返回 envelope 按原契约处理。

新增 `CartCheckedDTO` 与 `CartSelectionDTO`。使用 Jackson 委托创建器接收原相同泛型 Map 后只提取业务字段，以保留已有 checked/skuIds 缺省值、显式 null、数值转换及额外字段的解析规则。Controller 直接绑定 DTO，Service 参数仍为明确的 Boolean 或 List<Long>。测试通过旧泛型 Map 解析与新 DTO 解析对照合法/非法载荷，并经真实 MVC 验证。具体 DTO 与 Feign 改名由当前主 Agent 对照契约审阅，格式化、原 Cart 全回归与八模块 test-compile 作为最终验收。

第三切片补充兼容校正。真实 MVC 反例确认首次拆分的提前鉴权把匿名非法数量从原400变为401。Controller 改为读取 AuthContext.userId 并显式传递，CartStorage 在原取 Key 的位置按缺失身份抛原401。原商品查询/参数校验/清理证明校验优先顺序恢复，Service 继续独立于线程上下文。该差异由新增反例暴露，前两切片的测试通过范围未包含此情况，最终以本切片回归结果为准。

### Cart 最终验收与后续状态

第三切片已完成 CartCheckedDTO、CartSelectionDTO 和 ProductSkuDTO 的收敛，Controller 使用 DTO 绑定，Feign 传输对象归入 domain.dto。Jackson 的原泛型 Map 绑定与新 DTO 在十四组合法/非法输入上对照，覆盖缺失/null、额外字段、重复 SKU、字符串与数值转换。匿名请求的原校验优先级已通过先失败后修复的 MVC 反例确认。

最终执行 29 个测试类共 116 项，Common 25 项、Cart 35 项单测/MVC 和 42 项真实 Redis IT、Order 14 项契约与提交边界检查全部通过，失败、错误与跳过均为零。真实 Redis IT 覆盖正常写入、条件竞争、清理快照、金额/身份校验、TTL 与故障边界。完整记录位于 `F:/Cloud-Mall/target/backend-refactor-20261104/verification.json`，本次测试原日志位于该目录的 cart-dto/final-regression.log。

八个后端模块已通过根工程 `-o test-compile -DskipTests`，日志为 `F:/Cloud-Mall/target/backend-refactor-20261104/all-modules-test-compile.log`。该项覆盖全部生产与测试源码编译，其他模块的完整运行测试及真实跨服务交易留待对应模块验收。Google Java Format 1.22.0 与 git diff 检查完成。

Redis 实验数据所在 DB15 在结束时为零键。容器与网络的 inspect、日志已归档，随后以同一 Compose 项目和 `--profile cm21 down --timeout 10` 清理本次容器与网络，清理后按项目标签查询均为空。命名卷 `cloud-mall-refactor-cart-20261104_redis_data` 按项目规则保留。现有 Compose 基础配置、应用端口与历史实验记录沿原规格使用。

本模块三个提交分别对应 HTTP 与 Service 解耦、Redis 存储与 VO 分离、DTO 与兼容顺序收敛。模块通过点标记为 `refactor/cart-complete-20261104`。前两切片的验证未覆盖缺失身份时的错误优先级，最终切片已修正该差异。需要完整恢复原 Cart 实现时，按下列顺序撤销整个模块，并执行原基线验证。执行前按照第 10 节检查和保存当前任务修改。

```powershell
$cartFinalCommit = git rev-parse 'refactor/cart-complete-20261104^{commit}'
git show --stat $cartFinalCommit a5d1020e622bd01fd599095c629a35bb9046b0dd c1e34c4cf48cb23ccf56dda0aadda4b75645a680
git revert --no-edit $cartFinalCommit a5d1020e622bd01fd599095c629a35bb9046b0dd c1e34c4cf48cb23ccf56dda0aadda4b75645a680
```

| 模块 | 当前阶段 | 后续动作 |
| --- | --- | --- |
| Common | 契约与引用盘点完成，25 项回归通过 | 随依赖方实际需求推进 |
| Cart | 三个切片验收通过 | 已具备逐模块重构样板 |
| Gateway | 结构迁移验收通过 | 结果与通过点见第 14 节 |
| Product | 待开始 | 下一模块，先细化分类、商品、活动、索引与热度职责 |
| User / Stock / Order / Pay | 待开始 | 按既定顺序逐模块细化与实施 |

本次核心代码已逐段复盘。HTTP 入口负责绑定、读取身份与统一响应，Service 负责业务规则，CartStorage 负责原 JSON、Redis 原子操作和存储错误映射，ProductClient 使用独立传输 DTO。后续会话从本表、实际 Git 状态和模块通过标签继续。


## 14. Gateway 重构实施清单

2026-11-04 从 `9eab801a80fa56cb55f9ca3fdcecfa0f975c9a74` 开始，由当前会话主 Agent 单独执行。工作分支继续使用 codex/refactor-backend。已跟踪工作区与暂存区为空，原实验缓存目录按原归属保留。此前总体方案的 Gateway 范围由本节具体化。

基线已执行 Gateway 原有三类十项及 Common 四类二十五项，全部通过。工具沿用已核定的 Maven 3.9.16、JDK 17.0.20 与 Google Java Format 1.22.0。原始日志为 `F:/Cloud-Mall/target/backend-refactor-20261104/gateway/baseline/maven.log`。

| 当前职责 | 目标位置 | 兼容约束 |
| --- | --- | --- |
| AuthenticationFilter 中的公开路径与内部 TCC 路径判定 | util.GatewayAccessPolicy | 原方法与路径匹配、大小写、编码检查次数、内部接口与库存拦截先后顺序 |
| AuthenticationFilter 的认证编排 | 原 filter.AuthenticationFilter | 最高优先级、Bearer 文本规则、auth:token: 前缀、Redis 延迟订阅、查找故障503、下游异常传播、身份头覆盖及 JSON 错误体 |
| Sentinel 规则 JSON 解析 | util.GatewayFlowRuleParser | 每次基于原 ObjectMapper 副本严格解析，整批校验完成才允许发布，原资源/count 校验与异常语义 |
| SeckillSentinelGatewayConfig 的配置及订阅生命周期 | config.SeckillSentinelGatewayConfiguration | 显式保留 Bean 名 seckillSentinelGatewayConfig、全部配置键、秒杀路由资源名、默认规则、定时重试、Nacos 监听和关闭顺序、429 JSON及指标 |

第一个提交保存本清单和新增行为回归。测试先在原实现上运行，覆盖匿名路径矩阵、内部路径编码、Token 与身份头、响应式异常范围、实际 FilteringWebHandler 调用链、Nacos 订阅重试与规则原子发布、关闭顺序。Nacos ConfigService 使用替身，避免测试依赖外部配置中心。

第二个提交提取两个具名职责并统一 Configuration 命名。GatewayAccessPolicy 为无状态路径工具，GatewayFlowRuleParser 为持有 ObjectMapper 的具体 Spring Bean，构造器注入原配置类。AuthenticationFilter 和 Sentinel 配置继续作为原入口，调用方与原测试随真实 Java 类型迁移，HTTP 路由和持久协议作为兼容基线。无需为辅助类增加接口。

重命名配置类后执行 Gateway clean 构建，并核验目标产物中的旧类已消失。clean 仅针对已核查的本模块标准 target 目录，测试报告先复制到本次根 target 记录目录。增加 Spring 组件注册验证，使用不执行任务的 TaskScheduler 替身，检查 Bean 名、构造器注入和默认规则初始化。最终执行 Gateway、Common 与 Cart 无外部资源回归，以及八模块生产和测试源码编译。

当前 POM 实际解析出的 spring-cloud-gateway-server 为 3.1.4，来自本次 Surefire classpath，项目规格表记录为 3.1.5。此为既有依赖现状差异，本阶段按现有 POM 验证结构迁移，版本对齐作为独立核对项记录。网络路由与 Sentinel 的真实 Nacos 推送在本阶段的替身测试范围之外。

Gateway 扩展基线通过。新增七项认证契约检查和三项 Sentinel 检查在原生产实现上通过，Gateway 四类20项与 Common四类25项全部零失败/错误/跳过。WebTestClient 经真实 FilteringWebHandler 验证401 JSON与已认证转发，Nacos监听、重试和关闭通过ConfigService替身验证。原始日志为 gateway/baseline/expanded-regression.log，基线标签使用 refactor/gateway-baseline-20261104。


### Gateway 验收结果与回退点

扩展基线提交为 `2b79691734d70bddfa09b3132f006a2601054a83`。实现已提取 GatewayAccessPolicy 与 GatewayFlowRuleParser，SeckillSentinelGatewayConfiguration 显式保留原 Bean 名。所有原配置键、定时注解参数和 seckill-order 资源名已逐项比对一致。AuthenticationFilter 使用清晰变量名与实际流程注释，保留原认证与异常链路。

最终运行 Gateway 五类21项、Common四类25项、Cart十三类35项，共22类81项，失败、错误、跳过均为零。Gateway 的21项包含实际 FilteringWebHandler HTTP 契约、路径与编码矩阵、Redis延迟订阅/故障、身份头覆盖、下游异常传播、Nacos监听与重试、整批规则拒绝、429指标以及 Spring真实组件扫描与定时注册。ConfigService、Redis和调度执行使用替身。

新增装配测试夹具中的 Micrometer AutoCloseable、Spring registerBean 重载及 TaskScheduler Clock 问题已修正并回归，原失败记录保留在本轮日志与 bugfix_log 条目中。当前生产实现的新增行为回归均通过。

Gateway 已完成本模块 clean 后编译与测试，并成功 package。旧配置类和旧测试类在 target 中均已清除，打包 JAR 内只有新配置类及两个新职责类，保留 Spring Bean 名的组件扫描测试通过。八个后端模块再次完成生产与测试源码编译。Google Java Format 1.22.0 和 git diff 检查通过。

本轮证据根为 `F:/Cloud-Mall/target/backend-refactor-20261104/gateway`，verification.json 汇总实际结果，final/combined-regression.log 保存81项回归，final/package.log 保存Gateway打包，final/all-modules-test-compile.log 保存全模块编译。Nacos 实际推送、Redis 实联和真实跨服务代理未在本轮执行。第14节记录的实际Gateway依赖3.1.4与规格3.1.5差异继续作为独立核对项。

模块通过点标记为 `refactor/gateway-complete-20261104`。需要撤销本次结构迁移时，撤销通过标签指向的实现提交即可回到扩展测试基线，随后执行 Gateway 的 clean test 和 Common 定向回归。清理旧编译产物是配置类改名后重建或回退验证的一部分。

```powershell
$gatewayRefactorCommit = git rev-parse 'refactor/gateway-complete-20261104^{commit}'
git show --stat $gatewayRefactorCommit
git revert --no-edit $gatewayRefactorCommit
& $mavenExecutable -o -pl backend/cloud-mall-gateway clean test
```

核心调用链已逐行复盘。Gateway FilteringWebHandler → AuthenticationFilter → GatewayAccessPolicy / Redis会话查找 → 下游过滤链。应用就绪与定时任务 → SeckillSentinelGatewayConfiguration → Nacos监听 → GatewayFlowRuleParser → GatewayRuleManager发布。限流回调沿原指标与429 JSON处理。后续重构进入 Product 模块细化。


## 15. Product 重构实施清单

2026-11-04 从 `faaecef256bc75cbdf03f598788aff7359626d57` 开始，当前会话由主 Agent 单独完成细化、实现与验证。分支继续为 codex/refactor-backend。原 Product 定向十四类57项和 Common四类25项基线通过，记录位于 `F:/Cloud-Mall/target/backend-refactor-20261104/product/baseline/maven.log`。

ProductController 继承约900行的 ProductServiceImpl，空 ProductService 及同层大量索引/指标实现增加了定位成本。本轮按既有规格分三个切片，真实SQL、消息和启用模式继续作为兼容基线。

| 职责 | 目标类型与位置 | 验收重点 |
| --- | --- | --- |
| HTTP十七个入口 | 原 ProductController，构造器注入四项业务契约 | 原路径、方法、参数默认值、仅create的Valid、GET异常Advice、JSON金额/null/大整数ID |
| 分类查询与管理 | service.CategoryService / service.impl.CategoryServiceImpl | 三项写方法的事务代理、管理员校验顺序、分类默认值及原返回字段 |
| 商品列表、详情、SKU与热度 | service.ProductQueryService / service.impl.ProductQueryServiceImpl | SQL/ES路由、短事务与五秒窗口、ONLINE快照、登记失败、requestId与原指标事件绑定 |
| 商品创建、修改和上下架 | service.ProductManagementService / service.impl.ProductManagementServiceImpl | 管理员校验、事实/版本/索引outbox同事务、参数和SKU原更新方式 |
| 秒杀活动查询与管理 | service.SeckillActivityService / service.impl.SeckillActivityServiceImpl | 原逐步数据库/Redis行为、Key/TTL/写入顺序、活动时间、配额与错误优先级 |
| 商品行与附属数据装配 | service.impl.ProductCatalogReader、util.ProductValueCodec | 原Map查询投影、金额格式、SKU JSON、原SQL调用次序 |
| 响应与内部上下文 | 独立CategoryVO/ProductVO/SkuVO/ProductParameterVO/SkuSummaryVO、ProductReadResultDTO、ProductOperatorDTO | VO与请求DTO分离，ID字符串注解与默认值逐字段复制，内部读结果携带可空指标requestId供Controller装配 |
| 原静态辅助与活动快照 | util.ProductEventEnvelope、util.ProductIdentifiers、util.SeckillActivityStock，复用SeckillActivityPO和ProductDateTimes | 原追踪ID、事件字段、ID算法、活动原时间转换和剩余库存算法 |
| 已有索引与指标实现 | service.impl.index、service.impl.metrics | 类名、Bean默认名、事务和消息注解、算法与指标契约，保留service中的检索/索引写入接口 |
| 测试辅助命名 | JdbcTransactionAnchorObserver、TransactionPhaseAnchorBridge及对应测试 | 替换当前Java引用和测试选择器，历史冻结runner与归档按原版本保存 |

管理员上下文在Controller读取为ProductOperatorDTO，Service在原方法位置校验身份。分类与商品管理的Transactional入口仍在业务方法开始时建立代理事务。商品查询生成原指标requestId，ProductReadResultDTO只在Controller转换为统一ApiResponse，启用指标时响应ID与登记ID保持同一值。查询的可选指标/搜索依赖与开关在第一切片保持原注入/启动校验语义，避免改变LEGACY与ONLINE装配条件。

ProductCatalogReader通过现有ProductSqlMapper读取事实并装配VO，Mapper SQL及表映射按原契约执行。创建响应保留请求对象原金额字符串及被写回的ID，读取响应继续使用数据库金额格式。活动快照使用已有SeckillActivityPO，原Timestamp/LocalDateTime转换往返需覆盖。ProductReadExceptionHandler继续针对同一Controller包生效。

首先记录本清单与基线，再完成核心HTTP/业务分离和正常入口回归。随后归组已有索引与指标实现、整理测试辅助类名，同步所有现行测试和集成测试的装配引用。新职责类使用实际依赖构造器，辅助实现采用具体类，按真实需要保留接口。

必要回归包括原商品/索引/指标单测、Common源码规则断言迁移后的同等保障、真实MVC响应与身份边界、Spring事务代理中的提交/回滚和outbox协同、已有ES页内事实及ONLINE登记测试。对涉及数据库写入的迁移使用新独立Compose项目和新MySQL卷执行实际数据库回归。真实ES/MQ端到端与冻结性能runner的执行范围独立记录，测试结果逐项标注。

重命名或移动完成后检查新旧类与Spring Bean注册，八模块生产/测试源码编译，Product打包，原格式工具与diff检查。每个完成切片提交，并在本节记录验收范围和可回退节点。


### Product 核心职责切片验收

ProductController已改为组合调用四项Service契约，空ProductService与原集中实现已移除。分类、商品查询、商品写入和活动实现各自独立，公共读装配与字段编解码形成具体实现。商品写及分类写的事务注解保留在真实Service入口，指标关联号由内部查询结果传到HTTP响应，VO保留原ID字符串注解与金额/null表示。

首轮编译发现的方法引用与测试入口迁移遗漏已修正。最终Product原57项、响应模型3项、真实MySQL/MVC五项与Common25项共90项通过，零失败/错误/跳过。实库用例经过真实事务代理和Mapper，覆盖十七个HTTP入口、创建/更新的outbox故障回滚、版本递增、分类鉴权与停用、活动时间及Redis失败后的原已提交状态。每次实库运行创建并清除UUID数据库。

实验使用 `deploy/experiments/compose.yml` 新项目 `cloud-mall-refactor-product-20261104` 与新MySQL卷，端口19924，已核查的局部子网10.254.241.0/24。初次与最终日志位于 product/core/full-regression.log 和 product/core/verified-regression.log。

### Product 包结构切片细化

进一步核查确认原ProductKeywordSearch为具体ES实现，归入 `service.impl.search.ElasticsearchProductKeywordSearch`，显式保留Spring Bean名productKeywordSearch。现有ProductIndexWriter继续作为接口，ElasticsearchProductIndexWriter与八个索引实现归入service.impl.index。六个ProductMetric实现及ProductHotStatFreshnessAudit归入service.impl.metrics。

测试辅助类改名为JdbcTransactionAnchorObserver、TransactionPhaseAnchorBridge、JdbcTransactionAnchorQualificationIT，对应两项单测类同步命名。当前Java引用、配置Import和资源定位字符串同步迁移，历史冻结runner与归档文件保留原版本引用。

已发现Product模块target中存在cm21-online-metrics、cm21-pipeline等历史记录，本模块采用精确清除已移除全限定名对应class文件的方式重建，清理范围仅限target/classes与target/test-classes，避免清理历史测试证据目录。打包前核验旧类缺失及新类存在。

扩大单测时发现历史阶段开关断言过期。当前QueryInfo资格检查先于bridge检查，先返回CM21_QUERYINFO_PHASE_ANCHOR_DISABLED_REQUIRED。独立修正单测并保留bridge精确拒绝码验证，新增actor hook零资源动作断言。该类24项回归通过，原拒绝门及运行代码未修改。本修正单独提交后继续包迁移。
