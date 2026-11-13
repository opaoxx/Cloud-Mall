# CloudMall 后端逐模块重构方案书

日期为 2026-11-04。方案责任人为 `pm_arch`，实施责任人为 `backend`，验证责任人为 `qa` 与主协调 Agent。

用户已确认采用中等深度重构，重点为规范命名、理顺分层、按业务职责组织代码，并在实施过程中通过 Git 保存可回退节点。当前已完成 Common 契约盘点及 Cart、Gateway、Product、User、Stock、Order 本轮结构重构；Pay已完成查询、创建、模拟成功/失败和共用处理规则提取，后续处理回调及旧继承入口。最新验证、兼容保留项与回退信息见文末实施记录。

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
| Product | 核心职责拆分与包结构归组验收通过 | 结果与通过点见第 15 节 |
| User | 已完成本轮结构重构 | 账号/资金拆分、TCC/Fence及只读取证归组完成，真实TC/RM与取证链回归通过 |
| Stock | 已完成 | 本轮四个切片及模块验收完成，见第19至22节；不代表全链路或历史性能事项闭合 |
| Order | 本轮结构完成 | 演练命名、主业务/消息、辅助分组与规范收尾完成，见第23至33节；全商城验收不在此完成口径 |
| Pay | 实施中 | 查询、创建、模拟成功/失败已组合委托，锁、协议和独立事务边界保持，见第34至37节；回调及旧继承入口待后续处理 |

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
| 已有索引与指标实现 | service.impl.index、service.impl.metrics | 类名、Bean默认名、事务和消息注解、算法与指标契约，保留service中的业务契约与ProductIndexWriter接口 |
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

全量回归另暴露既有线程关闭夹具的时序竞争，原用例的任务可能已结束而不进入可中断等待。新增本例latch保持一个任务活动，在finally释放并确认退出，确保验证原中断等待分支。资源关闭实现未修改，该类3项回归通过，作为独立测试修正提交。

批次关闭夹具的stopped信号曾早于真实模拟停止完成，并在并发回调中重新stub共享channel。修正为原子状态、完成回调后通知，同时补finally释放与有界join；原3秒完成断言保留。该类27项回归通过，测试逻辑独立于包导入变更提交，生产关闭逻辑保持原样。


### Product 最终验收与回退点

本模块已完成两项实现切片。核心职责提交为 `02464c3769f36b0d31b0aa4ac398e419606b38c1`，包归组与语义命名提交使用最终标签定位。原集中类拆为商品查询438行、商品管理165行、分类105行、活动184行以及独立事实装配/字段编解码能力，按职责定位调用链。

17个生产实现及5个测试辅助类完成包移动或命名整理。索引实现位于service.impl.index，指标实现位于service.impl.metrics，ES检索实现位于service.impl.search。ProductKeywordSearch具体实现命名为ElasticsearchProductKeywordSearch并保留原Bean名。Cm21JdbcAnchorObserver、Cm21PhaseAnchorBridge、Cm21JdbcAnchorQualificationIT及对应单测已使用语义名称，当前源码引用同步完成。

22个迁移类在应用已声明的包名、类名与引用替换后，执行词法序列逐一相同，注释和格式差异单独处理。该检查结合编译及正常入口回归确认迁移，不替代真实中间件验收。明细位于 grouping/moves.json 与 grouping/lexical-parity.json。

扩大回归发现的三项历史测试问题已独立修正并提交，分别为 `10fd543c687b43a6e0f0c6686949104ff504df62` 的阶段拒绝码断言、`ec3f5e1cf4659679743ea6c2eb7255ab24ccfe0c` 的中断等待夹具、`8819f73f79cf4c5fa9ccfe3755b3a0ded4273ee1` 的批次停止通知时序。原运行门和生产关闭算法保持既有实现，原失败日志保留。

最终执行60个测试类447项，Product全部单测361项及真实MySQL/MVC五项、Common25项、Gateway21项、Cart35项全部通过，失败、错误和跳过为零。覆盖十七个HTTP入口、DTO/VO JSON一致性、指标requestId关联、SQL/ES路由与窗口、商品事实和outbox事务回滚、活动状态/Redis边界，以及现有索引、指标、生命周期与观察辅助回归。

Product打包通过，八个后端模块的生产和测试源码编译通过。JAR核验新实现存在，旧ProductService/集中实现和被移动的旧全限定名均缺失。50个旧class文件按已核查的精确类名清理，四个旧命名单测报告在校验归档后移出当前报告目录，历史cm21目录保留。Google Java Format 1.22.0已用于本次Java改动，大型既有测试仅格式化修改区和import，diff检查通过。

本轮证据根为 `F:/Cloud-Mall/target/backend-refactor-20261104/product`，verification.json汇总447项结果，final/verified-regression.log为最终执行日志，final/package.log与final/all-modules-test-compile.log记录打包和八模块编译。真实ES/RabbitMQ端到端、原在线指标实库专用验收及历史性能runner未在本轮执行，相关结果不由单测替代。

独立MySQL实验结束前核对已无本轮UUID数据库，容器inspect与日志已归档。项目cloud-mall-refactor-product-20261104的容器和网络已移除，命名卷cloud-mall-refactor-product-20261104_mysql_data按约定保留。

模块通过点为 `refactor/product-complete-20261104`。原业务源码基线为 `refactor/product-baseline-20261104`。回退整个Product实现时，按最新包结构提交、核心拆分提交的倒序执行，独立测试修正可保留。源码回退后，先核对当前已删除/改名Java类型并只清理其对应target/classes与target/test-classes内的class和嵌套class，再编译与执行对应基线回归。Product的target包含历史实验证据，清理范围需精确到生成类，源码回退不代表中间件数据回退。文档冲突按最新回退记录与历史验证事实处理。

```powershell
$productGroupingCommit = git rev-parse 'refactor/product-complete-20261104^{commit}'
git show --stat $productGroupingCommit 02464c3769f36b0d31b0aa4ac398e419606b38c1
git revert --no-edit $productGroupingCommit 02464c3769f36b0d31b0aa4ac398e419606b38c1
```

核心调用链已逐行复盘。ProductController → 分类/查询/管理/活动Service → ProductSqlMapper及事实装配。商品写Service事务 → 同资源事实、SKU、参数、索引outbox。查询Service → 原SQL/ES验证与指标登记 → ProductReadResultDTO → Controller统一响应。活动Service → 原数据库状态写入 → Redis配额及元数据。索引和指标处理按原消费、投影与恢复契约在各自包中组织。下一模块为User。

## 16. User 第一切片：账号、资料、地址与 HTTP 委托

### 16.1 范围与基线

本切片于2026-11-04从 `e345bb4d3cf371637c91fd49dec651312398792b` 开始，分支仍为 `codex/refactor-backend`。本会话继续按用户要求由主Agent独立完成规格细化、实现和验证；不改变其他会话的协作规则。User整体尚未完成，本切片之后继续资金职责拆分，再整理TCC与只读证据。

现有UserController继承UserServiceImpl，后者承载13个HTTP映射、管理员初始化和约850行资金证明/编排。已运行原User全部279项单测与Common25项，全部通过。证据为 `target/backend-refactor-20261104/user/baseline/unit.log`。

### 16.2 实际迁移清单与边界

| 原职责 | 本切片目标 | 兼容要求 |
| --- | --- | --- |
| register/login/logout | service.AuthenticationService、impl.AuthenticationServiceImpl | BCrypt、重复用户名409、禁用账号401、auth:token前缀、2小时TTL、Redis故障503、Bearer大小写与trim |
| me/update/currentUser | service.UserProfileService、impl.UserProfileServiceImpl | Controller读取用户身份；null更新与空字符串响应、金额原toPlainString、更新后重读 |
| addresses/add/update/delete及两个私有helper | service.UserAddressService、impl.UserAddressServiceImpl | 默认标志顺序、数据库生成ID、数字JSON、原默认地址清理顺序；归属缺陷另行修复 |
| ensureLocalAdmin与身份结构/范围检查 | config.UserAccountInitializer | 保留原两个DependsOn初始化器、先只读schema检查后管理员写入、旧默认密码迁移；Controller依赖此初始化器 |
| debit/debitInternal/refundInternal/balanceOperation及全部资金helper | service.BalanceLedgerService、impl.BalanceLedgerServiceImpl | 原事务注解、noRollbackFor、RR快照、执行顺序和全部方法体保持；本次只隔离为独立Bean |
| HTTP绑定、校验、响应 | controller.UserController构造器委托上述4个接口 | 保留全部13个路由和形参注解；账户接口响应由Controller封装 |
| 注册与登录Map响应 | domain.vo.UserRegistrationVO、UserLoginVO | 原字段名、数字ID、嵌套UserVO、无新增字段 |

删除空UserService和混合UserServiceImpl。资金Service暂保留ApiResponse返回作为下一切片的明确迁移项，避免在资金算法未逐项细化前同时改写其所有返回分支。新账号Service不依赖HTTP、ApiResponse或AuthContext；持久化PO、DTO、Feign、消息、Redis值及SQL保持原契约。当前资金类名变更需同步实际Java引用、Spring测试注册、反射目标与源码契约测试，不改冻结历史runner和归档。

本次不新增账号/地址事务。新增默认地址仍先清默认再insert，更新仍成功后清其他默认；将其改为原子事务属于另行设计事项。资金原注解迁至真实Service代理，Controller不再持有事务。

### 16.3 独立缺陷：地址更新归属

检查发现原updateAddress调用BaseMapper.updateById，把当前userId作为SET值，并未限定WHERE归属。先在真实MySQL复现跨用户更新，再独立修复：UserAddressMapper提供updateOwnedAddress，在同一UPDATE中以id与user_id为条件，保持既有字段/时间写入；影响0行沿原NOT_FOUND/404，不进入默认地址清理。合法更新及默认标志顺序保持。该修复以独立提交保留，结构回退时可继续保留安全修复。

### 16.4 验证与提交计划

1. 保存本切片细化基线，建立 `refactor/user-account-baseline-20261104`。
2. 真实MySQL复现地址归属缺陷，独立提交修复及回归。
3. 完成账号/资料/地址/启动职责和Controller委托，补充实际MVC行为及Spring装配测试；资金方法体以逐方法词法比较核对，保持注解与逻辑。
4. User/Common全单测、生成ID真实MySQL专项、实际MySQL/Redis账号MVC与资金代理专项；User打包及八模块test-compile。所有专项先检查隔离/清理逻辑，再执行。
5. 归档证据、逐行复盘、格式化与git diff --check，提交并建立本切片通过标签。独立中间件使用deploy/experiments/compose.yml的新项目与新卷，完成后归档并移除容器/网络，卷保留。

源码回退按结构提交倒序revert；保留独立地址缺陷修复。Git不回退数据库、Redis及忽略的本地证据。回退后精确清理过期生成class，再重新编译验证。下一步为BalanceLedgerService的扣款/退款/操作查询职责和响应解耦，随后TCC/证据归组。

### 16.5 第一切片验收与调用链

第一切片已完成。准备提交为 `3579a1a9`，独立地址归属修复为 `c835b778`；结构提交通过标签为 `refactor/user-account-complete-20261104`。Controller仍使用原Bean名userController，四个能力由构造器注入；原UserServiceImpl没有独立Service Bean，旧实现方法在Controller代理上运行，现在资金事务由BalanceLedgerServiceImpl代理承接。所有当前Java引用、反射注解断言及实库测试注册均已同步；历史材料保留。

逐行复盘本切片账号核心代码并核对以下调用链：

- UserController → AuthenticationServiceImpl → UserMapper/BCrypt → Redis auth:token → UserRegistrationVO/UserLoginVO → Controller统一响应。
- UserController读取AuthContext身份 → UserProfileServiceImpl/UserAddressServiceImpl → 原Mapper → 原UserVO/UserAddressVO。地址修改通过Mapper.updateOwnedAddress一次限定id/user_id，失败不进入默认标志清理。
- UserController的DependsOn → UserAccountInitializer → 原UserTableInitializer/BalanceTccTableInitializer → 身份结构/范围只读检查 → 管理员幂等初始化。原管理员密码升级和默认余额保持。
- UserController → BalanceLedgerService事务代理 → 原钱包行锁、扣款归属、账本、TCC/receive事实读取与可选Redis缓存。三个写入口保持noRollbackFor=BizException，操作查询保持readOnly与REPEATABLE_READ；私有函数不经过新的事务跳转。

资金实现的40个方法体与初始化器3个方法体经忽略注释/空白的词法逐项比对全部一致，报告为account/method-parity.json。资金Service暂留ApiResponse与字段注入，扣款/退款/操作查询内部职责仍需下一切片细化，本次不将User模块记为整体完成。

验证结果如下，均为本轮实际执行：

| 范围 | 结果 |
| --- | --- |
| 原User/Common基线 | User279 + Common25通过 |
| 地址缺陷专项 | 修改前真实MySQL复现跨用户更新未拒绝；独立修复后15项通过，含7项MySQL生成ID/迁移/归属测试 |
| 原资金实库基线 | BalanceTccStateMySqlIT 103项通过 |
| 最终统一回归 | 24类420项，0失败、0错误、0跳过：Common25、User单测281、生成ID MySQL7、资金MySQL103、账号MySQL/Redis/MVC4 |
| 打包与引用 | User package通过；八模块生产/测试源码test-compile通过；Jar排除两个已删除类型并含全部新实现 |
| 格式与差异 | google-java-format 1.22.0执行；大规模既有资金测试只整理import；git diff --check通过 |

新增MVC回归使用真实Spring Web、MyBatis、事务管理器、MySQL与Redis，覆盖全部13个路由、身份数字JSON、资料null/空字符串、默认地址排序、校验与鉴权优先级、账号禁用、Token内容/两小时TTL、Bearer匹配/trim与登出。资金HTTP回归证明成功/重放/退款查询、余额不足REJECTED账本提交，以及真实SQL触发器故障时余额/归属回滚且不写缓存。资金103项实库测试保留原并发、互斥、退款证据与快照断言。本轮没有执行真实Seata RM/TC进程回调、跨服务浏览器端到端及性能实验。

测试适配中发现并修正了新夹具的错误码常量、方法引用、JdbcTemplate调用误替换与@Import全限定Bean名问题，相关失败日志保留；最终结果以account/final-regression.log为准。证据根为 `F:/Cloud-Mall/target/backend-refactor-20261104/user`，verification.json包含逐类统计，package.log与all-modules-test-compile.log位于account目录。

实验使用全新Compose项目cloud-mall-refactor-user-20261104，MySQL19925、Redis19926，核对无重叠的独立子网10.254.242.0/24。全部UUID测试数据库已删除，实验cloudmall_user两张来源表仍为0行且原测试校验和通过，Redis DB15为0键。容器inspect与日志归档后已移除本项目两容器和网络；mysql_data、redis_data两个命名卷按规则保留，既有中间件未更动。User target下历史cm15b_rm_it证据保留，仅精确移除旧UserService.class和UserServiceImpl.class。

### 16.6 回退与下一步

回退本切片结构、保留独立地址修复：

```powershell
$userAccountCommit = git rev-parse 'refactor/user-account-complete-20261104^{commit}'
git show --stat $userAccountCommit
git revert --no-edit $userAccountCommit
```

回退后按本次新增类型清单核对并删除target/classes中的过期新类型及其嵌套class，再重新编译；禁止清空整个User target中的历史实验证据。只反向应用结构提交应回到c835b778的源码，地址WHERE归属限制仍在。临时Git索引中的反向补丁检查只证明源码回退路径，不等于运行态回滚；执行实际回退后仍需对应测试。

下一任务先为BalanceLedgerService细化扣款、退款、操作查询与共享证明规则的依赖图，冻结事务外边界后拆分，并把ApiResponse封装移回Controller。TCC/Fence、证据读取与恢复类的业务归组在其后实施；保留原资金算法、错误优先级和跨协议互斥契约。

## 17. User 第二切片：资金职责与响应解耦

### 17.1 基线与实施范围

2026-11-04，基线为 `860acf3cb5966847feda7c3bfbb57db16ceceec5`，分支 `codex/refactor-backend`。本会话继续由主Agent单独评审、实现与验证。现有BalanceLedgerServiceImpl约911行、40个业务/证明方法，三类业务共享归属和退款集合校验。重新运行User281/Common25单测全部通过，上一切片的103项资金实库及账号实库结果保留为历史基线。本切片仍需在新实验项目上重跑实库回归。

### 17.2 方法归属与依赖冻结

业务接口在service根包，具体实现和共享证明在service.impl.balance。共享证明不另造接口；纯规则不注册Bean；两个依赖Mapper的证明类通过构造器装配且不启动额外事务。删除旧BalanceLedgerService及BalanceLedgerServiceImpl，不保留转发壳。

| 新类型 | 迁移方法/职责 | 依赖 |
| --- | --- | --- |
| BalanceDebitService / BalanceDebitServiceImpl | debit、debitInternal、debitFromLedger、readLegacyDebitLedgerCandidate、ensureLegacyAttemptMatches、ensureDebitMatches、cacheDebitResult | UserMapper、BalanceLedgerMapper、BalanceDebitAttemptMapper、Redis、BalanceOwnershipEvidence |
| BalanceRefundService / BalanceRefundServiceImpl | refundInternal、ensureRefundMatches | UserMapper、BalanceLedgerMapper、BalanceOwnershipEvidence、BalanceRefundEvidence |
| BalanceOperationQueryService / BalanceOperationQueryServiceImpl | balanceOperation、requireUniqueOperationKey、containsRefundOperationKey | BalanceLedgerMapper、BalanceOwnershipEvidence；调用退款集合纯规则 |
| BalanceOwnershipEvidence | requireAppliedDebitOwnership、isProvenLegacyDebitOwnership、requireRejectedDebitOwnership、hasTccFreezeConflict、readConfirmableTccFreeze、readDebitOwnershipCandidate、hasTccReceiveConflict、两个confirmedTccRefund重载、requireDisabledTccRefund | BalanceDebitAttemptMapper、BalanceTccDebitMapper、UserTccReceiveMapper |
| BalanceRefundEvidence | readRefundLedgerCandidate、requireRefundReplayCollection、requireRefundGlobalKeys、requireRefundDebitScope、matchesRefundDebitScope、requireFreshRefundCollection、provenRefundPaymentMembership、matchesRefundPaymentOriginal、requireRefundMembership、provenRefundedAmount、containsRefundSnapshot | BalanceLedgerMapper；含原100条上限及集合纯规则 |
| BalanceLedgerRules | assertInternalCaller、validateMoney、validBalanceMoney、isProvenDebitSnapshot、isProvenBalanceOperationRow、isProvenRefundOriginalDebit、requireBalanceOperationEvidence | 无外部依赖；原DECIMAL(18,2)常量、身份/数值/单行证明 |

全部40个方法各有唯一归属，不复制证明算法。跨类调用只增加明确的依赖接收者或静态import，不增加预取、缓存、锁、重试或异常转换。所有新依赖使用构造器和final字段。读写SQL与Mapper、DTO、VO、持久化表及Redis Key/value/TTL均沿用现状。

### 17.3 事务与HTTP兼容

- debit/debitInternal两个Service入口保留@Transactional(noRollbackFor=BizException.class)，各自在同一实例调用私有debitFromLedger，不引入Service间事务嵌套。
- refundInternal保留同一noRollbackFor及默认传播行为。先钱包锁、来源/状态/双集合/额度证明，再credit与ledger同事务；意外写入行数失败仍抛可回滚RuntimeException。
- balanceOperation保留readOnly=true、REPEATABLE_READ，原主行/来源/关联集合/pay集合/selector/owner/全局键唯一性顺序与查询预算不变。共享证明运行于调用方现有事务，不能脱离同一连接或开启新快照。
- Service改为返回BalanceDebitVO、BalanceRefundVO、BalanceOperationVO；Controller按原四个路由统一ApiResponse.ok。VO构造仍在原业务分支，只有外层响应包装移到Service事务完成后。原异常、HTTP状态、JSON金额/时间/null、Feign字段保持；requestId在同一请求线程生成，不引入异步或重试。
- Redis扣款缓存仍处于原调用位置，作为可丢弃加速值处理；本次不将其改成afterCommit或更改故障容忍边界。

### 17.4 验收与提交

先保存本节并建立user-balance基线标签，再实现整体可编译拆分；运行106项余额单测及相关契约，随后完整User/Common、103项资金MySQL、7项主键MySQL和4项账号MySQL/Redis/MVC。补充共享证明全部位于同一只读RR连接的原测试装配，保留原断言；事务代理、缺字段/错误优先级、退款累计、冻结/receive互斥与注入故障的零写入/回滚必须继续成立。

测试只将旧混合对象调用改为三项真实业务能力，反射故障注入改到实际拥有Mapper的Service或证明Bean；不得用旧转发壳维持假边界。记录40方法迁移清单与剔除明确接收者/响应包装后的词法对照。User package、八模块test-compile、Java格式化、git diff --check通过后提交并标记完成。临时MySQL/Redis使用deploy/experiments/compose.yml全新项目和卷，归档后移除容器/网络，保留卷。

本切片完成后继续User TCC/Fence及只读取证实现的命名和归组。真实Seata RM/TC进程、跨服务全链路与性能实验不由本地状态机/事务回归替代。

### 17.5 实际实现与调用链复盘

已按17.2完成拆分：扣款实现279行、退款158行、查询141行、归属证明193行、退款集合证明208行、纯规则107行。行数仅作导航参考；三个业务接口分别只暴露2/1/1个方法。依赖Mapper的证明类注册为Component，其方法限于实现包内可见，内部独用方法保持private；纯规则类不注册Bean。所有依赖均为构造器注入的final字段。

核心调用链逐项复盘如下：

- 四个资金HTTP路由由UserController保持原绑定与校验，再分别调用扣款/退款/查询接口。Service返回原三个VO，Controller包装ApiResponse；不改其他九个账号路由。
- 扣款事务入口 → 私有debitFromLedger → 原caller/金额校验 → 钱包行锁 → 归属与账本范围检查 → BalanceOwnershipEvidence冻结/receive互斥 → 原attempt/余额/ledger写入 → 原Redis加速值 → VO。兼容支付键入口在同一实例和同一事务中转换DTO，未经过第二个Service代理。
- 退款事务入口 → 钱包行锁 → 原DEBIT/状态/重放字段校验 → BalanceOwnershipEvidence确认来源 → BalanceRefundEvidence同pay唯一扣款、linked/pay双集合、金额额度与全局键证明 → 原credit/ledger写入 → VO。证明Bean不新开事务或重新读取钱包。
- 查询只读RR入口 → primary/source唯一性 → 原候选集合 → 退款纯规则校验 → 原selector/membership → BalanceOwnershipEvidence终态归属 → 全局观察键唯一性 → VO。数据读取仍由原入口按原次序发起，纯校验不隐藏额外SQL。

40个原方法各迁移一次，`method-moves.json`记录去向；`method-parity.json`证明忽略注释、格式、明确的证明对象接收者和ApiResponse包装后40个方法体词法一致。`BalanceLedgerServiceTest`保持106项测试，断言/verify调用数量核对一致；资金实库原103项全部保留，并增加退款Service与共享证明使用同一写事务连接的专项。既有同连接RR查询测试的反射观察点迁至queryService和BalanceOwnershipEvidence，原顺序、快照和零写入断言保留。

首次完整420项回归通过。新增退款同连接检查也已单独通过，覆盖来源查询、双集合证明、钱包credit、ledger insert及返回后事务结束。最终带新增项的整批回归、打包和跨模块编译结果在下一节登记。

### 17.6 第二切片验收

第二切片完成，源码基线提交为 `c5dd2a54`，基线标签 `refactor/user-balance-baseline-20261104`，通过标签 `refactor/user-balance-complete-20261104`。当前Java源码、测试装配、反射观察点及源码契约测试均已同步新类型；HTTP映射、DTO/VO字段、Feign、Mapper、SQL、配置和依赖版本保持。

| 验证 | 实际结果 |
| --- | --- |
| 原基线 | User281与Common25单测通过 |
| 定向回归 | 106项余额单测及1项源码契约通过 |
| 完整最终回归 | 24类421项，0失败、0错误、0跳过：Common25、User单测281、资金MySQL104、生成ID MySQL7、账号MySQL/Redis/MVC4 |
| 新事务专项 | 退款来源、集合证明、credit与ledger insert使用同一写事务连接，RR隔离级别保持，返回后无事务残留 |
| 编译与制品 | User package通过；八模块生产/测试源码test-compile通过；Jar包含9个新类型且不含旧混合接口/实现 |
| 源码检查 | 40方法迁移词法对照通过，106项余额单测断言/verify数量一致；格式化与git diff --check通过 |

测试涵盖原兼容/持久扣款入口、幂等重放、金额域/尾零、禁用用户退款、原来源和双集合证明、100条退款上限、并发及跨协议互斥、退款/扣款数据库故障回滚、REJECTED账本提交、查询同连接RR快照与原错误优先级。最终以 `F:/Cloud-Mall/target/backend-refactor-20261104/user-balance/final-regression.log` 为准；verification.json记录逐类统计、方法迁移及制品信息。真实RM/TC进程回调、跨服务端到端与性能实验未运行，User整体尚待下一切片验收。

开发过程仅发现验证夹具/工具问题：词法脚本误识别缩进行、测试代理不能用BaseMapper声明类判断委托对象、缺少assertFalse静态import，均已记录并修正。原资金业务规则未作缺陷修复或行为扩展。

本轮使用全新Compose项目cloud-mall-refactor-user-balance-20261104，MySQL19927、Redis19928及独立子网10.254.243.0/24。UUID测试库均已删除，实验来源表仍为空且校验和保持，Redis DB15最终0键。归档inspect与容器日志后，两容器和网络已移除，两个命名卷保留。只精确移除旧BalanceLedgerService.class与BalanceLedgerServiceImpl.class，User target下历史证据保持。

### 17.7 回退与后续任务

仅撤销本次资金拆分、保留账号重构及地址归属修复：

```powershell
$userBalanceCommit = git rev-parse 'refactor/user-balance-complete-20261104^{commit}'
git show --stat $userBalanceCommit
git revert --no-edit $userBalanceCommit
```

源码反向补丁在临时Git索引中核对，应精确回到c5dd2a54；此检查不修改真实工作区/索引，也不等于运行态回滚。实际回退后依据17.2的9个新类型清单精确清理target/classes中的过期class，再编译并回归；不清空含历史证据的target，不将Git回退当作数据库或Redis回退。

下一步盘点User的LocalTCC资源、Fence回调、HTTP接收状态、只读状态查询和取证报告实现，在service.impl内按真实职责归组。先冻结Bean名/条件装配、actionName、回调入口、Mapper引用和测试入口，再执行迁移；不改RM身份、状态机、取证分类或协议字段。

## 18. User 第三切片：TCC/Fence与只读取证归组

### 18.1 基线与范围

2026-11-04从 `55f26e87b5ef78f38a32158e44c682d57d35ad4a` 继续，分支仍为codex/refactor-backend。本会话由主Agent单独执行评审、实现及验证，其他会话协作规则保持。账号、资金切片已完成；本次只归组16个已有实现并同步引用，保留已有清楚的类名，不把规格编号或协议字符串机械改名。

### 18.2 全限定名迁移清单

下表旧包均为 `com.cloudmall.user.service.impl`，类名保持，目标包为该包下的tcc/evidence。稳定业务接口BalanceTccDebitService与BalanceTccDebitLocalResource留在service根包；Controller、config、Mapper、DTO/VO/PO不移动。

| 新子包 | 原类名 | 职责/装配 |
| --- | --- | --- |
| tcc | BalanceTccDebitServiceImpl | 现有本地Try/Confirm/Cancel状态机；Service Bean及事务保留 |
| tcc | BalanceTccDebitLocalResourceImpl | 官方Fence与业务同事务适配；由原配置创建 |
| tcc | BalanceTccDebitBusinessCallback | 官方Fence反射回调委托；公开方法名和签名保留 |
| tcc | UserTccHttpService | 内部Try/GET鉴权与一次性派发；原配置创建 |
| tcc | UserTccReceiveStore | CLAIMED/bindBranch独立提交；REQUIRES_NEW保留 |
| tcc | UserTccStateQuery | 原只读RR状态证明，供HTTP Bean和取证plain delegate复用 |
| evidence | UserTccReconciliationService / UserTccReconciliationReadService | 非事务入口门与只读基础报告 |
| evidence | UserLinkedRefundEvidenceService / UserLinkedRefundEvidenceReadService | 关联退款候选证明 |
| evidence | UserFrozenOwnershipEvidenceService / UserFrozenOwnershipEvidenceReadService | 当前TRYING冻结归属证明 |
| evidence | UserTerminalFrozenOwnershipEvidenceService / UserTerminalFrozenOwnershipEvidenceReadService | 历史终态冻结归属证明 |
| evidence | UserPaymentLedgerEvidenceService / UserPaymentLedgerEvidenceReadService | 同pay账本只读证明 |

跨包的UserTccStateQuery依赖通过显式import关联；取证Reader仍自行构造原plain delegate并使用已有外层RR，不能把它替换成另一个事务代理。十个取证实现保持同一业务子包，原包内协作不扩大可见性。所有当前生产/测试Java FQN、类资源路径及Spring注册引用同步，历史冻结runner和归档保留。

### 18.3 冻结契约

- LocalTCC接口FQN、ACTION_NAME=cloudmallUserBalanceDebit、prepare/commit/rollback、request上下文键、useTCCFence=false全部保持。Fence反射tryBusiness/confirmBusiness/cancelBusiness/payload签名保持，统一裸DataSource/REQUIRED事务及回调false/rollbackOnly语义保持。
- 保持balanceTccDebitServiceImpl默认Bean名，原@Bean方法名balanceTccDebitBusinessCallback/balanceTccDebitLocalResource/userTccReceiveStore/userTccStateQuery/userTccHttpService及DependsOn/init/destroy顺序。原default-off开关、callback token、caller、HTTP路由、状态/错误码、账本键、消息字段不改。
- CLAIMED先独立提交，首次winner才登记分支；旧接收重放只读、不补发；禁用新Try仍能读取与清算历史。RootContext绑定/清理和RR快照边界保持。
- 取证wrapper在连接/事务前验证开关、身份与上下文；ReadService的readOnly/RR、候选上限、指纹、分类、no-store及不写事实约束保持。取证不得因归组注册RM、访问TC或触发恢复。
- 不更改SQL/表/Mapper、依赖版本或业务算法；完整16类按去除package/import与注释/格式的执行词法对照验收，日志category因简单类名保持而只随FQN变化，不新增原始载荷日志。

### 18.4 验证、实验与提交

先保存细化方案及user-tcc基线标签，再执行迁移。原User/Common全单测作为基线；迁移后运行全部User单测、全部8类MySqlIT及账号MySQL/Redis/MVC，覆盖取证门、接收事务和单连接快照。补充必要的组件发现/Bean名/default-off隔离校验，不能只验证import编译。

真实Seata验证分两次独立Maven测试JVM：BalanceTccFenceSeataIT（6项真实TC回调/Fence场景），BalanceTccRmFailureIT（9项独立RM进程、提交窗口崩溃与连接中断恢复）。后者禁止与其他注册RM的测试共享JVM，沿用原Process句柄与未决保留规则；只终止本测试创建的子进程，不接触既有服务。若事务未收敛，保留该UUID数据库和TC会话证据，不宣告通过或覆盖现场。

中间件使用deploy/experiments/compose.yml加本次独立override，项目cloud-mall-refactor-user-tcc-20261104，新建MySQL/Redis/Seata三个卷；MySQL19929、Redis19930、TC19931，子网10.254.244.0/24（启动前核对无重叠）。TC复用固定镜像seataio/seata-server:1.5.2、file模式，SEATA_PORT=19931且宿主/容器端口一致，客户端显式grouplist指向127.0.0.1:19931。override另设SEATA_IP=127.0.0.1，但实测XID使用容器地址10.254.244.4:19931，不能仅从环境变量推断发布地址；以实际SDK连接日志及RPC/恢复验收判断本轮可达性。不得复用默认TC会话或原中间件卷。额外TC仅在本次override声明，不改常驻部署。

全部专项收敛后归档日志、源码词法/装配证据、TC/RM工件，移除本次容器与网络并保留卷。User打包、八模块test-compile、旧class精确清理、Java格式化及git diff --check通过后提交并建立完成标签。源码回退只撤销该归组提交，账号/资金及地址归属修复保留；Git不回退运行态或数据。User本轮验收结束后再转向Stock。

引用核对补充：6个Pay取证MySqlIT通过标准sibling target/classes和URLClassLoader反射加载User实现，需同步FQN字符串并实际回归，不能只依赖编译。范围为PayTccUserEvidenceMySqlIT以及Account/FrozenOwnership/LinkedRefund/PaymentLedger/TerminalFrozenOwnership五个EvidenceMySqlIT；只改变反射类路径，Pay业务源码不动。在同一独立MySQL内创建空cloudmall_pay来源表，所有测试仍创建自有UUID库并核对来源校验和；不访问默认Pay数据库。

运行前置补充：真实TC/RM测试按README既有命令带 `-DargLine=--add-opens=java.base/java.lang=ALL-UNNAMED`。实际Boot扫描复现账号测试@Configuration缺少Profile的历史隔离缺陷；修复为只在其所属测试上下文激活，独立提交 `0b50c167`。本次归组回退可保留该测试修复。

### 18.5 实际迁移与调用链核对

16个实现已移动，稳定接口、所有原Bean方法名和default-off配置保持。新增两个package-info说明运行实现与只读取证的边界，校正“只读不绑定事务”等易误读注释为“不绑定Seata XID、仍使用本地RR”，并准确描述既有诊断日志输出的用户/支付号；日志行为未变。`lexical-parity.json`确认16类除声明位置/import/注释/格式外执行词法一致，`reference-parity.json`确认34个已有Java引用文件只发生声明的FQN/import变化，其中6个是Pay反射加载测试。

逐行复盘修改、入口装配与主要事务调用链：

- BalanceTccResourceConfiguration依原开关与DependsOn准备同库Fence，原LocalTCC接口注册稳定action；资源适配器TransactionTemplate → 官方TCCFenceHandler → 公开BusinessCallback → 本地资金Service事务代理。反射方法名、分支身份、回滚标志和终态核验保持。
- UserTccHttpConfiguration依资源条件装配桥、接收Store和状态Query。Controller → 认证/参数门 → Store.claim的REQUIRES_NEW提交 → 唯一winner调用LocalTCC → 状态只读查询 → Store.bindBranch独立提交。重放不再派发，RootContext只清理由当前方法建立的绑定。
- 取证Controller → 非事务Service门 → 对应ReadService RR代理。基础Reader构造plain UserTccStateQuery并读取同一快照；冻结Reader的peer Query同样加入现有快照，未增加Spring同类型Bean或RM调用。
- 关联退款、当前/历史冻结及同pay账本Reader按原依赖复用基础报告或已证明退款报告，保留候选上限、金额/身份核对、指纹、缺证/冲突分类和固定no-store接口行为。

组件扫描新增3项验证覆盖11个稳定Service Bean名、事务代理、默认关闭时无RM/HTTP桥与无Mapper/事务访问、缺资源时HTTP配置拒绝、以及开启HTTP后的Store/Query/桥Bean名。夹具本身使用专用Profile，防止进入其他真实Boot测试；Runner需在配置注册前通过环境属性激活该Profile。

真实Fence/TC 6项和独立RM故障9项均已通过，后者包含Try/Confirm/Cancel提交窗口、应答前崩溃和TC连接中断两种决议恢复。9个场景全部结束，资金及TC状态收敛后精确删除测试库；93个本轮RM日志/快照/参数工件已复制并校验哈希，原target/cm15b_rm_it目录保留。完整本地及Pay取证回归结果在最终验收节登记。

### 18.6 补测发现的Pay测试契约滞后

6个Pay取证IT原用例在当前User代码上复现构造参数和读取预算失配，属于归组前已存在的测试滞后：User迁移16类执行词法未变。UserTccHttpService实际旧测试传5参，遗漏现有debitEnabled第6参；GET夹具继续保留写依赖null并显式传false，不开放POST。

按已冻结的User TCC全pay DEBIT范围及无资金全账本范围规格，健康CONFIRMED state需要6次SELECT，TRYING/CANCELLED需要7次；基础账户报告另加receive定位、账户、冻结汇总、固定退款共4次。当前冻结报告为CONFIRMED基础10+候选1+每个TRYING peer7；历史终态Reader还保留每个peer独立scope检查，CONFIRMED/CANCELLED peer分别7/8次。linked报告为CONFIRMED基础10+候选1+每个合法refund的COUNT；payment报告再加同pay集合及其每个合法键COUNT。101哨兵仍禁止后续逐行读，不删减预算断言。

同pay额外DEBIT现在会在基础User state门先以HTTP409拒绝，未到后续候选报告。Pay取证Service沿既有Runtime/HTTP失败契约返回USER_EVIDENCE_UNAVAILABLE；不能将它误当已收到LOCAL_CONFLICT报告的USER_EVIDENCE_CONFLICT。相关测试按具体额外DEBIT变体断言固定UNAVAILABLE和6次User读取，其他坏候选仍保留原缺证/冲突报告断言。所有单连接、只读、上下文恢复、禁止POST、两库事实/校验和断言保留。该测试校正独立提交，生产读取顺序、错误映射和资金业务不改。

构造修正后还暴露最早state GET夹具的健康接收状态仍为ACCEPTED，现行receive元数据契约只接受CLAIMED。健康seed改为CLAIMED，同时在拒绝变体中显式保留ACCEPTED，禁止把旧错误数据放行为健康。账户双状态循环分别采用10/11次读取；变体循环内新增的6次短路预算按单次调用增量核对，保留原累计SQL日志。现阶段受影响4类34项定向回归通过，6类组合结果作为最终验收依据。

### 18.7 最终验收与资源归档

User本轮三个重构切片全部完成。第三切片基线为 `3a2ce3c4`，期间两个独立测试修正为 `0b50c167`（账号测试配置隔离）与 `9e4fd741`（Pay取证夹具契约同步）。归组通过标签为 `refactor/user-tcc-complete-20261104`，模块通过标签为 `refactor/user-complete-20261104`。

最终取各类最后一次成功执行结果，39类547项，0失败、0错误、0跳过，不重复累计基线/定向重跑次数：

| 验证范围 | 结果与证据 |
| --- | --- |
| User/Common本地完整回归 | 31类483项：Common25、User单测284、8类MySQL170、账号真实MySQL/Redis/MVC4；local-regression.log |
| 真实TC/Fence回调 | 1类6项；fence-verified.log，独立TC会话 |
| 独立RM进程故障恢复 | 1类9项；rm-failure.log，9场景均收敛并清理测试库 |
| Pay/User真实只读取证HTTP/Feign | 6类49项；pay-evidence-final.log，所有来源校验和与零写入断言通过 |
| 构建与制品 | User package及八模块生产/测试test-compile通过；16个新实现路径存在、旧路径及测试控制辅助类不进入Jar |
| 源码与格式 | 16类执行词法对照、34个引用文件仅声明的路径变化，Java格式化及git diff --check通过 |

证据根为 `F:/Cloud-Mall/target/backend-refactor-20261104/user-tcc`，verification.json汇总逐类统计、制品哈希、独立修正及回退检查。93个RM工件已复制并逐文件核对哈希，原 `backend/cloud-mall-user/target/cm15b_rm_it_77e066b9c7ab44bf8a23f06f934d635b` 保留。真实RM/TC与本地资金、只读取证链已经覆盖；商城完整下单/支付/库存全链及性能实验未在本轮执行，不将本次结构重构视作历史CM18/CM21项目整体闭环。

全部测试结束后SHOW DATABASES仅剩系统库及空的实验cloudmall_user/cloudmall_pay来源库，所有UUID测试库已删除；来源行数和测试校验和保持，Redis DB15为0键。三个容器的inspect/日志归档后，cloud-mall-refactor-user-tcc-20261104容器和网络已移除，mysql_data、redis_data、seata_data三个命名卷保留。既有中间件与历史实验卷未修改。编译后核查旧16个实现class已不存在，无需清空User target历史工件。

### 18.8 回退与下一模块

仅撤销本次TCC/取证归组，保留账号/资金重构、地址修复及两个独立测试修正：

```powershell
$userTccCommit = git rev-parse 'refactor/user-tcc-complete-20261104^{commit}'
git show --stat $userTccCommit
git revert --no-edit $userTccCommit
```

反向补丁在临时Git索引中核对，源码应精确回到9e4fd741；真实工作区和索引保持当前验收版本。该检查只证明源码回退，不是运行态回滚。实际回退后按moves.json清理新包内过期class，并核对本次新增UserImplementationGroupingTest及其嵌套class，再编译和回归；不删除历史target证据。模块完成标签表示当前通过点，撤销全部User重构需按三个实现切片逆序处理依赖。

下一模块为Stock：先盘点普通库存查询/预扣/确认/回滚、秒杀配额与恢复职责，以及Cm18演练类的实际调用和Mapper映射。细化命名与兼容清单、记录基线后再修改；继续保留Git阶段回退点。本会话单Agent约束仍有效。

## 19. Stock 第一切片：Seata 演练语义命名与隔离边界

### 19.1 范围、顺序与基线

源基线为 `418cd0d0`（User完成点）。主Agent按本会话约定独立评审和执行，不启用子Agent，不改变持久协作规则。已读AGENTS、README、Bug台账以及Seata全局事务、Stock依赖保护、普通流水/快照证明规格。原Common25、Stock185单测全部通过。

盘点确认StockController仍继承约1034行StockServiceImpl，空StockService未表达业务契约；普通数量查询、预扣/确认/回滚、持久状态证明、秒杀受理混合。其事务代理、Sentinel切面、独立claim与提交后Redis清理必须在下一切片一起拆分。本切片先按第7节顺序完成独立演练命名与归组，不混入普通库存/秒杀状态机变化。

后续Stock切片顺序：①本节演练命名；②组合Controller及普通库存查询/预扣接口、共享原请求证明和秒杀受理入口拆分；③缓存核对、秒杀结算、outbox与恢复归组。②须在实现前进一步登记全部方法归属和事务/鉴权次序，不能套用泛化Service模板。

### 19.2 精确迁移清单

包根仍为 `com.cloudmall.stock`，启动类、MapperScan范围、依赖版本不变。

| 原类型 | 新类型 |
| --- | --- |
| config.Cm18SeataStockConfiguration | config.SeataStockDemoConfiguration |
| config.Cm18SeataXidBindingFilter | config.SeataStockXidBindingFilter |
| controller.Cm18SeataStockDemoController | controller.SeataStockDemoController |
| domain.dto.Cm18SeataBranchDTO | domain.dto.SeataStockBranchDTO |
| domain.vo.Cm18SeataBranchVO | domain.vo.SeataStockBranchVO |
| domain.po.Cm18SeataStockDemoPO | domain.po.SeataStockDemoPO |
| mapper.Cm18SeataStockDemoMapper | mapper.SeataStockDemoMapper |
| service.Cm18SeataStockDemoService | service.SeataStockDemoService |
| service.Cm18SeataStockDemoOrchestrator | service.SeataStockDemoOrchestrator |
| service.impl.Cm18SeataStockDemoServiceImpl | service.impl.seata.SeataStockDemoServiceImpl |
| service.impl.Cm18SeataStockDemoOrchestratorImpl | service.impl.seata.SeataStockDemoOrchestratorImpl |

现有 `Cm18SeataStockDemoTest` 同步改名为 `SeataStockDemoTest`。新增包说明解释本地分支与事务外编排关系；仅类型和装配表达调整，执行算法逐词核对。

### 19.3 冻结契约与运行陷阱

- 路径仍为 `/api/internal/cm18/seata/demo/branch`，内部Header、TX_XID、失败点、`cm18-`事务号、错误码/错误文本、JSON字段和XID摘要均保持。CM18在这些位置是已有协议标识，不能全仓替换。
- `@Profile("!prod")`、`cloudmall.seata.cm18.enabled`、暂停配置及0–30000ms范围保持；默认关闭与prod禁用仍不创建演练入口/Filter/服务。
- Controller、Configuration、Filter、Mapper以及两服务沿用原Spring Bean名，类型改名后通过显式组件/Bean名称保留。Mapper仍使用BaseMapper，PO表/字段/主键映射保持 `seata_cm18_stock_demo`；不改SQL/DDL。
- 两实现放入 `service.impl.seata`。`StockDependencyAspect` 原 `!within(...impl.Cm18*)` 必须改为明确的 `!within(...impl.seata..*)`。真实Spring AOP断言演练begin/commit/rollback不进入库存业务熔断，普通库存事务仍被完整保护。
- 编排调用独立事务代理，`STOCK_AFTER_WRITE`仍发生在本地分支提交之后；不把编排也加事务、不在同类调用中丢失代理。保留XID Filter绑定、同XID嵌套、不一致拒绝和finally清理语义。
- Order自己的DTO/VO和Feign方法本轮不改；它们以JSON/HTTP对接，不依赖Stock Java类型。通过Order现有演练测试和八模块编译核对。

### 19.4 验证、证据与回退

先提交本节并打 `refactor/stock-seata-baseline-20261104`，再实施迁移。新增装配/HTTP/Filter/事务测试覆盖默认关闭、prod拒绝、开启后的原Bean名、原JSON与Header、XID清理、真实事务代理与切面排除。使用独立Compose项目和全新MySQL卷验证真实Mapper namespace/表映射、本地提交、异常回滚及分支提交后故障；不借用默认业务库。

实验基于 `deploy/experiments/compose.yml`，项目名 `cloud-mall-refactor-stock-seata-20261104`，localhost MySQL端口19932，独立网段10.254.245.0/24，使用前检查冲突；凭据root/root。测试只创建UUID schema，清理自己的schema，归档容器信息/日志后down并保留卷。未启用真实TC时必须把该验收明确标为本地事务验证，不宣称AT二阶段或RM恢复已经重跑。

全Stock单测、相关Order演练测试、Stock打包与八模块test-compile为本切片门槛。Google Java Format与git diff --check通过；只清理目标目录内旧类名对应的class文件，保留历史实验数据。源码与新增测试、方案按阶段提交，建立完成标签；用临时Git index验证逆补丁回到父提交，不修改真实工作树。Git回退不覆盖数据库、Redis或忽略的证据目录。

证据写入 `target/backend-refactor-20261104/stock-seata/`。本节验收只表示Stock演练命名切片完成，Stock整体及普通业务拆分仍待后续任务。

### 19.5 首切片验收与调用链复盘

11个生产类型及原演练测试已按清单改名；两个实现进入 `service.impl.seata`，新增包说明。六个原Bean名显式保留，其中Mapper名称由真实MyBatis扫描验证，SQL statement namespace使用新类名，表名仍为 `seata_cm18_stock_demo`。配置工厂方法改为语义名称，通过 `@Bean` 保留运行身份。演练包从普通库存事务切面明确排除，原三项Stock完整事务begin/commit/rollback保护测试继续通过。

核心链：原HTTP路径 → `SeataStockXidBindingFilter`校验并按需绑定XID → `SeataStockDemoController`核对Header/事务号 → `SeataStockDemoOrchestrator` → 独立代理的 `SeataStockDemoService.writeBranch` → `SeataStockDemoMapper`写原演练表 → 本地分支提交 → 编排暂停/受控故障 → Filter清理自身绑定。已有相同XID保留，冲突XID拒绝且不改已有上下文。没有给编排添加事务，没有把Redis/MQ或真实库存表纳入演练。

新增MVC夹具曾按Controller抛出的403期待HTTP403，实际公共异常处理器将ResponseStatusException归入500/COMMON_INTERNAL_ERROR；Filter的sendError仍为403/400。这是原Controller与Common处理器的既有行为，本切片保持并用精确断言记录，未修改全局异常映射。后续如统一该错误协议，应独立评审和提交，不能混入纯命名回退。

最终52类227项测试全部通过，零失败、错误和跳过：

- Common25；Stock191单测（原185+6项配置/HTTP/Filter/事务边界）；Stock真实MySQL2；Order演练契约9。
- 实库验证原Bean名、namespace、主键/列/时间映射，以及实际INSERT返回后抛错的完整本地回滚。独立连接确认受控STOCK_AFTER_WRITE发生在已提交分支之后；这不表示已验证TC全局回滚。
- 11/11生产类型排除package/import、显式Bean名及声明的标识符迁移后执行词法一致，原3项演练测试保留。
- Stock打包和八模块生产/测试源码编译通过；Jar包含11个新类型，无旧类型或新增测试夹具。旧class精确检查无残留，没有清空target历史证据。
- 使用Google Java Format 1.22.0并检查git diff；依赖、资源配置、SQL/DDL、Order/Pay生产文件未改。

独立MySQL 8.0.46实验使用全新项目/卷，4次测试例资源均为独占UUID schema并已清理（专项2例、最终2例）。最终仅剩空源库及系统库；inspect、日志和数据库清单已归档，容器与网络已移除，保留卷 `cloud-mall-refactor-stock-seata-20261104_mysql_data`。默认中间件未变。证据索引为上述目录的 `verification.json`。

本轮未启动真实TC、未重跑AT二阶段/RM崩溃恢复，也未执行商城完整链路或性能测试。Stock整体重构未完成，下一切片按19.1继续普通库存与秒杀入口职责拆分。

### 19.6 Git回退点

方案提交/基线为 `90087094`，标签 `refactor/stock-seata-baseline-20261104`；本切片验收点使用 `refactor/stock-seata-complete-20261104`。源码回退使用：

```powershell
git revert refactor/stock-seata-complete-20261104
```

回退同时恢复原类型名、原切面排除和原测试名。逆补丁使用临时index验证能够恢复父提交，真实工作树和index不回退；实际演练时先按精确清单移除当前新类型及新增测试夹具对应的class再编译，避免旧Maven产物产生同名Bean。不得清空带历史实验数据的target，不使用reset --hard。该标签只撤销本Stock切片，不撤销已完成的User等模块，不回退运行数据；后续若提交依赖此切片，需要按依赖顺序逆序撤销。

## 20. Stock 第二切片：主业务职责与HTTP继承解耦

### 20.1 范围与方法归属

基线为 `f50c6734`，原Common25/Stock191单测已通过。主Agent单独评审实施。本切片删除空StockService及混合StockServiceImpl，将StockController改为构造器注入三个实际业务接口，原六个HTTP路径、绑定required属性不变。为控制Java调用方与JSON同时迁移的范围，本切片暂保留原ApiResponse返回契约和Map字段形状；后续单独完成响应VO/包装移回Controller，这不是最终分层例外。

| 新职责 | 原方法与依赖 |
| --- | --- |
| StockQueryService / StockQueryServiceImpl | get/quantity及reservation入口；Redis数量投影、StockSqlMapper、共享StockReservationReader |
| StockReservationService / StockReservationServiceImpl | reserve、confirm/rollback双重载、validateActionKey、requireNoUnsettledSeckillFact、settleNormalLine、stockSnapshot、clearLegacyReservationAfterCommit、flow、merge、validate、validateSalesMetrics；保留claim、结算与销售outbox真实代理 |
| SeckillReservationService / SeckillReservationServiceImpl | seckill、seckillAfterSentinel、redisOperation、checkedSeckillLua、checkedOrderMarker、payloadMatchesOrder、redisUnavailable、markSeckillRejected、accepted、acceptedOrRecovering、recoveryRequired、acceptAfterLua、toSeckillPayload、epoch；Clock和Lua仅此职责持有 |
| StockReservationReader | reservationResult、normalReservationProof、requestSnapshotMatches、exactReservationNumber、unknownReservationProof、itemsFromSnapshot、alreadyReserved、reservationProofUnavailable；不新增事务或独立读切面 |
| StockReservationSnapshotCodec | 原toJson和sha256，writer与reader共享同一ObjectMapper序列化与hash规则 |

原now在写/秒杀实现内保持同一+08:00表达式；仅删除无调用的number/id/recordSeckillPending/markSeckillAccepted及未使用RabbitTemplate依赖。短变量、占位注释按真实含义修正，算法与SQL不改。新增实现使用构造器与final依赖，允许历史直构造测试显式传入null的可选settlement/sales依赖；Spring生产装配仍要求原必需依赖。

### 20.2 冻结的执行次序与代理

- Controller仅秒杀入口先调用AuthContext.requireUserId，再将明确long身份传入Service。与原方法首行鉴权相同；其他五入口不新增身份限制，Spring请求绑定仍先执行。
- reserve保留noRollbackFor(StockReservationRejectedException)，独立REQUIRES_NEW claim与整笔升序锁定、条件扣减、流水、cache-repair、状态更新次序不变。confirm/rollback两重载保留@Transactional；幂等动作键验证、秒杀优先路由和普通fallback顺序不变。
- StockDependencyAspect事务入口继续包住真实Service代理的begin/commit/rollback；只读切面从旧StockServiceImpl.reservation移至StockQueryServiceImpl.reservation。共享Reader本身没有新切面，reserve内部读继续处于原事务与保护Entry中。
- GET仍非RR且不写库；数量缓存命中不受MySQL读熔断阻断，缓存失败沿原Mapper回退。原快照严格解析使用ObjectMapper副本，重放缺证503、UNKNOWN和null候选语义不变。
- 秒杀入口不新增外层事务：鉴权→参数/归属→Sentinel→指纹→单次时钟→持久claim→Redis Lua→独立accept/outbox提交。固定订单号、eventId、requestHash、Redis Key、Lua文本、失败后marker保留与未知受理恢复均不变。
- 原sales-metrics.enabled与PostConstruct实际代理校验保留在普通写实现；普通/秒杀结算与销售outbox的同连接提交或回滚必须实库验证。旧StockController不会继续承载事务或注入Mapper。

### 20.3 验证与提交

先提交本节并打 `refactor/stock-core-baseline-20261104`，再按方法清单迁移。现有单测保留业务断言，只迁移被测对象/装配；真实MySQL/MVC、Pay读取Stock的反射夹具随新构造器同步，冻结历史runner不改。新增组合Controller绑定/鉴权优先级及真实Spring代理测试，并验证普通写事务、库存不足持久REJECTED、销售outbox原子性、提交后Redis清理与共享读取同连接。

独立Compose项目 `cloud-mall-refactor-stock-core-20261104`，MySQL19933/Redis19934，新卷，候选网段10.254.246.0/24；启动前检查冲突，root/root。按原schema/迁移准备空源表，各IT只写自己的UUID schema。显式运行Stock全部单测及适用MySqlIT、PayTccStockEvidenceMySqlIT；新增真实Redis检查不借用默认DB。归档后down本次容器/网络，保留卷。完整商城、TC/RM恢复和性能实验不在本轮。

门槛为业务回归、路由/Feign契约、执行词法对照、Stock打包、八模块test-compile、Google Java Format与git diff --check。只清理精确旧class，保留target历史工件。证据写入 `target/backend-refactor-20261104/stock-core/`；源码按可回退阶段提交，并以临时index验证逆补丁。第一切片Seata命名保留，本轮结束仍须继续响应VO及缓存/恢复归组。

### 20.4 实际迁移与调用链复盘

已删除空StockService及混合StockServiceImpl。StockController仅持有三个业务接口，保留原六条路由；秒杀在原首个业务检查点读取身份，其余五入口不新增身份要求。实现规模分别为查询69行、普通预扣357行、秒杀受理465行、共享证明Reader257行、规范快照Codec44行。原未使用RabbitTemplate字段和四个无调用私有方法已删除，所有原测试方法仍保留。

主要调用链逐项对照如下：

1. 普通预扣：Controller → StockReservationService真实事务代理 → 原请求校验/旧流水重放 → 独立REQUIRES_NEW claim → 升序锁定SKU → 数量/流水/cache-repair/request同事务更新 → Reader证明。真实连接ID确认claim独立连接，写入和Reader使用同一事务连接；库存不足持久REJECTED，触发器SQL失败回滚数量/流水但保留已提交INIT。
2. 确认/回滚：Controller → 受Sentinel保护的写代理 → 原动作键 → 秒杀结算优先 → 普通状态/流水条件更新及销量outbox → afterCommit清理旧Redis标记。外层回滚不清标记，成功提交才清理；销量开启后的同事务成功/失败由既有StockSalesMetricsMySqlIT覆盖。
3. 查询：Controller → StockQueryServiceImpl → Reader。状态查询进入原MySQL读保护，不新增RR；数量查询缓存命中不进入MySQL保护，缺值/故障才沿原Mapper回退。Reader不另开事务或Entry，避免改变预扣重放的事务边界。
4. 秒杀：Controller身份 → SeckillReservationServiceImpl的原校验/Sentinel → 指纹与单次时钟 → 原claim代理 → 原Redis Lua → 原accept代理提交ACCEPTED、结算行和outbox。真实HTTP重放验证同一订单/eventId及一次扣配额，结果未知与marker保留逻辑不变。

43个方法/重载位置在声明的接收者与变量替换、身份参数迁移后执行词法一致；12个Mapper/资源文件与基线一致，包含Lua。测试装配从Controller事务代理迁至实际写Service代理，查询/写入职责分别构造；新增配置均使用专属Profile，已有依赖事务夹具也限定为显式激活，避免真实启动扫描到测试配置。Pay只更新反射组装，Redis参数类型从原Stock子ClassLoader读取，不新增Pay依赖。

### 20.5 历史夹具修正与验证结果

独立提交 `7153ad65` 修正五类Stock实库夹具的六处INSERT：显式填写schema要求的request_hash/request_payload，未改DDL、sql_mode或生产证明。这两个字段在该组身份/状态证明测试中不被核验，使用明确的64位占位摘要/空JSON，不将其作为完整指纹证明。OrderIdentity综合测试的legacy PENDING期望按已有T57规格改为UNKNOWN/items=[]，ACCEPTED/REJECTED仍精确为1项。五类103项全部通过，所有原库checksum、只读、事实与恢复CAS断言保留。

迁移过程另修正了测试工厂误加身份参数、过期普通claim字段注入、Mockito空Map与真实无记录null差异、SQL触发器45000的原异常分类期望，以及Pay缺少Redis编译依赖时的反射类型读取。这些是测试装配/期望修正，生产业务拒绝与依赖错误分类保持。失败日志均保留。

最终60类396项测试通过，零失败、错误和跳过，按每类最后一次结果计数：

| 范围 | 项数 | 说明 |
| --- | ---: | --- |
| Common | 25 | 公共契约回归 |
| Stock单测/MVC/AOP | 194 | 原191项及新增Controller2、查询保护1 |
| Stock真实MySQL/Redis | 171 | 全部MySqlIT，包含新增核心4、销量原子性及44项恢复器/31项操作键测试 |
| Pay→Stock | 6 | 真实HTTP/Feign、只读SQL、上下文与原库事实核验 |

新增核心4项验证六个并发订单争抢最后1件库存只有1个成功、5个持久REJECTED；独立claim/共享证明连接；SQL故障回滚与原号重放；事务提交/回滚对应的Redis清理；原Lua与outbox的一次受理。完整Stock回归发现的最后一项T57旧期望已定向回归，依赖事务夹具新增Profile后的4项也重新通过，证据明确记录这两类的最新结果替换，不累加重复执行次数。

Stock打包和八模块生产/测试源码编译通过。Jar包含八个新业务/共享类型，无旧StockService/StockServiceImpl或测试夹具；旧class精确检查无残留。Google Java Format 1.22.0已用于全部改动Java文件，大型历史测试只格式化改动范围；git diff --check通过。

全部UUID库已清理，源Stock10表及Pay2表精确COUNT均为0，Redis DB0/15均为0键；日志、inspect、源码/方法对照和60类报告已归档，另复制校验40份本次销量原子性工件。实验容器/网络已移除，保留新建的mysql_data/redis_data卷，默认环境和历史target工件未变。总证据为 `target/backend-refactor-20261104/stock-core/verification.json`。

### 20.6 回退与后续

方案提交/基线为 `430ebd28`，标签 `refactor/stock-core-baseline-20261104`；验收标签为 `refactor/stock-core-complete-20261104`。撤销本切片：

```powershell
git revert refactor/stock-core-complete-20261104
```

逆补丁以临时index核对可恢复父提交 `7153ad65` 的完整树，真实工作树/index未执行回退。因此回退结构拆分仍保留五类历史夹具修正，以及上一切片Seata命名。实际回退后仅按新类型与新增夹具的准确class清单清理构建残留，再编译；保留target中的历史证据，不使用reset --hard。Git不回退数据库/Redis或忽略的工件。

本切片未启用真实TC/RM恢复，也未跑完整商城链路或性能实验。Stock整体仍在进行中：下一步先逐端点登记Map的可选字段与null语义，把响应装配移回Controller并形成VO，再细化缓存核对、outbox和恢复实现的归组清单；不得顺带改Lua、CAS、租约或公平扫描算法。

## 21. Stock 第三切片：主接口响应VO与HTTP包装

### 21.1 范围与评审

基线为 `c16419e4`，已验证Common25、Stock194单测/171实库、Pay→Stock6共396项。本会话仍由主Agent独立评审实施。已复读AGENTS、README、Bug台账、API规格库存部分与依赖保护规格；不修改外部接口契约。

本切片只处理StockController的六个主接口：三个Service接口、对应实现、共享StockReservationReader及SeckillQuotaSettlementService返回具体VO，ApiResponse只在Controller成功返回处创建。确认/回滚仍委托同一个结算状态机，settle没有秒杀事实时仍返回null，不能变成空VO。

内部受理事实GET暂保留现有Map：其已观察字段可以显式null，未观察字段则不存在，与本节结果Map的非null规则不同。将在后续内部查询VO及恢复归组切片单独冻结，禁止本轮套用NON_NULL而改变证据语义。Seata演练入口已有VO，不改。

### 21.2 原JSON形状与新类型

| 结果分支 | 新类型 | 精确data字段 |
| --- | --- | --- |
| 数量查询 | 原StockQuantityVO | skuId、availableQuantity，沿用原类型/null规则 |
| 预扣/状态查询 | 原StockReservationVO | orderNo、status、items，沿用原明细、UNKNOWN及ALREADY_RESERVED规则 |
| 确认首次/幂等 | StockActionResultVO | confirmed=true、idempotent=false/true；其它字段不存在 |
| 回滚完成首次/幂等 | StockActionResultVO | rolledBack=true、idempotent=false/true；其它字段不存在 |
| 秒杀释放待投影 | StockActionResultVO | rolledBack=false、pending=true、status=UNKNOWN；idempotent/confirmed不存在 |
| 秒杀受理成功/重放 | SeckillAcceptanceVO | accepted=true、orderNo、eventId、reservationStatus、status=PENDING_PAYMENT；reason不存在 |
| 已有REJECTED受理 | SeckillAcceptanceVO | accepted=false、orderNo、eventId、reservationStatus=REJECTED；status/reason不存在 |
| 受理待恢复 | SeckillAcceptanceVO | accepted=false、orderNo、eventId、reservationStatus=RECOVERY_REQUIRED、status=ORDER_RECOVERY_PENDING、reason |

两个新VO位于domain.vo，字段不可变，通过语义工厂生成合法分支。可选字段仅使用NON_NULL省略，不使用NON_DEFAULT/NON_EMPTY；false必须保留。必填字符串保留原Map.of拒绝null语义，恢复reason仍必填。原数量/预扣VO不加新的全局包含规则，ObjectMapper配置不改。

### 21.3 返回边界与兼容核验

- StockQueryService返回StockQuantityVO/StockReservationVO；StockReservationService.reserve返回StockReservationVO，confirm/rollback两个重载均返回StockActionResultVO；SeckillReservationService返回SeckillAcceptanceVO。
- Reader返回原VO；alreadyReserved将原wrapper/data两层缺证检查折为VO非null且status精确RESERVED，保持读取异常503、原对象状态修改为ALREADY_RESERVED的顺序。
- SeckillQuotaSettlementService.settle/pendingRollback/conflict仅改变Java返回类型和结果构造；所有null候选、校验、CAS、锁、事务、幂等与未知结果门保持。辅助受理方法同样只改结果构造。
- 六个Controller方法在Service正常返回后调用一次ApiResponse.ok，成功code/message/data/requestId字段保持。requestId仍由公共包装器生成UUID；生成时机由事务内转为Service代理返回后。本模块没有把该响应requestId写入DB/Redis/outbox，销量事件关联使用原MDC traceId或已有显式null，均不改。
- 异常不被Controller包装为成功，原HTTP错误码、状态与响应头保持。路径、请求DTO、Feign签名/JSON、金额、时间、Redis Key、Lua、消息以及事务/切面均不改。Order、Pay生产代码不引入Stock Java类型依赖。

### 21.4 验证与提交

先提交本节并建立 `refactor/stock-response-baseline-20261104`，再实现。现有测试仅把直接Service返回值从ApiResponse/Map改为具体VO，HTTP/Feign仍断言原统一包装；原所有业务断言保留。新增完整JSON字段集合矩阵、false/缺省/null控制、必填null拒绝、Controller包装及事务提交失败不返回成功的检查。

运行Stock全部单测/实库、Common、Pay→Stock实际HTTP/Feign，以及Order库存动作和秒杀消费契约专项。新建独立Compose项目 `cloud-mall-refactor-stock-response-20261104`，MySQL19935、Redis19936、网段10.254.247.0/24（先核对冲突），全新卷、root/root；复用已验证空源DDL与UUID库隔离方式，测试后核对并归档、移除本次容器/网络、保留卷。证据放 `target/backend-refactor-20261104/stock-response/`。

结果构造按旧Map字段与VO工厂逐项比对，其余执行词法不变；Stock打包、八模块test-compile、Google Java Format和git diff --check通过后提交，并用临时index验证逆补丁。该切片可独立回退，不回退前两次Stock拆分或历史夹具修正。真实TC/RM、商城全链及性能不在本轮；后续仍有内部查询VO与缓存/恢复职责归组。

### 21.5 实际改动、核对与验收

已新增不可变的StockActionResultVO、SeckillAcceptanceVO及各分支语义工厂。三项主Service接口、五个业务/读取实现均不再引用ApiResponse，六个Controller方法各在业务正常返回后包装一次。普通及秒杀最终动作、受理重放与待恢复返回具体VO；settle无秒杀候选仍返回null，不改变普通库存fallback。旧查询VO及全局Jackson配置未改。

调用链现为HTTP绑定/身份 → 原Service事务或Sentinel代理 → 原业务规则与持久操作 → 具体VO → Service代理提交/回滚结束 → Controller统一包装。Reader重放仍拒绝null或非RESERVED证明，成功时修改同一原VO为ALREADY_RESERVED。requestId仍为UUID、仅在HTTP包装时生成，未引入消息/数据库关联字段变化。新增真实AOP+MVC用例证明提交失败仍返回503/STOCK_SERVICE_UNAVAILABLE，而不是成功响应。

55个方法/重载位置经限定归一化对照通过：只移除ApiResponse包装、把VO工厂展开为原字段Map、折叠原wrapper/data的重放null检查，其余执行词法一致；12个Mapper及资源文件保持。两种VO的八个具体结果组合（确认/回滚首次与重放、待释放、秒杀成功/拒绝/待恢复）通过独立JSON字段集合矩阵；false保留、未适用字段省略、必填null拒绝及旧查询显式null均检查。HTTP包装完整字段、UUID及通用Feign解码后的Map内容也核对一致。

迁移首轮测试编译发现两处旧多行Map强转和一处在Service上读取统一响应码的断言。已改为具体VO字段，并将code/message/data/requestId检查放到Controller契约测试；原业务状态与数据库事实断言保留。这批问题仅在测试迁移中出现，未改生产异常分类或放宽测试条件，失败日志保留。

最终65类431项全部通过，零失败、错误和跳过：

| 范围 | 项数 | 验证边界 |
| --- | ---: | --- |
| Common | 25 | 公共返回及其它既有契约 |
| Stock单测/MVC/AOP | 201 | 原194项及新增VO/包装契约6、提交失败HTTP1 |
| Stock真实MySQL/Redis | 171 | 全部MySqlIT；并发防超卖、重放、销量outbox、提交后清理、Lua及恢复/证明 |
| Order契约专项 | 28 | StockFeignDefaultActionContractTest、OrderStockActionRecoveryTest、SeckillAddressPropagationTest、SeckillSentinelFeignMappingTest |
| Pay→Stock | 6 | 实际HTTP/Feign只读取证、上下文恢复与两库事实 |

Stock打包与八模块生产/测试源码编译通过。Jar包含两个新VO，八个主业务接口/实现的字节码均无ApiResponse引用，不含新增测试夹具。改动Java文件已执行Google Java Format 1.22.0，大型历史文件限定改动范围；git diff --check通过。Order/Pay生产和测试文件、Feign签名、PO、SQL、配置、Lua及消息均未修改。

全部独占UUID库已清理；源Stock10表、Pay2表精确COUNT均为0，Redis DB0/15均为0键。日志、inspect、65类报告、字段/执行对照已归档，另复制并校验20份本轮销量原子性工件；本轮两个容器及网络已移除，两个新卷保留。默认中间件与历史target数据未变。证据索引为 `target/backend-refactor-20261104/stock-response/verification.json`。

### 21.6 回退与下一步

方案提交/基线为 `69767b00`，标签 `refactor/stock-response-baseline-20261104`；验收标签为 `refactor/stock-response-complete-20261104`。撤销本切片：

```powershell
git revert refactor/stock-response-complete-20261104
```

逆补丁通过临时index验证可恢复父提交 `69767b00` 的完整树，真实工作树/index未回退。此操作恢复原主Service的ApiResponse/Map返回和原测试调用，保留前两次Stock拆分及历史夹具修正。实际回退后仅清理两个新增VO、StockResponseContractTest及其嵌套class的准确构建产物，再编译；保留历史target证据。Git不回退运行数据，后续若有依赖提交应按依赖逆序撤销。

本轮未运行真实TC/RM恢复、完整商城或性能测试。下一切片处理内部受理事实查询的显式null/缺省字段VO，再整理缓存核对、outbox和恢复类的包归属及切面匹配；仍需先落盘精确清单，不改变状态机、Lua、CAS、租约与公平扫描算法。

## 22. Stock 第四切片：内部事实VO及缓存/秒杀/指标归组

### 22.1 范围与响应冻结

基线 `7444e58d`，上一切片431项验证通过。本会话主Agent独立评审执行。已核对API内部事实查询、缓存重建/周期审计、恢复证据及公平扫描规格。

`SeckillReservationQueryService`改为根service层接口，新增 `service.impl.seckill.SeckillReservationQueryServiceImpl`，沿用显式Bean名 `seckillReservationQueryService`。Controller保持原路径与大小写敏感的内部Header校验，返回 `ApiResponse<SeckillReservationFactVO>`，不增加事务/身份要求或新查询。

三个顶层VO均在domain.vo，使用只读字段和普通VO继承表达实际JSON形状，避免自定义序列化器或Map透传包装：

| 类型/分支 | 固定字段 | 缺省及null语义 |
| --- | --- | --- |
| SeckillReservationFactVO / 未找到 | orderNo、status=NOT_FOUND | 仅2字段；沿原Map.of拒绝null orderNo；只查受理表一次 |
| SeckillReservationObservedVO / 受理存在、outbox不存在 | 上述2字段及activityId、skuId、userId、idempotencyKey、requestHash、reservationStatus | 共8字段；已观察原值为null仍输出null；eventId不存在 |
| SeckillReservationOutboxVO / outbox存在 | 上述8字段及eventId | 共9字段；outbox缺event_id或值null仍输出eventId:null |

Observed继承Fact，Outbox继承Observed；不跨DTO/PO继承。六个原JDBC字段及eventId使用Object保存原观察值，不强转Long/String、不丢失小数/超长数/字符串等原语义。VO明确ALWAYS包含已声明字段；Mapper与全局Jackson策略不改。

严格保留原读取顺序：先读受理→捕获六个原字段→读outbox→再次读取受理status并String.valueOf推导外部status→有outbox才读event_id。REJECTED优先，受理存在但无outbox返回UNKNOWN，outbox状态null仍是原字符串"null"；非null空Map不当成NOT_FOUND，不顺带增加身份/指纹校验。两次读仍非RR、不写库。

### 22.2 精确包迁移

类名、Bean默认短名、方法、字段、配置、调度、消息和状态机保持，仅新增清晰的一级职责分组：

| 原包/类型 | 新包 |
| --- | --- |
| service.impl.StockCacheReconciler、StockConsistencyReconciler | service.impl.cache |
| service.impl.SeckillReservationServiceImpl、SeckillRequestClaimService、SeckillAcceptanceService、SeckillQuotaSettlementService、SeckillQuotaSettlementRecovery、SeckillOrderOutboxDispatcher、SeckillEventTimeResolver | service.impl.seckill |
| service.StockSalesOutboxService、StockSalesOutboxDispatcher | service.impl.metrics |

共11个已有类迁移；内部查询实现新增在seckill。根service保留接口，普通库存实现/共享Reader/Codec继续位于service.impl，既有seata包不改。补充三个package-info与明显占位注释，不拆分恢复算法，不变更方法可见性。跨包必须显式import；更新源码、测试及已有反射夹具的全限定名，冻结历史runner不改。

StockDependencyAspect的只读查询切面指向新内部查询实现；缓存三个任务、周期审计、秒杀恢复两个入口及订单outbox dispatch按新包更新，资源名与异常处理不变。事务切面仍覆盖整个stock包并排除seata；销量dispatcher不新增恢复切面，claim/finish仍是独立短事务。

### 22.3 验证与提交

先提交本节并建立 `refactor/stock-grouping-baseline-20261104`。新增内部查询矩阵核对2/8/9字段、显式null/字符串"null"、原JDBC值、Header与两次读取顺序、异常短路/只读保护。使用真实Spring组件扫描验证Bean名、metrics默认关闭/开启、事务代理、定时任务注册与新包切面，测试调度器不自动执行任务或访问外部RabbitMQ。

新增真实MySQL内部GET检查，以及Order实际StockClient通过Feign/HTTP消费Stock VO序列化结果的契约检查；不增加模块依赖。Stock全部单测/MySqlIT、Common、既有Order专项、Pay→Stock实际HTTP/Feign、Stock打包和八模块test-compile均须通过。11个移动类执行词法与原生命周期/调度元数据对照；Mapper、资源、Lua、SQL、表、消息不改。大型历史测试仅整理改动行/导入。

实验用全新Compose项目 `cloud-mall-refactor-stock-grouping-20261104`，MySQL19937/Redis19938，候选网段10.254.248.0/24，先检查冲突，root/root；空源DDL与UUID库隔离沿用已验证方式。证据写入 `target/backend-refactor-20261104/stock-grouping/`，归档后移除本次容器/网络并保留卷。Git提交只纳入本切片，验证临时index逆补丁后打完成标签；不以Git回滚运行数据。真实TC/RM、商城全链及性能实验不在本切片，Stock模块本轮结构验收只覆盖上述已实施范围。

### 22.4 实际实现与调用链核对

内部查询已形成根service接口、seckill包实现及三个顶层VO。Controller沿用原内部Header、路径、错误及ApiResponse包装。已观察到的原字段通过Object保留JDBC值而不做强制类型转换；NOT_FOUND为2字段、无outbox为8字段、有outbox为9字段。显式null、缺省eventId、REJECTED优先及原字符串"null"均保持。字段在第二次查询前捕获，状态仍在第二次查询后重新读取，未引入RR或修复写入。

11个已有类按22.2完整归入cache/seckill/metrics，原类名和Bean短名保留。内部新实现显式使用原Bean名 `seckillReservationQueryService`。缓存启动和周期核对、秒杀claim/accept/settle/recovery/outbox、销量事务及发布生命周期未改；明显占位注释已替换为实际含义。11类全文执行词法与47个引用文件的声明迁移对照通过，12个Mapper/资源文件保持。旧全限定类及其嵌套class精确检查无残留，没有清空历史target。

关键链路：

- 内部事实GET：Header校验 → 新查询实现的原MYSQL_READ切面 → 受理SELECT → 捕获原字段 → outbox SELECT → VO → HTTP包装；缺失时只执行第一个SELECT，数据库故障仍503。
- 缓存/周期任务：按原短Bean名扫描新包 → 原初始化流程 → 原6个定时任务注册 → 新包匹配的MYSQL_RECOVERY切面 → 原批次/租约/版本算法。测试调度器仅记录任务，不自动触发资源动作。
- 销量开启时：额外注册原metrics Service/Dispatcher，MANDATORY及REQUIRES_NEW代理保持，调度任务总数为7；网络发布仍在短事务外。组件扫描测试不宣称已连接真实RabbitMQ。
- Order实际StockClient保持原GET/Header/返回Map契约，通过受控HTTP服务器消费实际Stock VO序列化结果；Stock侧实际Controller/切面/Mapper另由真实MySQL GET测试核对。该组合不冒充整套服务启动与Nacos/MQ全链路。

新增夹具首轮一项失败源于Mockito再次when时执行旧Answer，准备阶段没有Guard；已改为doThrow，并回归全部6项。Feign夹具在执行前源审修正了Long经JSON解码为Integer的比较方式，改比较实际线上JSON树，未要求跨HTTP保留Java数值包装类。生产逻辑未因夹具问题修改。

### 22.5 Stock模块本轮验收

最终69类442项全部通过，零失败、错误和跳过：

| 范围 | 项数 | 验证内容 |
| --- | ---: | --- |
| Common | 25 | 公共契约 |
| Stock单测/MVC/AOP | 209 | 原201项，新增事实矩阵6、扫描/调度/切面2 |
| Stock真实MySQL/Redis | 173 | 原171项，新增只读内部GET2；原库存并发、Lua、销量及恢复证明继续通过 |
| Order既有契约 | 28 | 库存动作、Feign默认动作、秒杀地址及限流映射 |
| Order新增Feign/HTTP | 1 | StockSeckillFactFeignIT，实际VO序列化后的2/8/9字段/null语义 |
| Pay→Stock | 6 | 实际HTTP/Feign只读证据与上下文恢复 |

新增Order测试命名为IT，只有显式指定才运行，避免默认根Maven构建中Order先于Stock时依赖尚未生成的兄弟模块产物；标准执行顺序为先打包Stock，再执行该IT。未添加模块/POM依赖。

Stock打包和八模块生产/测试源码编译通过。Jar包含11个新路径实现、新内部查询实现及3个VO，不含旧移动类型或新增测试夹具；Java格式化和git diff --check通过。新增/修改的测试配置使用专属Profile，未改变真实应用默认开关。

全部UUID库已清理，12张源表精确空、Redis DB0/15均为0键；69类报告、日志、inspect、迁移对照和20份本轮销量原子性工件已归档。两个实验容器和网络已移除，新建mysql_data/redis_data卷保留；默认环境和历史工件未变。证据索引为 `target/backend-refactor-20261104/stock-grouping/verification.json`。

Stock已按本轮方案完成演练语义命名、主业务拆分、HTTP/Service分离、主/内部响应VO及职责归组。此结论限定于结构重构，不宣称历史CM18/CM21全部完成；真实RabbitMQ投递故障链、TC/RM恢复、商城全链及性能没有在本切片重跑。下一模块为Order。

### 22.6 回退与后续任务

方案提交/基线为 `151638e1`，标签 `refactor/stock-grouping-baseline-20261104`。本切片完成标签为 `refactor/stock-grouping-complete-20261104`；模块验收标签为 `refactor/stock-complete-20261104`，两者指向同一验收提交。

```powershell
git revert refactor/stock-grouping-complete-20261104
```

逆补丁以临时index核对可恢复父提交 `151638e1` 的完整树，真实工作树/index不回退。此操作只撤销内部VO与包归组，保留前三个Stock切片。模块验收标签不是一键撤销整个Stock重构：若需整体回退，应按依赖逆序处理本切片、`7444e58d`、`c16419e4`、`f50c6734`，历史夹具修正 `7153ad65` 保留；有后续依赖提交时先评审冲突。实际回退只清理准确的新类/嵌套class构建残留，保留target证据，不使用reset --hard。Git不回退数据库、Redis或忽略工件。

Order下一步先盘点创建/查询/状态流转、秒杀接收、超时消息、库存动作恢复、分片与演练职责，结合现有规格明确首切片及事务/Feign/分片兼容清单后再改。继续主Agent单独执行本会话任务，不恢复历史性能队列。

## 23. Order 第一切片：Seata 演练语义命名与代理边界

### 23.1 盘点与范围冻结

源码基线 `ef53b37d`，主Agent按本会话约定单独评审、实施和验证。已复读项目规则、README、Bug台账、架构/API订单部分及 `batch6_seata_global_transaction_review.md`。Order主Controller仍继承约1319行的OrderServiceImpl，包含创建、查询、状态迁移和两个消息消费者；库存动作/预扣恢复、超时outbox、分片配置已有独立类。上述主业务拆分另立切片，本轮仅整理独立演练职责，避免同时迁移多种事务模型。

演练现有三个独立Bean分别承担：事务外完成标记、Seata全局事务、MySQL本地分支。保留三个真实代理调用边界，新增对应Service接口；Controller只依赖演练Service接口，接口可抛异常位于service层，三个实现进入 `service.impl.seata`。不加新的事务、重试、鉴权或数据校验。

### 23.2 精确迁移清单

下表路径相对 `backend/cloud-mall-order/src/main/java/com/cloudmall/order/`。

| 原类型/位置 | 新类型/位置 | 职责 |
| --- | --- | --- |
| controller.Cm18SeataDemoController | controller.SeataTransactionDemoController | HTTP绑定、演练访问限制、结果/异常响应 |
| service.impl.Cm18SeataDemoFacade | service.impl.seata.SeataTransactionDemoServiceImpl | 全局代理成功返回后的COMMITTED确认 |
| service.impl.Cm18SeataDemoService | service.impl.seata.SeataTransactionDemoOrchestratorImpl | 全局事务、XID、分支调用、响应证明 |
| service.impl.Cm18SeataOrderBranchService | service.impl.seata.SeataOrderDemoBranchServiceImpl | 单表本地事务插入 |
| service.impl.Cm18ControlledRollbackException | service.SeataDemoControlledRollbackException | 明确注入的受控回滚 |
| service.impl.Cm18UnknownTransactionException | service.SeataDemoUnknownTransactionException | 无法确认全局结果 |
| domain.dto.Cm18SeataDemoDTO | domain.dto.SeataTransactionDemoDTO | 原HTTP请求 |
| domain.dto.Cm18SeataBranchDTO | domain.dto.SeataStockDemoBranchDTO | 原Feign分支请求 |
| domain.vo.Cm18SeataDemoVO | domain.vo.SeataTransactionDemoVO | 原全局结果 |
| domain.vo.Cm18SeataBranchVO | domain.vo.SeataStockDemoBranchVO | 原Stock分支结果投影 |
| domain.po.Cm18SeataOrderDemoPO | domain.po.SeataOrderDemoPO | 原单表映射 |
| mapper.Cm18SeataOrderDemoMapper | mapper.SeataOrderDemoMapper | 原演练表访问 |

新增 `service.SeataTransactionDemoService.execute(request)`、`service.SeataTransactionDemoOrchestrator.execute(request)`、`service.SeataOrderDemoBranchService.write(txId,xid)` 三个单方法接口。前两者返回原三字段VO；write返回void。Facade依赖Orchestrator接口，Orchestrator依赖Branch接口。原三个测试类同步按职责改名，保留所有原测试。StockClient的Java方法 `cm18SeataBranch` 改为 `executeSeataDemoBranch`，仅更改Java调用名称和DTO/VO类型。

### 23.3 兼容清单与调用顺序

- 五个既有Bean身份显式保留：`cm18SeataDemoController`、`cm18SeataDemoFacade`、`cm18SeataDemoService`、`cm18SeataOrderBranchService`、`cm18SeataOrderDemoMapper`。Mapper仍由原包扫描；显式Component名不产生第二个Bean。
- HTTP路径 `/api/internal/cm18/seata/demo`、Feign路径后缀 `/branch`、`X-Internal-Service: cloud-mall-cm18-test`、`TX_XID`、`X-CM18-Failure-Point`不变。非prod且 `cloudmall.seata.cm18.enabled=true` 才注册Controller；原三个Service仍按原规则注册，不扩大开关范围。
- DTO字段及正则、响应txId/xid/status及null语义、200/202/503与no-store、403及错误信息全部保留。FrameworkException只有原连接故障识别才映射UNKNOWN，其他错误继续抛出。
- 全局事务名称 `cm18-seata-demo`、rollbackFor=Exception、分支默认REQUIRED保持。先读已提交演练行；仅COMMITTED重放；否则读取RootContext、独立本地分支写入、故障点、原Feign请求/摘要证明。全局代理返回后才markCommitted，更新0行仍查原行确认；不存在新事务包围这一确认。
- 表 `seata_cm18_order_demo`、主键tx_id、tx_xid/status/created_at、原UPDATE条件不变；MyBatis namespace随Java类改名。没有XML/反射外部调用方；冻结runner/历史规格引用保留。原业务Mapper、分片规则、资源配置、Redis、MQ和主业务状态机不改。
- XID摘要算法、16位摘要与Stock摘要前缀比较、故障注入先后顺序保持。普通订单不新增Seata全局事务，不将本地回滚测试等同于AT二阶段验收。

### 23.4 验证与提交策略

先提交本节并建立 `refactor/order-seata-baseline-20261104`，然后实施。执行Order/Common现有单测基线；迁移后全量同范围单测、补充真实MVC绑定/开关/响应矩阵、Bean与事务注解/代理边界、真实Feign HTTP头/体/响应契约。使用新独立Compose项目 `cloud-mall-refactor-order-seata-20261104`，MySQL宿主端口19939及网络10.254.249.0/24；首次启动前核对项目/卷/端口不存在。实库测试只创建和删除自有UUID库，核验真实MyBatis扫描、原表映射、本地提交/异常回滚及事务外确认；不用真实TC，明确标记AT二阶段和进程故障链未测。

十二个迁移类型逐项比较执行token（仅允许声明的接口/依赖/命名/Bean元数据变化），未涉及业务源码/资源做哈希对照；检查旧class构建残留后精确归档/移除，禁止全量clean删除历史证据。运行Order打包、八模块test-compile、Google Java Format及git diff --check，检查Jar旧类缺席/新类存在。通过后补充实际结果及调用链、建立 `refactor/order-seata-complete-20261104`，临时Git index验证逆补丁恢复父树。本轮仅源代码回退，不回退数据库、Redis或忽略证据；实验结束归档、移除本项目容器/网络，保留新卷。

### 23.5 实施结果

本切片完成12个生产类型迁移、三个单方法Service接口及seata包职责说明；StockClient同步语义方法名，原三个测试类按职责改名且9个既有用例保持。所有Controller/Service依赖均以对应业务接口注入，原五个Bean名显式保留。未调整主OrderServiceImpl、普通订单Mapper、分片配置或任何资源文件。

逐行复盘后的调用链为：

```text
原演练HTTP → SeataTransactionDemoController（原绑定/开关/身份/响应）
  → SeataTransactionDemoServiceImpl（事务外入口）
    → SeataTransactionDemoOrchestrator代理（原cm18-seata-demo全局事务）
      → 只重放COMMITTED事实 / 读取RootContext
      → SeataOrderDemoBranchService代理 → 原演练表CREATED本地提交
      → 原故障点 / StockClient.executeSeataDemoBranch（原HTTP协议）
      → 原分支响应证明 / 原Stock后故障点 / 全局代理完成
    → markCommitted；0行时读取原行确认，否则UNKNOWN
  → 原COMMITTED/ROLLED_BACK/UNKNOWN及202/503映射
```

16个迁移/引用文件（12个生产类型、StockClient、3个测试）的执行token在声明的命名、接口、Bean元数据变换下全部一致；其余68个生产源码/资源文件哈希保持。85份原测试源码的方法保留检查通过。24个变更Java文件执行Google Java Format，diff检查通过。旧class精确检查未发现残留；Order Jar中12个旧类型缺席、12个新类型及三个接口存在，无测试夹具。

| 验证范围 | 测试数 | 实际结论 |
| --- | ---: | --- |
| Common单测 | 25 | 公共认证、返回及基础契约 |
| Order单测 | 306 | 原299项及新增HTTP/Feign/代理边界7项 |
| Order演练MySQL | 3 | 真实扫描/Bean名、原PO/Mapper、提交后远程观察、事务外确认、插入后异常回滚、原键重写 |
| Stock演练单测 | 9 | 原分支HTTP、配置、身份与XID契约 |
| Stock演练MySQL | 2 | 同一新实验中核对原表映射及真实本地提交/回滚 |
| 合计 | 345 | 63个测试类，0失败、0错误、0跳过 |

Order打包及八模块test-compile通过。新HTTP用例使用真实MockMvc和项目异常处理器；Feign用实际StockClient、SpringMvcContract、序列化/解码器和本地受控HTTP服务器，核验原路径、POST、三个请求头及字段；不冒充真实Stock Boot/Nacos链。代理时序使用受控Spring代理，实际MySQL用例启用真实本地事务代理；全局事务注解元数据保持，但本轮未启动TC，未验收AT二阶段、RM重启或跨库全局回滚。普通订单分片、真实MQ故障链、商城全链和性能未在本切片重跑。

实验 `cloud-mall-refactor-order-seata-20261104` 使用全新MySQL卷，版本8.0.46、端口19939。五个用例自有UUID库全部删除，结束时只余空的cloudmall_order/cloudmall_stock及系统库，没有业务表或测试行。63份报告、日志、inspect、源码对照和Jar摘要归档到 `target/backend-refactor-20261104/order-seata/verification.json` 及同目录；实验容器和网络已移除，新mysql_data卷保留，默认环境未变。

### 23.6 回退与下一切片

方案提交/基线为 `271dc371`，标签 `refactor/order-seata-baseline-20261104`；完成标签为 `refactor/order-seata-complete-20261104`。临时Git index验证当前提交的逆补丁可恢复父提交完整树，不改变真实工作树/index。

```powershell
git revert refactor/order-seata-complete-20261104
```

这只撤销Order首切片，保留此前Stock等模块成果。实际回退后应按本节清单精确清理新类型/测试及其嵌套class残留再编译，保留target历史证据；有后续依赖提交时先处理依赖与冲突。Git不回退运行数据或忽略工件，不使用reset --hard。

下一切片继续Order主业务拆分：先明确创建、查询、状态迁移、秒杀与超时消费的方法归属，再逐项冻结现有同步锁、REQUIRED/REQUIRES_NEW/只读RR、身份读取顺序、库存动作恢复及分片调用边界。具体类名和是否分多步以实际方法清单为准，方案先落盘再改代码；本轮不宣称Order模块整体重构完成。

## 24. Order 第二切片：跨月列表查询独立分层

### 24.1 盘点与本轮范围

基线 `b756d93a`；主Agent单独审阅当前实现、API列表契约、17D精度与17ED/17EF影子读取规格、分片启动测试及上轮345项验证。主实现的事务边界如下，逐项拆分而不一次改变整个交易链。

| 现有入口 | 原事务/同步边界 | 本轮处理 |
| --- | --- | --- |
| create | REQUIRED + synchronized；内部意图通过独立REQUIRES_NEW服务提交 | 保持 |
| list | readOnly + REPEATABLE_READ，未同步 | 独立查询Controller/Service |
| get / paymentState | 无显式事务；先查订单再核对身份 | 保持，后续独立迁移 |
| cancel / paid | REQUIRED + synchronized，与create共用原实例监视器 | 保持 |
| confirm / seckill | 无显式事务/同步；前者状态CAS，后者限流及远程调用 | 保持 |
| consumeSeckill / consumeTimeout(Message) | Rabbit入口 + REQUIRED | 保持 |
| consumeTimeout(String) | REQUIRED，Message重载内部自调用沿已有事务 | 保持 |

选择列表为本轮唯一业务切片：`list/unavailableOrderMonth/requireCorrectOrderMonth` 独立迁移；`view/timestamp` 是列表及原find共享的行映射。首次编译发现find中的 `stream.map(this::view)`，纠正前期仅检索view(...)造成的遗漏：两映射方法统一提取到无状态 `util.OrderSummaryAssembler`，不复制算法。原OrderController及OrderServiceImpl暂保留其他入口，空OrderService会随后续写职责拆分移除；这是阶段状态而非最终分层目标。

### 24.2 变更清单与契约

- 新增 `controller.OrderQueryController`：唯一承接原 `GET /api/orders`，保留status/startTime/endTime可选、page默认1/pageSize默认20、参数名称和类型。构造器注入 `service.OrderQueryService`；读取可空AuthContext.userId并显式传入，Service代理返回后再构造ApiResponse。
- 新增 `service.OrderQueryService.list(Long callerUserId,String status,int page,int pageSize,String startTime,String endTime)`，返回 `PageResult<OrderVO>`。不引入新的HTTP参数或前端字段。
- 新增 `service.impl.OrderQueryServiceImpl`：构造器仅依赖OrderSqlMapper，承接上述三个列表方法；共享 `util.OrderSummaryAssembler.fromRow` 承接原view，私有formatTimestamp承接原timestamp。原find只将this::view换为OrderSummaryAssembler::fromRow，保持读取顺序和明细装配。原MONTH格式保持，时间窗口仍一次性使用上海偏移now；ts转换直接调用原Timestamp.from(...toInstant())，不引入新时钟或时区规则。
- 列表readOnly/RR仍在Service方法上，所有月份COUNT/目标页读取属于同一次事务。传入身份在事务内原第一步判空，保持UNAUTHORIZED/401及“请先登录”，再执行原分页/窗口验证；不把认证失败提前到事务开启之前。Controller只读取身份，不提前调用requireUserId。
- 原list从OrderServiceImpl移除，不留转发方法，原OrderController不再继承此GET。其余方法、事务、同步锁、业务依赖、Mapper、DTO/VO和资源配置不改。列表日志使用新实现类身份，其他业务日志保持原类。
- 保持月份倒序、完整COUNT、long偏移、DATETIME(3)向上取整、同一快照、空窗口无SQL、目标页有界读取、错月/缺页/缺表503、用户隔离和status原值。保持金额BigDecimal字符串、地址String.valueOf(null)语义、时间null、items=[]；不新增明细查询。
- ApiResponse包装由事务内移至Controller代理返回后；requestId生成时间随之移动，未用于存储或业务幂等。事务提交失败仍由现有全局异常处理器处理，不返回提前构建的成功响应。

六个现有测试调用点适配：OrderMonthlyPaginationTest、OrderListFailureTest、OrderQueryWindowTest、OrderQueryPrecisionMySqlIT、OrderListShardingShadowMySqlIT、config.OrderShardingRuntimeMySqlIT。普通列表夹具通过新Controller连接真实查询实现，保留原响应断言；Runtime显式装配新增两个Bean，并核对查询Service代理和原写Controller代理均存在。影子详情与恢复路径继续使用原实现；所有旧测试方法保留，不回写冻结runner或历史证据。

### 24.3 验证、实验与提交

先提交本节，建立 `refactor/order-query-baseline-20261104`。运行Common/Order单测及三类相关MySQL基线：精度、物理/逻辑影子对照、实际分片Bean装配。重构后同范围回归，并补充真实MVC绑定/JSON/认证顺序、实际事务代理与并发COUNT→PAGE快照测试。运行Order打包、八模块test-compile、实际Java格式化、git diff --check；源码对照五个迁移方法及其余方法/Mapper/资源不变，检查Jar与测试源码保留。

实验使用新Compose项目 `cloud-mall-refactor-order-query-20261104`、MySQL19940、10.254.250.0/24；首次启动核对项目/卷/端口不存在。来源库仅初始化schema.sql中的三张模板、order_idempotency及三张恢复表共7个空表；LIKE模板按原定义只替换CREATE目标名物化，不初始化业务数据或执行整个schema.sql。测试写入只在各自新UUID库内；失败证据按原夹具规则保留，不绕过cleanup gate。当前新实验按本节执行，不复用历史限定单method runner。结束后归档本轮新增native证据与报告，清理自有容器/网络，保留新卷。

通过后建立 `refactor/order-query-complete-20261104`，临时Git index验证逆补丁恢复父树。Git仅回退源码；不得清空包含历史证据的target。真实TC、MQ服务、完整商城及压测不属于本轮验收。

### 24.4 实施结果

本轮完成独立OrderQueryController、OrderQueryService、OrderQueryServiceImpl，以及列表/详情共用的OrderSummaryAssembler。主混合实现由1319行减至1188行，移除列表HTTP方法和五个原辅助/业务方法；原详情仅替换共享映射方法引用。新查询实现没有ApiResponse或AuthContext字节码依赖，不依赖Feign、消息或其他写服务。

调用链为 `GET /api/orders → OrderQueryController读取可空身份 → OrderQueryService事务代理(readOnly/RR) → 原身份/分页/时间窗口规则 → OrderSqlMapper逐月COUNT和有界PAGE → OrderSummaryAssembler → 事务成功返回 → ApiResponse包装`。原详情仍沿 `find → 原订单号精确证明 → OrderSummaryAssembler → 明细查询`，没有增加事务或调整鉴权顺序。

基线Common25、Order306单测及18项MySQL/分片测试通过。实施中两处编译问题已修正并记录：遗漏this::view方法引用导致初始映射归属判断不完整，已修正为共享纯映射；新增并发夹具覆盖Mapper时错误收窄Object为Timestamp，已恢复原签名，不改生产Mapper。最终验证如下。

| 范围 | 测试数 | 验证内容 |
| --- | ---: | --- |
| Common单测 | 25 | 既有公共契约 |
| Order单测 | 310 | 原306项及新增MVC/真实事务拦截器4项，包含唯一GET路由、默认参数、金额/null、认证顺序、提交失败 |
| OrderQueryPrecisionMySqlIT | 7 | 实际MySQL毫秒/纳秒窗口、offset、分页、缺月和用户隔离 |
| OrderListShardingShadowMySqlIT | 8 | 原物理/逻辑影子、只读快照、详情及恢复边界 |
| OrderShardingRuntimeMySqlIT | 3 | 实际生产Bean选择、分片启动与冷发布；新查询Service及原写Controller代理同时核验 |
| OrderQuerySnapshotMySqlIT | 1 | 新Controller调用真实Service代理，COUNT后独立事务改状态并插入两单，当前页保持旧快照、下一次查询看到新事实 |
| 合计 | 354 | 64个测试类，0失败、0错误、0跳过 |

并发用例同时核对COUNT/PAGE的MySQL连接ID、JDBC autoCommit=false/readOnly=true/REPEATABLE_READ和Spring事务标记；第二用户数据不可见。新增HTTP用例通过项目金额序列化与异常处理器核对金额字符串、地址字符串null、显式null时间及空明细；原Controller和新Controller一起注册，确认没有重复GET映射。

五个迁移方法体在声明的身份参数、HTTP包装、共享映射及等价时间调用变换下逐token一致；主实现其余内容逐token一致。其他84个生产/资源文件与83个原测试文件哈希保持，六个适配测试文件保留原方法。13个变更Java文件按新增全文件、既有变更行/import执行Google Java Format，diff检查通过。Order打包、八模块test-compile通过；Jar四个新类型齐全，无测试夹具，旧混合类字节码不再有list/view/timestamp方法。

新实验MySQL8.0.46/19940使用独立新卷；所有UUID库清理完成，七张来源表逐表精确0行。基线与最终的四份native class-finish均created=deleted、errors为空、无preserveRequired；402份本轮新增native工件已复制并核对SHA，原工件保留。实验容器和网络移除，新mysql_data卷保留。日志、64份报告、Jar摘要、源代码对照和数据库验证见 `target/backend-refactor-20261104/order-query/verification.json` 及同目录。

本轮没有启动真实TC/MQ服务，没有执行完整商城或性能测试。Order其余Controller继承关系及写/消息业务仍待后续切片，不宣称模块整体完成。

### 24.5 回退与后续

方案提交/基线 `6ec924cc`，标签 `refactor/order-query-baseline-20261104`；阶段完成标签 `refactor/order-query-complete-20261104`。临时Git index已验证逆补丁恢复父树，真实工作树/index未回退。

```powershell
git revert refactor/order-query-complete-20261104
```

仅回退本轮查询/共享映射提取，保留Order Seata及之前模块成果。实际回退后精确移除新增四类型和两个测试类（含嵌套类）的class残留再编译，保留target历史证据；Git不回退数据库或忽略工件。有后续依赖提交时先处理依赖/冲突，不使用reset --hard。

下一步先细化单订单详情和支付状态只读查询：重点保留“先查订单、再读身份”的失败顺序、原库存动作证明/Feign降级和无新增RR保证；随后再分离创建、状态迁移与消息消费，显式维持原共享同步锁及事务边界。

## 25. Order 第三切片：详情与支付状态查询（2026-11-10）

### 25.1 范围与精确边界

基线 `6a961c62`。主Agent继续独立评审、实施和验证。已复读规则、README、Bug台账、API支付状态与取证规格、当前调用点。工作区已有README修改和两个pycache目录，记录指纹并保留，不纳入提交。本轮只拆分get/paymentState及其共享读取逻辑，不改变创建、状态迁移、消息消费的事务与同步锁。

- 新增 `service.OrderDetailQueryService`、`service.impl.OrderDetailQueryServiceImpl` 和 `controller.OrderDetailQueryController`。GET `/api/orders/{orderNo}` 与 `/api/internal/orders/{orderNo}/payment-state` 从混合实现移出，保留原路径、可选X-User-Id及响应包装；列表查询类保持不变。
- Service的 `get(orderNo,LongSupplier userId)` 返回OrderVO，`paymentState(orderNo,LongSupplier userId)` 返回新的五字段 `OrderPaymentStateVO`。Controller传递延迟身份读取：详情传AuthContext::requireUserId；支付状态显式header存在则返回该值，否则延迟读取上下文。Supplier仅是本地调用参数，不是HTTP/Feign字段。只有主表身份和明细读取成功后才调用它，保持原实际读取时点、404/数据库/明细异常优先于401，以及header优先语义。
- 新增包内无状态 `service.impl.OrderDetailReader`，接收调用方既有OrderSqlMapper、ObjectMapper及orderNo，统一原find/viewItem/readSnapshot算法。无Bean、无新数据源、无事务/身份读取；明确使用调用方所选Mapper和当前事务连接。混合实现的private find仅委托Reader，现有默认构造、六参测试构造及运行时字段装配保持；不引入第二份依赖或复制读取逻辑。
- 原tableFromNo抽为纯 `util.OrderTableRouting.tableFromOrderNo`，供Reader、剩余写/消费流程使用；保留原正则、yyyyMM校验、404和表名，不扩大分片范围。原明细读取顺序、只核对首个主表候选身份、映射全部候选再取首个、明细toList与坏JSON异常均保持。
- paymentState先读完整详情，再取身份/归属，再查询动作Mapper；只有返回null才调用原legacyStockActionStatus。原状态方向、StockCircuitBreaker.STATUS资源、精确原单身份、UNKNOWN/CONFLICT/SUCCEEDED/NONE与异常降级全部保持，不读写Redis/MQ，不修复历史事实。
- OrderPaymentStateVO为orderNo/status/payAmount/userId/stockActionStatus五个public final字段；构造器逐字段requireNonNull，保持旧Map.of拒绝null语义和JSON字段/金额字符串。Stock、Pay生产Feign签名不变；只有消费Order测试产物的反射夹具随新入口调整。
- 两项查询不增加@Transactional，不与列表只读RR混为一体。原写流程内Reader沿同一外部事务执行；独立GET保持原多次观察。查询结果的ApiResponse只在Controller装配，Service不返回HTTP包装或读取AuthContext；原Stock Feign响应仍按ApiResponse消费。

迁移核验方法为get、paymentState、legacyStockActionStatus、find、viewItem、readSnapshot、tableFromNo。完整检索普通调用与方法引用；其余方法只允许find委托及表名工具引用变化。真实表、Mapper SQL、PO、消息、配置、普通订单鉴权与状态机不改。

### 25.2 测试与运行方案

先提交方案并建立 `refactor/order-detail-baseline-20261110`，运行Common/Order单测基线。修改四个Order调用方测试（FailureHandling、LegacyStockIdentity、ListShardingShadow、ShardingRuntime），增加仅测试使用的旧写夹具→新读Controller装配助手；返回值改成VO时保留原断言。四个Stock跨模块反射夹具及PayTccOrderEvidenceMySqlIT按新Controller/Service构造，保留真实Stock证明和Pay→Order HTTP/Feign校验。

补充真实MVC默认/显式身份、缺失/错单/坏明细与认证失败顺序、延迟上下文读取、五字段JSON/金额/null约束和无额外事务测试。回归Common/Order全部单测，OrderLegacyStockIdentityMySqlIT、OrderListShardingShadowMySqlIT、OrderShardingRuntimeMySqlIT及必要的新实库读取测试；四个改动Stock IT与PayTccOrderEvidenceMySqlIT均须运行。适配之前已通过的工件调用，不增加生产模块依赖或改变Feign协议。

实库使用全新Compose项目 `cloud-mall-refactor-order-detail-20261110`，MySQL19941、10.254.251.0/24；事前确认项目/卷/端口不存在。仅从既有schema.sql提取必要Order七表、Stock被测事实表和Pay六张取证表的空DDL，不导入业务数据，不启动真实TC/MQ。每个IT只写自身UUID库并遵守原清理/保留规则；默认中间件和历史卷不动。结束先归档本轮证据，再移除自有容器/网络，保留新卷。

阶段门为测试全部通过、方法体与未改文件对照、Java实际格式化、Order打包、八模块test-compile、Jar/旧入口检查、git diff --check。建立 `refactor/order-detail-complete-20261110`，临时Git index验证源码逆补丁可恢复父树，另核对README原字节未变。Git不回退运行数据和忽略工件；不使用reset --hard或全量clean。此切片不代表Order整模块或完整商城/性能验收。

### 25.3 实施结果

本切片于2026-11-10实施和完成测试，2026-11-11恢复会话后核对源码、报告、进程及数据库，再完成归档、清理和提交。标签沿用切片启动日20261110。恢复时没有活动Java测试进程，剩余Stock/Pay测试及八模块编译已在前次会话中断后成功完成；未重复执行没有变化且已通过的用例。

新增六个生产类型：详情查询Controller/Service/Impl、OrderPaymentStateVO、包内OrderDetailReader、OrderTableRouting。原混合实现由1188行减至1070行；详情读取与支付状态HTTP入口已经移出，剩余写流程通过原private find委托同一读取算法，原同步锁/事务/状态机保持。列表查询实现、Mapper SQL、PO、Feign生产契约、配置与消息未修改。

复盘调用链为：

```text
详情GET → OrderDetailQueryController → 查询Service
  → OrderTableRouting → 主表原身份证明 → 摘要/明细/JSON读取
  → 延迟读取AuthContext身份 → 原归属校验 → 原OrderVO → HTTP包装

支付状态GET → 原header优先的延迟身份来源 → 同一完整详情Reader
  → 原归属校验 → 原动作方向与statusForOrder
  → 仅无动作时走原Stock STATUS资源及持久事实证明 → 五字段VO → HTTP包装

原创建/状态/消息流程 → private find → 同一Reader（沿原外层事务）
```

首轮Common静态检查仍要求YearMonth.parse位于旧混合类，已在Bug台账记录后改为同时检查新工具的解析逻辑和旧实现的调用，运行时非法月份仍有MVC404验证。源码对照工具首次把方法调用误识别为定义，修正为明确方法声明后七组方法体全部通过；没有修改业务逻辑来满足对照。OrderReadTestSupport仅复用原写夹具依赖构造真实新查询入口，并把实际VO投影为Map以保留已有大规模状态矩阵断言，真实JSON另由新MVC用例验证。

| 范围 | 数量 | 实际覆盖 |
| --- | ---: | --- |
| Common单测 | 25 | 既有契约和调整后的路由静态守卫 |
| Order单测 | 317 | 原310项及新增7项MVC/字段/身份顺序测试 |
| Order实库 | 30 | LegacyStockIdentity18、ListShardingShadow8、ShardingRuntime3、DetailRead1 |
| Stock证明链实库 | 73 | 四个既有IT；真实Stock SQL及新Order读取判断，OrderMapper为既有替身 |
| Pay→Order实库/HTTP/Feign | 9 | PayTccOrderEvidenceMySqlIT，真实Order Mapper/MVC经本机HTTP和Feign消费，非完整Nacos部署 |
| 合计 | 454 | 70个测试类，0失败、0错误、0跳过 |

新增MVC用例核对原路由共存、金额字符串、显式null时间、空SKU快照、原五字段结构和非空约束，以及404/坏明细优先于401、读取期间上下文变化后的身份与显式header优先。新实库用例核对独立GET无事务，原paid事务内主表/明细读取均沿同一MySQL连接，实际PAID与CONFIRM动作共同提交；未增加RR承诺。原分片装配同时核对新查询类无事务代理、原写Controller仍有事务代理。

七组核心方法体在声明的依赖传入、身份来源、返回类型及工具引用变换下逐token一致，剩余混合实现对照通过。Order/Stock/Pay其他574份原源码/资源哈希保持，九个适配调用方测试保留原方法；Common只调整一项静态守卫。20个变更Java文件执行Google Java Format（既有大文件只处理变更行/import），diff检查通过。Order打包及八模块test-compile通过；Jar六个新类型齐全、与最终编译class逐字节一致，无测试夹具；旧get/paymentState及迁出的辅助方法不再存在，新Service无AuthContext/Web绑定/新增Transactional字节码依赖。

实验MySQL8.0.46/19941使用本轮独立新卷；最终仅余三个空来源库及系统库，18张来源表逐表精确0行，所有UUID库已删除。两份本轮native class-finish均created=deleted、errors为空、无需保留失败库；201份native工件复制并核SHA，原件保留。70份报告、日志、inspect、数据库证明和Jar摘要归档到 `target/backend-refactor-20261110/order-detail/verification.json` 及同目录。实验容器与网络在2026-11-11移除，新mysql_data卷保留。README与本轮开始时逐字节相同，其已有修改和两个旧pycache目录不纳入提交。

本轮未启动真实TC/MQ服务，未运行完整商城或性能测试；Order剩余写Controller继承关系、创建/状态迁移及消息职责继续后续处理，不宣称模块整体完成。

### 25.4 回退与后续

方案提交/基线 `da1beafd`，标签 `refactor/order-detail-baseline-20261110`；阶段完成标签 `refactor/order-detail-complete-20261110`。临时Git index验证逆补丁恢复父提交完整树，实际工作树/index不回退，README已有修改不变。

```powershell
git revert refactor/order-detail-complete-20261110
```

仅回退本轮详情/支付状态/共享读取提取，保留先前Seata和列表切片。回退后按本节清单精确移除新增六类型及三个测试辅助/测试类（含嵌套类）的class残留，再编译；保留target历史证据，不使用全量clean或reset --hard。Git不回退数据库或忽略工件，有后续依赖时先核对依赖与冲突。

下一步先细化订单创建职责：普通下单与共享监视器、REQUIRES_NEW意图、外部库存结果未知、事务提交后购物车清理之间的边界。创建/取消/支付当前共用同步锁，不能简单按类拆分成独立锁；先冻结方案和验证清单，再推进写流程与消息消费解耦。

## 26. Order 创建职责预拆分：购物车清理协调（2026-11-11）

### 26.1 阶段边界与锁盘点

基线 `f430d37a`，由主Agent独立评审、实施、验证；README现有修改及两个旧pycache目录保留。本轮复读API购物车证明/提交后清理契约、`order_cart_transaction_mysql_followup_20261027.md`、创建实现及已有测试。

当前create/cancel/paid均为同一Controller目标对象的synchronized方法。Spring事务在进入目标方法、获取监视器之前开启，目标方法退出释放监视器后才提交及执行afterCommit；这不是覆盖数据库提交全过程的互斥锁。原REQUIRES_NEW意图服务是独立代理，创建主事务和Cart回调仍依原边界运行。完整拆分创建Service前须另冻结共享锁身份及持有区间，不能把不同Service各自synchronized当作等价迁移。

本轮选取创建专用的独立业务能力先行提取：前置Cart证明采样、原DTO数量/身份校验、提交后清理注册。创建HTTP入口/同步锁/事务注解/认证位置与库存/订单/补偿编排保持，后续再拆创建入口及共享写锁。本阶段不宣称普通创建已完全脱离混合实现。

### 26.2 精确变更

- 新增 `service.impl.creation.OrderCartCleanupCoordinator`，不可变、请求内使用的普通业务协作对象，不注册Bean、不新开事务。构造器接收当前已配置的CartClient与ObjectMapper；在普通创建数量预检后、原采样位置构造一次。它只保存原依赖引用，不产生远程/数据库行为，直接幂等重放和无效请求不进入该位置。
- 原captureCartCleanupSnapshots迁为public captureSnapshots，原registerCartCleanup迁为public registerAfterCommit，原validCartCleanupSnapshot迁为private matchesSnapshot；原afterCommit匿名回调随之迁移。方法体只允许这些命名及字段归属变化。
- 保留真实事务+同步上下文双门、数量超过int上界仅跳过清理、每SKU一次GET、成功null静默跳过、原JSON完整解析/整数范围/购买聚合证明、List.copyOf原DTO集合、成功末尾才注册回调。afterCommit逐SKU使用原userId及DTO，普通异常隔离并继续，Error仍传播；不重GET、不调用旧DELETE、不新增重试。
- CartClient/JSON依赖在请求原采样位置绑定，此后同一协调对象注册并执行回调。运行期原Spring依赖在装配后保持同一实例；用户身份和请求/Cart内容后续变化继续由原捕获证明隔离。不改Feign参数、Redis键、DTO、JSON、SQL或状态机。
- 所有Cart清理日志移入新协调类，保留消息、参数、级别和记录时点；Logger名称改为实际职责类。OrderCartCommitBoundaryTest只调整日志捕获目标及说明，其余原断言不变。原混合类删除已不再使用的logger和三个私有方法，create仅增加局部协调对象与两处委托。

### 26.3 验证、资源与提交策略

先提交方案并建立 `refactor/order-cart-cleanup-baseline-20261111`。运行Common/Order全部单测基线；迁移后同范围回归，现有CartCommitBoundary单测覆盖失败/原证明/回调/Error等矩阵，不新增镜像测试。

真实MySQL使用OrderCreationPrecisionMySqlIT中已审的九个方法：cartCleanupWaitsForCommitAndObservesCommittedOrderAndIntent、cleanupUsesPrecommitProofAndOriginalUserWithoutReadingTheChangedCartAgain、cartChangesDuringReserveKeepTheirNewItemAfterTheOrderReallyCommits、partiallyPurchasedCartQuantityDoesNotGetDeletedAndStillCommitsTheOrder、outerRollbackNeverDeletesCartOrPretendsTheOrderCommitted、lateLocalReadFailureRollsBackWithoutRemovingCartItems、postCommitCartFailuresDoNotCompensateOrdersAndLaterSkuStillCleansUp、completedNormalOrderAliasesCannotReplayTheOriginalOrder、committedIntentAliasesCannotReplayWhenIdempotencyProjectionIsMissing。使用原真实事务代理、独立连接事实断言和REQUIRES_NEW，Cart等远端为既有mock；不冒称真实Cart HTTP/Redis全链测试。

新独立Compose项目 `cloud-mall-refactor-order-cart-cleanup-20261111`，MySQL19942，网络10.254.252.0/24，事前核对项目/卷/端口不存在。只初始化新cloudmall_order的mall_order_template、mall_order_item_template、order_idempotency三个空源表；item模板由schema.sql原月表定义仅替换CREATE目标名物化。测试只写其新UUID库并负责清理。归档报告/日志/源表指纹后移除自有容器与网络，新卷保留，默认环境不变。

源码核对三个方法及回调执行token、剩余混合逻辑、原锁/事务元数据与未改文件哈希；执行实际Java格式化、Order打包、八模块test-compile、Jar与git diff --check。通过后建立 `refactor/order-cart-cleanup-complete-20261111` 并以临时Git index验证逆补丁恢复父树；README不入提交，Git不回退数据库或忽略工件，不做全量clean/reset --hard。

### 26.4 实施结果

创建流程的三个Cart方法及原afterCommit回调已集中到OrderCartCleanupCoordinator，主混合实现由1070行减至967行。create在原数量预检后创建当前请求的协调对象，原位置采样证明、原成功末尾注册回调；创建/取消/支付仍使用原实例监视器，HTTP、事务和库存/订单/意图/补偿编排没有迁移。该阶段完成Cart职责提取，创建主Service仍是明确后续项。

逐行复盘后的调用链为：`原create事务代理 → 原synchronized入口 → 身份/幂等/数量 → 协调器captureSnapshots → 原地址/商品/意图/库存/落单 → 协调器registerAfterCommit → 原方法返回释放锁 → 原事务提交 → 原userId及DTO逐SKU条件清理`。回滚不触发afterCommit；普通Cart失败仍逐项告警/继续，Error仍传播。日志名称归入新协调类，消息/级别/参数与时点保持。

| 验证范围 | 数量 | 实际覆盖 |
| --- | ---: | --- |
| Common单测 | 25 | 现有公共/源码契约 |
| Order单测 | 317 | 全部现有单测，包含原Cart证明/回调/日志/Error矩阵 |
| 创建实库专项 | 9 | 精确执行26.3列出的九方法，真实MySQL及原Spring事务/REQUIRES_NEW代理，远端为既有mock |
| 合计 | 351 | 62个测试类，0失败、0错误、0跳过 |

九个实库方法名与Surefire XML集合完全一致，未把整个OrderCreationPrecisionMySqlIT宣称为本轮全跑。已验证提交前零清理、提交后独立连接可见订单及COMMITTED意图、原user/DTO不被后续上下文与Cart变化替换、部分购买不授权删除、外层回滚保留独立RESERVED意图、局部失败沿原补偿，以及提交后清理失败仍保持已提交事实并继续后SKU。

三个方法体（含afterCommit嵌套体）按字段/方法改名逐token一致；主混合实现除协调对象和两处委托外对照一致，原事务注解数量和三个synchronized修饰符保持。OrderCartCommitBoundaryTest执行token仅日志捕获目标变化，其余断言保持；其他187份原源码/资源哈希不变。三个变更Java文件执行Google Java Format，diff检查通过。Order打包与八模块test-compile通过；Jar协调器/回调齐全并与最终class字节一致，旧OrderServiceImpl$1缺席，旧三个Cart方法移除，原三个同步入口仍存在，没有新Bean/事务注解或测试夹具混入。

新MySQL8.0.46实验使用19942与独立新卷，九个UUID库全部清理，结束仅剩空来源库及系统库、三张源表逐表精确0行。62份报告、日志、inspect、旧回调class备份、DDL与源码对照见 `target/backend-refactor-20261111/order-cart-cleanup/verification.json` 及同目录。自有容器和网络已移除，新mysql_data卷保留。README原有修改逐字节保持，两个旧pycache目录不纳入提交。

本轮没有新增业务规则或修复协议行为，未运行真实Cart HTTP/Redis、TC/MQ、商城全链或性能测试；以上结果仅验收本次职责提取。

### 26.5 回退与后续

方案提交/基线 `e3dbae7f`，标签 `refactor/order-cart-cleanup-baseline-20261111`；阶段完成标签 `refactor/order-cart-cleanup-complete-20261111`。临时Git index验证逆补丁恢复父树，真实工作树/index与README已有修改保持。

```powershell
git revert refactor/order-cart-cleanup-complete-20261111
```

仅撤销当前Cart协调器提取，保留前面Order查询/Seata成果。实际回退后精确清理新OrderCartCleanupCoordinator及其匿名回调class残留并重新编译；保留历史target证据，不做全量clean/reset --hard。Git不回退数据库或忽略工件，后续依赖提交应先处理依赖与冲突。

下一切片继续普通创建主Service：先冻结共享操作监视器身份、事务在加锁前开启/解锁后提交的原区间、REQUIRES_NEW意图、结果未知与补偿以及Cart协调器装配。届时同时处理创建HTTP委托和现有测试依赖构造，不把创建Service与剩余状态Service各自加synchronized当作等价实现。

## 27. Order 普通创建Service与共享写锁（2026-11-12）

### 27.1 范围冻结

基线 `c9da3dfc`，主Agent继续单独执行。已复读规则、README、Bug台账、架构普通下单/状态迁移、创建实现及事务/购物车规格。README已有修改和旧pycache保留。此次完整迁移普通create，保留秒杀/状态/消息流程为后续切片。

- 新增 `service.OrderCreationService` 与 `service.impl.OrderCreationServiceImpl`，create返回OrderVO，接收原key/request及LongSupplier身份来源。新增 `controller.OrderCreationController` 承接原POST `/api/orders`，Idempotency-Key和请求体绑定不变，传入AuthContext::requireUserId，成功后包装ApiResponse。原混合实现移除create HTTP/Java入口、创建专属依赖和辅助规则。
- 新增单例 `service.impl.OrderWriteMonitor`（普通Component，无AOP方法），创建、取消、支付业务体共同在synchronized(writeMonitor)中运行。三个入口原@Transactional默认REQUIRED保持，锁在代理事务开始之后获取，在方法返回/抛出时释放、早于提交/回滚与afterCommit。不是跨提交互斥，也不是分布式锁，不扩大confirm/秒杀/消息入口的锁范围。原三个方法的隐式this锁替换为显式同一监视器，不保留彼此独立的synchronized方法。
- 剩余OrderServiceImpl暂通过原字段装配注入共享监视器；仅手动构造的隔离对象默认拥有自身监视器。生产Spring注入的实例与新CreationService构造注入的实例必须相同，并以真实装配和并发用例核验。旧orderController Bean及其余入口保持。
- 以构造器注入Stock/Product/User/Cart、OrderSqlMapper/ObjectMapper、意图Service/Mapper、OutboxMapper与监视器；creationClock保持原上海默认与单次采样语义。创建体内鉴权仍在获取锁后的原第一步。Cart协调器继续在原数量预检后建立、成功末尾注册。
- 完整保持直接幂等重放、数量/商品身份/金额/metadata/JSON校验顺序、原INIT独立提交、Stock专用持久拒绝证明、UNKNOWN202、独立RESERVED、主事务订单/明细/幂等/outbox/COMMITTED、补偿原错误及suppressed链。不开Seata全局事务，不改SQL、表、Redis、消息、Feign协议或状态机。
- 公用商品快照校验进入包内 `OrderProductSnapshotRules`；普通/秒杀写流程复用的地址读取、JSON/Feign错误解码、超时outbox及原时间/标识小函数进入包内 `OrderWorkflowSupport`，显式接收已有客户端/JSON配置，无Bean/事务。主实现与新创建实现调用同一算法，不复制金额或消息规则。原内部ItemRow改为独立 `service.impl.creation.OrderCreationLine` 记录（sku/lineAmount/quantity），只用于创建计算，不作为HTTP/持久化DTO。
- ApiResponse构造从事务内移至Controller返回阶段，requestId生成时点变化但未持久化或参与幂等；commit/afterCommit错误仍沿原异常处理流程，不额外补偿已提交订单。

### 27.2 测试迁移与验收

大量原测试在同一对象上测试普通/秒杀/状态，并动态替换依赖。新增仅测试使用的OrderTestService混合夹具，继承剩余实现，在测试create入口保留原REQUIRED边界，构造实际CreationService并使用相同监视器/依赖/时钟，返回旧统一响应以保留原矩阵断言；不复制创建业务。历史用例侧重原算法/事务事实，其注解检查改指真实生产CreationService。生产Controller/Service代理、共享监视器、事务在等锁前开启/提交时已解锁，必须另用直接装配生产类的测试验收，不能以夹具注解代替。

现有CreationPrecisionMySqlIT、CreationShardingShadowMySqlIT、ReplayRecoveryShardingShadowMySqlIT全类回归；ShardingRuntimeMySqlIT实际装配新增CreationController/Service与监视器，直接运行生产创建代理。Common源码守卫迁移到实际入口/实现。补充真实MVC绑定与响应、并发创建/取消/支付互斥、原无锁入口、异常释放及事务生命周期检查。测试旧方法保留，其他模块仅编译/契约检查，不改冻结runner。

先提交方案及 `refactor/order-creation-baseline-20261112`，运行Common/Order单测基线。实库使用新独立Compose项目 `cloud-mall-refactor-order-creation-20261112`，MySQL19943、10.254.253.0/24，确认项目/卷/端口不存在后建立。只从原schema.sql提取七张空Order源表（3模板、幂等、意图、outbox、动作），测试写入自有UUID库；沿原保留/清理门，结束归档后删除本项目容器/网络，卷保留。

最终门：适用测试全部通过，迁移方法/剩余算法对照、原方法保留、真实Java格式化、Order打包、八模块test-compile、Jar旧入口/新类型检查、git diff --check、README指纹保持。完成标签 `refactor/order-creation-complete-20261112`，临时index逆补丁恢复父树验证；不动真实工作树做演示回滚，不全量clean，不回退运行数据。真实Cart/Stock HTTP、TC/MQ、全商城及性能不在本切片验收范围。

### 27.3 实施结果

普通创建已完整迁入OrderCreationServiceImpl（370行），由OrderCreationController构造器注入业务接口并承接原POST；旧混合实现由967行减至572行，不再暴露create入口，也不持有Cart、创建时钟、意图Service/Mapper等专属依赖。商品快照规则、共享写流程辅助及计算明细独立归位，七个新生产类型全部落盘。剩余状态和秒杀/消息仍在旧实现，空OrderService随后续整理移除，本轮不宣称Order全模块完成。

实际调用链为：

```text
POST /api/orders → OrderCreationController
  → 创建Service代理先开启原REQUIRED事务
  → 获取OrderWriteMonitor → 原位置读取身份
  → 直接幂等重放 / 数量与Cart证明 / 地址与商品金额、metadata、JSON准备
  → 原独立INIT提交 → 原Stock预扣及持久证明 → 独立RESERVED
  → 同事务订单/明细/幂等/outbox/COMMITTED → 注册原Cart回调
  → 解锁 → 事务提交及afterCommit → Controller包装原OrderVO
原cancel/paid代理 → 开启事务 → 获取同一监视器 → 原状态/CAS/动作 → 解锁后提交
原confirm/秒杀/消息入口保持原锁与事务范围
```

历史混合矩阵通过仅测试的OrderTestService使用真实创建算法；该夹具保留原测试事务入口，不用于证明生产事务注解。直接装配生产类的OrderCreationWiringTest独立验证真实Spring事务拦截器、共同监视器、两个竞争者已开始事务但不能进入业务、commit/rollback时当前线程已解锁、异常释放、原confirm不加锁/不新开事务、HTTP绑定和提交失败不返回成功。ShardingRuntime使用真实生产创建Controller/Service代理并核对两个目标持有同一个监视器；这些验证未经过混合测试夹具。

| 最终有效范围 | 测试数 | 实际覆盖 |
| --- | ---: | --- |
| Common单测 | 25 | 原公共契约，创建入口/规则源码检查指向新类 |
| Order单测 | 322 | 原317项及5项直接生产装配/MVC/锁边界检查 |
| OrderCreationPrecisionMySqlIT | 72 | 全类创建精度、商品/金额/快照、意图/补偿、Cart提交边界 |
| OrderCreationShardingShadowMySqlIT | 2 | 全类物理/逻辑创建影子及故障事实 |
| OrderReplayRecoveryShardingShadowMySqlIT | 4 | 全类幂等重放、意图、outbox和库存动作恢复影子 |
| OrderShardingRuntimeMySqlIT | 3 | 实际装配、启动拒绝及冷发布/回退 |
| 合计 | 428 | 66个类，最终逐类去重报告0失败、0错误、0跳过 |

首次整体运行并非全绿：Runtime重启及Replay库存动作两项在客户端建立MySQL socket时失败，Runtime根因链为java.net.BindException Address already in use；Replay的CannotGetJdbcConnectionException消息SHA与其一致。只读观察TCP默认49152起/16384端口、当时全机TIME_WAIT约3752/本实验约3112，不能据此确定全机端口耗尽。保留首轮日志和两个失败XML后，两类分别独立重跑全类通过，Replay重跑同时再次通过全部单测。没有调整业务、断言、MySQL或Windows网络配置来绕过失败；最终统计不重复计算重跑，也不把首次失败抹去。

新增装配测试初次编译出现ObjectMapper::new与registerBean重载推断歧义，改成明确Supplier lambda后通过；源码审计首次误将地址字段字符串id纳入变量改名，限定变量位置后通过，未修改生产行为满足审计。以上均记录于原Bug台账。

19组迁移方法体及状态同步包装按声明变换逐token一致，其他混合结构/算法对照通过；Order其他168份原源码/资源哈希保持，22个适配测试文件的原方法全部保留。32个变更Java文件完成Google Java Format，新增同步块按完整方法格式化；diff检查通过。Order打包及八模块test-compile通过，Jar七个新生产类型与最终class逐字节相同，无测试夹具或旧ItemRow，旧混合类无create。创建Service没有AuthContext/Web绑定依赖，仍保留原@Transactional；显式同步块由真实装配测试验证，不再使用各自方法的隐式this锁。

### 27.4 证据、资源与回退

证据根 `target/backend-refactor-20261112/order-creation/` 的verification.json记录最终428项、首轮失败与独立重跑、Jar摘要、方法对照和资源状态。66份最终XML、两个失败XML、504份本轮native工件均保留；成功的三个native run满足其自身清理门，最初两个失败run按原门保留现场，不能归为成功清理。

新MySQL8.0.46/19943的七张来源表均精确0行，所有成功用例UUID库已移除。按native记录保留三个失败现场库（一个cm17ej、两个cm17ei），已用只读mysqldump导出并记录SHA，且仍存在于保留卷中；不是默认业务数据，也没有手工删库伪装测试通过。自有容器与网络已移除，`cloud-mall-refactor-order-creation-20261112_mysql_data` 卷保留。README与起始指纹一致，其已有修改和旧pycache不纳入提交。

方案提交/基线 `1e28b517`，标签 `refactor/order-creation-baseline-20261112`；完成标签 `refactor/order-creation-complete-20261112`。临时Git index验证当前阶段逆补丁恢复父提交完整树，实际工作树/index与README修改不回退。

```powershell
git revert refactor/order-creation-complete-20261112
```

此回退只撤销本轮普通创建及共享监视器/辅助规则，保留之前查询/Cart/Seata成果。回退后精确清理七个新生产类型及OrderTestService/OrderCreationWiringTest（含嵌套类）对应class残留再编译，保留target历史证据；有后续依赖时按逆序处理，不使用全量clean/reset --hard。Git不恢复运行数据或忽略证据。

本轮真实MySQL验证中的远端服务仍为既有mock，未执行实际Cart/Stock HTTP、TC/MQ、完整商城或性能测试。下一步拆分取消/支付/确认状态迁移，继续共享OrderWriteMonitor，保持各入口原鉴权时点、事务/无事务差别、CAS与库存动作意图；之后再处理秒杀和消息消费。

## 28. Order 状态迁移Service（2026-11-13）

### 28.1 范围与兼容边界

基线 `e1779d3e`，主Agent继续单独执行。已复读项目规则、README、Bug台账、架构/API状态迁移及超时关单规格；README原有修改和旧pycache保留。只迁移cancel/paid/confirm及其专属鉴权/竞争重读辅助，秒杀和消息入口继续后续处理。

- 新增 `service.OrderStateService`、`service.impl.OrderStateServiceImpl` 与 `controller.OrderStateController`，承接原POST `/api/orders/{orderNo}/cancel|paid|confirm`。Service返回OrderVO，Controller构造器注入接口并包装ApiResponse。cancel只保留可选X-User-Id绑定，不新增reason请求体解析或校验；paid/confirm保持由AuthContext获取身份，不新增显式Header参数。
- 身份以LongSupplier延迟读取：三个入口仍先读取并证明原订单及明细，再取身份/核归属；取消显式Header优先。错误顺序（404/数据库/快照失败优先于401）、严格状态比较、原消息/HTTP码保持。CAS竞争重读只按原目标状态决定幂等返回或409，不追加鉴权/修复/远程Stock操作。
- cancel/paid保留方法级默认REQUIRED及synchronized(writeMonitor)，使用与创建Service同一单例；事务先于等锁开始、解锁后提交。confirm不新增事务/锁，也不挂起调用方已有事务。三个状态方法不新增重试、RR或锁定SELECT。
- 原setStatus提取到包内无状态 `OrderStateWriter.transition(OrderSqlMapper,OrderVO,targetStatus)`，原UPDATE条件/时间采样/影响行数==1保持。超时String消费者仅改为调用同一helper，原到期门、事务、无共享写锁及消息解析保持。
- 只有CAS成功的取消/支付写原ROLLBACK/CONFIRM动作；动作插入与订单状态在同一原事务内，失败继续回滚。目标终态重放不新增动作；收货确认不新增库存动作。SQL、PO、状态机、Feign、消息/Redis、OrderWriteMonitor均不改。
- 旧混合类移除三个HTTP/Java入口、owned和completedOrConflict、writeMonitor字段及旧setStatus。剩余timeout仍需OrderSqlMapper/ObjectMapper/动作Mapper与private find。新StateService仅构造器注入这三项及监视器，无StockClient依赖；ApiResponse构造移至代理返回后的Controller，requestId不参与持久化。

### 28.2 测试、实验与提交

先提交本节并建立 `refactor/order-state-baseline-20261113`，运行Common/Order单测基线。OrderTestService仅测试夹具接管原状态方法适配，保留旧矩阵响应及外层测试事务，调用真实StateService；把原手动对象的监视器移到夹具以保持与创建共用。直接生产装配的OrderCreationWiringTest改为真实StateController/Service，继续检查互斥、无锁confirm及事务时序；ShardingRuntime显式装配新Bean并核对状态/创建Service监视器身份。

新增状态MVC矩阵覆盖原绑定、延迟身份、严格目标状态、CAS0重读和金额/null响应。真实StateService/MySQL测试覆盖取消/支付状态与动作原子性、动作INSERT失败回滚、幂等重放及无事务confirm；OrderDetailReadMySqlIT改为直接使用生产StateService代理。回归OrderLegacyStockIdentityMySqlIT及适用动作实库，保留原方法。新增Pay侧显式IT用实际OrderClient与本机HTTP/MVC验证新状态入口的Header/路径/响应，Order Mapper为受控替身；不增加生产模块依赖或把它当成完整Nacos/商城链。

新Compose项目 `cloud-mall-refactor-order-state-20261113`，MySQL19944、10.254.254.0/24，确认项目/卷/端口不存在后创建。原schema.sql仅取Order七张空源表；用例只写自有UUID库。吸取上一阶段客户端socket异常经验，相关实库分组串行执行；ShardingRuntime只运行与本次装配/业务直接相关的 `explicitModeUsesProductionMappersAfterBootstrapAndAudit`，不把一个方法写成全类通过。保留原失败清理门，不动上轮保留卷/现场；本轮结束归档、移除自有容器/网络、新卷保留。

阶段门：适用测试全部通过、迁移方法与剩余逻辑对照、原测试方法保留、实际Java格式化、Order打包/八模块test-compile、Jar旧入口与测试夹具检查、git diff --check、README指纹保持。完成标签 `refactor/order-state-complete-20261113`，临时index验证逆补丁恢复父树；不做真实工作树回滚展示、全量clean或reset --hard。未运行部分和失败尝试如实记录，Git不恢复运行数据或忽略证据。

### 28.3 实施结果

状态迁移已完整落到OrderStateController/Service/Impl，新实现117行；条件更新由OrderStateWriter与原超时消费者共享。旧混合实现从572行减至483行，不再持有状态共享监视器或暴露cancel/paid/confirm。创建与状态两个生产Service持有同一OrderWriteMonitor；剩余消息/秒杀流程没有因此加锁。

调用链复盘：`取消/支付HTTP绑定 → 状态Service原REQUIRED事务 → 共享锁 → 原完整详情/身份/归属 → 目标重放或状态门 → CAS → 成功才写ROLLBACK/CONFIRM动作 → 原详情重读 → 解锁 → 提交 → HTTP包装`。`confirm → 原详情/身份 → PAID/COMPLETED规则 → 同一CAS及竞争重读 → HTTP包装`，不新增事务或锁。超时消费者继续先核对原状态与期限，再直接调用共享CAS和动作Mapper；没有借用HTTP取消的身份/状态规则替代消息处理。

| 验证范围 | 测试数 | 实际覆盖 |
| --- | ---: | --- |
| Common单测 | 25 | 既有契约，路径绑定守卫指向新StateController |
| Order单测 | 326 | 原322项及新增4项状态MVC矩阵；直接生产装配的原5项并发/事务时序测试保留并改用真实StateService |
| OrderStateMySqlIT | 3 | 真实状态代理，状态/动作原子性、触发器拒绝INSERT时回滚、终态重放、confirm原自动提交及后续读取失败语义 |
| OrderDetailReadMySqlIT | 1 | 已改为直接生产StateService代理，支付内主表/明细同连接，状态与动作提交 |
| OrderLegacyStockIdentityMySqlIT | 18 | 原身份、状态和超时边界矩阵 |
| OrderStockActionStateMySqlIT | 4 | 原动作状态与恢复持久化规则 |
| ShardingRuntime精确单方法 | 1 | 仅explicitModeUsesProductionMappersAfterBootstrapAndAudit，真实分片装配/创建/取消，监视器与代理身份 |
| PayOrderStateFeignIT | 1 | 真实Pay OrderClient、原Feign拦截器、本机HTTP及实际Order MVC/Service；SQL为替身 |
| 合计 | 379 | 69个类，0失败、0错误、0跳过 |

状态MVC验证原路由共存及未新增请求体解析、金额字符串/null/空明细、终态重放、CAS0目标重读与冲突、原明细错误/404优先于401、延迟身份与取消Header优先。实库对支付与取消分别注入动作INSERT失败，原PENDING_PAYMENT及零动作得到保持；confirm仍沿原自动提交，在成功更新后读取失败时不会新加回滚，这是既有语义的保留而非新增行为。

Pay契约使用两个原订单身份分别支付/取消并各重放一次，恰好生成一份对应动作；验证原Header、POST路径、OrderView金额/身份/状态解码。公开OrderVO没有stockActionStatus，实际五参record解码为null，不因四参兼容构造器补成SUCCEEDED。该IT不访问数据库、不注册Nacos或启动完整Order服务；事务与SQL证据由上述独立实库测试提供。

七个方法体（含共享find/owned/CAS）按已声明返回类型、身份来源及委托变化逐token一致，原混合实现其余算法与消息解析保持；其他194份Order原源码/资源哈希不变。五个适配测试文件保留原方法，测试夹具不复制状态业务。13个变更Java文件完成Google Java Format，diff检查通过。Order打包、八模块test-compile通过；Jar四个新生产类型齐全且与最终class一致，无测试夹具，旧状态入口/辅助方法/监视器字段已移除。新Service无AuthContext/ApiResponse/Web绑定字节码依赖。

本轮分组执行全部通过，未复现上一阶段socket异常。Runtime的XML恰一方法；其native class-start中的声明方法数不作运行数。16份本轮native工件及69份报告已归档，实际一个UUID fixture created=deleted、errors为空、无需保留失败库。新实验MySQL8.0.46/19944最终仅余来源库及系统库，七张来源表逐表精确0行，所有本轮UUID库均已清理。自有容器/网络已移除，新mysql_data卷保留；上轮失败卷和现场未触碰。证据为 `target/backend-refactor-20261113/order-state/verification.json` 及同目录；README与起始指纹一致，其原修改和旧pycache不入提交。

未运行真实Stock/Cart HTTP、TC/MQ、完整商城或性能测试。Order剩余秒杀受理与消息处理/Controller继承仍待继续，未宣告模块整体完成。

### 28.4 回退与后续

方案提交/基线 `0ea4975f`，标签 `refactor/order-state-baseline-20261113`；完成标签 `refactor/order-state-complete-20261113`。临时Git index验证逆补丁恢复父提交完整树，实际工作树/index及README原有修改保持。

```powershell
git revert refactor/order-state-complete-20261113
```

只回退本轮状态职责迁移，保留创建与之前切片。实际回退后按清单精确清理四个新生产类型和OrderStateContractTest/OrderStateMySqlIT/PayOrderStateFeignIT（含嵌套类）的class残留并编译，不清空target历史证据；已修改的OrderTestService恢复源码后重新编译。Git不回退数据库/忽略工件，有后续依赖时先处理冲突，不使用reset --hard。

下一步拆分秒杀受理入口：冻结Sentinel资源与参数、地址快照、Stock返回包装/错误码透传及无新增事务的边界，再整理秒杀落单和超时消息消费者，最终移除剩余Controller继承和空OrderService。

## 29. Order 秒杀受理Service（2026-11-13）

### 29.1 实施前评审与范围

基线 `e397e1e4`，本会话仍由主Agent单独评审、实施及验证。已核对项目规则、README、Bug台账、架构/API和Sentinel秒杀规格及当前调用点。本轮只迁移POST `/api/seckill/orders` 的受理编排；秒杀落单与超时消费者、队列/事务和恢复任务后续单独拆分。README原有修改与两个pycache目录保留，不纳入提交。

- 新增 `controller.OrderSeckillAdmissionController`、`service.OrderSeckillAdmissionService`、`service.impl.OrderSeckillAdmissionServiceImpl`。Controller构造器注入接口，保留必填Idempotency-Key和原SeckillOrderDTO绑定，在原首个业务动作读取AuthContext身份，传入Service的long userId；Service只注入StockClient、UserClient和ObjectMapper。
- 鉴权仍早于业务参数校验；Spring绑定失败仍早于方法执行。不添加正数校验、trim或归一化，保留Unicode code point最多128、空字段及空请求语义。没有原先需要延迟到读库/等锁之后的身份读取，本入口无需LongSupplier。
- 维持原顺序：参数校验 → Sentinel `seckill-order-submit` IN/一次计数/参数[userId,activityId] → 原地址首个数值ID匹配与JSON快照 → 五字段Stock请求 → `order-stock-seckill` OUT保护的一次Stock调用。进入资源后所有成功/异常路径都通过原finally退出；本地Block只记录原指标并返回429，不调用User/Stock。
- 明确保留完整上游 `ApiResponse<?>` 对象：code、message、data（含额外字段/null）和requestId全部透传，Controller不重新包装。这里是远端受理响应的兼容边界，不把它改成OrderVO或ApiResponse.ok(data)；不新增中间包装DTO。原StockClient Map契约本轮保持，避免合并跨服务模型迁移。
- 保留全部原错误分类与消息：已知429区分短期流控/限购，未知429为503；409幂等冲突保留，五种活动拒绝映射SECKILL_NOT_AVAILABLE，其他HTTP错误、null结果、熔断为503。User/序列化异常继续原样沿原边界传播，不扩大catch或增加写重试。非null的上游非零业务码仍透传，不追加成功码校验。
- 不新增事务、锁、SQL、Redis、MQ操作或服务调用，不挂起调用方已有事务；accepted只表示受理。原混合类删除该HTTP方法及专属Stock/User依赖，手动构造器只保留Product/Mapper/JSON。暂保留旧Controller Bean承载原消息消费者，最后消息切片再移除继承和空接口。
- 旧OrderTestService测试夹具接管Stock/User字段（保持反射字段名）及seckill适配，调用实际新Controller/Service，不复制业务。其余旧测试保留；六项既有秒杀错误映射测试直接装配新入口，不依赖混合夹具。

### 29.2 验收与提交

先提交本节及 `refactor/order-seckill-admission-baseline-20261113`，运行Common/Order单测基线。补充实际MVC与Spring装配覆盖响应完整透传、绑定/鉴权/参数/地址先后、Sentinel流控/用户和活动热点、异常退出、原无新增事务与沿用已有事务。通过本机HTTP替身与实际StockClient、SpringMvcContract、原Feign认证拦截器验证路径、五字段载荷、身份头、一次调用、原409/429/503映射及响应信封；不把受控Stock替身写成真实Stock持久受理测试。

本入口没有Order持久化路径，本轮不新建MySQL/Redis/RabbitMQ实验；不重跑无关实库矩阵。全Common/Order单测、Order打包、八模块test-compile、迁移方法/剩余源码对照、Java格式化、Jar入口和git diff --check为门槛。原测试方法保留、未涉及源码/资源哈希与README指纹核验。所有尝试与最终报告归档到 `target/backend-refactor-20261113/order-seckill-admission/`。

完成后建立 `refactor/order-seckill-admission-complete-20261113`，临时Git index验证逆补丁恢复父树，不动真实工作树展示回滚，不全量clean或reset --hard。Git回退不恢复数据库或忽略工件；实际撤销新增类型后只精确清理对应class并重编译。尚未运行的真实Nacos规则订阅、Stock/User HTTP业务、中间件和完整商城链明确列为限制。

### 29.3 实施结果与调用链

三个受理类型已落盘，Service实现110行；旧混合类483→390行，只剩消息消费及其辅助方法，不再持有Stock/User客户端、AuthContext、HTTP注解或秒杀受理方法。旧OrderController暂保留Bean承载原Rabbit监听器，注释已说明当前用途；空OrderService与剩余继承关系等待消息切片收尾。

实际调用链为：`POST绑定 → Controller读取原身份 → AdmissionService参数校验 → 原Sentinel IN资源 → User地址查询/首个数值ID匹配 → JSON快照 → 五字段Stock载荷 → 原Stock熔断OUT资源 → 一次Feign调用 → 完整上游响应透传/原错误映射 → finally退出资源`。没有Order Mapper、消息发布或新增事务；原已有调用方事务不被挂起。未修改Sentinel配置/指标、Feign接口和拦截器、地址helper、Stock受理事实或异步落单协议。

| 验证范围 | 测试数 | 实际覆盖 |
| --- | ---: | --- |
| Common单测 | 25 | 原公共契约与跨模块规则 |
| Order既有单测 | 326 | 原创建/状态/消息矩阵，含直接改用新入口的6项秒杀错误映射 |
| OrderSeckillAdmissionContractTest | 8 | 原响应对象身份及完整HTTP信封、绑定与鉴权顺序、原参数/地址/五字段载荷、实际Sentinel流控指标及用户/活动参数位置、成功/异常资源退出、JSON失败、Spring无新增事务及沿用外层事务 |
| OrderSeckillAdmissionFeignTest | 1 | 实际StockClient/SpringMvcContract/认证拦截器到本机HTTP替身的18组响应；原路径、两个身份头、原键/快照、完整响应、全部已知409/429和未知/畸形/null/5xx映射，每次恰一次Stock请求 |
| 合计 | 360 | 65个类，0失败、0错误、0跳过 |

HTTP绑定验证保留了既有统一异常处理器将畸形JSON归为500的行为，没有在结构重构中顺带调整错误协议。业务层继续只要求活动/SKU/地址非null，不新增正数门；地址只匹配数值ID而不接受字符串ID，选择首个匹配项，保留null字段。非null上游响应不检查成功码、不改requestId或data；受理结果不被误表述为订单落库。

首次测试编译因当前Micrometer SimpleMeterRegistry未实现AutoCloseable失败，已改为finally移除全局registry并显式close；同时按Feign Encoder接口处理测试JSON序列化受检异常。修复后先15项定向通过，再完整360项通过。原失败日志保留，生产逻辑未为测试错误调整。测试HTTP服务器/线程池、Spring上下文和指标注册器均在结束路径释放。

受理方法457个token经声明的变量改名及身份读取移到Controller后逐项一致；剩余混合源码（含两个消费者和辅助规则）对照一致，其他201份Order原源码/资源哈希保持。两个适配测试文件原方法保留，夹具只委托实际新入口。九个变更Java文件完成Google Java Format及diff检查。Order打包和八模块test-compile通过；Jar三个新类型与最终class逐字节一致，无测试夹具，旧混合类不再依赖Stock/User或HTTP绑定，新Service无AuthContext/Web绑定/事务注解/订单Mapper/共享监视器依赖。

### 29.4 证据、回退与后续

证据入口 `target/backend-refactor-20261113/order-seckill-admission/verification.json`，同目录保留基线、失败编译、定向及最终日志、65份最终XML、源码对照、格式化/产物校验及逆补丁记录。README与起始字节指纹一致，其原修改及旧pycache不入提交。本轮未创建Docker实验资源，历史容器/卷及上轮失败现场未触碰。

方案提交/基线 `e90cc7b2`，标签 `refactor/order-seckill-admission-baseline-20261113`；完成标签 `refactor/order-seckill-admission-complete-20261113`。临时Git index验证本轮逆补丁恢复父提交完整树，真实index和工作树保持。

```powershell
git revert refactor/order-seckill-admission-complete-20261113
```

只回退本轮秒杀受理职责迁移。实际回退后精确清理三个新增生产类型、OrderSeckillAdmissionContractTest和OrderSeckillAdmissionFeignTest（含嵌套类）的class残留，再编译恢复后的旧实现/夹具；不清空target历史证据。后续存在依赖时逆序处理，Git不恢复运行数据或忽略工件。

下一步提取秒杀消息落单Service与监听适配器，先冻结消费事务、重复/冲突指纹、时间/商品快照和原重试/死信语义；随后拆分超时消费者并移除旧Controller继承和空OrderService。本轮未执行真实User/Stock业务服务、Nacos规则订阅、中间件、完整商城或性能验证，Order模块整体尚未完成。

## 30. Order 秒杀消息落单Service（2026-11-13）

### 30.1 实施前评审与边界

基线 `7842d80a`。主Agent继续单独评审、实施及验证；已读取项目规则、README、Bug台账及秒杀时间、商品身份、数值解析、冲突原文/序列化、dedup提交结果规格。只迁移consumeSeckill及其五个私有辅助方法，超时消费、恢复扫描、Mapper/SQL、消息协议和共享创建/状态锁保持原样。

- 新增 `service.OrderSeckillCreationService`、`service.impl.OrderSeckillCreationServiceImpl`，以createFromMessage(Map<String,Object>)承接原方法。新实现构造器注入ProductClient、OrderSqlMapper、ObjectMapper、OrderOutboxMapper、SeckillOrderDedupMapper、SeckillMessageRecoveryMapper；保留方法级默认REQUIRED，不新增锁、独立事务或重试。
- `config.OrderSeckillMessageListener` 作为消息接入适配器，构造器注入Service接口，consumeSeckill只委托服务。原RabbitListener队列、默认containerFactory、Map泛型签名、转换器和异常传播保持；事务在业务体前开启，commit成功后才返回监听容器。监听器不捕获或包装Service异常，Service原catch(Exception)继续保留cause、Error不吞，提交阶段异常仍传播至容器。
- 原注解没有显式endpoint ID，本轮仍由框架自动生成，自动序号不作为业务标识；不新增固定ID或改变consumerTag策略。实际注册时秒杀队列只允许一个监听端点，旧OrderController移除秒杀方法后继续承载原超时端点。原AUTO确认、三次重试、1s/2s退避及拒绝后DLX配置不修改；不新增手工ACK/NACK。
- 保留原null消息返回、Number/String无损long解析、legacy缺地址规则、原指纹计算与大小写比较、claim/find分支、两类冲突证据、COMMITTED重放先于时间/Product的顺序。首次落单保留上海毫秒/月份校验、商品身份/金额/metadata规则、quantity=1、原时间和30分钟期限。
- 订单、明细、timeout outbox、dedup COMMITTED及匹配恢复状态沿同一原事务提交；markCommitted必须恰1行。冲突只使用成功序列化的一份文本生成原文/哈希/稳定事件号，不把Rabbit原始body与Map序列化文本混称。所有helper算法迁入新实现，不复制到旧类，不扩大捕获范围。
- 旧实现移除Product、Outbox、秒杀dedup/recovery专属依赖，手动构造器只保留OrderSqlMapper/ObjectMapper。对应字段迁至OrderTestService测试夹具（保持反射字段名和注入能力），consumeSeckill测试适配保留原外层@Transactional并调用真实新实现；不以夹具替代生产代理证据。Runtime实库装配改为实际新监听器与服务代理。

### 30.2 验证与提交策略

先提交本节并建立 `refactor/order-seckill-creation-baseline-20261113`，运行Common/Order单测基线。保留原单测及CreationPrecisionMySqlIT全部72方法；Runtime只运行 `explicitModeUsesProductionMappersAfterBootstrapAndAudit`，不声称全类。新增监听适配单测和真实Spring AMQP注册/转换验证，检查无重复监听、同一消息委托和异常不吞。

新独立Compose项目 `cloud-mall-refactor-order-seckill-creation-20261113`，MySQL19945、Rabbit19946/管理19947、子网10.254.239.0/24；核实项目/卷/端口/子网不存在后创建，沿用deploy/experiments/compose.yml的固定镜像/认证。只从schema.sql准备原Order七张空源表，实库用自有UUID库。新增真实MySQL/Rabbit IT直接装配生产Service、监听器、消息配置/转换器及原重试参数，覆盖正常提交/重复、商品失败回滚并三次重试入DLQ、提交门故障回滚/原消息可重放、冲突证据落盘。远端Product为受控替身，不宣称真实商品/库存全链。

各实库组串行运行，保留原native清理门及失败证据，不动历史失败卷。结束核对来源表为空、成功UUID库清理、消息队列清空，归档日志/XML/本轮native及容器元数据，再仅移除自有容器/网络，新MySQL/Rabbit卷保留。

验收门包括原六方法及剩余算法对照、原测试方法保留、未涉及源码/资源指纹、实际Java格式化、Order打包、八模块test-compile、Jar监听器/旧入口检查、git diff --check及README原修改保持。完成标签 `refactor/order-seckill-creation-complete-20261113`；临时Git index逆补丁恢复父树，保留真实工作树和暂存条目，不全量clean/reset。Git不回退运行数据；未测的真实Product/Stock/TC/Nacos、商城与性能范围如实记录。下一步仅剩超时消费及旧继承入口收尾，其他Order辅助服务分组随后独立盘点。

### 30.3 实施结果与调用链

三个新生产类型已落盘：监听器只委托接口，秒杀落单实现300行承接原业务方法及五个辅助方法；旧混合实现390→138行，只持有超时消费所需的订单/动作Mapper和ObjectMapper。测试夹具保留可替换字段与原事务适配，原算法矩阵继续调用真实新实现。Runtime直接装配新监听器/Service，验证监听器不代理、Service通过事务代理，并将本轮证据字段准确记为actualListenerClass。

逐行复盘的实际调用链为：`原队列/转换器 → Map消息 → 独立监听器 → Service开启原REQUIRED事务 → 数值身份/legacy/指纹 → claim/find → 冲突证据或已提交重放 → 原时间/月门 → Product身份/价格/metadata → 订单/明细/timeout outbox → dedup恰1行COMMITTED → 匹配recovery完成 → 事务提交 → 返回监听容器AUTO确认`。业务异常沿原catch保留cause，Error不捕获；提交失败也传播到监听容器。原三次重试耗尽后拒绝并进入原DLX，没有手工ACK、额外Stock调用或独立数据库事务。

| 验证范围 | 测试数 | 实际覆盖 |
| --- | ---: | --- |
| Common单测 | 25 | 路由源码守卫改为检查新落单实现，其他规则保持 |
| Order单测 | 337 | 原335项及新增2项监听委托/实际AMQP注册转换测试；秒杀队列恰一个端点，原超时端点仍在 |
| OrderCreationPrecisionMySqlIT | 72 | 整类回归，包含秒杀时间、商品/金额/metadata、无损数值身份、冲突原文、提交结果、恢复身份及普通创建/Cart矩阵 |
| OrderSeckillMessageMySqlRabbitIT | 4 | 真实MySQL/Rabbit/事务代理、转换器、原重试/DLX和AUTO确认；首次提交/重复、商品故障三次回滚和死信、晚期dedup写失败整体回滚及原消息重放、冲突证据 |
| ShardingRuntime精确单方法 | 1 | 仅explicitModeUsesProductionMappersAfterBootstrapAndAudit；真实分片数据源/Mapper与新业务代理，普通/秒杀落单及原恢复扫描 |
| 合计 | 439 | 69个类，最终去重报告0失败、0错误、0跳过 |

真实Rabbit用例加载原application.yml重试参数，由Boot原factory configurer装配容器，不缩短1s/2s退避。实测商品失败恰三次调用、各次均在数据库事务内，订单/明细/outbox/claim均回滚，原消息保留类型头及x-death rejected后进入死信；纠正Product替身后同一死信重投成功。dedup更新触发器故障同样三次整体回滚，移除本例触发器后原事件成功。正常消息保留上海毫秒和30分钟期限；COMMITTED重放缺时间仍不查询Product；非法数值身份只提交原冲突证据。

本轮失败与复核分别记录，未把最终通过写成从未失败：

- Common原源码守卫仍要求旧实现直接调用月份路由，迁移后首轮失败；断言改指新Service并保留YearMonth.parse检查，随后完整回归通过。
- 新消息IT首轮2项断言把原内存LongNode与wire JSON解析后的IntNode直接比较，文本实际一致；改为两侧均按原序列化字节解析后比较完整JSON，未改消息转换器或业务。全类4项复跑通过，两个首轮失败UUID库按新IT保留门留存。
- 原72项创建实库首轮仅wrongStockOrderCannotFinishIntentAndMatchingFactCanRecoverLater失败，第二次扫描未进入认领，状态仍RESERVE_UNKNOWN。该方法和对应生产恢复代码本轮均未修改；独立同方法及整类加全部单测重跑均通过。首轮没有记录扫描参数与行值的完整时钟证据，根因仍未确认，不宣称修复了时钟或恢复业务。原夹具按既有AfterEach清理其库，失败XML/完整日志及单例输出保留。
- 只读实验预检兼容了Docker host/none网络空IPAM配置。隔离Rabbit原3秒CLI健康探针持续超时而标记unhealthy；独立原ping约5.17秒exit0，仅诊断进程设置2个调度器时约0.53秒exit0，管理alarms为200/ok，实际AMQP全类通过。没有修改broker、基础Compose或宿主网络，不把该探针标志写成healthy。

六个迁移方法按已声明改名和注解归属变化共1612个token一致；剩余超时算法对照一致，207份其他Order原源码/资源指纹不变。三个适配测试文件原方法保留。九个Java完成实际格式化（大Runtime仅改动行）、diff检查；Order打包及八模块test-compile通过。Jar三个新类型与最终class一致，无测试夹具；旧类不含秒杀方法/辅助方法或四个专属依赖，新Service不含HTTP/鉴权上下文/Rabbit监听注解/共享写锁，仍保留原事务注解。

### 30.4 证据、资源、回退与后续

证据入口 `target/backend-refactor-20261113/order-seckill-creation/verification.json`。同目录保留69份最终XML、各次失败/复跑日志、三个失败XML、方法对照、格式化和Jar检查、16份本轮Runtime native工件。Runtime的class-finish记录created=deleted=1、errors为空、无需保留失败库；只运行一个方法，不把class-start的声明方法总数作执行数。

MySQL8.0.46七张来源表最终均精确0行；所有成功UUID库已清理。保留两个首轮消息断言失败库 `order_seckill_msg_732acb2245634319ad5ea1c754a9ce5b` 和 `order_seckill_msg_ae57844dd14d43d082f7c18723755186`，已只读导出为preserved-failed-fixtures.sql并记录SHA，数据库仍在保留卷中。Rabbit五个队列ready/unacked/consumer均为0；实验容器/网络已移除，项目mysql_data和rabbit_data卷保留。历史实验卷及失败现场未触碰，README原有修改逐字节保持，两个旧pycache不纳入提交。

方案提交/基线 `5c6d17e7`，标签 `refactor/order-seckill-creation-baseline-20261113`；完成标签 `refactor/order-seckill-creation-complete-20261113`。临时Git index验证本轮逆补丁恢复父提交完整树，真实index和工作树保持。

```powershell
git revert refactor/order-seckill-creation-complete-20261113
```

实际回退后精确清理三个新生产类型与OrderSeckillMessageListenerTest/OrderSeckillMessageMySqlRabbitIT（含嵌套类）的class残留，再编译恢复后的旧实现/夹具；不清空target证据。后续依赖按逆序处理，Git不恢复数据卷或忽略工件。

下一步提取超时消息解析/关单Service并移除旧OrderController继承和空OrderService；随后盘点Order辅助实现的职责分组。本轮没有真实Product/Stock远端、TC/Nacos、完整商城或性能验证，Order模块整体尚未完成。

## 31. Order 超时消费与旧继承入口收尾（2026-11-13）

### 31.1 实施前评审

基线 `6116cb53`。上次中断仅完成只读核对，没有代码修改；本轮主Agent继续单独评审、实施及验证。已复读项目规则、README/Bug记录及17DP/DQ/DR/DS/DT/DU/DV消息与期限规格，工作树仅有原README和两个pycache，均保留。

- 新增 `service.OrderTimeoutService`、`service.impl.OrderTimeoutServiceImpl`，提供closeFromMessage(Message)与closeExpiredOrder(String)两个原REQUIRED入口。接口保留原AMQP Message输入，以保持获取body之前已进入事务、容器原始字节输入和异常时点；本轮不引入消息DTO或再次JSON转换。Message入口在同一目标对象内委托String业务，不新增第二个代理调用、独立事务或共享写锁。
- 新增 `util.OrderTimeoutMessageParser`，只接收原ObjectMapper和byte[]，返回原orderNo。严格UTF-8 REPORT、per-call reader的single-root/duplicate-key、四字段原生文本/必填代理对、裸ASCII整数整份原文、quoted/raw legacy、catch顺序及cause保持。消息头/contentType/encoding不参与猜测；不修改共享ObjectMapper或原body，不新增expireAt语法/身份校验。
- 上述事务边界是进入业务方法后的body读取/解析。原Spring AMQP转换器仍在方法调用前运行，application/json本身无法转换时先拒绝且不开业务事务；text/plain保留原byte[]并交给Message入口。解析工具不读取消息头，不代表可以跳过原容器转换过程；前置转换拒绝与业务内解析拒绝分别验收。
- 新增 `config.OrderTimeoutMessageListener`，consumeTimeout(Message)仅调用业务接口，保留TIMEOUT_DLQ队列、默认factory、无显式endpoint ID和无手工ACK。原三次重试/AUTO/default-requeue-rejected=false保持；TIMEOUT_DLQ自身没有二级DLX，耗尽后的原拒绝行为不伪称再次进入其他死信队列。
- 关单仍完整读取原详情、精确状态为PENDING_PAYMENT才继续；缺失DB期限拒绝、未来期限拒绝，消息expireAt不可授权关单。原共享OrderStateWriter条件更新恰1行才插入ROLLBACK动作，两者同事务；CAS=0不补写动作/远端回滚，不借用HTTP取消鉴权。不改SQL、字段、时间采样、ID或状态机。
- 删除已无剩余职责的OrderController、OrderServiceImpl和空OrderService。OrderTestService改为普通测试装配，接管原三个注入字段并保留两个@Transactional适配入口，调用真实TimeoutService；OrderReadTestSupport使用具体测试夹具类型。MVC/创建/Runtime/秒杀监听装配中的旧Controller引用逐一改为真实新角色或移除空HTTP注册，原测试方法保留。Common守卫指向新监听器；旧故障runner无类名依赖，其容器外事务结束观测仍适用，本轮不改冻结runner。

### 31.2 验收、实验与回退

先提交本节及 `refactor/order-timeout-baseline-20261113`，运行Common/Order单测基线。适配后运行全部单测，原OrderLegacyStockIdentityMySqlIT全18项、状态实库3项、秒杀真实消息4项和Runtime精确explicitModeUsesProductionMappersAfterBootstrapAndAudit一项；创建大矩阵不重复全跑，本轮新增/改动依赖的创建装配单测继续覆盖，上一阶段普通恢复首轮原因未确认的记录不改成已修复。

新增监听委托/无继承检查，以及实际生产Service/MySQL/Rabbit测试：原TTL→DLX到期关单及重复/legacy无重复动作；动作INSERT故障三次整单回滚后同原消息可重试；非法UTF-8/重复键/多root在已开启事务内拒绝且不访问订单；缺失/未来DB期限不被消息文本覆盖、已支付等终态不加动作。记录真实事务开始/提交/回滚和原队列注册，不以测试夹具代替生产代理证明。

新Compose项目 `cloud-mall-refactor-order-timeout-20261113`，MySQL19948、Rabbit19949/管理19950、子网10.254.238.0/24，创建前核实无项目/卷/端口/子网冲突。固定镜像、root/root与基础compose保持；仅本实验override把健康探针命令设为 `env ERL_FLAGS=+S 2:2 rabbitmq-diagnostics -q ping`，只限制短命CLI进程的调度器，避免上轮默认探针启动超过3秒，不改变broker进程。七张Order空源表、测试自有UUID库；成功库清理、失败按原门保留，归档后仅移除自有容器/网络、两卷保留。

阶段门：四个原方法拆分前后对照、原测试方法保留、未改源码/资源指纹、真实格式化、Order打包及八模块test-compile、Jar确认三个旧类型及测试夹具不存在、git diff --check和README指纹保持。仅对已删除类型的target class精确备份/清理后重编译，不全量clean。完成标签 `refactor/order-timeout-complete-20261113`；临时index逆补丁还原父树，不动真实工作树展示回退。未验证真实Stock/Product/TC/Nacos、完整商城、进程硬停及性能范围明确保留。

### 31.3 实施结果与调用链

OrderTimeoutMessageListener、OrderTimeoutService/Impl及OrderTimeoutMessageParser已落盘。解析工具89行，事务实现74行；原138行混合实现、旧OrderController及空OrderService已删除。HTTP Controller均独立组合业务接口，两个消息接入适配器分别绑定秒杀与超时队列。测试混合夹具也不再继承生产实现，原SQL/JSON/动作字段在夹具内保留反射和构造注入语义。

实际调用链为：`原TTL队列 → 原DLX/超时队列 → 容器转换/重试 → OrderTimeoutMessageListener → Service原REQUIRED事务 → 获取body/严格UTF-8及原JSON/legacy解析 → 原完整详情/精确身份 → 原状态及DB期限 → CAS恰1行 → 同事务ROLLBACK动作 → 提交 → 监听返回/AUTO确认`。Message入口对String业务是同目标对象直接调用，未增加事务代理层次或锁。消息expireAt只保留原文本资格，不取代数据库期限；CAS=0不补写动作，不调用Stock。

| 验证范围 | 测试数 | 实际覆盖 |
| --- | ---: | --- |
| Common单测 | 25 | 原公共契约，超时队列源码守卫指向新监听器，移除无用旧源码读取 |
| Order单测 | 339 | 原337项及新2项监听委托/无继承测试；原七类超时解析矩阵、创建/状态/查询契约继续执行 |
| OrderLegacyStockIdentityMySqlIT | 18 | 全类原订单身份、状态、缺失期限及String/Message边界 |
| OrderStateMySqlIT | 3 | 既有状态与库存动作事务规则 |
| OrderSeckillMessageMySqlRabbitIT | 4 | 实际新超时监听/Service参与装配，秒杀原消费、重试和落单仍通过 |
| OrderTimeoutMySqlRabbitIT | 4 | 原TTL/DLX真实投递、原事务/CAS/动作原子性、失败重试、DB期限权威及解析拒绝 |
| ShardingRuntime精确单方法 | 1 | 仅explicitModeUsesProductionMappersAfterBootstrapAndAudit，实际分片与新超时服务代理装配 |
| 合计 | 394 | 72个类，最终逐类去重0失败、0错误、0跳过 |

真实TTL消息关单后，quoted和裸数字重投保持一个ROLLBACK动作；动作INSERT触发器拒绝时三次尝试全部回滚状态与动作，移除本例触发器后同原消息可关单。待支付NULL/未来DB期限分别三次失败，消息的历史expireAt不能覆盖；PAID/CANCELLED/COMPLETED/OTHER且NULL期限保持无动作。超时队列本身没有二级DLX，失败耗尽沿原拒绝/不重新入队语义；用例停止容器后核对队列空，不虚构其他死信目的地。

本轮还明确区分了原转换器与业务解析边界：application/json非法根字节由原转换器在事务前拒绝，三次失败后不进入Service；text/plain原始非法UTF-8及JSON重复键/多root进入Service后各三次事务回滚、零订单访问。单位装配使用同一原转换器独立验证前置拒绝不调用Service、text/plain保持原Message；真实用例发送四份消息，共九次业务事务回滚。所有最终行为验证均未改转换器、生产事务或重试配置。

新超时IT首轮2通过、1错误、1失败：测试SQL误写action而真实列为action_type；另把转换器前置拒绝错误计为业务事务，等待九次回滚超时。两处仅修正测试/验收描述，首轮XML/日志与两UUID库按门保留；随后2项监听单测和完整4项超时IT通过，最终全部适用回归通过。上一阶段普通恢复首轮异常的未确认原因保持原记录，本轮未改或重新验收该不相关恢复用例。

四个原方法共543个token经明确参数/名称及返回拆分后对照一致，Message事务内委托链单独核对；205份其他Common测试/Order源码资源哈希不变，11个适配测试文件原方法保留。17个变更Java文件完成格式化（大Runtime限改动行）及diff检查。Order打包、八模块test-compile通过，Jar四个新类型与最终class一致；三个旧类型及测试夹具均不存在，新Service无HTTP/鉴权/共享监视器/远端客户端依赖，解析工具没有Spring/Mapper依赖。原冻结故障runner仅编译，不改写或宣称执行其硬停验收。

### 31.4 证据、资源与回退

证据入口 `target/backend-refactor-20261113/order-timeout/verification.json`。保留72份最终XML、首轮失败XML/日志、源码对照、格式化记录、三份精确清理前class备份、Jar检查和16份本轮Runtime native工件。Runtime class-finish为created=deleted=1、errors为空、无需保留失败库，仅一方法实际执行。

MySQL8.0.46七源表逐表精确0行，成功UUID库已清理；两个首轮失败库 `order_timeout_refactor_159b5fa7ccf64894a911a2d54e0da6ef`、`order_timeout_refactor_5d2ba776002647c79a81b068613ec42f` 已只读导出并随新卷保留。Rabbit五队列ready/unacked/consumer均为0；归档时两个容器均healthy，CLI探针调度器限制只存在于本实验override。自有容器/网络已移除，mysql_data/rabbit_data两卷保留；历史资源、失败现场及README原修改均未变，旧pycache不入提交。

方案提交/基线 `9d626e93`，标签 `refactor/order-timeout-baseline-20261113`；完成标签 `refactor/order-timeout-complete-20261113`。临时index验证本轮逆补丁恢复父提交完整树，真实暂存条目、工作树和README保持。

```powershell
git revert refactor/order-timeout-complete-20261113
```

实际回退后精确清理四个新增生产类型及OrderTimeoutWiringTest/OrderTimeoutMySqlRabbitIT（含嵌套类）的class，再编译恢复后的旧三个生产类型和夹具；不全量清空target、不回退数据卷。有后续依赖时逆序处理。

Order主业务/消息入口拆分已完成，模块整体尚未结束。下一步先盘点并整理outbox、库存动作、预扣意图恢复和秒杀恢复辅助实现的职责分组，冻结Bean名、定时任务和配置契约后再实施；之后再进入Pay。未执行真实Stock/Product/TC/Nacos、完整商城、进程硬停或性能测试。

## 32. Order 辅助实现职责分组（2026-11-13）

### 32.1 实施前评审与迁移清单

基线 `82c3eb49`。主Agent继续单独执行，已核对项目规则、README、Bug台账、架构中的意图/动作/outbox边界及发布完成证明、中断规格。本轮只整理五个已有独立辅助类的包归属，不拆改恢复状态机、不引入新业务接口或兼容桥。顶层业务Service实现与包内共享规则保持，避免为包美观扩大工具可见性或复制算法。

| 原service.impl顶层类 | 新包（类名保持） | 保留的Bean名/职责 |
| --- | --- | --- |
| OrderReservationIntentService | service.impl.reservation | orderReservationIntentService；五个原REQUIRES_NEW写入口和原无事务查询 |
| OrderReservationRecovery | service.impl.reservation | orderReservationRecovery；普通预扣意图按原单恢复 |
| OrderStockActionRecovery | service.impl.stockaction | orderStockActionRecovery；确认/回滚动作的租约与远端事实核验 |
| OrderOutboxDispatcher | service.impl.outbox | orderOutboxDispatcher；已提交超时事件的投递与确认 |
| SeckillMessageRecovery | service.impl.seckill | seckillMessageRecovery；秒杀死信持久化、验真及重放 |

- 类名、默认Bean名、构造器/方法签名、字段名与可见性保持，移动后仍由启动根包递归扫描。增加四个package-info并补充顶层包说明；SeckillMessageRecovery原缺失的成员/构造器说明补齐，不改语句。默认Class日志类别随包路径变化，原日志消息/级别/参数不变。
- 四个Scheduled的原配置键及默认1000/30000/5000/5000毫秒保持；不新增定时任务、初始延迟、并发线程或代理层。Seckill死信继续SECKILL_DLQ和seckillDlqContainerFactory的MANUAL确认，先持久化/确认重投再单次ACK，异常与中断语义保持。
- 普通意图五个REQUIRES_NEW仍在创建主事务之外提交；恢复器/outbox不新增本地事务。原数据源、扫描/认领/租约/CAS、业务指纹、原事件号、publisher confirm/returned、UNKNOWN/CONFLICT及单条失败隔离全部保持，不改Mapper/SQL/PO/API/Feign/Redis/消息字段。
- 原共享OrderStockCircuitBreaker/异常及其资源名留在顶层；三个迁移恢复器显式import公共熔断入口。创建Service显式import意图类。更新普通import、全限定引用及两个原通配符装配点，不给历史日志/规格批量改名。
- 测试源只做必要类型地址适配，保留原方法；OrderCreationFaultProcess仅两条import更新以继续标准Maven编译，进程/控制/故障逻辑不改。deploy中的冻结实验runner、历史工件、默认中间件均不修改或执行。未发现外部配置/脚本依赖这五个旧完整类名。

### 32.2 验证、实验与提交

先提交本节及 `refactor/order-auxiliary-baseline-20261113`，运行Common/Order单测基线。新增实际子包扫描装配测试：五个原Bean名恰各一份、原REQUIRES_NEW代理、四个原调度任务及间隔、秒杀死信队列/MANUAL factory；使用不执行任务的测试TaskScheduler和不启动连接的容器配置，避免虚构真实网络结果。

执行全部Common/Order单测，OrderIntentStrictInsertMySqlIT全13项、OrderCreationShardingShadowMySqlIT全2项、OrderReplayRecoveryShardingShadowMySqlIT全4项、SeckillRecoveryDispatchMySqlIT全28项、SeckillRecoveryJsonMySqlIT全27项、OrderStockActionStateMySqlIT全4项、Runtime精确explicitModeUsesProductionMappersAfterBootstrapAndAudit一项，分组串行运行。原普通恢复72项大矩阵本轮不重复全跑，其历史首轮异常原因仍未确认。

新增实际moved OutboxDispatcher→publisher confirm→原DLX→TimeoutService→MySQL关单/动作的用例，放入已有OrderTimeoutMySqlRabbitIT并回归该类。其余远端仍为原替身，不把租约/恢复实库写成真实Stock/全商城链。旧OrderTimeoutRabbitFixture硬编码默认Rabbit端口，本轮不调用或改写它。

新Compose项目 `cloud-mall-refactor-order-auxiliary-20261113`，MySQL19951、Rabbit19952/管理19953、子网10.254.237.0/24；核实无冲突后从基础compose创建全新卷。只在本实验健康探针使用 `env ERL_FLAGS=+S 2:2 rabbitmq-diagnostics -q ping`，不改变broker。七张Order空源表和测试UUID库；遵守各自清理门，证据归档后移除自有容器/网络、保留两卷，不动历史失败卷。

验收门：五个迁移类去除package/import/注释后逐token一致、引用适配之外源码/资源不变、原测试方法保持、Java实际格式化、Common/Order与指定实库全通过、Order打包及八模块test-compile、Jar新类型唯一/旧类无残留、git diff --check及README指纹保持。仅对五个旧类型及其内部class精确备份清理，不全量clean。完成标签 `refactor/order-auxiliary-complete-20261113`，临时index逆补丁恢复父树，不操作真实工作树演示回滚；Git不恢复数据卷。之后进行Order模块阶段盘点与收尾，再细化Pay首切片。

### 32.3 实施结果与验收

五个辅助类已按清单迁入reservation、stockaction、outbox、seckill四个子包，保留原类名和五个默认Bean名；四份子包说明及顶层说明已落盘。顶层入口Service和包内共享规则继续就近协作，不引入空接口或兼容桥。36份原测试文件完成类型地址适配，原测试方法体全部保留；仅OrderTimeoutMySqlRabbitIT增加一个新投递链用例。OrderCreationFaultProcess恰两条import变化，控制/故障逻辑不变；deploy冻结runner和历史工件未修改。

真实子包扫描装配验证五个Bean各一份，只有意图Service被事务代理；五个写方法仍REQUIRES_NEW，findByUserAndKey无新事务。实际Scheduled注册器恰四个固定延迟任务，目标Bean和1000/30000/5000/5000间隔均保持；扫描测试的TaskScheduler不执行Runnable，数据库/RPC替身零调用。原秒杀死信监听唯一绑定SECKILL_DLQ、MANUAL确认，测试仅关闭自动连接；两个原构造器仍各一次设置Rabbit mandatory。

调用链复盘：`创建Service → reservation意图独立提交 → 原Stock调用 → 主订单事务`；`reservation恢复扫描 → 原租约/原单查询 → 原预扣/补偿事实`；`stockaction扫描 → 原owner认领 → Stock持久事实/同操作键动作 → 完成或UNKNOWN/CONFLICT`；`outbox扫描 → 原超时载荷与身份校验 → broker confirm/returned → 原发布完成证明`；`seckill死信持久化/延迟重投后ACK → 原租约扫描/Stock验真 → 原事件重放及完成CAS`。上述语句、异常/中断和状态条件保持。

| 最终验证范围 | 测试数 | 实际覆盖 |
| --- | ---: | --- |
| Common单测 | 25 | 原公共契约和源码规则 |
| Order单测 | 340 | 原339项及新增1项真实子包扫描/Bean/事务/调度/监听注册验证 |
| OrderIntentStrictInsertMySqlIT | 13 | 原意图严格插入、并发和状态/错误记录矩阵 |
| OrderCreationShardingShadowMySqlIT | 2 | 独立意图与主事务创建、outbox失败补偿的真实分片对照 |
| OrderReplayRecoveryShardingShadowMySqlIT | 4 | 原订单重放、预扣恢复、outbox完成/失败、库存动作租约与结果写入 |
| SeckillRecoveryDispatchMySqlIT | 28 | 原租约认领/验真/发送/完成与异常隔离 |
| SeckillRecoveryJsonMySqlIT | 27 | 原死信原文、数值/指纹、JSON/UTF-8及确认规则 |
| OrderStockActionStateMySqlIT | 4 | 原动作状态与条件更新 |
| OrderTimeoutMySqlRabbitIT | 5 | 原4项真实超时矩阵及新增实际moved dispatcher投递链 |
| ShardingRuntime精确单方法 | 1 | 仅explicitModeUsesProductionMappersAfterBootstrapAndAudit，四恢复任务真实装配及分片调用 |
| 合计 | 449 | 76个类，0失败、0错误、0跳过 |

新增投递链使用独立实验Rabbit的实际publisher confirm/returned及原JSON converter：原已过期事件由迁包后的OrderOutboxDispatcher送入原DLX，outbox持久化为PUBLISHED，实际TimeoutService将订单CANCELLED并写唯一原ROLLBACK操作键；再次扫描不产生新动作。发布确认和消费者提交是异步事实，不宣称两者原子提交或固定先后。该用例没有启动outbox定时器，调度注册另由上述装配测试和Runtime实测保障；没有调用默认Rabbit硬编码夹具。

五类去掉package/import/注释后共7898个token与基线一致；其他引用文件除明确新增测试外正文一致，179份未涉及原源码/资源哈希保持。48个Java文件执行实际格式化：五类、新包说明和新增测试按完整文件，纯引用适配文件仅整理import，避免扩散旧代码样式差异。Order打包、八模块test-compile、diff检查通过；Jar新类型及内部class与最终产物一致，旧五类型及其内部class均无残留，无测试夹具混入。九份旧class先精确备份再移除，未清空target。

本轮所有基线及迁移后测试首次执行均通过，没有新增失败现场。日志类别按新包路径变化，日志消息/等级/参数保持。原72项创建精度大矩阵本轮未全跑，其先前普通恢复首轮未认领原因仍未确认，不用本轮结果覆盖该历史限制。

### 32.4 证据、资源、回退与后续

证据入口 `target/backend-refactor-20261113/order-auxiliary/verification.json`，保留76份最终XML、分组日志、源码/引用对照、格式化与Jar校验、252份本轮native工件。三个class-finish均errors为空且无需保留失败库：CreationShadow创建/删除4个库，ReplayShadow8个，Runtime1个；Runtime只执行约定单方法。

MySQL8.0.46七源表最终精确0行，全部本轮UUID库清理；Rabbit五队列ready/unacked/consumer均为0，归档时两容器healthy。自有容器和网络已移除，mysql_data/rabbit_data两卷保留；没有操作历史失败卷或默认中间件。README原修改逐字节保持，两个旧pycache及忽略证据不入提交。

方案提交/基线 `aa3f2822`，标签 `refactor/order-auxiliary-baseline-20261113`；完成标签 `refactor/order-auxiliary-complete-20261113`。临时index验证逆补丁恢复父提交完整树，真实暂存条目与工作树保持。

回退工具首次仅在路径清单比较处停止：Windows迁移清单使用反斜杠、Git返回正斜杠；统一表示后显式54个非rename路径与暂存区完全一致，再执行逆补丁校验。没有调整业务、测试或放宽暂存文件范围。

```powershell
git revert refactor/order-auxiliary-complete-20261113
```

实际回退后精确清理四个新子包中本轮五类型及内部class、OrderAuxiliaryWiringTest残留，再重新编译恢复后的旧路径类及测试；不全量clean、不回退运行数据。后续依赖按逆序处理。

下一步做Order阶段收尾盘点，核对原方案剩余项、公共类型/契约和完整模块目录，再细化Pay首切片。本轮未跑真实Stock/Product/TC/Nacos、完整商城、进程硬停或性能验证，不宣告全项目重构结束。

## 33. Order 模块收尾与Pay准备（2026-11-13）

### 33.1 本轮范围与基线

基线 `d013d831`，主Agent继续单独执行。Order十个实施切片已覆盖演练命名、HTTP组合委托、查询/创建/状态/消息职责和辅助包分组。只读检查确认Controller/Service无JdbcTemplate或内嵌SQL、生产类无Cm编号命名、无旧OrderController/OrderServiceImpl/空OrderService残留；保留的cm18 URL、Bean名、表名和事务名是已冻结协议标识。

JDK语法树初检发现30项文档缺口（28个显式public/接口/Bean入口、2个字段）及消息配置9处泛化占位注释。本轮补齐这些说明、Feign局部record参数说明，修正可靠性初始化“仅两张表”的过时描述，按原流程补注释。对Order全部生产Java执行既有Google Java Format，保留长字符串原文；只允许注释、排版和import顺序/未使用import整理，不修改变量、方法、字段、注解参数、SQL、配置、依赖或业务语句。

现有Feign内部不可变record继续作为客户端局部传输模型，全部业务Controller/ServiceImpl内已无嵌套DTO/VO/PO；本轮补全客户端及响应record的字段说明，不为目录统一再引入跨服务模型迁移。动态分片与元数据访问保留已评审的JDBC Mapper/PO方案，不把本轮结构完成表述为所有Mapper已改成BaseMapper。全量命名改写和ORM替换不在本轮冻结范围。

本轮以当前Git源码和第32节449项归档报告作为已核实基线，另存原源码指纹和Order目录/契约盘点。现有Java缺少的注释通过源码阅读补充，不生成泛化占位文字；语法树复核显式入口/字段缺口为零。全部生产Java去注释并规范化import后逐token必须相同，resources/POM/测试源码必须不变；本轮不新增镜像实现的测试。

### 33.2 验证、资源及提交

先提交本节并建立 `refactor/order-closeout-baseline-20261113`。完成后运行Common/Order全部现有单测、Order侧StockSeckillFactFeignIT、Pay侧PayOrderStateFeignIT，后两者用本机HTTP与实际客户端/响应类型核对兼容；先以标准八模块test-compile准备相应模块class。Order打包、Google Java Format全模块检查、git diff --check、旧类型/空接口/嵌套业务模型/SQL分层及原协议标识核验共同组成收尾证据。

本轮仅注释与排版，不新建MySQL/Rabbit实验，不重复之前实库矩阵；历史结果与本轮新跑结果分别列出，不累加为一次全链验证。README原有修改、历史失败现场、两个旧pycache及冻结实验runner保持。完成标签 `refactor/order-closeout-complete-20261113` 与模块结构通过点 `refactor/order-complete-20261113`；临时index验证本轮逆补丁恢复父树，Git不回退运行数据。更新模块清单、兼容保留项、已知验证限制和逆序回退索引。

Pay本轮仅盘点并冻结首切片，不同时改业务源码。已确认PayController继承PayServiceImpl、PayService为空；create/success/callback原来共用this锁，创建还有本地事务，后续写拆分须单独评审共享锁与TCC/Legacy/回调事实，不能照搬Order接口形状。首个候选是无事务无锁的GET `/api/payments/orders/{orderNo}`：鉴权先于查询，原Mapper同时按orderNo/userId过滤，完整映射后取第一条，空结果原404；使用现有PayRecordViews保留金额/时间/null字段，不增加Order远端查询或支付驱动。具体实施命令与新基线在下一切片补齐，本轮不把Pay写成已重构。

### 33.3 Order收尾结果与完成口径

本轮为19份生产源码补说明/统一排版，没有修改业务语句。修复30项必需文档缺口（28方法/2成员）、替换9条消息配置占位注释，补齐5个record的参数说明，并修正初始化表数量的过时描述和步骤说明。JDK语法树复查显式public、接口、Bean/消息/定时入口及非record成员的JavaDoc缺口为0；record字段在各record声明说明，隐式生成的构造器/accessor不按手写入口计数。117份生产Java（含包说明）全部通过Google Java Format检查；去注释并规范化import后逐token与本轮基线一致。

| Order模块检查项 | 结果与实际边界 |
| --- | --- |
| 命名与迁移 | 生产类型无Cm编号名；旧混合Controller/实现/空接口及辅助类旧包位置已清除；十个实施切片均有独立Git标签 |
| HTTP与业务契约 | 6个组合式Controller、9条无重复声明路由、10个非空Service接口；9条包含默认开关控制的Seata演练路径，不等于全部环境均启用 |
| 持久化分层 | Controller/Service无JdbcTemplate与嵌入SQL；11份PO保留TableName映射；动态分片及元数据JDBC Mapper按原评审方案保留 |
| 配置与外部标识 | Order resources/POM自模块基线未改；cm18路径/表/Bean/事务名称为兼容标识，不作为漏改类名；Redis/MQ/Feign/金额/时间契约沿各切片核验 |
| 业务规则与事务 | 原身份读取时点、创建/状态共享监视器、REQUIRED/REQUIRES_NEW/查询RR、消息解析/确认、租约和未知结果边界见第23至32节，未通过收尾格式化改动 |
| 规范与复盘 | 必需入口/成员说明补齐，泛化占位说明清除；所有生产Java统一排版，核心调用链已在各切片逐项记录 |
| 验证与资源 | 本轮新跑367项全部通过；前一源码点449项归档逐份校验SHA作为历史基线，两组不累加为一次验收；本轮无新Docker/数据库实验 |
| 回退与遗留 | 每阶段源码回退点可定位；历史失败现场保持；普通恢复首轮未认领原因仍未确认，不借本轮结果宣称修复环境或历史问题 |

以上为已约定的中等深度结构重构完成。Feign内部局部record、JDBC动态分片Mapper及冻结测试/实验工具名称属于明确保留项；没有把它写成全面ORM替换、全部历史工具改名或生产全链验收完成。

本轮新执行范围：

| 范围 | 测试数 | 说明 |
| --- | ---: | --- |
| Common单测 | 25 | 原公共与源码契约 |
| Order单测 | 340 | 当前全部现有单测，不新增镜像实现的测试 |
| StockSeckillFactFeignIT | 1 | 实际Order StockClient与Stock响应类型经本机HTTP，保留缺失/null字段 |
| PayOrderStateFeignIT | 1 | 实际Pay客户端与Order状态入口经本机HTTP，保留路径、身份头、金额与null语义 |
| 合计 | 367 | 70个类，0失败、0错误、0跳过 |

八模块test-compile与Order打包通过，Jar中123个Order生产class均与最终class相同，无旧混合类型、辅助旧包类型、Cm编号生产class或测试夹具。Order/Pay其他365份被跟踪文件逐字节不变，覆盖Order配置/POM/测试以及Pay源码/资源/测试；README原修改保持。本轮未连接真实Stock/Product业务服务或中间件，没有重跑SQL/TC/Nacos、完整商城、硬停或性能场景；此前449项证据仍明确归属于第32节。

### 33.4 模块回退索引与证据

方案基线 `0a528bac`，标签 `refactor/order-closeout-baseline-20261113`。本轮完成标签 `refactor/order-closeout-complete-20261113`，同一源码点建立模块结构通过标签 `refactor/order-complete-20261113`。临时index验证本轮逆补丁恢复父提交完整树，不回退真实工作树或README。仅撤销本轮注释/排版时：

```powershell
git revert refactor/order-closeout-complete-20261113
```

如以后需要逐阶段撤销Order结构改造，先撤销本轮收尾，再按下表自上向下评估；每一步处理后续依赖/方案记录冲突、清理该阶段新class残留并重编译，不把这些索引当成无需核对即可执行的运行数据回滚脚本。

| 逆序切片 | 实施提交 | 完成标签后缀 |
| --- | --- | --- |
| 辅助职责分组 | d013d831 | order-auxiliary-complete-20261113 |
| 超时与旧继承收尾 | 82c3eb49 | order-timeout-complete-20261113 |
| 秒杀消息落单 | 6116cb53 | order-seckill-creation-complete-20261113 |
| 秒杀受理 | 7842d80a | order-seckill-admission-complete-20261113 |
| 状态迁移 | e397e1e4 | order-state-complete-20261113 |
| 普通创建 | e1779d3e | order-creation-complete-20261112 |
| Cart清理协调 | c9da3dfc | order-cart-cleanup-complete-20261111 |
| 详情/支付状态读取 | f430d37a | order-detail-complete-20261110 |
| 列表查询 | 6a961c62 | order-query-complete-20261104 |
| Seata演练命名 | b756d93a | order-seata-complete-20261104 |

全部标签前缀均为 `refactor/`，十个完整SHA已通过Git读回保存到rollback-catalog.json。Git不恢复MySQL/Redis/Rabbit/Seata运行事实、历史失败卷或忽略证据，不使用全量clean/reset演示回滚。

证据入口 `target/backend-refactor-20261113/order-closeout/verification.json`，包含本轮70份XML、模块/路由/PO盘点、语法树缺口前后结果、117文件token对照、全模块格式检查、Jar摘要、历史449报告验证及回退索引。本轮没有新增失败尝试或中间件资源；原Bug台账已补说明修复与验证结果。

### 33.5 Pay首切片准备结论

下一步从只读支付状态查询开始，候选类型为PayQueryController、PayQueryService、PayQueryServiceImpl，承接原GET `/api/payments/orders/{orderNo}`。绑定后先按原位置要求登录，再调用原selectByOrderNoAndUserId；使用PayRecordViews映射所有返回记录后取首条，空列表仍为原404“支付记录不存在”。保持无新增事务、锁、远程调用、协议路由或业务驱动；返回原PayVO字段/金额/时间/null，由Controller包装同样的响应。原共享toView方法仍服务写流程，不为迁移一个GET改写其余方法。

本轮Pay源码指纹未变，也没有运行Pay业务全套测试或创建其回退标签。实施时先补精确调用点/契约测试与当前Git基线；随后再按已记录共享锁、Legacy/TCC、回调持久绑定和恢复事实逐项处理写流程。不得将这份准备结论理解为支付创建/成功/失败/回调的统一拆分授权细节已全部冻结。

## 34. Pay只读支付状态查询（2026-11-13）

### 34.1 范围与冻结契约

基线458efb7c，仍由主Agent单独实施和评审。新增controller.PayQueryController、service.PayQueryService、service.impl.PayQueryServiceImpl；Controller通过构造器组合Service，get方法保留GET `/api/payments/orders/{orderNo}`，先读取AuthContext.requireUserId，再委托getByOrderNo(orderNo,userId)，包装ApiResponse.ok。Service只依赖既有PayRecordMapper，复用PayRecordViews.toView，返回PayVO。

原selectByOrderNoAndUserId同时按orderNo和userId过滤，SQL没有ORDER BY/LIMIT；保留数据库原返回顺序，不宣称“最新支付记录”。仍完整映射所有返回行后取首条，空列表原404/COMMON_NOT_FOUND/“支付记录不存在”；Mapper异常及异常行原失败行为不变。身份必须在Mapper之前读取，不增加订单远端查询、403判断、trim或额外路径校验。保持六响应字段、金额字符串、+08:00 paidAt及显式null，debit_mode不参与GET路由/校验，状态原样返回。

删除旧PayServiceImpl的get方法，不留委托桥。已搜索现有测试/源码，没有直接调用该get的生产或测试调用点；现有构造器、旧PayController继承和其余四条POST暂留。create/success/callback的原this同步监视器、create本地事务及fail无同步全部不变。只读新入口不添加Transactional、锁、模式指标、写操作或恢复动作，也不挂起调用者已有事务。Mapper、PO/VO、映射工具、Feign、配置/依赖、DDL/Redis/MQ契约均不改。

### 34.2 验证与提交策略

先提交方案并建立refactor/pay-query-baseline-20261113。新增HTTP行为契约测试先对原PayController运行，再只替换测试装配至新组合入口，复用同一组断言，覆盖认证前置、身份过滤、原首条/完整映射、404、金额/时间/null、各状态/模式只读返回及Mapper异常。使用实际AuthInterceptor、全局异常处理和公共Jackson配置，Mapper为受控替身；不称作真实数据库或Gateway/Bearer全链测试。

迁移后新增Spring装配/路由/事务检查，核验与旧Controller共存时GET仅一份、四条POST仍由旧Bean处理，新Service无新增事务，查询不等待旧写监视器。运行Common/Pay全部现有*Test，八模块test-compile、Pay打包；旧写流程测试保留原文，无新兼容测试门面。对旧实现删除get后的其余token、所有未涉及源码/资源及README指纹核验，新增/改动Java执行既有Google Java Format及git diff --check；本轮SQL未变，不创建中间件实验或重跑真实TC/TCC资金矩阵。

证据存target/backend-refactor-20261113/pay-query，记录每轮日志/XML、源码基线、格式/Jar及回退校验。完成后单独实施提交和refactor/pay-query-complete-20261113；临时index逆补丁恢复父树，不修改真实工作树演示回退。README原修改、两个旧pycache和忽略证据不入提交，历史失败现场保持；Git不恢复运行数据。

### 34.3 实施结果与验证

新增PayQueryController、PayQueryService和PayQueryServiceImpl，旧实现仅删除15行get入口（377→362行）。调用链为 `HTTP路径绑定 → Controller.requireUserId（AuthInterceptor建立身份上下文） → PayQueryService.getByOrderNo → 原Mapper同时筛选orderNo/userId → PayRecordViews完整映射 → 首条或原404 → ApiResponse.ok → 公共金额序列化`，请求完成后仍由原Interceptor清理身份。新Service只持有Mapper，不依赖Feign、支付模式或恢复组件。

先对旧实际Controller执行新增7项HTTP契约，再将测试工厂改为新组合入口，七项方法体和断言逐token保持。金额以大额且有尾零的十进制字符串验证，paidAt保留毫秒及+08:00；空对象六字段仍显式null。28组状态/模式组合包含现有状态、未来状态、LEGACY/TCC/null/非法模式，查询均只读原样返回。第二行异常仍使整次请求失败，不提前返回第一行；Mapper原异常对象保留，不转成404或成功。无身份/非法身份不访问Mapper，别用户匹配为空仍404而非403，成功/失败后上下文均清理。

新增3项装配验证：真实Spring注入唯一查询Service，启用事务拦截时没有新事务代理；普通查询0次事务，在调用者事务内查询仍保留原事务和绑定资源，仅由调用者开始/提交一次。MVC同时注册新旧Controller，公开支付恰5条路由，GET唯一属于新入口，四条POST仍由旧Controller继承的方法处理；create/success/callback原synchronized和仅create的@Transactional保持。持有旧写入口监视器时另一线程查询可完成。

| 当前验证 | 数量 | 结果 |
| --- | ---: | --- |
| Common全部*Test | 25 | 通过，原公共与源码契约保持 |
| Pay原有全部*Test | 600 | 通过，旧测试源码逐字节保持；含原受控本机HTTP指标与协议路由验证 |
| PayQueryContractTest | 7 | 迁移前/后同一组HTTP行为断言均通过，Mapper替身 |
| PayQueryWiringTest | 3 | Spring注入/事务、路由归属和原写监视器边界通过 |
| 最终合计 | 635 | 65个测试类，0失败、0错误、0跳过 |

旧入口基线632项（64类）另行归档，不与迁移后635项累计。两轮均首次通过，无新增失败现场。旧实现删除get以外语句逐token相同；查询74个token在鉴权/响应边界搬迁和toView委托展开后等价，原Helper继续服务写流程。其余1051份已跟踪后端文件及README原修改逐字节保持，覆盖Mapper/SQL、PO/VO、资源/POM、其他模块及原测试。六份新增/改动Java通过实际Google Java Format检查，八模块test-compile、Pay打包及git diff --check通过；Jar中新增三类型存在，176个Pay生产class与最终classes一致、没有测试类混入。

### 34.4 证据、回退及后续

方案提交346ddecf，基线标签refactor/pay-query-baseline-20261113；实施完成标签refactor/pay-query-complete-20261113。证据入口 `target/backend-refactor-20261113/pay-query/verification.json`，保留前后独立XML、日志、旧源码/契约测试、源码指纹、Jar摘要、格式和临时index回退证明。只撤销本切片时：

```powershell
git revert refactor/pay-query-complete-20261113
```

前后两轮原生metrics工件各5份也已归档；归档工具首次因日志重复runId而在重复copytree处停止，按runId去重并逐文件校验源/已归档SHA后通过，不覆盖不同内容，不改业务/测试或重跑已通过检查。

回退后按确切类名清理三个新生产类型及两个新增测试类（含其内部class）的target残留，再重新编译恢复后的旧get；不全量clean。临时index已验证实施逆补丁恢复父树，真实index/工作树和README保持；后续依赖按逆序评估。Git不恢复运行数据，本轮未创建或修改中间件资源、业务表或历史失败现场。

下一步以支付创建为候选切片，先冻结create与success/callback的共享监视器归属、原本地事务在获取锁之前开始/解锁后提交、幂等键载荷检查顺序及新支付debit_mode选择，再执行提取。Legacy/TCC资金编排、回调持久绑定和恢复职责继续单独规划；旧PayController继承和空PayService目前明确暂留，不宣称Pay模块已完成。当前验证不覆盖真实MySQL、TC/TCC资金链、Gateway Bearer或全商城；不重复或覆盖之前阶段的真实资金矩阵与历史异常结论。

## 35. Pay支付创建与共享写监视器（2026-11-13）

### 35.1 冻结范围与行为

基线35ac1bb0，主Agent单独评审/实施。新增PayCreationController、PayCreationService、PayCreationServiceImpl和单例PayWriteMonitor，提取POST `/api/payments`。请求头Idempotency-Key和PayCreateDTO绑定不变；Controller传入AuthContext::requireUserId，创建Service原REQUIRED事务开始后获取共享监视器，再调用身份supplier，保证未登录请求不提前绕过原事务/锁顺序。Service返回PayVO，Controller提交成功后ApiResponse.ok包装。

原create/success/callback共享this监视器迁为同一Spring PayWriteMonitor；success/callback保留原方法体和完整临界区，只将synchronized方法改为synchronized(writeMonitor)，不新增事务。fail和查询继续不加锁/事务。监视器在Service返回/抛异常时释放，事务提交/回滚在解锁后，不能扩大为提交锁或分布式锁。旧实现字段使用直接构造时独立默认实例以保持原手工测试单对象互斥；生产@Autowired必须注入同一Bean，不能required=false或静态全局锁。两个手工Spring测试上下文补此Bean，普通根包扫描自动发现。

创建顺序保持：身份→原非空/正金额校验→SHA-256(userId+":"+原key)前48位小写hex→按payNo完整映射/首条重放（orderNo/userId/BigDecimal.compareTo一致，否则原409）→原Order.get→null响应/data的404→归属403→PENDING_PAYMENT检查409→金额409→按orderNo完整映射/首条返回→采样+08时间/原UUID主键→newPaymentMode→insert→按payNo重读及原404。保留空白orderNo、金额精度、原key字节、远端非零code但非null data等既有行为，不借重构收紧校验、不引入重试或额外身份/状态检查。

create专属order/payNoFor/now/id辅助方法及模式配置依赖迁入创建实现；剩余写流程的find/findBy/toView和四参数旧构造器暂留，查询仍复用PayRecordViews。不改Mapper/SQL/PO/VO、模式开关、Feign/资金编排、回调绑定、调度、消息或DDL。直接创建支付的PayTccDeliveryIT一处改用真实新Service代理，保留资金断言；旧指标夹具移除已迁走mode字段注入；源码守卫及上轮查询装配测试改指新归属和显式共享锁，保留原行为约束，不新增生产桥或混合测试门面。

### 35.2 验证与回退策略

先提交方案并建立refactor/pay-creation-baseline-20261113。新增创建契约测试先对旧入口验证，再只改装配验证新入口；覆盖认证/参数、key重放冲突、完整映射、原Order错误顺序、订单已有记录、新模式/插入/重读失败、金额时间及HTTP绑定。新装配测试使用真实Spring事务代理与可控屏障，验证事务先于锁/鉴权、commit及rollback在锁外、三个写入口共用一个监视器及fail/查询不受其阻塞。

真实MySQL新用例通过生产Mapper和新事务Service核验创建/幂等重放/LEGACY与TCC持久模式、插入后读取失败整笔回滚，以及同key并发最终至多一行；不把单JVM监视器描述为跨实例幂等保证。独立Compose项目cloud-mall-refactor-pay-creation-20261113，仅mysql，候选宿主19954、子网10.254.236.0/24；先核实无冲突，创建全新卷，按schema.sql复制Pay空源表，测试各自UUID库。失败保留库并归档，成功核对后清理测试库，最终归档后移除自有容器/网络、保留卷，不触碰默认/历史中间件。

运行Common/Pay全部*Test、新MySQL全类及原PayDebitModeM2MySqlIT五个持久模式拒绝方法（不运行其启动子进程/迁移族）；八模块test-compile、Pay打包、Java实际格式化、diff及源码token对照。完整TC/User/Order/Stock资金交付矩阵本轮不运行，PayTccDeliveryIT仅编译适配，不算真实链路通过。证据存target/backend-refactor-20261113/pay-creation；README原修改与两个旧pycache不入提交。实施独立提交及refactor/pay-creation-complete-20261113，临时index逆补丁验证恢复父树；Git不回退运行数据。

实施时核实后的验证调整：原M2五方法在任何建库/连接之前拒绝19954/cloudmall_pay；它的冻结安全门只接受13307/cloudmall_user。该五项标记为本轮环境不适用，不放宽URL门、不转向默认中间件、不把编译或单元结果算作这五项通过。最终验收使用全部单测、实际共享锁/事务测试和新增创建MySQL全类；M2只补新监视器Bean并保持标准编译，持久模式负向实库矩阵保留为验证限制。模式路由业务未修改，原模式/资金单测继续完整执行。

### 35.3 实施与验证结果

新增创建Controller/接口/155行实现及PayWriteMonitor；旧混合实现362→262行，删除create和四个创建专属助手，保留其余写行为。生产创建、成功和回调通过@Autowired共享同一监视器，直接构造旧实现的测试仍有独立对象监视器；没有静态锁、可选注入或新事务装饰。旧PayController/空PayService及旧四参数构造器暂留，待剩余三个写入口迁移后统一清理。

调用链复盘：`MVC头/体绑定 → Controller传身份supplier → Spring REQUIRED开始 → 获取共享监视器 → 身份/参数 → 原key重放 → Order详情/归属/状态/金额 → orderNo重放 → 时间/主键/模式 → INSERT → 原payNo重读 → 释放锁 → Spring提交 → Controller包装响应`。任一业务/Mapper异常沿原默认回滚规则传播，锁在回滚之前释放。成功/回调仍在同一临界区内执行原完整算法，fail与查询无新增锁/事务；不改变Legacy/TCC分流、回调独立提交或恢复状态机。

创建9项契约先对旧实际入口通过，再仅改变装配验证新入口，九项方法体逐token保持。包括原key尾空格和固定48位hash、金额compareTo尺度语义、参数/鉴权先后、键冲突、完整映射、订单错误优先级、非零外层code但非空data的原处理、已有订单支付重放、模式/INSERT/重读异常和HTTP绑定/金额字符串/null。创建411个token及六助手286个token按明确的返回包装/身份supplier/变量改名/映射委托规范化后与基线一致；剩余13个旧方法正文除显式锁外一致。1050份其他已跟踪后端文件与README原修改逐字节保持，6份旧测试仅按本轮归属/装配约定适配。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 原公共规则及创建源码归属守卫 |
| Pay原有全部单测 | 610 | 原模式/资金/回调/指标及上轮查询测试；只适配必要装配 |
| PayCreationContractTest | 9 | 新旧入口同一行为矩阵 |
| PayCreationWiringTest | 2 | 真实Spring代理、线程BLOCKED屏障、共享监视器和事务生命周期 |
| PayCreationMySqlIT | 3 | 真实MySQL/生产Mapper/创建代理，4个成功UUID库 |
| 合计 | 649 | 68类，最终0失败、0错误、0跳过 |

基线644项另行归档，不与最终结果累计。装配测试在主线程持锁时看到创建线程事务已经开始、身份supplier尚未调用；成功/回调也等待同一锁，fail/查询在另一线程完成。创建成功提交和未登录回滚均在锁外。实际MySQL验证LEGACY/TCC新支付字段、按键/订单重放不改原行、INSERT已执行后读故障整笔回滚及原键重试；这里的TCC只指新支付模式持久化，没有启动TC、冻结或扣款。

同key四线程实测为3次成功返回同一payNo、1次原唯一键冲突；最终恰一行，全部事务结束后原键重放成功。该冲突由锁外提交窗口与数据库唯一约束处理，符合保留的原行为；没有加自动重试、吞异常或扩大临界区来制造全成功。

首轮新增验证有10项错误，原619项Pay单测通过：装配2项因AopTestUtils泛型推断选中ReflectionTestUtils的Class重载而ClassCast，改为显式Object目标；新实库3项在断言后工件导出时遇LocalDateTime未注册模块，改用测试本地ObjectMapper的已有时间模块。三库未删除，已dump并保留。M2五项为上文URL拒绝，未执行业务；调整验收范围后重跑全部单测和新实库，最终649项通过。生产代码未因这些测试问题改动；初次错误日志/XML和不完整工件仍归档。

14份新增/改动Java通过实际Google Java Format检查，八模块test-compile、Pay打包及git diff --check通过；Jar的180个Pay生产class与最终编译结果一致，新四类型均存在、无测试类混入。PayTccDeliveryIT原完整交付用例仅改真实创建代理调用并通过编译，未执行其资金矩阵；M2五项持久模式负向实库验证仍明确未通过本轮环境门。

### 35.4 证据、资源、回退与后续

方案提交5a5567ab，基线标签refactor/pay-creation-baseline-20261113；实施完成标签refactor/pay-creation-complete-20261113。证据入口 `target/backend-refactor-20261113/pay-creation/verification.json`，包括基线/首轮失败/最终XML、生产及测试前后对照、线程/实库结果、native事实、dump、格式/Jar和临时index回退证明。

仅本轮独立MySQL8.0.46项目：三个Pay空源表最终0行，四个成功UUID库均删除；首轮三个归档失败库各保留一条已提交测试支付，已导出preserved-first-fixtures.sql。自有容器/网络已移除，mysql_data卷保留；默认数据库、历史失败卷及其他项目没有写入。保留库名称与完整源码SHA记入verification.json。

```powershell
git revert refactor/pay-creation-complete-20261113
```

临时index验证逆补丁恢复父树且真实index保持；实际回退后按本轮四个新生产类型和三个新测试类（含内部class）精确清理target残留、重编译，不全量clean或回退运行数据。后续依赖须先逆序处理。README原修改、两个旧pycache、bug台账与忽略证据不混入提交。

下一步以模拟支付成功入口为候选，先梳理其与回调共享的持久模式核验、Legacy/TCC分流、结果响应映射及支付记录读取，再冻结拆分范围；沿用本轮共享监视器与原异常/状态事实。尚未完成Pay整个模块，本轮不宣称真实TC/User/Order/Stock资金链、Gateway Bearer或全商城验证通过。

## 36. Pay模拟成功入口与共用处理规则（2026-11-13）

### 36.1 范围与兼容基线

基线3d86f278，主Agent单独评审/实施。新增PaySuccessController、PaySuccessService、PaySuccessServiceImpl承接POST `/api/payments/{payNo}/mock-success`；Controller传AuthContext::requireUserId，Service获取原PayWriteMonitor后读取身份。返回PayVO并沿原ApiResponse.ok包装，不新增Transactional、重试、调度或远程调用。创建/回调继续共用同一锁，fail/查询不加锁；调用者已有事务不被主动挂起。

入口顺序冻结为：锁→身份→按payNo完整映射/首条404→归属403→再次查询支付且必须恰一条→读取原意图→精确持久模式规则→原MOCK_SUCCESS指标→SUCCESS直接返回旧视图、FAILED/REFUNDED原409→按原协议调用一次既有编排→结果映射。TCC且存在任意Legacy意图均503；非TCC只接受精确LEGACY，已有意图也必须精确LEGACY。持久SQL null、空串/小写/尾空格不能回落Legacy；TCC入口缺失503、不回落。终态快捷返回仍在模式核验和指标之后；不加首读/次读快照一致性或新业务校验。

包内新增无状态PayProcessingSupport集中原ownedForUser/find/paymentMode/initiate/tccEntry/结果映射，参数显式接受原Mapper/编排依赖，不注册新Bean或事务。旧fail/callback保留入口和方法体顺序，通过私有委托复用这些规则；结果SUCCESS仍重读支付，FAILED/REFUNDED仍PAYMENT_FAILED/409，REFUND_PENDING为原202，其余含null一律原未知202。原私有ownedForUpdate已无调用；随共享读取迁移删除它和不再使用的findBy/toView，不保留死代码或复制模式算法。创建/查询自己的映射与SQL不改。

旧PayController/PayServiceImpl/空接口及构造器暂留给失败/回调。适配BalancePaymentContractTest、PayTccPublicRoutingTest、指标夹具/真实指标上下文、创建/查询装配测试的实际成功入口；不引入混合测试门面。原M2只适配新调用/Bean/源码证据类型，固定13307安全门保持，继续不在本轮隔离环境运行。资金编排Service、Mapper/SQL、配置、PO/VO、HTTP/Feign/JSON、MQ/Redis及现有协议标识不变。

### 36.2 验证与提交

先提交方案并建立refactor/pay-success-baseline-20261113。新增成功HTTP契约先对旧入口运行，再仅替换测试装配复用断言，覆盖身份/归属/首读异常、次读唯一性、模式拒绝、终态指标顺序、Legacy/TCC唯一调用、原结果/异常与成功重读、金额/时间/null。新Spring验证成功Service无事务代理、锁内鉴权/调用、保留调用者事务；上轮创建屏障与路由唯一性测试继续约束三个写入口共享监视器。全部Common/Pay *Test和八模块test-compile、Pay打包、Java实际格式化及diff检查必须通过；核心迁移方法逐token对照并记录调用链。

补充独立PaySuccessMySqlIT验证实际Mapper读出的模式/意图、异常与终态拒绝先于业务驱动、原成功重读。只有编排为受控替身，不代表真实扣款或TCC资金闭环。新Compose项目cloud-mall-refactor-pay-success-20261113，仅MySQL19955、候选子网10.254.235.0/24；先检查无冲突，用全新卷与空Pay源表，每例UUID库。成功核对后删除测试库，失败归档保留；结束归档并移除自有容器/网络，卷保留。默认和历史资源不动，旧M2五项不算本轮通过。

证据入口target/backend-refactor-20261113/pay-success；方案/实施分开提交，完成标签refactor/pay-success-complete-20261113，临时index逆补丁验证恢复父树。README原修改、两个旧pycache及忽略证据不混入提交，Git不恢复运行数据；不运行完整TC/User/Order/Stock交付或性能矩阵。

### 36.3 实施与验证结果

新成功Controller/接口/71行实现已组合委托；79行包内PayProcessingSupport保存六个无状态共用方法。旧混合实现262→175行，删除success路由和原无调用ownedForUpdate及失去用途的findBy/toView；fail/callback方法体逐token保持，私有方法仅精确委托共享规则。旧字段/四参数构造器、继承Controller及空接口暂留，未提前改变失败或回调入口的装配边界。

调用链复盘：`POST绑定 → 传入身份supplier → 获取原PayWriteMonitor → 身份/支付首读/归属 → 支付次读恰一条与意图模式 → MOCK_SUCCESS指标 → 终态判断 → 唯一原Legacy/TCC编排 → SUCCESS重读或原202/409 → 释放锁 → Controller包装`。结果未知/异常不增加重试、退款或切换协议；旧回调仍先claim再执行原编排，最后complete后重读；旧fail仍在原位置走其独立当前读授权Service。支持类不注册Bean、不开事务、不持有监视器，锁仍由入口业务持有。

| 最终执行范围 | 测试数 | 实际覆盖 |
| --- | ---: | --- |
| Common全部单测 | 25 | 原公共及源码约束 |
| Pay原有全部单测 | 621 | 原协议/资金/回调/指标及查询/创建边界，必要调用点适配 |
| PaySuccessContractTest | 8 | 新旧实际HTTP入口同一读取/路由/状态/金额时间/null/异常矩阵 |
| PaySuccessWiringTest | 2 | 真实Spring无新增事务、保留调用者资源、锁内身份读取 |
| PaySuccessMySqlIT | 3 | 真实Mapper/意图SQL、12组拒绝、双协议单次分流/成功重读、未知/异常零改写 |
| 合计 | 659 | 70类，最终0失败、0错误、0跳过 |

旧入口654项基线独立归档，不与最终计数叠加。八项契约方法体在前后装配之间逐token保持；特别保留首次完整映射及归属、次读精确数量、协议先于终态、指标先于终态、SUCCESS重读且不新增重读状态校验、null结果原202与TCC缺失503/no-store。调用者已有事务测试捕获原业务202后仍只提交调用者一次，成功Service/Controller无事务代理；未登录请求在另一线程等待共享监视器后才401。上轮创建屏障继续验证创建、新成功和旧回调共用同一锁，查询/失败仍可独立完成。

MySQL三例使用三个UUID库：第一例12组SQL null、空/小写/尾空格/未知模式及意图冲突均在已SUCCESS状态下返回503，指标/编排零调用、两表完整事实不变；第二例LEGACY/TCC各只调用一次对应替身，由替身受控更新本地投影后验证第三次查询读回金额和+08时间；第三例两协议各覆盖null/UNKNOWN/原异常，保持两表事实和原异常对象、不重试。这里的投影写入由测试替身完成，不能描述为真实资金或订单交付；源表NOT NULL约束未改，仅UUID夹具两列放开以构造SQL null。

成功入口109个token及共享六方法在明确的依赖参数、返回包装、身份supplier、变量名和原映射展开后等价；失败/回调两个入口正文和私有委托核验通过。1056份其他已跟踪后端文件及README原修改逐字节保持，全部七份适配旧测试方法名保留，Balance原两个测试断言按入口类型改名规范化后相同。15份Java实际格式化、八模块test-compile、Pay打包及diff检查通过；Jar184个Pay生产class与最终classes一致，新增四类型存在、无测试类混入。

首次迁移后仅测试编译失败：自动适配误定位BalancePaymentContractTest局部变量而非private factory，已从基线恢复并限定factory范围修改，两个原测试断言逐token复核。源码校验工具首次又把return调用行识别为声明，限定真实方法声明后完成全部等价校验。两项均未导致生产业务修补，首轮编译日志和Bug记录保留；修正装配后654项单测及最终659项分别通过，新实库首次运行全部通过，无新增失败数据库。

### 36.4 证据、回退与后续

方案提交506bb6ad，基线标签refactor/pay-success-baseline-20261113；完成标签refactor/pay-success-complete-20261113。证据入口 `target/backend-refactor-20261113/pay-success/verification.json`，包含前后独立XML、编译失败日志、源码/断言对照、三库原生事实、实际指标工件、Jar摘要及临时index逆补丁证明。

独立MySQL8.0.46的两张空源表最终0行，debit_mode仍NOT NULL；全部三个UUID库通过成功门删除。自有容器/网络已移除，mysql_data卷保留，没有新增失败库；上轮三个失败库及更早历史卷、默认中间件均不操作。

```powershell
git revert refactor/pay-success-complete-20261113
```

临时index已验证逆补丁恢复父树，真实暂存内容及README保持。实际回退后精确清理本轮四个新生产类型和三个新测试类（含内部class）的target残留并重编译，不全量clean或回退运行数据；后续依赖先逆序评估。README、两个旧pycache及忽略工件不入提交。

下一步以模拟支付失败入口为候选，保持其无外层事务/无共享锁、先持久模式再分流、Legacy独立当前读授权与TCC失败入口语义，单独冻结拆分方案。回调仍保留原token/持久绑定/完成顺序，最后再移除旧继承入口。旧M2固定13307矩阵本轮仅编译适配，未运行；新实库结果不替代其原审计矩阵，也不代表真实TC/User/Order/Stock资金、Gateway Bearer、全商城或性能验证。

## 37. Pay模拟失败入口（2026-11-13）

### 37.1 冻结范围与契约

基线49238b39，主Agent单独评审/实施。新增PayFailureController、PayFailureService、PayFailureServiceImpl承接POST `/api/payments/{payNo}/mock-fail`。原方法无请求体绑定，继续忽略可选reason及其他body，不新增DTO校验。Controller在调用业务前requireUserId，再传显式long身份；原入口本来无事务/锁且第一步即鉴权，本切片无需延迟supplier。Service复用已提取PayProcessingSupport，返回PayVO，Controller沿原ApiResponse.ok包装。

顺序保持身份→支付完整首读/404/归属403→支付次读恰一条及意图持久协议核验→MOCK_FAIL指标→唯一原协议入口。TCC调用原failure(payment)，正常返回后直接返回同一个可能被更新的payment视图，不重新查询；Legacy返回原legacyFailureService.fail(payment)的当前事实视图，含null结果语义。未知/退款中/终态拒绝和原异常不改，TCC依赖缺失仍503，不回落，不重试、不增加全局终态短路。

新入口无PayWriteMonitor、synchronized、Transactional或事务挂起；不能把成功入口的锁照搬过来。Legacy仍由原真实REQUIRES_NEW代理先Pay行锁再intent当前锁定读，按既有条件取消并CAS；TCC仍由原真实NOT_SUPPORTED代理暂停调用者事务。两底层类、Mapper/SQL、共用处理规则、HTTP/Feign/JSON、模式/指标配置及所有资金状态机保持。旧实现仅删除fail及失去调用的owned/AuthContext依赖，callback及全部相关私有方法保持；旧继承入口待下一切片处理。

适配原Legacy失败结果、TCC路由、指标夹具/上下文、创建共享锁和查询路由测试，只替换入口及必要装配，保留原案例与断言；不引入生产兼容桥或混合测试门面。

### 37.2 验证、实验及提交

先提交方案及refactor/pay-failure-baseline-20261113。新增失败契约先在旧入口运行，再仅切换装配验证新入口，覆盖鉴权/归属/完整首读、协议门与指标顺序、TCC正常返回同一视图且无重读、Legacy返回当前视图/null、缺失依赖与原异常，以及HTTP忽略body/金额时间/null。运行全部Common/Pay *Test，上轮锁屏障继续证明失败入口在创建/成功/回调锁被持有时可完成，五条公开路由仍唯一归属。

新增PayFailureMySqlIT使用真实Mapper和原LegacyFailureService事务代理：核对取消成功/重放、远端失败与本地CAS异常、外层RR旧快照中的无意图不能覆盖独立当前读看到的新INIT意图，调用者回滚不能撤销已提交的独立失败事务。另以真实PayTccPaymentService.failure方法/NOT_SUPPORTED代理和受控依赖验证外层事务暂停及返回后恢复；不启动TC或资金RPC。新入口无事务代理，读/授权/事务边界均观察实际Spring/MySQL行为。

独立Compose项目cloud-mall-refactor-pay-failure-20261113，仅MySQL19956、候选子网10.254.234.0/24，预检后使用新卷、原schema.sql两张空Pay源表、每例UUID库；失败保留，成功核对后清理。结束归档并移除自有容器/网络、保留卷，不触碰默认或历史资源。旧M2固定13307矩阵不运行，不作为验收通过项；完整TC/User/Order/Stock交付、Gateway Bearer与性能本轮不测。

八模块test-compile、Pay打包、实际Java格式化、diff和原失败方法token对照必须通过，其他业务/配置/测试文件按清单核验。证据存target/backend-refactor-20261113/pay-failure；实施独立提交和refactor/pay-failure-complete-20261113，临时index验证逆补丁恢复父树，Git不恢复运行数据。README原修改、两个旧pycache和忽略工件不入提交。

### 37.3 实施与验证结果

新增失败Controller/接口/60行实现，复用原PayProcessingSupport；旧混合实现175→154行，仅删除fail、仅供其使用的owned及AuthContext import。回调、其他私有方法、字段/构造器的token完全保持，Legacy授权、TCC失败方法、共用规则和所有Mapper/SQL均未修改。新服务不依赖PayWriteMonitor、不声明事务，真实Spring装配中没有外层事务代理。

调用链复盘：`HTTP路径绑定（body仍不绑定）→ Controller.requireUserId → 支付首读/归属 → 次读/持久协议 → MOCK_FAIL指标 → Legacy REQUIRES_NEW当前锁定读/取消/CAS并返回当前视图，或TCC NOT_SUPPORTED处理并返回原被更新视图 → Controller包装`。TCC正常返回后没有第三次查询；Legacy返回对象或null均保留，不用初始视图覆盖，不加全局终态短路或自动重试。原创建/成功/回调共享监视器保持，失败入口在另一线程持有该锁时仍能完成。

| 最终执行范围 | 测试数 | 实际覆盖 |
| --- | ---: | --- |
| Common全部单测 | 25 | 原公共/源码契约 |
| Pay原有全部单测 | 631 | 原协议/资金/回调/指标及此前查询、创建、成功边界 |
| PayFailureContractTest | 7 | 新旧入口同一身份/读取/分流/对象返回/异常/HTTP矩阵 |
| PayFailureMySqlIT | 4 | 真实Mapper、Legacy独立事务、外层旧快照、真实TCC方法事务暂停/恢复 |
| 合计 | 667 | 71类，最终0失败、0错误、0跳过 |

基线663项单独归档，不与最终计数累计。七项新契约测试方法体逐token保留：未登录/无记录/他人归属先于协议与指标、完整首读与次读数量、非法模式/意图冲突、TCC同一可变视图返回、Legacy当前视图/null、原异常及无额外终态捷径。实际MockMvc保留不绑定body的既有行为（包括无效JSON文本不触发新解析）、六字段、金额字符串、时间毫秒/+08和null、503/no-store及认证上下文清理。原六份适配测试的案例方法均保留，原失败结果/TCC路由/指标方法除新入口引用外断言token保持。

实库四例首次执行均通过：Legacy REQUIRES_NEW使用与外层RR不同的实际连接，独立提交FAILED后外层仍读到旧PENDING，外层回滚后独立结果仍FAILED，终态重放不重复取消；Order空响应/业务拒绝/其他异常保持原错误与PENDING，取消返回成功后本地CAS触发器故障仍保留PENDING，移除故障后原请求成功。该测试不声称本地回滚能撤销真实远端取消，Order仅为受控替身。

旧快照例先在外层RR读出无意图，再通过独立连接提交INIT；外层仍见null，但原Legacy当前锁定读在新连接看到INIT并拒绝202、无Order调用，回到外层后同一旧连接/快照恢复。TCC例执行原PayTccPaymentService.failure及真实NOT_SUPPORTED代理：依赖调用时没有活动事务或外层数据源资源，UNKNOWN异常和ROLLEDBACK投影返回后均恢复外层连接/资源；后者返回被修改为FAILED的视图而库内受控样本仍PENDING，证明没有额外重读覆盖。TCC execution/projection为替身，完整启动配置的依赖门在专用测试上下文中用占位Bean隔离，未启动TC或验收真实资金证明。

首次迁移后仅一项原指标重放测试失败：新failureService遗漏在夹具install列表，仍为noop；加入原显式安装列表后原计数断言通过，生产构造器及实际指标上下文无需修改。原失败日志/XML归档，无新增失败数据库。最终失败方法75个token按身份/响应边界和共用调用规范化后等价；1064份其他已跟踪后端文件及README逐字节保持。12份Java实际格式化、八模块test-compile、Pay打包及diff通过，Jar187个Pay生产class与最终classes一致，新三类型存在，无测试类混入。

### 37.4 证据、回退与后续

方案提交4441c370，基线标签refactor/pay-failure-baseline-20261113；完成标签refactor/pay-failure-complete-20261113。证据入口 `target/backend-refactor-20261113/pay-failure/verification.json`，保存基线/首轮失败/最终XML、源码与断言对照、四库实际事务观察与事实、指标工件、Jar摘要和回退证明。

独立MySQL8.0.46两源表最终0行且debit_mode仍NOT NULL；全部四个UUID库按成功门删除，无新增失败库。自有容器/网络已移除，mysql_data卷保留；默认中间件及历史失败现场均未操作。

```powershell
git revert refactor/pay-failure-complete-20261113
```

临时index验证逆补丁恢复父树，真实暂存内容和README保持；实际回退后精确清理本轮三个新生产类型、两个新测试类（含内部class）的target残留并重编译，不全量clean，不恢复运行数据。后续依赖先逆序处理。README、两个旧pycache及忽略工件不入提交。

下一步提取回调入口，先冻结原token校验、共享监视器、独立claim绑定与complete/重读时序，再移除仅剩回调的旧继承Controller/混合实现/空接口。Pay整体重构尚未结束；旧M2固定环境矩阵、完整TC/User/Order/Stock资金交付、Gateway Bearer及性能本轮未运行。
