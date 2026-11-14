# CloudMall 后端逐模块重构方案书

日期为 2026-11-04。方案责任人为 `pm_arch`，实施责任人为 `backend`，验证责任人为 `qa` 与主协调 Agent。

用户已确认采用中等深度重构，重点为规范命名、理顺分层、按业务职责组织代码，并在实施过程中通过 Git 保存可回退节点。当前已完成 Common 契约盘点及 Cart、Gateway、Product、User、Stock、Order、Pay 本轮中等深度结构重构；Pay公开入口、Legacy/回调日志及TCC职责分组已完成范围收尾。已完成八模块统一离线、两项本机HTTP契约、Cart/User/Product/Stock独立MySQL/Redis数据回归、Order/Pay持久事务与真实Rabbit消息回归、Product常规内容索引有限MySQL/Rabbit/ES同步回归、User LocalTCC/官方Fence、Pay/User派发桥、双资源receipt、coordinator一次性编排及指定进程/定时自动恢复的有限回归。交付四服务夹具的显式环境、证据来源及现有12项有限真实交付回归已验证；完整商城资金链与剩余入口仍需按范围核定，尚未宣称全链路与全部历史实验验收完成。全局复核确认结构目标已达到，但Common/Product/User/Stock仍有47个文件的规范收尾，尚不宣称全部规范通过；详见backend_refactoring_acceptance.md及文末第60节。

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
| Pay | 本轮结构重构完成 | 五公开用例、旧继承清理、辅助/TCC职责分组及收尾复核完成，见第34至48节；真实TC资金/全商城/性能验证范围单独保留 |

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

## 38. Pay回调与旧继承入口清理（2026-11-13）

### 38.1 范围与冻结顺序

基线1f555de5，由主Agent单独评审/实施。新增PayCallbackController、PayCallbackService、PayCallbackServiceImpl承接POST `/api/payments/callback`；保留可选X-Internal-Callback-Token头和必需PayCallbackDTO请求体，响应仍ApiResponse.ok/原PayVO。原cloudmall.internal.callback-token配置及默认值不变，放入Service构造器参数；不使用AuthContext替换payload.userId，不改DTO字段/JSON或网关规则。

原共享PayWriteMonitor仍包围token校验、参数/归属/模式/金额检查、指标、claim、业务、complete和最后重读；HTTP绑定在加锁前，token在锁内且先于参数检查。callbackId为null/空白时取原payNo，其他值逐字保留。模式优先于金额，金额仍compareTo；原外层不新增事务，不主动挂起调用者事务。

claim仍在终态检查/编排之前，以原PayCallbackReceiptService.REQUIRES_NEW独立提交完整绑定；成功方向FAILED/REFUNDED仍在claim后409，SUCCESS不重复编排但仍claim/complete。非SUCCESS编排结果沿PayProcessingSupport映射，异常/202/409不得执行complete。失败方向仍进入原TCC failure或Legacy独立授权；complete沿原REQUIRES_NEW校验锁定支付终态并CAS，最后才重读支付。complete失败不撤销已提交claim或业务事实，末尾读取失败不撤销已完成日志；不新增重试/补偿/新事务/状态机。

删除PayController、PayServiceImpl及空PayService；不保留桥、无用四参数构造器或只为兼容字段注入而保留的依赖。新业务复用既有PayProcessingSupport，原回调绑定/资金编排、Mapper/SQL/PO、其他业务Service与配置保持。适配六组原单测/装配夹具，指标断言转向实际回调Service；五公开路由各自唯一，不再有Controller继承业务实现。

发现PayTccPublicEntryIT的legacyFailureCannotMissIntentCommittedAfterItsOldSnapshot仍将失败首读屏障注入旧PayController字段；失败入口已拆走后该hook不再处于调用链。删除旧类时将它精确改指PayFailureServiceImpl，原屏障/断言/恢复逻辑不变；此全链IT本轮仅编译，不宣称真实Gateway/TC链已重跑。其他未评审历史runner及既有证据名称保持。

### 38.2 验证、实验及回退

先提交方案并建立refactor/pay-callback-baseline-20261113。新增HTTP回调契约先验证旧入口，再仅替换装配复用断言，覆盖token/参数/身份来源、模式与金额优先级、callbackId原值/回退、claim→编排→complete→读取顺序、终态/未知/异常、两协议两方向及HTTP绑定/金额时间/null。原共享锁屏障改到新回调Service并保留等待关系；真实Spring确认外层无事务代理及五条路由唯一。

新增PayCallbackMySqlIT，以生产Mapper/实际Receipt及Legacy失败代理验证独立claim在未知/拒绝后持久存在、全局callbackId方向/载荷冲突、业务成功但complete故障、complete提交但末读故障、原键重放、Legacy失败与同方向重放，以及外层RR回滚不撤销claim/complete。业务成功/TCC编排为受控替身，不声称完整资金交付或TC证明；原Receipt类全部语句不改。

新Compose项目cloud-mall-refactor-pay-callback-20261113，仅MySQL19957、候选子网10.254.233.0/24，预检后用全新卷和原schema.sql三张Pay空源表，每例UUID库成功才清理、失败保留。结束归档后移除自有容器/网络，卷保留，默认/历史资源不动。运行全部Common/Pay *Test、新实库全类、八模块test-compile和Pay打包；不运行旧M2、完整Gateway/TC/User/Order/Stock或性能矩阵。

实际格式化所有新增/必要改动Java，核验迁移body token、旧夹具断言、未涉及文件和README指纹；精确备份并删除旧三类型class（及其内部class），不全量clean，以Jar和源码扫描确认旧类型无残留。证据target/backend-refactor-20261113/pay-callback，实施独立提交及refactor/pay-callback-complete-20261113；临时index逆补丁恢复父树。README原修改、两个旧pycache和忽略证据不入提交，Git不恢复运行数据。后续盘点Pay辅助组件及剩余命名/分组，不以本切片代表Pay全模块结束。

### 38.3 实施与验证结果

新增回调Controller/接口/119行实现，保留原凭证键/默认值、头/体绑定和共享临界区；删除旧PayController、154行PayServiceImpl及空PayService，不保留兼容桥或无用构造器。原六个私有委托直接调用已有PayProcessingSupport，原Receipt、Legacy/TCC资金/授权服务及Mapper/配置未改。五条公开支付路由分别归查询、创建、成功、失败、回调Controller，实际MVC同时注册无重复；Pay Controller不再继承Service实现。

调用链复盘：`HTTP绑定 → Service共享锁 → token → 参数/原callbackId或payNo回退 → payload身份归属 → 持久模式 → 金额compareTo → CALLBACK指标 → Receipt.claim独立提交 → 原终态/协议处理 → Receipt.complete独立提交 → 原支付重读 → 解锁 → Controller包装`。外层没有新事务或AuthContext身份替换；未知/拒绝/异常沿原位置退出，不执行后续complete。失败方向仍复用原TCC/Legacy失败处理，成功终态仍执行claim/complete而不重复编排。

| 最终执行范围 | 测试数 | 实际覆盖 |
| --- | ---: | --- |
| Common全部单测 | 25 | 原公共与源码契约 |
| Pay原有全部单测 | 638 | 原资金/协议/Receipt/指标及此前入口边界，必要装配适配 |
| PayCallbackContractTest | 8 | 新旧入口完全相同的HTTP/步骤/参数/结果断言 |
| PayCallbackMySqlIT | 4 | 真实Mapper、Receipt/Legacy事务代理、持久绑定、故障/重放、外层RR回滚 |
| 合计 | 675 | 72类，最终0失败、0错误、0跳过 |

基线671项、迁移后671项单测及最终675项分别归档，三轮均首次通过，不叠加计数。八项新契约方法体逐token保持，原七份适配测试案例名全部保留；原Legacy结果/TCC路由/指标及全链hook方法的断言按明确入口/依赖归属变化规范化后保持。覆盖原token先于参数、绑定先于token、payload身份独立于请求会话、callbackId空白回退/非空逐字保留、模式先于金额、claim先于终态拒绝、两协议成功/失败方向、未知/失败结果无complete、原异常及金额/时间/null。无效JSON仍由原全局处理产生既有500，本轮没有顺带更改参数错误策略。

原创建屏障在新回调Service上继续证明共享监视器唯一、token校验前等待该锁、失败/查询不受阻塞；新回调外层无事务代理。MySQL四例首次全部通过：未知结果保留RECEIVED_SUCCESS，方向/冻结金额/全局callbackId复用冲突拒绝且不追加业务副作用，FAILED支付的成功回调仍先持久claim再409；业务替身提交SUCCESS但complete触发器失败时保留RECEIVED_SUCCESS，随后complete成功而末读故障时日志已SUCCESS/processed_at非null，原键重放不再次编排。

真实Legacy失败回调按claim→独立取消/CAS→complete→读取形成FAILED日志，同方向/金额尺度不同重放不重复取消且原冻结payload保持。外层RR快照始终看不到本次新日志，但claim/complete各使用不同于外层且彼此不同的实际连接提交，外层回滚后外部连接仍看到SUCCESS；没有把本地事务描述为可撤销远端资金或Order事实。成功/TCC编排及Order仍为替身，完整资金/网关/TC链未执行。

回调338个token在明确共用依赖调用、响应包装迁移后等价，凭证配置/头体绑定准确保持。1066份其他已跟踪后端文件及README原修改逐字节保持，覆盖原Receipt/资金/SQL/配置及其他模块。12份新增/改动Java实际格式化，八模块test-compile、Pay打包和diff检查通过；精确备份/移除3个旧class，不全量clean。Jar187个Pay生产class与最终classes一致，新三类型存在，旧三类型及测试夹具均无残留。

旧PayTccPublicEntryIT的失败首读屏障已改指实际PayFailureServiceImpl，除局部变量及目标类型外原方法token保持，完整文件编译通过；其真实Gateway/TC交付测试本轮未重跑，不能把这个夹具修正写成全链回归完成。本轮没有新增测试失败、失败库或环境问题。

### 38.4 证据、回退与后续

方案提交a07b8201，基线标签refactor/pay-callback-baseline-20261113；完成标签refactor/pay-callback-complete-20261113。证据入口 `target/backend-refactor-20261113/pay-callback/verification.json`，包含基线/最终独立XML、源码/断言对照、四库原生事实与事务连接观察、指标工件、旧class备份、Jar摘要和回退证明。

独立MySQL8.0.46三源表最终0行，全部四个UUID库按成功门清理，无新增失败库。自有容器/网络已移除，mysql_data卷保留；默认数据库、此前失败卷及其他项目保持。

```powershell
git revert refactor/pay-callback-complete-20261113
```

临时index验证逆补丁恢复父树，真实index及README保持；删除/新增路径按no-renames清单精确核验。实际回退后清理三个新生产类型及两个新增测试类（含内部class）的target残留，再重编译恢复的旧三类型；不全量clean或回退运行数据，后续依赖先逆序处理。README原修改、两个旧pycache及忽略工件不入提交。

下一步盘点Pay辅助组件的职责、命名和包分组，优先识别Legacy恢复、TCC编排/交付/取消、只读取证的边界与Bean/反射引用，再冻结最小迁移清单。五个公开入口已拆分不等于整个Pay模块收尾；旧M2固定矩阵、完整Gateway/TC/User/Order/Stock资金交付和性能本轮未运行。

## 39. Pay辅助职责盘点与Legacy/回调收据分组（2026-11-13）

### 39.1 盘点与本轮范围

基线9339a084，主Agent单独评审/实施。Pay当前service.impl根包有48个生产类型（不计package-info），生产声明无Cm数字类名；问题主要是职责混排，不为已有语义类名再造新名。盘点分为：五个公开入口实现与共享规则/监视器7类；Legacy意图/恢复/失败3类；公开回调收据/恢复2类；TCC执行/收据/交付/取消等24类；本地及外部只读取证12类。后两组有交叉调用、条件Bean与显式DependsOn，后续分别冻结迁移范围，本轮不移动。

| 本轮类型 | 新包 | 业务职责 |
| --- | --- | --- |
| PayConsistencyService | service.impl.legacy | 原Legacy一致性恢复、租约处理及三个独立短事务模板 |
| PayDebitIntentService | service.impl.legacy | 原远端扣款前独立持久意图与锁定载荷核验 |
| PayLegacyFailureService | service.impl.legacy | 原独立当前读失败授权与取消/CAS |
| PayCallbackReceiptService | service.impl.callback | 原公开回调claim/complete/recoverReceived独立事务 |
| PayCallbackReceiptRecoveryScheduler | service.impl.callback | 原有界游标轮转，按已确认支付终态完成回调收据 |

保持原类名及默认Bean名payConsistencyService/payDebitIntentService/payLegacyFailureService/payCallbackReceiptService/payCallbackReceiptRecoveryScheduler；不增加兼容类型/空接口。构造器、成员、方法/注解、SQL/状态机、异常、指标和配置键均保持；只改package/import及必要全限定引用，补充两子包及顶层包说明。Pay启动类的根包扫描覆盖新子包，无需改配置。

五个原REQUIRES_NEW入口（ensureIntent、fail、claim、complete、recoverReceived）保持；Consistency无声明式外层事务，其terminal/stage/retry三个TransactionTemplate仍REQUIRES_NEW且不包含RPC。两处recoverDue仍用同一cloudmall.pay.recovery-interval-ms默认5000ms，扫描50条/游标/迟到CAS/单条异常隔离不变，不新增调度。迁包后Consistency与ReceiptRecoveryScheduler的日志类别跟随新FQN，日志等级/模板/参数不变。

已检查配置、部署脚本及Common守卫，没有这五个旧FQN的非Java运行依赖。适配生产调用方、普通import/通配符装配及测试全限定引用，保留历史日志/规格/runner名称；旧M2固定13307安全门和冻结部署runner不修改或执行。原Java测试只做地址适配，例外是已有PayCallbackMySqlIT补一项真实恢复调用及显式观察模式。

### 39.2 验证、实验与提交策略

先提交本节并建立refactor/pay-legacy-callback-baseline-20261113，运行Common/Pay全部*Test基线。新增PayAuxiliaryWiringTest实际扫描两新子包，核验五个原Bean名/代理、五个REQUIRES_NEW、三个原短事务模板、恰两项调度及默认/覆盖间隔；使用不执行Runnable的TaskScheduler替身，避免调度误触业务。所有数据库/Feign替身在装配检查中零调用。

迁移后运行全部单测、PayFailureMySqlIT全4项、PayCallbackMySqlIT原4项及新增1项实际迁包Scheduler→Receipt.recoverReceived→SQL日志完成/重复扫描，用原成功门保留/清理UUID库。新增用例显式期望无共享监视器，候选查询无事务、收据恢复独立事务；不改变原四项回调测试对共享锁和日志事务的断言。

另只运行既有PayLegacyRefundLedgerMySqlIT三项：terminalPairFailureRollsBackPaymentAfterIntentFinishFault、healthyTerminalPairCommitsOnOneConnectionWithRpcOutside、actualCallerTransactionRemainsUsableWithoutRecoveryEffects，以真实迁包Consistency验证终态两写回滚/提交及调用者事务拒绝；其他数百项和完整资金交付不运行。该原夹具通过cloudmall.pay.mysql.test.url支持独立地址，保持其既有池/源表指纹及AfterAll owned库清理规则，失败日志/XML保留，不宣称该旧夹具会保留失败数据库；不改原清理逻辑。

独立Compose项目cloud-mall-refactor-pay-legacy-callback-20261113，仅MySQL19958、候选子网10.254.232.0/24，预检后新卷和原三张Pay空源表，不复用历史卷。结束归档后移除自有容器/网络，卷保留。对迁移五类去package/import/注释逐token比较，其余引用适配文件正文必须相同（除新测试/明确夹具扩展）；未涉及后端文件与README指纹保持。Java使用既有Google Java Format，纯引用适配仅整理imports，完整格式化迁移类和新/扩展测试；八模块test-compile、Pay打包、Jar新旧类及git diff --check通过后提交。

证据target/backend-refactor-20261113/pay-legacy-callback，精确备份/清理旧五类型及内部class，不全量clean。完成标签refactor/pay-legacy-callback-complete-20261113，临时index按no-renames清单逆补丁恢复父树。README、两个旧pycache和忽略工件不入提交；Git不回退运行数据。后续优先盘点TCC只读取证分组，再处理剩余写编排，不以本轮分组宣告Pay结束。

### 39.3 实施与验证结果

五类已按清单进入legacy/callback子包，补齐两子包及顶层说明；48类型盘点落盘，impl根包剩43类。五个原类名/Bean名不变，没有兼容副本、新接口或算法拆解。五类去package/import/注释后共7781个token与基线一致；29份引用文件除声明地址及import外正文保持，唯一明确扩展为PayCallbackMySqlIT新增恢复用例及观察模式，其原四个测试方法体保持。1043份其他已跟踪后端文件及README原修改逐字节保持。

实际子包扫描在默认5000ms和覆盖7000ms两个上下文均找到恰五个原Bean。Intent、LegacyFailure、Receipt为事务代理，其ensureIntent/fail/claim/complete/recoverReceived仍恰五个REQUIRES_NEW；Consistency/Scheduler无新声明式事务代理，三个原TransactionTemplate均仍REQUIRES_NEW。注册器恰两项recoverDue固定延迟任务，按Bean身份区分，均使用同一原配置键和当前间隔。测试TaskScheduler不执行任务，数据库/Feign/事务替身零调用。

调用链复盘：`入口 → legacy意图独立提交 → 原租约/远端事实 → 原本地阶段/终态短事务`；`失败入口 → legacy当前读授权 → 原取消/CAS`；`回调入口 → callback收据claim/complete独立事务`；`收据恢复扫描 → 原有界候选/游标 → 另一Bean的recoverReceived代理 → 支付/日志当前锁定读与完成CAS`。上述状态/锁序/租约/单条异常隔离与调用者上下文门保持。仅两类Logger的类别按新包改变，消息、等级和参数不变。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 原公共与源码规则 |
| Pay原有全部单测 | 646 | 原入口/协议/资金/收据/恢复/指标矩阵，仅类型引用适配 |
| PayAuxiliaryWiringTest | 1 | 实际扫描两子包、五Bean、事务/三个模板及两调度的默认/覆盖间隔 |
| PayFailureMySqlIT | 4 | 迁包后真实Legacy失败授权、独立事务及原TCC边界 |
| PayCallbackMySqlIT | 5 | 原四项及新增实际迁包恢复扫描→Receipt代理→SQL日志完成/重复扫描 |
| PayLegacyRefundLedgerMySqlIT精确三方法 | 3 | 原终态两写故障回滚、同连接提交/RPC在事务外、调用者事务拒绝 |
| 合计 | 684 | 75类，0失败、0错误、0跳过 |

基线671项单独归档，不与最终计数叠加。本轮所有测试首次通过。新增恢复实库例先用实际claim留下RECEIVED_SUCCESS，释放原回调锁后由迁包Scheduler扫描并通过真实Receipt事务代理完成为SUCCESS；再次扫描不重复CAS，资金/Order替身零调用。候选查询无事务，收据恢复有独立事务且不持原回调监视器；夹具默认仍保留原四项HTTP回调对锁与事务的严格要求。

原Consistency实库三方法完整执行其既有循环和断言，不把只跑三方法表述为整类大矩阵通过。其原源表指纹前后相同，owned池/源池关闭，原AfterAll确认UUID库删除；其他User/Order行为仍为替身，没有真实资金RPC。本轮没有新增失败数据库或环境问题。

38份Java按约定执行格式化并检查：纯引用文件仅整理import，迁移类/包说明/新测试及明确扩展测试完整格式化，未扩散旧大文件正文排版。八模块test-compile、Pay打包和diff通过；Jar187个Pay生产class与最终classes一致，新五类型位置存在、旧包五类型及内部class无残留，无测试夹具混入。旧五class均先备份后精确移除，未清空target。

### 39.4 证据、回退与后续

方案提交d4a02ae8，基线标签refactor/pay-legacy-callback-baseline-20261113；完成标签refactor/pay-legacy-callback-complete-20261113。证据入口 `target/backend-refactor-20261113/pay-legacy-callback/verification.json`，包含48类盘点/分组、前后XML、源码/引用对照、原生实库事实、精确Legacy三方法与清理日志、格式/Jar及临时index回退证明。

独立MySQL8.0.46的三源表最终0行，Failure四库、Callback五库、原Legacy矩阵一库共10个测试库均删除，自有容器/网络已移除、mysql_data卷保留；默认中间件及历史失败卷均未操作。无新增失败现场，README、两个旧pycache和忽略工件保持不入提交。

```powershell
git revert refactor/pay-legacy-callback-complete-20261113
```

临时index按no-renames完整路径清单逆补丁恢复父树，真实暂存内容与README保持。实际回退后精确清理本轮两新子包五类型及新增装配测试class（含内部class），重编译恢复旧路径；不全量clean、不回退运行数据，后续依赖先逆序处理。

下一步以12个TCC只读取证组件为候选，先核验跨类/包可见性、入口/ReadService及条件装配，再冻结最小迁移清单；24个TCC写编排组件继续另行安排。公共入口/shared规则与监视器留在impl顶层。Pay整体尚未收尾，本轮未运行旧M2固定环境、全量Legacy大矩阵、真实TC/User/Order/Stock、全商城或性能验证。

## 40. Pay TCC只读取证职责分组（2026-11-13）

### 40.1 范围与兼容基线

从c0e51687继续，本会话按用户要求由主Agent独立评审、实现、验证。将以下12类整体迁入`service.impl.evidence`，保留类名、默认Bean名、构造器和可见性：PayTccReconciliationService、PayTccReconciliationReadService、PayTccDeliveryEvidenceService、PayTccDeliveryEvidenceReadService、PayTccOrderEvidenceService、PayTccStockEvidenceService、PayTccUserEvidenceService、PayTccUserAccountEvidenceService、PayTccUserFrozenOwnershipEvidenceService、PayTccUserLinkedRefundEvidenceService、PayTccUserPaymentLedgerEvidenceService、PayTccUserTerminalFrozenOwnershipEvidenceService。

12类内部依赖闭合；Account的verify/verifyLinkedRefundBase及LinkedRefund的verify为包内纯校验，随调用者一起迁移，不扩大public接口。PayTccExecutionEvidence参与写入授权，继续留在写编排范围。生产引用为10个原Controller，另20份既有测试仅适配import/FQN；Controller、Mapper、DTO/VO、Feign与SQL不迁移。增加包说明，impl根包由43类减至31类。无新接口、旧地址兼容副本或业务逻辑合并。

所有12类原本均为无条件@Service；默认关闭的是入口配置开关，并非Bean条件装配。原本地门按开关、凭证/caller、payNo、Spring事务/Seata XID校验；各扩展入口的独立开关顺序保持原样，不能统一调整。两个ReadService的三个public方法仍为REQUIRED、readOnly、REPEATABLE_READ；Delivery读事务内通过另一Bean的localReader.capture加入同一快照。其余入口无新增事务；远端GET位于两次独立本地快照之间，AuthContext清理/恢复、异常后第二次采集、变化优先级、原生JSON字段校验及automaticActionsAllowed=false不变。不触发写入、恢复、TC、MQ或重试。仅两个审计Logger类别随新FQN变化，日志模板/等级/参数不变。

### 40.2 验证、实验与提交策略

先提交本节并建立`refactor/pay-tcc-evidence-baseline-20261113`。迁移前运行Common/Pay全部*Test基线；迁移后运行全部*Test并新增实际子包扫描测试，检查恰12个原Bean、恰两个只读事务代理/三个RR方法及各默认禁用门，禁用请求不接触Mapper/Feign/事务管理器。

运行既有10类完整实库矩阵：PayTccReconciliationMySqlIT、PayTccDeliveryEvidenceMySqlIT、PayTccOrderEvidenceMySqlIT、PayTccStockEvidenceMySqlIT、PayTccUserEvidenceMySqlIT、PayTccUserAccountEvidenceMySqlIT、PayTccUserFrozenOwnershipEvidenceMySqlIT、PayTccUserLinkedRefundEvidenceMySqlIT、PayTccUserPaymentLedgerEvidenceMySqlIT、PayTccUserTerminalFrozenOwnershipEvidenceMySqlIT。现有夹具覆盖单连接RR、独立写入不混快照、两次Pay采集、真实Feign GET/本地HTTP适配、跨服务源表指纹、凭证/上下文隔离、无写与有界证据。它们装配实际User/Order/Stock读取实现与独立库，不代表部署完整微服务、Nacos、Gateway或真实资金/TC流程。旧夹具AfterAll会删除owned库（包括失败时），保留日志/XML；不改其清理逻辑或宣称保留失败库。

独立Compose项目`cloud-mall-refactor-pay-tcc-evidence-20261113`，MySQL候选19959/10.254.231.0/24，预检后全新卷；从现有schema.sql精确复制所需空源表，不导入业务数据、不触碰默认实例。结束归档并移除自有容器/网络，卷保留。八模块test-compile先生成跨模块反射夹具需要的标准classes，再执行矩阵；Pay打包、Jar新旧类路径检查、精确清除旧12类class、格式化及diff检查均必须通过。

源码去package/import/注释后逐token比较12类和所有引用文件；其余后端及README按字节校验。纯引用文件仅整理imports；迁移类、包说明、新测试完整格式化。证据位于`target/backend-refactor-20261113/pay-tcc-evidence`。完成提交及`refactor/pay-tcc-evidence-complete-20261113`标签前，在临时index逆补丁验证恢复父树；只提交本节方案、精确迁移/引用文件和新测试，不包含README、旧pycache或忽略工件。后续审查24个TCC写组件，Pay模块尚不宣告完成。

### 40.3 实施与验证结果

12类已整体迁入evidence，原Bean名、构造器、包内校验可见性和业务语句保持。30份引用文件（10个Controller、20份测试）只适配import/FQN；新增PayTccEvidenceWiringTest与包说明，impl根包剩31类。去package/import/注释后12类共17635个token与基线完全一致，原20份测试正文不变；1038份其他已跟踪后端文件及README按字节保持。另核对79份已跟踪非Java运行文件，没有旧FQN依赖。

实际子包扫描验证默认配置及所有诊断开关显式开启两个上下文：恰12个原Bean、恰两个事务代理和三个REQUIRED/readOnly/RR方法；默认关闭返回503、开启后错误凭证返回403，所有Mapper、Feign及事务管理器零调用。各入口未增加事务，Account/LinkedRefund包内静态校验继续闭合在本包。

核心调用链复盘：`内部Controller → 原独立开关与共享入口门 → ReadService代理 → 有界无锁Mapper读取`；交付查询在外层RR中经localReader代理加入同一连接；远端取证为`第一次Pay RR capture → 事务外GET（隔离并恢复AuthContext）→ 第二次Pay RR capture → 变化优先/原生字段校验`。诊断报告始终不授权资金动作，读组件未引入写Store、恢复器或调度器。逐行复核本轮包声明、引用、包内依赖及新装配测试，原算法另以token对照与既有矩阵验证。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 原公共及源码守卫 |
| Pay原有全部单测 | 647 | 原入口/协议/取证/资金/恢复/指标矩阵 |
| PayTccEvidenceWiringTest | 1 | 实际扫描、原Bean、事务代理及默认/开启后拒绝门 |
| PayTccReconciliationMySqlIT / DeliveryEvidenceMySqlIT | 7 / 9 | 六/八来源同连接RR、并发写不混快照、所有原事实不变 |
| PayTccOrderEvidenceMySqlIT / StockEvidenceMySqlIT | 9 / 6 | 实际GET及原实现读取、源表指纹、上下文隔离、变化优先 |
| PayTccUserEvidenceMySqlIT / UserAccountEvidenceMySqlIT | 9 / 9 | 实际User读取、凭证、原账户观察、未授权动作 |
| PayTccUserFrozenOwnershipEvidenceMySqlIT / UserLinkedRefundEvidenceMySqlIT | 8 / 8 | 有界集合、独立并发写、冻结归属与关联退款观察 |
| PayTccUserPaymentLedgerEvidenceMySqlIT / UserTerminalFrozenOwnershipEvidenceMySqlIT | 8 / 7 | 同支付账本、终态归属、上下文恢复及无副作用 |
| 合计 | 753 | 83类，0失败、0错误、0跳过；其中实库80项 |

迁移前672项基线单独归档，不与最终累计。编译与最终测试首次通过；迁移清单检查在编译前拦截了3份通配符import遗漏，补明确新import后30份正文等价，原断言未改。实验DDL准备时识别订单item模板使用LIKE，按原schema依赖顺序复制基础表，不改生产DDL。45份Java通过约定完整/仅import格式化及检查；八模块test-compile、Pay打包与diff通过。Jar187个Pay生产class与最终classes逐字节一致，新12类型存在、旧路径与测试夹具无残留；旧12个class已备份并精确移除，未全量clean。

### 40.4 证据、回退与后续

方案提交f75b2f4e，基线标签`refactor/pay-tcc-evidence-baseline-20261113`，完成标签`refactor/pay-tcc-evidence-complete-20261113`。证据入口`target/backend-refactor-20261113/pay-tcc-evidence/verification.json`，含基线/最终XML、token及文件指纹、格式/编译/Jar、源表检查、数据库创建/删除事件和临时index回退证明。

独立MySQL8.0.46的Pay/User/Order/Stock共11张源表最终均0行，10类夹具创建的18个owned库全部删除，服务端binlog的18对CREATE/DROP事件与最终schema清单互证；各类原AfterAll源表checksum断言通过，HTTP监听与loader由夹具原逻辑关闭。本轮无测试失败现场，历史失败卷未触碰。归档初次尝试镜像缺失的mysqlbinlog命令，外层脚本未检查退出码而提前down；卷保留后只恢复本轮实例补存只读事实，改用SHOW BINLOG EVENTS并在归档成功后才down。问题与修复记录在原bugfix_log.md，未改业务或测试。最终自有容器/网络移除、mysql_data卷保留，README及旧pycache不入提交。

```powershell
git revert refactor/pay-tcc-evidence-complete-20261113
```

临时index用no-renames完整路径逆补丁恢复父树，真实index与README保持。实际回退后仅精确清理evidence迁入的12类型及新增装配测试class（含内部class），重编译恢复旧包路径；不全量clean、不回退运行数据，后续依赖先逆序回退。

下一步审查24个TCC写组件的回执、执行/恢复和交付/取消职责及跨包可见性，再冻结下一组最小迁移。Pay整体仍未收尾，本轮未部署完整Gateway/Nacos/TC链路、运行资金写入闭环或压测。

## 41. Pay TCC派发桥职责分组（2026-11-13）

### 41.1 范围与兼容基线

从57534d8b继续，由主Agent按本会话单Agent约定完成评审、实现和验证。盘点剩余24个TCC写组件后，先将PayTccBridgeServiceImpl、PayTccDispatchPersistence整体迁入`service.impl.dispatch`：两类通过包内sameAnchor共用原始派发身份校验，依赖闭合，作为后续receipt/执行编排的底层切片。ReceiptStore的包内有效状态门还被ExecutionEvidence使用，回执和写入决策需另行审查，本轮不扩大其public可见性或混入诊断evidence包。

保持两类默认Bean名、无条件@Service、Persistence对payTccCancelTableInitializer的DependsOn、配置键与默认值。Bridge本身不新增事务，prepare/markPostAttempted/mirror三个Persistence方法继续REQUIRES_NEW；配置仍验证启用Seata、禁用AT自动代理及同一裸DataSourceTransactionManager，不在本轮改动配置或LocalTCC接口。生产调用方均通过原PayTccBridgeService接口；原四份引用测试只适配import/FQN，补子包说明，impl根包由31类减至29类。

固定调用顺序为校验请求/XID/开关/支付原事实→独立提交PREPARED→独立消耗一次POST权限→原User Try→独立镜像→原锚点读回。既有记录、DuplicateKey、取消gate、原八字段锚点、锁序、金额数值等价、终态/分支不可逆、Unicode诊断截断、空读与异常传播均保持。未知结果不返还POST权限，后续仅按原payNo/XID/键GET；recover既有PREPARED/终态捷径及其原开关行为不变。不新增重试、事务包裹、调度、SQL或资金结论。

### 41.2 验证、实验与提交策略

先提交本节与`refactor/pay-tcc-dispatch-baseline-20261113`，运行Common/Pay全部*Test基线。迁移后运行全部单测；新增PayTccDispatchMySqlIT三个用例，实际扫描新包并装配原BridgeConfiguration、CancelTableInitializer与真实事务管理器，验证默认关闭门、原Bean/三个REQUIRES_NEW、外层RR回滚不撤销三个独立提交，以及User响应丢失加镜像写失败后POST权限仍保留、重入只GET。User为可观测替身，不运行真实TC或资金POST；绑定XID仅提供原上下文，不代表创建全局事务。新夹具成功才删owned UUID库，失败保留事实。

既有PayLegacyRefundLedgerMySqlIT只运行以下12个方法，原断言/循环不变：incompleteBridgeLedgerEvidenceKeepsUnknownAndOnePost、dispatchMirrorPrefixesFitColumnAndKeepFacts、dispatchMirrorGuardsAndZeroRowRollbackKeepFacts、dispatchMirrorAnchorChangesBlockTrustedAndUnknownResponses、dispatchMirrorKeepsMutableProgressAndOnePostPermission、changedPostClaimFieldsKeepPermissionAndSuppressAllUserRpc、postClaimStateAndMarkerMatrixKeepsSinglePostPermission、readbackAnchorChangesCannotReturnForeignPayloads、readbackKeepsMutableProgressAndMissingRowFallback、dispatchLookupAliasesRejectAllRecoveryAndMirrorStates、exactDispatchLookupKeepsRecoveryMirrorAndMissingSemantics、preparedDispatchWithConsumedMarkerCannotPostAgain。该旧夹具使用真实Mapper/短TransactionTemplate和受控RPC窗口，不代表Spring REQUIRES_NEW代理；由新增实库补此边界。保留旧池/源表指纹和AfterAll删库规则（失败也删），不宣称全类通过或保留失败库。

独立Compose项目`cloud-mall-refactor-pay-tcc-dispatch-20261113`，候选MySQL19960/10.254.230.0/24，核验后全新卷。复制原pay_record/pay_debit_intent空源表，另按原Mapper DDL准备pay_tcc_dispatch空源表供旧矩阵SHOW CREATE TABLE复制；owned库使用现有Mapper建dispatch/cancel表。归档成功后才移除自有容器/网络，卷保留；脚本失败必须阻断后续清理，不依赖镜像外置mysqlbinlog。默认实例及历史卷不动。

证据位于`target/backend-refactor-20261113/pay-tcc-dispatch`；两类和引用文件去package/import/注释逐token相等，其他后端及README按字节保持。Java使用既有格式化器，纯引用文件只整理import；八模块test-compile、Pay打包、Jar新旧类检查、精确备份清除旧两class及git diff --check通过后，临时index逆补丁验证父树恢复。仅提交方案、两类迁包/说明、四份引用测试与新增实库测试，建立`refactor/pay-tcc-dispatch-complete-20261113`；Pay剩余22个TCC写组件另行分组，不宣称模块或资金全链收尾。

### 41.3 实施与验证结果

两类已迁入dispatch，包内sameAnchor及全部业务语句保持，未增加接口或旧包兼容副本。四份引用测试正文保持，仅适配import/FQN；增加包说明和PayTccDispatchMySqlIT，impl根包剩29类。两类去package/import/注释后共3129个token与基线一致；1076份其他已跟踪后端文件及README逐字节保持，79份已跟踪非Java运行文件无旧FQN依赖。

逐行复核的调用链为`prepareDispatch → Persistence.prepare(REQUIRES_NEW) → postOnce → Persistence.markPostAttempted(REQUIRES_NEW) → User POST → Persistence.mirror(REQUIRES_NEW) → 原锚点读回`。已消费权限的重入走recover，只GET原User事实；PREPARED/终态捷径、DuplicateKey原行核验、取消gate、锁序、错误截断及未知结果仍使用原实现。事务在独立Bean上，未把Bridge整体包进新事务。原四份测试和两类生产算法的token对照同时通过。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 原公共及源码守卫 |
| Pay原有全部单测 | 648 | 原入口/协议/派发/回执/取证/恢复/指标矩阵 |
| PayTccDispatchMySqlIT | 3 | 实际扫描与原配置、默认门、三个独立事务、响应丢失及镜像故障后的单次POST权限 |
| PayLegacyRefundLedgerMySqlIT精确12方法 | 12 | 原完整方法循环：镜像锚点/状态/字段矩阵、权限与读回、Unicode截断、错误和零行回滚 |
| 合计 | 688 | 75类，0失败、0错误、0跳过；实库15项 |

基线673项与最终688项分别归档，不累计。新实库使用实际子包扫描、原BridgeConfiguration/CancelTableInitializer、真实MyBatis和裸数据源事务管理器；启动的原建表行为保留，默认关闭请求在启动完成后零业务SQL/零User调用。两类仍按原短Bean名装配，Bridge无事务代理、Persistence三个public方法恰为REQUIRES_NEW。调用者RR在三个独立提交前后仍看见旧空快照，三个写连接均不同于调用者，调用者回滚后PREPARED认领、POST标记及TRY_UNKNOWN镜像仍持久存在；支付仍PENDING。

另一实库例在User替身执行前确认POST_ATTEMPTED已经提交且无本地事务；模拟响应丢失，再用owned SQL trigger使镜像写失败，原POST权限和原载荷保留。移除测试故障后同请求重入只发一次GET并保存TRY_UNKNOWN，全部过程中POST恰一次、支付不被投影成功。RootContext仅绑定测试XID；没有实际TC/RM、User资金请求或全局事务。

首轮673项原单测通过，15项实库均在准备阶段错误：旧12项需要复制源pay_tcc_dispatch表，初始实验漏建；新3项的@Import使初始化器采用全限定Bean名，与生产DependsOn短名不匹配。补原Mapper派发表DDL，并把原配置/初始化器改为context.register保持生产短名后，完整688项回归通过。没有修改生产DependsOn、事务或业务断言。first-tests.log/XML与三个初始化失败库保留；旧夹具失败库按原AfterAll清理，未改其规则。

9份Java通过约定格式化/检查；修正后八模块test-compile、Pay打包和diff再次通过。Jar187个Pay生产class与最终classes逐字节一致，新两类型存在、旧路径及测试夹具无残留；旧两class先备份再精确移除，未全量clean。

### 41.4 证据、回退与后续

方案提交8e9c3ab2；基线标签`refactor/pay-tcc-dispatch-baseline-20261113`，完成标签`refactor/pay-tcc-dispatch-complete-20261113`。证据入口`target/backend-refactor-20261113/pay-tcc-dispatch/verification.json`，含基线/首轮/最终日志与XML、三实库事实、精确12方法清单、源码/格式/构建/Jar和临时index回退证明。

独立MySQL8.0.46最终三张源表均0行。最终成功轮的3个新夹具库及1个旧矩阵库清理；首轮旧矩阵库按原逻辑清理，首轮3个初始化失败库保留，DDL/数据与首次归档完全相同。服务端事件共8次建库、5次删库，剩余集合精确等于三个失败库。初次归档事件解析漏认旧夹具的DROP DATABASE IF EXISTS，断言正确阻断了清理；兼容实际语法后重新核对完整集合并成功归档，未放宽条件。最终自有容器/网络移除，mysql_data卷及失败现场保留；默认实例和历史卷未动。问题、根因、修复及验证均写入原bugfix_log.md。

```powershell
git revert refactor/pay-tcc-dispatch-complete-20261113
```

临时index用no-renames精确路径逆补丁验证父树恢复，真实index与README保持。实际回退后仅精确清理dispatch迁入两类型及新增实库测试class（含内部class），重编译恢复旧路径；不全量clean、不回退数据库，后续依赖先逆序处理。README、两个旧pycache及忽略工件不入提交。

下一步继续审查回执与执行事实校验共享的包内边界，再安排剩余22个TCC写组件分组。Pay整体尚未收尾，本轮未运行旧固定环境全链IT、完整TC资金流程、Gateway/Nacos或性能验证。

## 42. Pay LocalTCC回执与资源事实校验分组（2026-11-13）

### 42.1 范围与兼容基线

从40844c98继续，主Agent按本会话单Agent约定评审、实施和验证。将PayTccReceiptServiceImpl、PayTccReceiptStore、PayTccReceiptBusinessCallback、PayTccReceiptLocalResourceImpl、PayTccExecutionEvidence整体迁入`service.impl.receipt`。Evidence用ReceiptStore包内状态/分支门核对回执、Fence和传入的User镜像，给执行编排提供资源结论；它随回执整体移动，保留类名/Bean名和包内helper可见性，不进入只读诊断evidence包。receipt为LocalTCC资源回执，callback仍为公开支付回调日志。根包29类减至24类，余17个TCC写组件后续处理。

保留Store无条件@Service及DependsOn取消表初始化器、claim的REQUIRES_NEW、transitionClaimed/transition的REQUIRED；read/lock不增加事务。ExecutionEvidence的classify仍为REQUIRED/readOnly/REPEATABLE_READ，指标观察及原异常对象传播不变。Facade继续按receipt.enabled条件装配，先Bridge.prepare→独立claim→唯一winner登记分支→读回核验；Callback/LocalResource仍由原ReceiptConfiguration的@Bean生成，不加@Component。配置只改类型import，Bean名/顺序、同一裸数据源校验及官方Fence生命周期保持。

LocalTCC接口不迁移；ACTION_NAME=cloudmallPayDebitReceipt、request上下文参数名、commit/rollback方法名及useTCCFence=false全部不变。LocalResource继续用同一REQUIRED事务模板组合官方Fence与业务Callback，Confirm/Cancel反射目标随原类地址更新；Try异常整体回滚，清算失败返回false等原语义保持。空回滚/永久终态/Fence清理/被拒分支owner门、原锁序、金额/键/载荷/查询次数和分类优先级不改。仅LocalResource的Logger类别随新FQN变化，日志内容/级别不变。原26份Java引用按实际清单适配，包括6生产文件与20测试文件；根包同包引用需补显式import。

### 42.2 验证、实验与提交策略

先提交本节与`refactor/pay-tcc-receipt-baseline-20261113`，运行Common/Pay全部*Test基线。新增PayTccReceiptMySqlIT三个方法（默认/开启、认领及Try/Confirm、正常/空Cancel），实际扫描新包并注册原配置/初始化器的短Bean名，使用真实MyBatis、事务管理器、TCCFenceConfig及官方TCCFenceHandler。核验关闭时仅Store/Evidence、开启时五类型及原Bean；独立claim不被调用者回滚撤销；Try/Confirm/Cancel注入SQL故障时回执/Fence一同回滚，恢复后同方向重放、Fence清理及迟到Try继续遵守永久事实。classify通过真实RR代理执行，验证单连接只读和原资源分类。

新夹具只绑定测试BusinessActionContext/XID，不注册真实TC分支；Bridge为受控替身，Facade仅验证read/已认领重放，实际分支登记仍由既有单测及原契约守卫覆盖，不宣称真实TC/RM通过。原Fence配置启动其真实清理任务，数据仅位于本轮owned库；上下文关闭后等待清理执行器退出，再恢复Handler的DataSource/TransactionTemplate与DAO表名，核验LOG_QUEUE为空；不宣称官方类初始化没有后台daemon。每个新库成功才删，失败保留，准备时写fixture元数据。

旧PayLegacyRefundLedgerMySqlIT只运行25个既有方法，方法清单保存在本节证据legacy-methods.json，并在结果记录列明：覆盖lookup/source/fresh/replay/readback、合法/非法状态分支、被拒owner的分支/四键/载荷、两次Fence身份读取、public read及ExecutionEvidence的CLAIMED/Fence矩阵。原方法与断言不变，保留其程序化事务/官方Fence scoped static绑定、源表指纹、池关闭及AfterAll清理（失败也删）。不运行整个巨型测试类或旧固定Nacos/TC全链IT。

独立Compose项目`cloud-mall-refactor-pay-tcc-receipt-20261113`，候选MySQL19961/10.254.229.0/24，预检后新卷。pay_record/pay_debit_intent采用schema.sql，dispatch/receipt/execution/Fence采用原Mapper ensureTable DDL，准备六张空源表供旧夹具复制；取消表仅在新owned库按原初始化器创建。默认/历史资源不动，归档成功且集合检查通过后才down自有容器/网络，卷保留。

两类包内helper及五类算法去package/import/注释逐token比较，引用文件正文仅地址适配，其他后端及README字节保持。完整格式化迁移类/包说明/新测试，纯引用文件仅整理import；八模块test-compile、全部单测+新3实库+旧25方法、Pay打包/新旧Jar路径、diff检查通过。精确备份移除旧五类型class，不全量clean。证据`target/backend-refactor-20261113/pay-tcc-receipt`，临时index逆补丁恢复父树验证后提交与`refactor/pay-tcc-receipt-complete-20261113`标签；README/旧pycache/忽略工件不入提交，Git不回退数据库。

### 42.3 实施与验证结果

五类已整体进入receipt；samePayload、hasValidStateAndBranch继续为包内静态方法，原类名、Bean名及业务语句保持。26份引用文件（6生产、20测试）仅适配地址/import；增加包说明与PayTccReceiptMySqlIT，impl根包剩24类，其中17个TCC写组件待处理。五类共4271个业务token与基线一致，原引用文件正文全部等价；1053份其他已跟踪后端文件及README逐字节保持。79份非Java运行文件没有旧FQN依赖，LocalTCC接口及其动作契约未改。

逐行复核的调用链为`Facade → Bridge.prepare → Store.claim独立提交 → 唯一winner的LocalTCC资源 → 原读回核验`；`LocalResource的REQUIRED模板 → 官方Fence → 新包Callback → 同事务Store状态迁移`；`ExecutionEvidence的只读RR代理 → receipt/Fence → 原User镜像与永久终态校验 → 原指标观察`。Callback的反射Method随新类型正确绑定，原配置工厂/DependsOn和条件开关未改，未扩大包内方法可见性。LocalResource日志类别随新FQN变化，模板、等级与参数保持。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 原公共及源码守卫 |
| Pay原有全部单测 | 648 | 原回执/资源证据/指标及其他支付矩阵 |
| PayTccReceiptMySqlIT | 3 | 实际配置/扫描、独立claim、官方Fence原子回滚/重放、真实RR分类和静态恢复；含5个独立夹具 |
| PayLegacyRefundLedgerMySqlIT精确方法 | 25 | 原完整方法/数据变异循环，未改断言或事务夹具 |
| 合计 | 701 | 75类，0失败、0错误、0跳过；实库28项 |

基线673项另存，未与最终相加。编译、全部测试和实库本轮首次通过。新测试默认关闭时实际发现Store/Evidence两Bean，开启时发现原五类型；Store/Evidence为事务代理，Facade/Callback/LocalResource未增加事务代理，配置Bean与接口动作名/commit/rollback/useTCCFence保持。原启动建表和官方清理任务真实执行，但仅指向owned库。

认领在调用者RR之外的连接独立提交，调用者仍看旧空快照且回滚后CLAIMED保留。Try的SQL故障使新Fence与TRYING一起回滚；Confirm故障使Fence/receipt一起保持TRYING。正常Cancel与CLAIMED空Cancel的SQL故障均整体回滚，成功后分别为Fence3/Fence4和CANCELLED。Fence删除后同向终态可重放，迟到Try不复活回执且不留下新Fence；相反清算被拒。Facade只实测read和已认领重放，未模拟成真实TC分支登记。

实际classify代理分别返回COMMITTED、CONFLICT、ROLLEDBACK、UNKNOWN；每次receipt/Fence查询都断言readOnly/RR、同一连接，前后五表事实一致。User镜像为受控输入，不能据此宣称远端User账本或真实资金一致性已验收。5个夹具均恢复官方Handler的DataSource/TransactionTemplate、DAO表名和原XID，LOG_QUEUE保持空；4个启用上下文的真实清理执行器均停止并等待退出，官方类初始化daemon不宣称被测试关闭。

旧矩阵本轮精确执行以下25个方法（完整源码循环保留）：

```text
receiptLookupAliasesRejectAcrossAllPersistedStates
exactReceiptLookupsKeepNullAndCallbackSemantics
receiptClaimRejectsAliasSourcesBeforeFirstOrReplay
receiptClaimExactSourcesKeepWinnerReplayAndOriginalRejections
freshReceiptPermissionPredicatesBlockWithoutResourceCalls
freshReceiptWinnerAndExistingReplayKeepTheirBoundaries
receiptReadbackPayloadChangesCannotPublishForeignViews
receiptReadbackKeepsProgressEqualMoneyAndMissingResult
receiptReplayRejectsInvalidStateBranchMatrix
receiptReadbackRejectsInvalidStateBranchMatrix
receiptValidStateBranchMatrixKeepsReplayAndReadbackProgress
rejectedReceiptRollbackRejectsInvalidOwnerMatrix
rejectedReceiptRollbackKeepsValidOwnersAndPreconditionQueries
receiptSettlementRejectsFenceIdentityMismatchAtBothReads
receiptSettlementKeepsExactAndMissingFenceTerminalEvidence
rejectedReceiptOwnerRejectsPureAliasKeyMatrix
rejectedReceiptOwnerKeepsEachExactUniqueKeyAssociation
rejectedReceiptOwnerRejectsInvalidPayloadDomains
rejectedReceiptOwnerKeepsValidIndependentPayloadDomains
publicReceiptReadRejectsInvalidStateBranchMatrix
publicReceiptReadKeepsValidEvidenceAndLookupPriority
claimedReceiptEvidenceRejectsInvalidBranchAcrossUserStates
claimedReceiptEvidenceKeepsValidUnknownAndMissingReceipt
alienFenceOnEachFundPhaseRejectsWithoutMutation
exactOwnedFenceKeepsAllConclusionsAndCleanup
```

34份Java按完整/仅import范围格式化并检查通过；八模块test-compile、Pay打包和diff通过。Jar187个Pay生产class与classes逐字节一致，迁移五类型的新路径存在，旧路径及测试夹具无残留；旧五class先备份再精确移除，无全量clean。

### 42.4 证据、回退与后续

方案提交05d530ad，基线标签`refactor/pay-tcc-receipt-baseline-20261113`，完成标签`refactor/pay-tcc-receipt-complete-20261113`。证据入口`target/backend-refactor-20261113/pay-tcc-receipt/verification.json`，包括基线/最终XML、五夹具事实与共享绑定恢复、旧25方法清单、源码/格式/构建/Jar、资源事件和临时index回退证明。

独立MySQL8.0.46的六张源表最终均0行。新5个夹具库与旧矩阵1库全部删除，服务端6对建库/删库事件与schema清单互证；原源表checksum与有界池关闭断言通过。本轮没有新增失败库；自有容器/网络归档后移除，mysql_data卷保留，历史失败卷和默认实例未动。原bugfix_log.md记录本切片结果。

```powershell
git revert refactor/pay-tcc-receipt-complete-20261113
```

临时index按no-renames精确路径逆补丁恢复父树，真实index及README保持。实际回退后仅清理receipt中新迁入五类型及新增实库测试class（含内部class），重编译恢复旧路径；不全量clean、不回退数据库，后续依赖先逆序回退。README、两个旧pycache及忽略工件不入提交。

下一步审查执行协调/恢复与交付/取消之间的依赖，冻结剩余17个TCC写组件的下一组范围。Pay整体尚未收尾；本轮没有真实TC分支注册、RM扫描代理、User资金RPC、Gateway/Nacos全链或压测结论。

## 43. Pay TCC执行协调与恢复分组（2026-11-13）

### 43.1 范围与兼容基线

从497a967a继续，主Agent按本会话单Agent约定评审、实施、验证。将PayTccCoordinatorServiceImpl、PayTccExecutionStore、PayTccExecutionRecoveryStore、PayTccRecoveryScheduler迁入`service.impl.execution`，保留原类名/Bean名及public接口。PublicStateStore/RollbackProjectionService作为后续投影切片暂留原包，通过原public方法连接；DeliveryStore对retryDelaySeconds的调用仅补新import。根包由24类减至20类，剩余13个TCC写组件另行安排。

Coordinator/RecoveryScheduler继续按各自开关条件装配，RecoveryScheduler的DependsOn执行表初始化器、ExecutionStore的DependsOn取消表初始化器保持；两个Store始终存在。ExecutionStore十个写/锁定入口和RecoveryStore三个租约入口仍REQUIRES_NEW，其余读取无新事务，Coordinator和Scheduler无外层事务。新包只收拢既有实现，不重命名动作、扩可见性、拆方法或改原异常优先级。

固定流程仍为独立claim→唯一首次begin→原XID绑定→原两Try→独立同向决议→原TC请求→原快照复核。recover只观察原事实并重试原决议，NONE不生成决议；caller context拒绝/本次XID清理、冲突原样传播、状态不可逆、来源与owner快照、诊断和指标保持。恢复租约不转移begin/Try权；两阶段交替优先、共享批量/时间预算、AtomicBoolean重入门、随机token、租约期/退避和投影调用保持。调度仍使用原interval-ms键的fixedDelay和initialDelay（默认5000ms），不新增调度；Scheduler日志类别随FQN变化，模板/等级保持。

### 43.2 验证、实验与提交策略

先提交本节及`refactor/pay-tcc-execution-baseline-20261113`，运行Common/Pay全部*Test基线。新增PayTccExecutionWiringTest两个方法：实际扫描新子包与原表初始化器，验证默认两Bean、coordinator开启三Bean、recovery同时开启四Bean；两个Store的十三个REQUIRES_NEW、原Bean名、默认/覆盖间隔的一项调度；保留caller XID的前置拒绝与零业务依赖调用。TaskScheduler替身只注册不执行，TC/Bridge/Receipt/Evidence/Projection为替身，不把本测试称作真实TC配置启动。recovery缺少coordinator时必须按原DependsOn失败，不能静默启动。

实库复用PayTccRecoveryScanMySqlIT全14项：启动实际PayApplication的NONE上下文，关闭Nacos发现/注册和Seata、coordinator/recovery入口，使用独立MySQL；无Pay Rabbit监听器，Legacy/回调扫描只面对owned表。原断言覆盖SQL限流前过滤、两扫描器竞争、租约接管/过期/精确token、退避、幂等DDL、回滚投影租约和不反转决议。另只运行PayLegacyRefundLedgerMySqlIT精确22方法（清单在本轮legacy-methods.json，结果逐名列明），覆盖execution读写域/锁/影响行数、实际Spring Store owner事务、决议/RPC顺序、上下文漂移与原异常、恢复finish token等原完整矩阵。两旧夹具都保留原源表指纹/关闭/AfterAll owned库删除（失败也删）规则；不改测试正文、不宣称全巨型类通过或保留失败DB。

独立Compose项目`cloud-mall-refactor-pay-tcc-execution-20261113`，候选MySQL19962/10.254.228.0/24，预检后新卷；源库仅准备pay_record/pay_debit_intent/pay_callback_log及原dispatch/receipt/execution/Fence DDL，execution恢复列按原Mapper固定定义补齐，七张源表保持空。新表/恢复列只用于本轮实验源与owned库，生产DDL不变。URL只带allowPublicKeyRetrieval单参数；不会访问默认数据库。归档成功并核对事件/剩余schema后才移除自有容器/网络，卷保留。

证据`target/backend-refactor-20261113/pay-tcc-execution`。四类去package/import/注释逐token相等，原22份引用文件只改地址/import，其余后端及README字节保持。完整格式化迁移类/包说明/新测试，引用文件仅整理import；八模块test-compile、全部单测/两项新装配/36项指定实库、Pay打包/Jar新旧路径/diff通过。备份后精确清除旧四class，不全量clean；临时index逆补丁验证父树恢复后提交及`refactor/pay-tcc-execution-complete-20261113`。README、旧pycache、忽略工件不入提交。下一步考虑公开状态投影；本轮不接真实TC/User资金或性能全链。

### 43.3 实施与验证结果

四类已迁入execution；原class/Bean名、public边界与业务语句保持，未新建接口或旧包兼容副本。22份引用文件（2生产、20测试）仅地址/import适配；Scheduler显式导入原包RollbackProjectionService，DeliveryStore仍调用同一个public退避函数。根包剩20类，其中13个TCC写组件待整理。四类5176个业务token与基线一致，1060份其他已跟踪后端文件及README逐字节保持；79份非Java运行文件没有旧FQN依赖。

核心调用链复盘：`execute → claimOwned独立提交 → begin/原XID绑定 → Pay/User Try → decideOwned独立提交 → verifyDecisionSnapshot → 原方向TC调用 → 无上下文观察`；`recoverDue → 有界两阶段候选 → 独立租约claim → recover或projectClaimed → 原token finish`。读回身份、原决议、caller拒绝/本次context清理、原异常传播、锁序与事务位置保持。恢复租约仍不能授予begin/Try执行权，交付仅引用原退避函数。逐行复核本轮包声明、引用、新装配测试及入口调用顺序，并以token和既有实库矩阵核验原内核。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 公共及源码守卫 |
| Pay原有全部单测 | 648 | 原执行/恢复/资金/回执/指标矩阵 |
| PayTccExecutionWiringTest | 2 | 实际条件扫描、13个独立事务、调度注册/间隔/预算、caller保护和缺依赖拒绝 |
| PayTccRecoveryScanMySqlIT全类 | 14 | 实际PayApplication NONE上下文、真实Store/Mapper与两个扫描器竞争、过滤/接管/退避/DDL/投影租约 |
| PayLegacyRefundLedgerMySqlIT精确方法 | 22 | 原完整数据变异循环、实际Store代理与受控Coordinator依赖 |
| 合计 | 711 | 76类，0失败、0错误、0跳过；实库36项 |

基线673项单独归档。新增装配测试验证默认两Bean、仅coordinator三Bean、同时开启四Bean，默认5000ms与覆盖7000ms均恰一项fixedDelay/initialDelay任务；批量20、租约60秒、预算10秒保持。两个Store恰13个REQUIRES_NEW；Coordinator/Scheduler未增加事务代理。初始化器原DDL委托被核验后，带caller XID的execute/recover/扫描均在业务依赖调用前拒绝并保留caller XID。recovery缺少coordinator时按原DependsOn拒绝启动。TaskScheduler只登记Runnable，不运行任务，不使用真实TC工厂。

首轮36项实库及原单测均通过，新增装配测试1项因TaskScheduler替身getClock返回null，在正initialDelay注册时发生NPE。只补Clock.systemDefaultZone后，定点2项通过，随后完整711项重新执行全绿；生产调度器、间隔、事务和状态机未改。首轮/定点/最终日志及XML分别保留，未累计为最终数量，也未掩盖首轮错误。

恢复实库用实际PayApplication启动，但显式关闭Seata、Nacos发现/注册和TCC协调/自动恢复开关；手动构造的两个扫描器共用真实Store代理，只用受控recover替身。完整14项证明一次有效租约中仅一个扫描器调用recover、旧owner/过期token不能清新租约或改业务字段、过滤在LIMIT之前、失败头退避让后续批次推进、计数封顶不终止恢复、DDL重复执行和回滚投影租约保护。并非真实TC或User资金交付验收。

旧巨型类本轮只执行以下22方法，原方法和循环全部保留：

```text
executionLookupAliasesCannotEnterLockedActions
exactExecutionLookupKeepsWritesTerminalsAndMissingSemantics
invalidExecutionObservationStatesRejectBeforeSqlWrite
executionObservationHealthyWritesAndTerminalsKeepFacts
executionObservationSqlZeroRollsBackWithoutReadback
invalidExecutionRecoveryFinishTokensKeepAllFacts
validExecutionRecoveryFinishTokensAndLeaseControlsRemainUnchanged
ownedClaimAndBindKeepWinnerReplayAndPrebeginProgress
ownedDecisionsKeepProgressAndRollbackMissingReadback
ordinaryExecuteFailuresKeepOriginalClaimAndBoundSnapshots
healthyExecuteAndOrdinaryFailureKeepOriginalDecisions
ownedExecutionRejectsContextDriftAtCallBoundaries
ownedExecutionKeepsSameContextAndConflictPriority
recoveryContextDriftStopsAndCleansOwnedThread
recoveryContextKeepsEmptyFlowAndAmbientCaller
decisionSendRejectsCommittedSnapshotAndDirectionDrift
decisionSendKeepsLatestTerminalAndReadOnlyProgress
lostDecisionResponsesRespectExecutionAndPaymentSnapshots
changedExecutionAnchorsRejectReadBasedObservation
matchingSnapshotKeepsCurrentProgressAndTransaction
invalidOwnedExecutionSourcesStopRecoveryBeforeSideEffects
matchingOwnedExecutionSourcesKeepPayloadAndPaymentProgress
```

29份Java按约定完整/仅import格式化并检查；修正测试后八模块test-compile、Pay打包与diff通过。Jar187个Pay生产class与最终classes逐字节一致，四类新路径存在，旧路径及测试夹具无残留；旧四class先备份再精确移除，未全量clean。

### 43.4 证据、回退与后续

方案提交778b068c；基线标签`refactor/pay-tcc-execution-baseline-20261113`，完成标签`refactor/pay-tcc-execution-complete-20261113`。证据入口`target/backend-refactor-20261113/pay-tcc-execution/verification.json`，含前后/首轮/定点XML、22方法清单、源码/格式/构建/Jar、源表/建删事件及临时index回退证明。

独立MySQL8.0.46最终七张源表均0行。首轮和最终轮各两个owned库，共4对创建/删除事件，与最终schema清单一致；源表指纹断言、应用关闭及旧矩阵有界池关闭通过。本轮没有新增失败数据库，首轮错误仅在无数据库的调度替身。自有容器/网络归档后移除、mysql_data卷保留，默认实例和历史失败卷未动；原bugfix_log.md记录错误根因、修复与完整回归结果。

```powershell
git revert refactor/pay-tcc-execution-complete-20261113
```

临时index用no-renames精确路径逆补丁恢复父树，真实index及README保持。实际回退后精确清理execution迁入四类型及新增装配测试class（含内部class），重编译恢复旧路径；不全量clean、不回退数据库，后续依赖先逆序回退。README、两个旧pycache及忽略工件不入提交。

下一步整理公开支付状态投影的两个组件，再按依赖梳理交付/取消及TCC公开编排。Pay整体尚未收尾；本轮无真实TC/RM、User资金RPC、Gateway/Nacos全链或压测结论。

## 44. Pay TCC公开支付状态投影分组（2026-11-13）

### 44.1 范围与兼容基线

从0264d487继续，主Agent按本会话单Agent约定评审、实施、验证。将PayTccRollbackProjectionService、PayTccPublicStateStore整体迁入`service.impl.projection`；保留原class/Bean名、无条件@Service、public方法和配置token键/默认值。原13份引用文件仅地址/import适配（2生产、11测试），执行恢复扫描仍通过原public投影服务连接，不增加扫描器或开关。根包20类减至18类，余11个TCC写组件另行分组。

两投影入口继续NOT_SUPPORTED，暂停调用者本地事务后，先由Store.cancelledProof的REQUIRED/readOnly/RR与receipt Evidence代理取得同库快照，离开本地事务才查询原User GET，然后由projectFailed/projectFailedClaimed的REQUIRES_NEW独立提交。execution普通读取不新增事务，User调用/字段/错误处理顺序保持；FeignException沿原202 pending，其他RuntimeException原样传播。不能将无租约公开路径用于后台失败兜底，不主动取消、退款、改execution决议或写callback。

保留原TCC模式/tuple、Legacy意图和取消台账互斥、receipt/Fence/dispatch及User CANCELLED/显式no-ledger证明。projectClaimed先验证token域，写入时继续按Pay→execution锁序重核token/未来lease；原公开路径仍无lease，FAILED重放与SUCCESS/REFUNDED拒绝、其他未知状态与CAS语义不变。不补新的远端重查、重试、来源或状态门，不修改SQL/DTO/VO/Feign/金额/时间契约。

### 44.2 验证、实验与提交策略

先提交本节及`refactor/pay-tcc-projection-baseline-20261113`，运行Common/Pay全部*Test基线。新增PayTccProjectionMySqlIT三个方法，实际扫描新包并注册真实Receipt Evidence、Mapper和DataSourceTransactionManager，User为受控替身：验证实际原Bean及事务代理；public投影在调用者RR内暂停/恢复原连接、证明单连接RR、GET无事务、独立FAILED提交不被外层回滚撤销；后台GET窗口发生owner接管或lease过期后，当前读拒绝旧token且不降级公开路径；Feign不可用、缺账本字段、空响应及其他Runtime异常时无状态写入并恢复调用者。新夹具成功才删UUID库，失败留事实；RootContext/TC不模拟成真实资金流程。

旧PayLegacyRefundLedgerMySqlIT只运行8个完整方法：invalidRollbackProjectionTokensCannotChangeAnyFacts、validRollbackProjectionTokensKeepWritesAndPublicPath、incompleteRollbackLedgerProofCannotWriteAcrossStatesAndDecisions、explicitNoLedgerProofKeepsBothProjectionPaths、numericLedgerTokenCannotAuthorizeRollbackProjection、fractionalUserBranchCannotAuthorizeRollbackProjection、numericUserAmountCannotAuthorizeRollbackProjection、duplicateUserLedgerCannotAuthorizeRollbackProjection。其程序化事务/受控proof不冒称Spring集成，由新实库补实际代理边界。另运行PayTccRecoveryScanMySqlIT的rollbackProjectionFiltersBeforeLimitAndDrainsMultipleBatches、oldRecoveryTokenCannotProjectAfterRollbackLeaseTakeover两方法，实际Pay NONE上下文关闭TC/Nacos，验证扫描与投影租约接口。两旧夹具的指纹、关闭、AfterAll删库规则保持（失败也删）。

独立Compose项目`cloud-mall-refactor-pay-tcc-projection-20261113`，候选MySQL19963/10.254.227.0/24，核验后新卷。原schema的pay_record/pay_debit_intent/pay_callback_log，加原Mapper的dispatch/receipt/execution/Fence/cancel及execution固定恢复列，共八张空源表供owned复制。URL显式指向独立loopback，仅携带allowPublicKeyRetrieval；不使用默认库或历史卷。归档成功并核对精确schema/事件集合后才down自有容器/网络，卷保留。

证据`target/backend-refactor-20261113/pay-tcc-projection`。两类去package/import/注释逐token等价，引用正文不改，其余后端和README按字节保持；完整格式化迁移/新测试/包说明，引用文件只整理import。全部单测、3项新实库及10项旧实库、八模块test-compile、Pay打包/Jar新旧类型及diff通过。精确备份清除旧两class，不全量clean；临时index逆补丁恢复父树验证后提交及`refactor/pay-tcc-projection-complete-20261113`。README、旧pycache和忽略工件不入提交，Git不回退运行数据；后续交付/取消及TCC公开编排另行处理。

### 44.3 实施与验证结果

两类已迁入projection，原Bean名/配置键/方法和异常顺序保持；13份引用文件（2生产、11测试）仅地址/import适配，增加包说明和PayTccProjectionMySqlIT，impl根包剩18类，其中11个TCC写组件待分组。两类1525个业务token与基线一致，原引用正文全部等价；1073份其他已跟踪后端文件及README字节保持，79份非Java运行文件无旧FQN依赖。

逐行复核调用链：`project/projectClaimed代理暂停caller本地事务 → cancelledProof的RR事务 → 同连接Receipt Evidence → 结束本地证明 → User GET → projectFailed/projectFailedClaimed独立事务 → 恢复caller`。后台在原Pay→execution锁序内重核token/lease，公开路径继续无lease；FAILED重放和成功/退款保护、互斥来源、显式账本标志、金额字符串及Feign/普通异常差异保持。未增加查询、重试、清理callback或写execution决议。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 原公共及源码守卫 |
| Pay原有全部单测 | 650 | 原投影/租约/公开入口及其他支付矩阵，含上轮装配检查 |
| PayTccProjectionMySqlIT | 3 | 四个独立UUID夹具，实际扫描/代理、调用者暂停恢复、独立提交、租约窗口与异常边界 |
| PayLegacyRefundLedgerMySqlIT精确8方法 | 8 | 原token/三种decision/可写状态/账本缺失及原生JSON证明矩阵 |
| PayTccRecoveryScanMySqlIT精确2方法 | 2 | 实际Pay NONE上下文的投影候选过滤与旧token接管保护 |
| 合计 | 688 | 77类，0失败、0错误、0跳过；实库13项 |

基线675项单独归档，编译及本轮所有测试首次通过。新增实库通过实际新包扫描取得两个原Bean及其代理，Receipt Evidence也是真实组件。public成功例证明：caller原RR连接被暂停，证明阶段全部被观察Mapper使用同一readOnly/RR连接，User GET时无本地事务/绑定资源；投影写阶段使用另一连接，与caller及证明连接不同。调用返回后caller原连接/旧PENDING快照恢复，外层回滚后独立FAILED仍存在；execution/dispatch/receipt/Fence/意图/取消六表不变，User GET恰一次。

后台例在User替身窗口分别提交owner接管和lease过期，之后实际带锁当前读以202拒绝旧token，Pay保持PENDING、markFailed零调用，未降级公开路径；原caller连接和旧owner快照恢复，外部已提交的新owner/到期lease在caller回滚后仍保留。该窗口由同线程GET替身执行owned SQL构造，不冒称真实分布式并发。

Feign异常、缺ledgerApplied证明、空响应均维持202 pending；其他RuntimeException保持同一异常对象。四变体全部恢复原caller连接/事务，七表事实不变且没有写阶段Mapper调用。测试User响应为受控对象，真实User资金事实或TC未参与。

旧巨型类只运行本节冻结的8方法；RecoveryScan只运行冻结的2方法，未把整类结果计入。其原程序化事务/受控proof与新实际Spring三段事务分别记录，旧源表指纹、应用/有界池关闭断言通过。18份Java按完整/仅import范围格式化并检查；八模块test-compile、Pay打包及diff通过。Jar187个Pay生产class与classes逐字节一致，新两路径存在、旧路径及测试夹具无残留，旧两class先备份后精确移除，未全量clean。

### 44.4 证据、回退与后续

方案提交2249c37e；基线标签`refactor/pay-tcc-projection-baseline-20261113`，完成标签`refactor/pay-tcc-projection-complete-20261113`。证据入口`target/backend-refactor-20261113/pay-tcc-projection/verification.json`，含前后XML、四新夹具的连接/读写属性/事实与异常变体、旧8+2精确方法清单、源码/格式/构建/Jar、资源事件及临时index回退证明。

独立MySQL8.0.46八张源表最终均0行。四新夹具库与两旧夹具库全部删除，六对建库/删库事件与剩余schema互证，无新增失败库；自有容器/网络归档后移除、mysql_data卷保留，默认和历史资源未动。原bugfix_log.md记录本切片结果。

```powershell
git revert refactor/pay-tcc-projection-complete-20261113
```

临时index按no-renames精确路径逆补丁恢复父树，真实index及README保持。实际回退后仅清理projection迁入两类型及新增实库测试class（含内部class），重编译恢复旧路径；不全量clean、不回退数据库，后续依赖先逆序回退。README、两个旧pycache和忽略工件不入提交。

下一步检查交付三组件及其同包测试的可见性，再整理交付/取消与TCC公开编排。Pay整体尚未收尾，本轮无真实User资金RPC、TC/RM、Gateway/Nacos全链或性能结论。

## 45. Pay TCC交付与有界扫描分组（2026-11-14）

### 45.1 范围与兼容基线

从db9b9df4继续，主Agent按本会话单Agent约定评审、实施、验证。将PayTccDeliveryServiceImpl、PayTccDeliveryStore、PayTccDeliveryScheduler迁入`service.impl.delivery`，原同包PayTccDeliverySchedulerTest一起移动，保留包内预算常量及LongSupplier构造器，不扩大public可见性。原10项调度测试正文保持；其它12份引用文件仅地址/import适配。根包18类减至15类，余8个TCC写组件另行整理。

Store仍为无条件@Service，ensure/claim/stage/finish四个REQUIRES_NEW、proof的REQUIRED/readOnly/RR及普通read不变；Service/Scheduler仍按delivery.enabled条件装配，无新增外层事务。原DeliveryConfiguration/EntryConfiguration只适配类型，DependsOn执行表初始化器与交付配置、同一个默认5000ms interval/initialDelay、配置校验不变。Scheduler两个阶段分别最多20条，共享10秒启动预算并轮换先后，AtomicBoolean重入门和单调时钟保持；不能误改成整轮20条或强杀在途RPC。Scheduler Logger类别随新FQN变化，消息/等级保持。

调用链仍为ensure持久意图→claim独立租约→同库proof→User当前状态/账本→原Order事实→带token的stage→原RPC及读回→finally finish。Pay和delivery同事务更新，过期/被接管worker不写本地终态或覆盖新租约；READY不等于交付许可，固定refund:payNo、取消/重协调/退款方向和未知结果都保持。不新增重试、退款、API、状态、SQL或User/Order动作，也不把本地lease当作远端RPC撤回机制。

### 45.2 验证、实验与提交策略

先提交本节及`refactor/pay-tcc-delivery-baseline-20261114`，运行Common/Pay全部*Test基线。新增PayTccDeliveryWiringTest两项，实际扫描新包并注册原DeliveryConfiguration/ExecutionTableInitializer：默认仅Store，开启时原三类型；验证四个独立事务/一个RR证明、原Bean名、5000/7000ms一项调度、Service caller XID拒绝及缺coordinator前置依赖拒绝。调度替身提供标准Clock，只登记不执行Runnable；TC/Evidence/Feign/Mapper为替身，不称真实资金或完整TC配置启动。原同包调度测试保持确定性时钟、两阶段/轮换/预算/重入/异常断言；清除旧测试class后防止重复计数。

实库运行PayTccDeliveryRecoveryMySqlIT全7项：真实Pay NONE上下文关闭Seata/Nacos/TCC执行与调度，验证坏XID过滤、租约到期/旧owner、合法stage、delivery CAS故障时支付一起回滚。PayTccCommittedCancelMySqlIT仅4项：requestAndClaimCompetitionHasNoDeadlockAndProtectsWinner、requestAndOldStageCompetitionHasNoDeadlockAndProtectsDirection、acceptedCancellationRetainsValidOwnerForRefundOnlyStage、reconciliationStageAllowsSuccessOrRefundButNeverOldUnknown，保留原并发屏障及断言。PayLegacyRefundLedgerMySqlIT仅4项：deliveryInvalidLeaseTokensCannotMutateAnyFacts、validDeliveryLeaseTokensKeepAllExistingWrites、deliveryIdentityRejectsAliasesAcrossEveryState、canonicalDeliveryIdentityKeepsEnsureClaimAndReadSemantics。三旧夹具的源表指纹、关闭及AfterAll删除规则保持（失败也删）；不跑完整取消大矩阵或固定环境真实TC交付IT。

独立Compose项目`cloud-mall-refactor-pay-tcc-delivery-20261114`，候选MySQL19964/10.254.226.0/24，新卷及源表预检后运行。原Pay三表及原Mapper的dispatch/receipt/execution/Fence/cancel/delivery，共九张空源表；execution恢复列按原固定定义准备。显式loopback URL带allowPublicKeyRetrieval；不访问默认数据库或历史卷。成功归档并检查精确schema/事件集合后才down自有容器/网络，卷保留。

证据`target/backend-refactor-20261114/pay-tcc-delivery`。三个生产类、迁移测试及引用正文去package/import/注释逐token对照，其余后端/README按字节保持。原引用只整理import；完整格式化迁移类/包说明/新测试，八模块test-compile、全部单测/新装配及15项指定实库、Pay打包/Jar旧路径检查与diff通过。备份并精确删除旧三生产class及旧调度测试class（含内部class），不全量clean。临时index逆补丁恢复父树验证后提交及`refactor/pay-tcc-delivery-complete-20261114`，README、旧pycache及忽略工件不入提交。后续取消组件另行处理，Pay仍未整体收尾。

### 45.3 实施与验证结果

三类及原同包调度测试已整体迁入delivery，原测试构造器/预算常量仍为包内可见；原10项测试及ManualClock正文保持，没有为迁包扩大public接口。12份引用文件（1生产、11测试）仅地址/import适配。三个生产类3754个业务token、迁移测试2053个token分别与基线相同，总计5807；1074份其他已跟踪后端文件及README逐字节保持，79份非Java运行文件无旧FQN依赖。根包剩15类，其中8个TCC写组件待整理。

逐行复核调用链：`deliver → ensure独立提交 → claim独立租约 → proof的RR快照 → User当前状态与账本 → 原Order事实及动作 → 带token的stage → finally finish`；`recoverDue → 交替种意图/到期交付 → 各阶段最多20条且共享10秒预算`。state/paid/refund方向和固定退款键保持，PayRecord与delivery的状态CAS仍同一事务；未知结果和过期worker保留原行为。配置仅类型地址变化，Scheduler日志类别跟随新包，日志内容/等级不变。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 公共与源码规则 |
| Pay原有全部单测 | 650 | 原服务/退款/取消/指标矩阵，包含迁移后的10项调度测试 |
| PayTccDeliveryWiringTest | 2 | 实际条件扫描、原配置/初始化器、事务与调度、缺coordinator拒绝 |
| PayTccDeliveryRecoveryMySqlIT全类 | 7 | 真正Spring Store与MySQL过滤、lease/CAS、支付和交付原子更新 |
| PayTccCommittedCancelMySqlIT精确4方法 | 4 | 原认领/状态竞争、取消退款方向与重协调规则 |
| PayLegacyRefundLedgerMySqlIT精确4方法 | 4 | 原token域及身份/别名状态矩阵 |
| 合计 | 692 | 78类，0失败、0错误、0跳过；实库15项 |

基线675项另存，本轮编译和所有验证首次通过。实际扫描默认只有Store，启用时原三Bean；原初始化器委托按条件执行。Store四个REQUIRES_NEW与proof的REQUIRED/readOnly/RR保留，read不新增事务，Service/Scheduler无事务代理；5000ms默认及7000ms覆盖均恰一项fixedDelay/initialDelay任务。TaskScheduler提供标准Clock但不运行Runnable；带caller XID的deliver在业务依赖前拒绝并保留caller，缺coordinator时原配置拒绝启动。此装配验证不加载真实TC工厂。

原调度测试在新包只执行一次，共10项：阶段异常隔离、优先级轮换、共享时间预算、阶段各20条、running重入和异常后释放均保持。旧测试及ManualClock class先备份再精确清除，最终测试发现清单中没有旧FQN，未出现同一测试重复计数。

实库Recovery全7项证明坏XID记录不会占满种子窗口、只有未来有效lease可CAS、旧owner不能覆盖新lease、过期stage不改业务事实、合法stage共同推进Pay/delivery，delivery写失败时Pay更新一起回滚。取消4项原并发屏障完整执行，验证request/claim、request/旧stage保持原锁序和胜者方向，取消后只能进入退款方向，CANCEL_RECONCILING仅允许原成功/退款进展。巨型类只执行本节冻结4方法，保留token/身份完整原循环；没有宣称整类或真实User/Order资金流程通过。

19份Java按完整/仅import范围格式化并检查；八模块test-compile、Pay打包及diff通过。Jar187个Pay生产class与classes逐字节一致，新三类型存在、旧路径及测试夹具无残留。旧三生产class和旧测试两class均精确备份移除，未全量clean。

### 45.4 证据、回退与后续

方案提交a4d9c55d，基线标签`refactor/pay-tcc-delivery-baseline-20261114`，完成标签`refactor/pay-tcc-delivery-complete-20261114`。证据入口`target/backend-refactor-20261114/pay-tcc-delivery/verification.json`，包含前后XML、调度测试新旧FQN/单次计数、两组精确4方法清单、源码/格式/构建/Jar、资源事件及临时index回退证明。

独立MySQL8.0.46九张源表最终均0行，三个owned库全部删除，三对建库/删库事件与剩余schema互证；原源表指纹、应用关闭和巨型类有界池关闭通过。自有容器/网络归档后移除，mysql_data卷保留；无新增失败库，默认及历史资源未动。原bugfix_log.md记录本轮结果。

```powershell
git revert refactor/pay-tcc-delivery-complete-20261114
```

临时index按no-renames精确路径逆补丁恢复父树，真实index及README保持。实际回退后仅清理delivery迁入三类型、迁移的调度测试及新增装配测试class（含内部class），重编译恢复旧路径；不全量clean、不回退数据库，后续依赖先逆序回退。README、两个旧pycache及忽略工件不入提交。

下一步审查取消/中止七组件的依赖与同包测试，再处理TCC公开编排。Pay整体尚未收尾；本轮无真实TC/RM、User退款/Order交付RPC、Gateway/Nacos全链或压测结论。


## 46. Pay TCC取消与未决中止分组（2026-11-14）

### 46.1 范围与主Agent评审

从de026ba6继续，按本会话单Agent约定由主Agent冻结和实施本切片。七类PayTccAbortService/Store、PayTccCancelService/Store/Scheduler、PayTccCommittedCancelService/Store整体迁入`service.impl.cancellation`，根包15类减至8类，剩PayTccPaymentService另行整理。原同包PayTccCommittedCancelStoreTest只调用public方法，但为测试与职责同处仍整体随迁，保留九项断言；没有包内方法需要扩大可见性。十二份外部引用（1生产、11测试）仅地址/import适配。

三条流程保持独立：无execution的取消先独立持久REQUESTED，再Order查询/必要取消/读回，只有订单CANCELLED且库存SUCCEEDED才共同写Pay FAILED和COMPLETED；未决中止NOT_SUPPORTED→REQUIRES_NEW/RR本地证明→事务外User GET→新REQUIRES_NEW/RR当前证明和NONE→ROLLBACK CAS，不调用TC、不改owner/lease、不直接FAILED；已COMMITTED取消先按Pay→delivery锁序独立提交方向，READY转严格取消，ORDER_SYNC_UNKNOWN转待核验，保留原租约后在事务外抛既有202。

CancelStore四个REQUIRES_NEW、普通read；AbortStore两个REQUIRES_NEW/RR；CommittedCancelStore一个REQUIRES_NEW；服务四个NOT_SUPPORTED方法保持。Abort/Committed的caller上下文门不变，不给CancelService或Scheduler补原来没有的XID门。CancelStore/CommittedCancelStore无条件装配，其余五类按cancel.enabled，原CancelConfiguration依赖EntryConfiguration且DependsOn永久表初始化器；初始化器默认仍建cancel表。调度默认5000ms固定及初始延迟、20条/10秒启动预算、AtomicBoolean门、异常隔离与退避保持，Logger类别随FQN变化而消息不变。所有SQL、Feign/API、状态、错误优先级、token原值/UTF-16界限、退款键和事务注解保持。

### 46.2 验证、实验及提交策略

先提交方案与`refactor/pay-tcc-cancellation-baseline-20261114`，记录Common/Pay全部*Test基线。新增PayTccCancellationWiringTest两项：实际扫描新包、注册原CancelConfiguration/CancelTableInitializer，默认两个Store、开启七Bean，原Bean名/七独立事务/四暂停事务与两个RR保持；原5000/7000间隔仅注册一项任务，TaskScheduler提供Clock且不执行；缺EntryConfiguration拒绝启动，caller XID在Abort/Committed入口按原语义拒绝且无业务依赖调用。EntryConfiguration及Mapper/Feign/Evidence/事务管理器为替身，不称完整TC装配或真实事务执行。

原PayTccCancelLeaseMySqlIT全7项和PayTccCommittedCancelMySqlIT全17项验证实际Spring Store/MySQL租约、共同提交/故障回滚、取消与claim/stage竞争、交付方向与原metadata。PayLegacyRefundLedgerMySqlIT仅十方法：cancelRecoveryAliasRequestsCannotMutateAnyActiveState、canonicalCancelRecoveryKeepsLeaseAndAllExistingGates、invalidCancelLeaseTokensCannotMutateAnyFacts、validCancelLeaseTokensKeepExistingWritesAndLeaseGates、incompleteAbortLedgerEvidenceKeepsAllDecisionFacts、explicitNoLedgerAbortPreservesOriginalDecisionWrites、textualLedgerTokenCannotAuthorizeAbortDecision、fractionalUserIdentityCannotAuthorizeAbortDecision、decimalUserAmountCannotAuthorizeAbortDecision、duplicateUserIdentityCannotAuthorizeAbortDecision。保留完整原循环；Abort矩阵使用真实Pay/Execution/Cancel/Intent Mapper与程序化短事务，分支/Fence/User为替身，不能替代实际Spring暂停/RR或真实TC资金证明。不运行固定四服务TC取消IT。

独立Compose项目`cloud-mall-refactor-pay-tcc-cancellation-20261114`，候选MySQL19965/10.254.225.0/24，新卷与端口/网络预检。源库复用前轮已核验的九表DDL文本（非旧库/卷），含execution五恢复列；所有表空且源指纹保持。三个owned测试库按旧夹具AfterAll清理（失败也删），归档精确CREATE/DROP事件、源表与最终schema后才down自有容器/网络，卷保留。默认中间件与历史失败现场不动。

证据`target/backend-refactor-20261114/pay-tcc-cancellation`。源码去package/import/注释逐token对照，其他后端/README逐字节；迁移测试仅发现一次。格式化迁移类/新测试/包说明，外部引用仅整理import；八模块test-compile、全部单测/新扫描与34项精确实库、Pay打包/Jar旧路径检查和diff通过后验收。备份并精确移除七旧生产class（含CancelStore内部类）及迁移测试class，不全量clean。临时index逆补丁恢复父树且真实index/README保持后提交与`refactor/pay-tcc-cancellation-complete-20261114`。README、旧pycache与忽略工件不入提交。


### 46.3 实施、调用链与验证结果

七类和原同包测试整体迁入cancellation，public/包内可见性保持。原测试九项断言无改动；十二份引用文件（1生产、11测试）仅地址/import适配。七生产类5280个业务token、迁移测试1879个token与基线等价，总7159；1072份其他已跟踪后端文件及README逐字节保持，79份非Java运行文件无旧FQN。根包现剩8类，只有PayTccPaymentService尚待TCC职责整理。

逐行复核三条调用链及原限制：

- `CancelService.request → claimOrRead独立提交 → reconcile认领随机token → Order读/必要取消/再读 → stage独立事务同时写Pay FAILED和取消COMPLETED → finally finish退避`。订单/库存未证实完成仍202；冲突、响应丢失、终态和ORDER_CANCELLED不退回UNKNOWN均保持。
- `AbortService.requestRollback(NOT_SUPPORTED) → preparedProof(REQUIRES_NEW/RR) → User GET → requestRollback(REQUIRES_NEW/RR)`。两段都按Pay→execution当前锁核验；Receipt/Fence证据不加反序锁。GET要求同一完整身份、TRYING及明确未记账；只竞争原NONE决议，不夺lease、不覆盖COMMIT、不调用TC或投影FAILED。
- `CommittedCancelService.request(NOT_SUPPORTED) → Store.request(REQUIRES_NEW) → 正常提交 → 服务抛原202`。Pay→delivery原锁序、COMMITTED永久证据和旧gate保持；READY严格取消、已有paid许可则结果待核验，方向登记保留原lease/attempts/metadata，原交付链负责后续退款或结果确认。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 公共与源码规则 |
| Pay原有全部单测 | 652 | 原取消/中止、回滚、资金与指标矩阵，含迁移的9项Store测试 |
| PayTccCancellationWiringTest | 2 | 新包实际扫描、原配置/初始化器、代理/事务元数据、调度及缺依赖/XID门 |
| PayTccCancelLeaseMySqlIT全类 | 7 | 真Spring Store/MySQL，严格未来lease、旧owner隔离、Pay/cancel共同更新及SQL失败回滚 |
| PayTccCommittedCancelMySqlIT全类 | 17 | 真Spring Store/MySQL，原20轮并发竞争、方向与metadata保护、失败插入回滚 |
| PayLegacyRefundLedgerMySqlIT精确十方法 | 10 | 原身份/token矩阵、User字段严格证明、原决议四列写入与其它事实不变 |
| 合计 | 713 | 79类，0失败、0错误、0跳过；实库34项 |

基线677项另存；八模块编译、全部测试及打包首次通过。本轮无测试失败或生产缺陷修复。新扫描验证默认两个Store、启用七个原名Bean，永久cancel表初始化一次，原CancelConfiguration缺Entry时拒绝；七独立事务/四NOT_SUPPORTED注解、Abort两个RR与普通read保持。5000ms默认和7000ms覆盖均恰一项fixedDelay/initialDelay；调度只登记不执行。实际入口代理使用替身事务管理器，在caller XID下按原错误拒绝且保留caller、零业务交互；不将其等同于真实数据库暂停/恢复证明。

实库Lease全7项和Committed全17项使用关闭Seata/Nacos执行的实际Pay上下文，原两组各十轮并发屏障/超时断言保持。巨型类只运行冻结十方法，不宣称整类通过；Abort部分是实际Mapper加程序化短事务和受控分支/Fence/User证据，验证缺失/null/true等不足证明不改决议、明确false只改变原四列且不覆COMMIT。上述测试不包含真实User资金RPC、TC/RM或完整公开入口依赖装配。

23份Java按完整/仅import范围格式化及检查；Jar187个Pay生产class与classes逐字节一致，七类新路径存在且旧路径/测试无残留。九个旧class（七生产含CancelStore内部类共八个、迁移测试一个）备份后精确移除；迁移测试新FQN仅发现一次、恰九项，未全量clean。源码、构建、Jar及diff检查通过。

### 46.4 证据、回退与后续

方案提交5f5ee58c，基线标签`refactor/pay-tcc-cancellation-baseline-20261114`，完成标签`refactor/pay-tcc-cancellation-complete-20261114`。证据入口`target/backend-refactor-20261114/pay-tcc-cancellation/verification.json`，保存实际commit/tree/标签、前后XML、精确方法清单、迁移测试单次发现、源码/格式/构建/Jar、资源事件及临时index逆补丁证明。

独立MySQL8.0.46九源表最终均0行；三个owned库均已删除，三对CREATE/DROP事件与最终schema集合一致。源指纹检查、应用关闭和巨型类有界池关闭通过。归档后移除本轮自有容器/网络，保留`cloud-mall-refactor-pay-tcc-cancellation-20261114_mysql_data`卷；无新增失败数据库，默认中间件/历史现场不动。原bugfix_log.md已补本轮结果。

```powershell
git revert refactor/pay-tcc-cancellation-complete-20261114
```

临时index按no-renames精确路径逆补丁恢复父树，真实index及README保持；回退后仅清理迁入七类（含内部类）、迁移测试与新增装配测试class，再重编译恢复旧路径。后续依赖先逆序回退，不全量clean、不回退数据库，忽略证据工件不受Git回退管理。README、旧pycache与忽略工件未入提交。

下一步审查PayTccPaymentService的公开TCC支付/失败编排，冻结其与已分组组件的调用、事务和错误顺序后再调整；随后评估Pay范围收尾。Pay整体仍未验收完成，本轮无真实TC资金全链路、Gateway/Nacos全商城或压测结论。


## 47. Pay TCC公开支付与失败编排归组（2026-11-14）

### 47.1 范围与主Agent评审

从11593f01继续，按本会话单Agent约定冻结本切片。PayTccPaymentService整体迁入`service.impl.entry`，补齐类说明中的失败请求职责，类型名和默认Bean名仍为原名。保留同一公开路由，不拆出只有转发行为的Service；已分组的execution/delivery/projection/cancellation继续负责各自事务。原17份引用（4生产、13测试）仅地址/import适配，根包8类减至7类（五个公开用例、无状态共用规则、写监视器）。API/Feign、DTO/VO、SQL、所有状态/错误顺序和可见性均不变。

pay方法无事务注解：终态立即返回；已有execution直接recover，不再订单预检；无execution先检查永久cancel gate再校验当前订单身份/金额/PENDING_PAYMENT，然后execute。COMMITTED只deliver、ROLLEDBACK只project，空执行仍202、空交付仍UNKNOWN，外层仅FeignException转既有202，其它异常保持。failure继续NOT_SUPPORTED：SUCCESS/REFUNDED拒绝、FAILED返回；COMMITTED退款中保留专有202，其余按原窗口委托已提交取消；未决NONE可请求中止后202；ROLLEDBACK必须投影返回FAILED才修改原PayVO；无execution才委托可选取消。取消功能关闭后的保守202、原caller上下文规则、无新增监视器/重试/回读保持。

仍由entry.enabled条件装配并DependsOn原payTccEntryConfiguration；原Receipt配置校验以及Recovery/Delivery调度器依赖保持。cancellations Mapper仍必需，三个取消Service仍@Autowired(required=false)，不把独立取消开关变为入口启用的强依赖。主入口、回调与PayProcessingSupport仅类型引用调整。

### 47.2 验证、实验与提交策略

先提交方案及`refactor/pay-tcc-entry-baseline-20261114`，运行Common/Pay全部*Test基线。新增PayTccEntryWiringTest两项：实际扫描entry并注册原EntryConfiguration，默认无入口、开启原名Bean及事务代理；验证pay不新增事务、failure仍NOT_SUPPORTED，三个可选取消依赖缺失/存在时正确装配；原Receipt validate委托一次。分别缺Receipt/Recovery/Delivery前置依赖时拒绝启动。原配置的三项依赖和业务依赖使用替身，事务管理器为替身，不触发真实TC、Fence清理器或定时任务；事务实际暂停/恢复由原实库用例验证。

PayFailureMySqlIT全4项与PaySuccessMySqlIT全3项原断言保持。Failure包含实际TCC failure的Spring事务代理：入口暂停真实MySQL外层事务，未知异常与FAILED视图返回后恢复原资源/连接，不从旧RR快照重新覆盖视图；资金执行/投影为受控替身，不冒称真实资金。另三项Legacy验证独立提交、当前读拒绝旧快照、故障回滚；Success验证真实持久模式先于终态、ObjectProvider原类型分流、同一监视器、返回时回读及未知结果，TCC资金端仍为替身。其余入口/回调路由、TCC支付/失败状态矩阵由全部原单测覆盖。不运行硬编码默认环境的PayDebitModeM2MySqlIT或四服务TC全链IT。

独立Compose项目`cloud-mall-refactor-pay-tcc-entry-20261114`，候选MySQL19966/10.254.224.0/24，新卷/端口/网络预检。只准备原DDL的pay_record、pay_debit_intent两张空源表；按夹具要求传裸loopback URL，夹具自行补allowPublicKeyRetrieval及timezone。七个UUID库各自记录before-cleanup/finished，成功才删除、失败保留；源表计数/校验和与七对建删事件核验后归档并down本轮容器/网络，卷保留。默认和历史资源不动。

证据`target/backend-refactor-20261114/pay-tcc-entry`。迁移类和引用去package/import/注释逐token对照，其他后端/README按字节保持，非Java运行配置不含旧FQN。迁移类/新测试/包说明完整格式化，其余仅import整理；八模块test-compile、全部单测/新增扫描及7项实库、Pay打包/Jar旧路径和diff检查通过后验收。仅备份并删除原单个class，不全量clean。按精确路径临时index逆补丁恢复父树、真实index/README保持后提交及`refactor/pay-tcc-entry-complete-20261114`；无关README/pycache/忽略工件不入提交。下一步评估Pay范围收尾，尚不宣称Pay整体完成。


### 47.3 实施、调用链与验证结果

PayTccPaymentService已迁入entry，类说明覆盖公开支付/失败两条职责；原类型名/Bean名、两public方法、条件及DependsOn保持。17份引用（4生产、13测试）只适配地址/import；迁移类893个业务token与基线等价，1076份其他已跟踪后端文件及README逐字节保持，79份非Java运行文件无旧FQN。根包只保留五个公开用例、PayProcessingSupport和PayWriteMonitor共7类。

逐行复核调用链：`成功/成功回调 → PayProcessingSupport按持久模式分流 → entry.pay → 终态直接返回 / 既有execution恢复 / 无execution先cancel gate和Order预检再execute → COMMITTED交付 / ROLLEDBACK投影 / UNKNOWN`；`失败/失败回调 → entry.failure暂停外层事务 → 终态拒绝或返回 → 资金状态决定已提交取消 / 未决中止 / 回滚投影 / 无execution取消`。只有原投影或无execution取消正常返回后才将原PayVO标FAILED，不回读旧外层RR；无新锁、重试、SQL、远程动作或错误转换。三个可选取消依赖缺失时继续原保守202，永久取消Mapper仍必需。

| 最终执行范围 | 测试数 | 实际证据 |
| --- | ---: | --- |
| Common全部单测 | 25 | 公共及源码规则 |
| Pay原有全部单测 | 654 | 原公开路由、回调、TCC支付/失败/取消、指标与协议矩阵 |
| PayTccEntryWiringTest | 2 | 实际新包/原配置扫描、默认门、原Bean/事务元数据、三项可选依赖、三种缺前置依赖拒绝 |
| PayFailureMySqlIT全类 | 4 | 实际TCC failure代理的暂停/恢复、Legacy独立提交、当前读拒绝旧快照与故障回滚 |
| PaySuccessMySqlIT全类 | 3 | 真持久模式检查、原ObjectProvider分流、监视器/回读及未知结果事实保持 |
| 合计 | 688 | 79类，0失败、0错误、0跳过；实库7项 |

基线679项另存。编译、测试及打包首次通过；本轮无生产缺陷修复。扫描默认不产生入口Bean，开启后只有原payTccPaymentService并经原EntryConfiguration调用Receipt.validate一次；pay不增事务，failure仍NOT_SUPPORTED。可选取消组件缺失/存在均可装配，存在时原无execution分支只调用取消并修改视图，关闭时保留202；分别缺Receipt配置、Recovery或Delivery调度器均由原配置拒绝启动。该扫描使用替身事务管理器/前置依赖，不启动真实TC/Fence或任务。

Failure实库的TCC方法使用真实MySQL事务管理器和实际entry.failure代理，未知202与投影返回FAILED之后都恢复原外层连接/资源；投影为替身，返回视图FAILED而数据库保留PENDING，确认没有额外旧快照读取替换视图，不能据此宣称真实资金回滚。另三个Legacy方法验证已提交FAILED不随caller回滚、远端/本地CAS失败原语义、旧RR视图不能越过新提交意图。Success三项保持原模式优先检查、同一监视器、唯一协议分支及成功回读；TCC/Legacy执行端均为受控替身。完整Gateway/Nacos/TC资金链不在本轮范围。

21份Java按完整/仅import范围格式化并检查；八模块test-compile、Pay打包、源码对照和diff通过。旧单个entry class备份后精确移除，Jar仍为187个Pay生产class并与classes逐字节一致；新路径存在，旧FQN和测试类未打入Jar，未全量clean。

### 47.4 证据、回退与后续

方案提交afe83a30，基线标签`refactor/pay-tcc-entry-baseline-20261114`，完成标签`refactor/pay-tcc-entry-complete-20261114`。证据入口`target/backend-refactor-20261114/pay-tcc-entry/verification.json`，包含实际commit/tree/标签、前后XML、七UUID夹具事实、源码/格式/构建/Jar、资源事件与临时index逆补丁证明。

独立MySQL8.0.46的两张源表最终均0行，前后校验和及SHOW CREATE TABLE一致；四Failure库、三Success库均在成功门通过后删除，七份finished证据与七对建删事件及最终schema一致，无失败库。归档后移除本轮容器/网络，保留`cloud-mall-refactor-pay-tcc-entry-20261114_mysql_data`卷，默认及历史资源未动。原bugfix_log.md已补结果。

```powershell
git revert refactor/pay-tcc-entry-complete-20261114
```

临时index精确no-renames路径逆补丁恢复父树，真实index/README保持。实际回退后只清理迁入entry单类和新增装配测试class（含内部类），重编译恢复原类路径；后续依赖先逆序回退，不全量clean、不回退数据库。README、旧pycache及忽略工件未入提交。

下一步对Pay模块做范围收尾复核：核对包职责、命名/注释/格式与旧引用清单，确认公开API/Feign/持久化/调度/事务兼容证据和剩余限制，再形成Pay本轮结构验收记录。当前只完成公开编排归组，尚未宣称Pay整体收尾或真实TC资金全链验收。


## 48. Pay模块结构收尾与兼容复核（2026-11-14）

### 48.1 范围、缺口与主Agent评审

从d0da1593继续，由主Agent按本会话单Agent约定完成Pay收尾。第34至47节十四切片已经覆盖公开查询/成功/创建/失败/回调、旧继承入口清理及辅助职责归组。187份生产Java（含包说明）中无Cm编号生产类型、旧PayController/PayServiceImpl/空PayService，无Controller/Service内JdbcTemplate或SQL。JDK语法树短变量检查仅发现八个PO的语义明确id主键字段，保持数据库/JSON既有命名。

必需JavaDoc初检发现39项：FeignAuthConfiguration.authHeaders的说明写在@Bean之后，JDK未将其识别为方法JavaDoc且原说明泛化；PayProtocolMetrics的34个有限枚举值、PayRecoveryLog.OrderStage的4个枚举值缺独立说明。本轮补准含义与正确位置，为OrderClient/UserClient五个局部record补参数说明并补配置/Feign类型职责说明。全Pay生产Java执行既有Google Java Format，跳过长字符串重排。只允许注释、排版、import顺序与未使用import整理，不改业务token、变量/字段名、注解、SQL、配置、依赖和测试源码，不新增镜像实现的测试。

现有Feign局部不可变record继续保留在客户端内，业务Controller/ServiceImpl无嵌套DTO/VO/PO；九份PO保留表映射，PayDebitIntentMapper继续封装既有JDBC意图/恢复SQL，其余Mapper保留原MyBatis契约。结构收尾不等于全面ORM替换，也不更名冻结工具、表/Bean/日志/指标标签及历史CM标识。原查询鉴权顺序、创建/成功/回调共享监视器、创建事务先于锁/晚于解锁提交、独立意图/回执事务、TCC单向决议/证据/租约及未知结果边界以逐切片记录为准。

### 48.2 验证、证据与回退策略

先提交本节并建立`refactor/pay-closeout-baseline-20261114`。上一源码点688项/79类XML逐份SHA、最终路径指纹及HEAD已核验，作为历史基线单独归档，不与本轮新测试累加。当前187份生产Java去注释和规范化import后逐token必须与基线一致；其余后端（含测试、资源、POM）及README按字节保持。JDK语法树复查必需入口/非record成员文档缺口为零，record参数说明按源码核验，隐式生成构造/accessor不当作手写入口计数。

本轮新跑Common/Pay全部现有*Test，加PayOrderStateFeignIT全1项：实际Pay Feign、本机HTTP与实际Order MVC/Service协作，SQL/库存动作受控替身。先八模块test-compile准备Order class，再运行以上测试、Pay打包、187文件全格式检查、旧类型/旧包/路由/PO/服务接口/SQL分层盘点、Jar与git diff检查。纯注释排版不重复此前实库矩阵，不创建Docker/数据库资源，不访问默认中间件/历史失败卷；本机HTTP夹具按原测试关闭。

证据`target/backend-refactor-20261114/pay-closeout`。完成后建立`refactor/pay-closeout-complete-20261114`和同源码点`refactor/pay-complete-20261114`，保存十四切片逆序回退索引与实际SHA。临时index逆补丁恢复父树且真实index/README保持；实际回退只撤销本轮说明/排版后重编译，不全量clean/reset，不回退运行数据。无关README、pycache及忽略工件不入提交。

收尾完成口径为已约定的Pay中等深度结构重构；各阶段原实库/本机HTTP/替身测试的边界分别保留，不扩张为真实TC资金全链、全商城、性能或生产可用性验收。后续按总方案进入跨模块回归范围梳理，再冻结所需服务、环境与证据，不自动恢复历史CM21维护队列。


### 48.3 Pay范围验收结果

本轮Git差异为5份生产源码的说明/排版：FeignAuthConfiguration、OrderClient、UserClient、PayProtocolMetrics、PayRecoveryLog。修复39项必需文档缺口（1个Bean方法、38个枚举值），补五个Feign局部record参数与三个类型职责说明，明确OrderView四参数兼容构造器的SUCCEEDED默认值与远端缺失字段null是不同来源。187份生产Java（含包说明）全格式检查通过；PayApplication另有仅CRLF/LF本地归一化，不形成Git源码差异。全部70,117个业务token与本轮基线相同，未改状态、注解、SQL或运行语句。

JDK语法树复查显式public/接口/Bean/定时入口及非record成员JavaDoc缺口为0；record参数在声明说明，隐式生成方法不按手写入口计数。短变量清单仅八个PO的id字段，均为明确主键含义；没有为了字母数改变持久化字段。OrderClient、UserClient局部不可变record和唯一JDBC意图Mapper按兼容清单保留。

| 模块检查项 | 结果与边界 |
| --- | --- |
| 命名与旧入口 | 无Cm编号生产类型；旧PayController、PayServiceImpl、空PayService及41个已迁移组件的旧运行引用清除。PayQueryWiringTest中三处旧类字符串用于ClassNotFoundException反向断言，属于有效测试 |
| HTTP与接口 | 15个组合委托Controller、15条无重复声明路由（5公开、10内部证据读取）；11个非空Service接口。内部路径的原配置门和调用凭证保持 |
| 数据与分层 | 9份PO保留9张表映射；Controller/Service无JdbcTemplate或内嵌SQL。PayDebitIntentMapper保留既有JDBC实现，其余Mapper及SQL/CAS契约保持，未宣称全部改为BaseMapper |
| 包职责 | impl根包7类为五公开用例、PayProcessingSupport、PayWriteMonitor；其余41类分入legacy、callback、evidence、dispatch、receipt、execution、projection、delivery、cancellation、entry十组 |
| 契约与持久标识 | Pay POM/application自query基线不变；本轮全部测试/资源/依赖及其他后端910个已跟踪文件逐字节保持。JSON字段、金额/时间/null、Feign、SQL/Redis/MQ/日志/指标标签沿原契约保留 |
| 事务与调用链 | 查询鉴权与错误顺序、创建/成功/回调共享monitor、创建事务在锁外开始/提交、独立claim/决议/租约、NOT_SUPPORTED与RR证明等见第34至47节逐链记录，本轮业务token等价 |
| 验证与资源 | 本轮682项首次通过；上一源码点688项/79类归档逐SHA校验，分别记账不相加。只使用本机HTTP夹具，无新增Docker、数据库或真实TC资金资源 |
| 回退与限制 | 十四切片标签已按实际Git历史核验，并建立本轮收尾及模块结构通过点。冻结CM工具和历史失败卷不动，结构完成不替代资金全链/全商城/性能结论 |

上述为用户确认的Pay中等深度结构重构完成口径。核心调用链已在各阶段按公开用例、Legacy恢复、回执/Fence、执行决议、只读取证、交付与取消逐项复核；保留Feign局部record、JDBC Mapper与历史测试工具名，不扩大为全面ORM替换、所有外部标识改名或生产就绪。

本轮新执行：Common单测25、Pay全部现有单测656、PayOrderStateFeignIT 1，合计682项/78类，0失败、0错误、0跳过。FeignIT经实际Pay客户端、本机HTTP和实际Order MVC/Service核验paid/cancel路径、原身份头、金额及库存动作null语义，SQL/库存动作仍受控替身；不声称连接真实Order/Stock/User业务环境。八模块test-compile及Pay打包通过，Jar187个Pay生产class与最终classes逐字节一致，44个已移除/迁移旧类型和测试夹具均不在Jar中。

### 48.4 回退索引与后续范围

方案提交e7975890，基线标签`refactor/pay-closeout-baseline-20261114`；本轮完成标签`refactor/pay-closeout-complete-20261114`与模块结构通过标签`refactor/pay-complete-20261114`指向同一最终源码点。证据入口`target/backend-refactor-20261114/pay-closeout/verification.json`，包含本轮78份XML、旧688报告核验、语法树前后缺口、187文件token/格式对照、模块/路由/PO盘点、Jar、十四阶段索引与临时index回退证明。

```powershell
git revert refactor/pay-closeout-complete-20261114
```

上述命令只撤销本轮说明/排版。若继续撤销之前Pay结构改造，先撤销本轮，再由下表自上向下评估依赖和方案冲突；迁包阶段按对应记录精确处理旧class再重编译，不把索引当作无条件执行脚本。所有标签前缀为`refactor/`。

| 逆序切片 | 实施提交 | 完成标签后缀 |
| --- | --- | --- |
| 公开TCC编排 | d0da1593 | pay-tcc-entry-complete-20261114 |
| 取消/未决中止 | 11593f01 | pay-tcc-cancellation-complete-20261114 |
| TCC交付 | de026ba6 | pay-tcc-delivery-complete-20261114 |
| 回滚状态投影 | db9b9df4 | pay-tcc-projection-complete-20261113 |
| 执行协调/恢复 | 0264d487 | pay-tcc-execution-complete-20261113 |
| 资源回执/Fence | 497a967a | pay-tcc-receipt-complete-20261113 |
| 派发桥 | 40844c98 | pay-tcc-dispatch-complete-20261113 |
| 只读取证 | 57534d8b | pay-tcc-evidence-complete-20261113 |
| Legacy/回调日志 | c0e51687 | pay-legacy-callback-complete-20261113 |
| 公开回调与旧继承清理 | 9339a084 | pay-callback-complete-20261113 |
| 公开失败 | 1f555de5 | pay-failure-complete-20261113 |
| 公开成功 | 49238b39 | pay-success-complete-20261113 |
| 公开创建 | 3d86f278 | pay-creation-complete-20261113 |
| 公开查询 | 35ac1bb0 | pay-query-complete-20261113 |

临时index按本轮精确路径逆补丁恢复父树，真实index及README保持；Git不恢复MySQL/Redis/Rabbit/Seata事实或忽略证据。本轮无新增失败测试/中间件实验，原bugfix_log.md记录文档根因、修复和验证；README与旧pycache未入提交。

下一步按总方案梳理跨模块回归：以当前源码确认现有测试入口、调用链、所需独立服务/中间件、环境隔离和资源归属，再冻结新的执行范围。此前实库、TC及性能材料保持各自时间和源码归属，不把模块结构通过点当作全项目全链验收；历史CM21维护队列不自动恢复。


## 49. 跨模块离线回归基线与后续环境分层（2026-11-14）

### 49.1 本轮范围与主Agent评审

从Pay结构通过点3f5d069c继续，主Agent单独执行。七业务/网关模块的结构切片和Common盘点完成后，先在同一源码点运行八模块现有单测和显式本机HTTP契约，验证模块单独迁移后能共同编译、装配并保持协议；不把分散历史测试结果累加为本轮结果。上一Pay收尾682项/78类XML SHA与最终源码路径已核验，独立保存为历史依据。

现有*Test源文件共262份：Common4、Gateway5、User18、Product37、Cart13、Order64、Stock48、Pay73。另有107份*IT候选，不能按通配符整体启动。审阅资源入口后，本轮选择所有*Test，加Order的StockSeckillFactFeignIT与Pay的PayOrderStateFeignIT各一项；先完成八模块test-compile，确保Order引用的Stock标准class已就绪，再运行根工程测试。最终测试数按本轮XML实际结果登记，不拿源文件数代替测试数。

Product若干IntegrationTest使用真实Spring事务/Mapper外加受控JDBC叶子，不创建真实MySQL。ProductMetricSchemaIndexContractTest只运行解析/配置拒绝/归属校验，其同文件资源夹具仅供显式IT使用；ProductMetricAdminConnectionLifecycleTest会创建自有Java子进程及target证据，核验自然退出且无AMQP连接，不启动Rabbit或流水线负载。PayTccStageEvidenceTest中的DataSource只提供配置身份，数据库执行被替身截获，不连接默认13307。Pay指标测试及Feign契约使用本机临时端口，按原finally关闭。

本轮仅修改方案/验收记录，不改生产、测试、POM、版本、运行配置或冻结实验runner。若出现真实失败，先在原bugfix_log.md记录现象、定位归属，再冻结必要最小修复，不跳过失败/删断言来宣告通过。已有README与两个pycache保持。

### 49.2 新执行命令、证据与Git策略

先提交本节及`refactor/cross-module-offline-baseline-20261114`。使用原Java17/Maven工具，顺序执行根工程：

```powershell
mvn -o test-compile -DskipTests
mvn -o test '-Dtest=*Test,StockSeckillFactFeignIT,PayOrderStateFeignIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Duser.timezone=Asia/Shanghai'
mvn -o package -DskipTests
```

实际命令使用已核验的本地mvn.cmd绝对路径，日志归档`target/backend-refactor-20261114/cross-module-offline`。测试选择器不会执行其他*IT；本轮不运行deploy的历史CM21执行器、不建立新Docker/数据库资源或连接默认中间件。归档每模块实际XML、明确本机HTTP/替身边界、Product自有子进程退出证据及Pay指标结果；八模块Jar逐class对应编译输出，并与各自生产源码类型清单核对，无遗留旧类或测试夹具混入。

所有已跟踪backend/deploy文件及README按字节核验不变，差异应只含本节文档（bugfix_log仍按原忽略策略维护）。通过后写实际分模块测试数、缺口矩阵及下一阶段，建立`refactor/cross-module-offline-complete-20261114`；临时index逆补丁验证父树和真实index/README保持，不清理历史target证据、不改写Git历史。

### 49.3 跨模块验证矩阵与后续准入

| 范围 | 本轮可验证的实际入口 | 仍需后续独立验证 |
| --- | --- | --- |
| Gateway→业务身份 | GatewayAuthenticationContractTest、Http/MVC契约及Common异常/身份规则 | 真实Redis令牌、Gateway路由/Nacos、完整外部请求链 |
| Cart→Product与Order→Cart | Cart原业务/HTTP测试、CartCleanupContractTest、OrderCartCommitBoundaryTest | 实际Redis Lua/TTL/竞争、真实Cart/Product HTTP与下单提交后清理 |
| Order→Stock | 原StockFeign/秒杀受理单测、StockSeckillFactFeignIT的实际客户端与Stock VO、本机HTTP | 真实预扣/确认/回滚SQL、MQ、跨进程库存恢复与锁竞争 |
| Pay→Order | PayOrderStateFeignIT的实际Feign、本机HTTP、实际Order MVC/Service，SQL为替身 | 真实订单数据/库存动作及进程异常恢复 |
| Pay→User/TC | 原余额/TCC/证据/回调/装配单测和契约校验，依赖受控 | 真实余额账本、官方Fence/TC/RM、资金提交/取消/退款全链 |
| 商品索引/销量与消息 | Product/Stock原状态、批处理、事务观察、消息字段单测 | 实际MySQL/Rabbit/ES索引同步及查询、消费恢复 |

后续先盘点并冻结新的MySQL/Redis联合验证切片，候选为Cart十个Redis IT、UserAccountRefactoringIT、ProductRefactoringMySqlIT、StockCoreRefactoringMySqlIT及必要Order/Pay显式实库方法。逐一核对各夹具URL/源DDL/随机库、Redis DB/命名空间、失败保留和清理规则后才执行；不能把旧卷当fresh，也不能同时使用相互冲突的同名系统属性。每阶段使用deploy/experiments/compose.yml新项目和新卷，先归档后移除自有容器/网络，卷默认保留。

MQ/ES、真实TC资金与全商城链条再按依赖顺序单独冻结；固定默认端口/数据库或要求真实Nacos/TC的旧测试，不通过放宽归属/开关守卫临时绕过。性能/JMeter、历史CM21 guardian/启动修复不属于本轮结构回归，未重新纳入前保持历史归档状态。本轮通过只表示统一离线与本机契约基线通过，不表示所有真实跨服务链路完成。


### 49.4 统一源码点执行结果

本轮八模块test-compile、根工程测试及八模块package首次通过。实际发现并执行262个原*Test类与2个指定IT类，和冻结源清单逐一对应，无遗漏、无额外IT、无重复统计。最终264类/1933项，0失败、0错误、0跳过；本轮未改业务或测试源码、未修复/掩盖失败。

| 模块 | 测试类 | 测试项 | 本轮实际范围 |
| --- | ---: | ---: | --- |
| Common | 4 | 25 | 公共响应、身份/异常和后端源码规则 |
| Gateway | 5 | 21 | 真实过滤链与请求契约，令牌存储为替身 |
| User | 18 | 284 | 账号/余额/TCC与只读取证的原单测，持久化/外部叶子受控 |
| Product | 37 | 361 | 原商品/索引/指标与事务观察测试，含自有子进程生命周期验证 |
| Cart | 13 | 35 | HTTP/身份优先级、金额/JSON、原条件存储与结算单测 |
| Order | 65 | 341 | 原单测340，加StockSeckillFactFeignIT 1 |
| Stock | 48 | 209 | 原库存/秒杀/恢复/响应与配置契约单测 |
| Pay | 74 | 657 | 原单测656，加PayOrderStateFeignIT 1 |
| 合计 | 264 | 1933 | 同一源码点新跑结果，不叠加此前682项或其他历史实库矩阵 |

Order→Stock契约使用实际Order Feign、实际Stock响应VO和本机HTTP，核验原路径/调用身份及字段缺失/null，不包含真实Stock数据库处理。Pay→Order使用实际Pay Feign、本机HTTP、实际Order MVC/Service，SQL/库存动作受控；覆盖paid/cancel路径、原身份头、金额及库存动作null。Gateway、Cart等原HTTP/MVC测试继续在各自真实入口与依赖替身边界内执行，不能合称全商城跨进程请求完成。

Product自有Java子进程报告naturalExit=true、alive=false、exit=0，任务及执行器关闭、AMQPConnections=0；process/closed/exit及stdout/stderr均按本轮新UUID归档。Pay Prometheus指标用例的本轮runId及前后输出独立归档。本机HTTP/执行器按原测试finally关闭；本轮无新Docker项目/卷、真实SQL/Redis写入或TC事务，不触碰默认中间件和历史现场。

八模块Jar共628个本模块生产class：Common10、Gateway7、User88、Product110、Cart15、Order123、Stock88、Pay187。逐class核验Jar与target/classes字节一致，并解析class的SourceFile和内部类型名对应现存src/main/java路径；未发现无生产源码对应的旧路径class或测试夹具。Jar哈希及各模块明细归档；这不是重新部署服务。1117个已跟踪backend/deploy文件和README逐字节保持，Git变更仅为本方案，实际产品行为未因本轮验证改变。

### 49.5 后续实库候选的具体输入与边界

本轮只做代码审查，不执行下表实库候选；精确测试路径和源码SHA保存到next-native-candidates.json。下一切片须重新核对并冻结端口/项目/DDL与方法清单。

| 候选 | 现有显式输入 | 关键隔离/清理规则 |
| --- | --- | --- |
| Cart十个Redis IT | cloudmall.redis.test.port | loopback、root密码、DB15；逐类核验精确自有cart key及关闭路径 |
| UserAccountRefactoringIT | cloudmall.user.refactor.mysql.port、cloudmall.user.refactor.redis.port | 自建cm_refactor_user_UUID库，读取原schema.sql；Redis DB15须空，AfterEach只接受auth:token:/balance:debit:前缀；AfterAll在失败后也会删自有库 |
| ProductRefactoringMySqlIT | cloudmall.product.refactor.mysql.port | 自建cm_refactor_product_UUID库，执行原product DDL段；ES/MQ叶子替身；AfterAll在失败后也会删自有库 |
| StockCoreRefactoringMySqlIT | cloudmall.mysql.test.url、cloudmall.stock.refactor.redis.port | 裸loopback cloudmall_stock URL；需七张原源表再克隆stock_core_UUID库，Redis DB15及精确key清单；close/部分setup失败会删自有库 |

User、Cart、Stock共享DB15的候选不得并行；每组运行前后核验空库/精确key，不能以FLUSHDB或清理未知key来绕过污染。Stock的cloudmall.mysql.test.url与Order/Pay的同名属性有不同库名/查询参数约束，后续按组顺序运行独立Maven命令，不把多个URL强行混成一次调用。原夹具失败即删库的行为必须如实记录，归档日志、SQL/binlog、源指纹和保留实验卷，不能承诺失败库必然仍在。

后续所称联合验证是同一源码点、同一新隔离资源组下的跨模块数据回归；上述夹具多数远端仍为替身，不自动升级为真实服务间下单/资金链。MQ/ES与TC/Nacos全链另行规划，运行前核对服务默认开关、持久协议与安全归属；不沿用旧固定默认环境执行器，不解除其防误用守卫。

### 49.6 证据与回退

方案提交69df88b0，基线标签`refactor/cross-module-offline-baseline-20261114`，完成标签`refactor/cross-module-offline-complete-20261114`。证据入口`target/backend-refactor-20261114/cross-module-offline/verification.json`，含分模块XML/哈希、冻结/实际测试清单、八Jar对应关系、子进程/指标证据、源码保持与下一实库候选。

```powershell
git revert refactor/cross-module-offline-complete-20261114
```

此回退仅撤销本轮结果文档，不改变业务代码、不撤销已经执行的测试或删除忽略证据。临时index逆补丁恢复父树，真实index和README保持；现有README及两个pycache未入提交，未使用clean/reset。原bugfix_log.md记录本轮结果。下一步冻结并执行独立MySQL/Redis联合数据回归；真实TC资金、全商城、MQ/ES与性能仍按各自未验收边界保留。


## 50. 独立MySQL/Redis联合数据回归（2026-11-14）

### 50.1 冻结范围与主Agent评审

从4048bada继续，主Agent单独执行。上一统一离线1933项/264类XML SHA和最终文档路径已核验，作为独立历史基线，本轮不重复累加。新执行四组13类55项现有显式IT，严格串行：Cart十个Redis IT共42项；UserAccountRefactoringIT全4项；ProductRefactoringMySqlIT全5项；StockCoreRefactoringMySqlIT全4项。精确类/方法及源码SHA落在本轮selection.json，不使用全库*IT通配符，不修改原断言或资源保护条件。

Cart核对真实Lua比较写删、数量并发、checked与清理快照、非法Hash/商品身份/价格及TTL；每例先用NX式Lua及owner字段占有随机cart key，AfterEach仅在owner值仍相同时删除。User使用真实账号/地址/余额Mapper和Spring事务、真实Redis token/余额标记，核验登录TTL、鉴权顺序、地址归属、扣款/退款的提交回滚与重放。Product使用真实本地事务/SQL核验CRUD与outbox版本、写失败回滚、分类/活动时间和DB提交早于Redis失败，Redis/ES/MQ边界为替身。Stock使用真实SQL/Redis及原HTTP/Service代理验证最后一件库存并发防超卖、预扣重放/拒绝/回滚、提交后标记清理及真实Lua秒杀受理/outbox幂等。

本轮不改生产、测试、POM或运行配置；出现失败先在原bugfix_log.md记录现象、归档首轮证据后定位，再冻结最小修复，不跳过失败宣告通过。User/Cart/Stock共享DB15，组间必须空库检查通过才继续；不得FLUSHDB或清理未知key。联合指同一源码点与新隔离资源组的多个模块数据回归，不等于真实远端服务串成下单/支付链。

### 50.2 新资源、源表与清理边界

新Compose项目`cloud-mall-refactor-native-data-20261114`，基于deploy/experiments/compose.yml的cm21 profile仅启动mysql/redis，候选端口19967/19968、子网10.254.223.0/24，统一root/root。启动前核对项目容器/网络/两个命名卷均不存在、loopback bind和IPAM不冲突、本地镜像ID及渲染配置；保持pull_policy: never/restart: no，不启动默认中间件或挂载历史卷。

MySQL仅预建cloudmall_stock七张空源表：stock_sku、stock_flow、stock_reservation_request、stock_cache_repair、seckill_reservation、seckill_order_outbox、seckill_quota_settlement，DDL逐条取自当前backend/database/schema.sql，不执行整份默认数据库/演示数据初始化。User/Product各自从原SQL创建UUID owned库；Stock每例克隆七源表到stock_core_UUID库。预计6个owned库（User1、Product1、Stock4），必须以实际CREATE/DROP事件、剩余schema及测试报告互证。源表前后DDL/计数/checksum保持。

每组开始/结束归档SQL schema列表、源表指纹、Redis各DB大小/INFO及DB15空库结果；只在本项目两个容器label身份吻合时操作。Cart遵循原owner条件清理；User AfterEach仅允许auth:token:/balance:debit:前缀并要求DB15清空；Stock只清自己的key清单。User/Product/Stock原夹具失败后也可能删除自有库，保留日志/binlog和实验卷，不能承诺失败库仍存在。

最终成功后归档两个容器inspect/log、精确建删库事件、源表及Redis检查，再down本轮容器/网络（不带-v），保留mysql_data/redis_data卷；归档或测试失败时不自动进行后续组/资源清理，也不动默认/history资源。若未知key/库残留，先记录调查，不以清空绕过验收。

### 50.3 执行与证据策略

先提交本节及`refactor/cross-module-data-baseline-20261114`。使用原Java17/Maven，按Cart→User→Product→Stock顺序，各组单独`-o -pl backend/cloud-mall-<模块> -am test`，使用精确类选择器与surefire.failIfNoSpecifiedTests=false、user.timezone=Asia/Shanghai；只统计本轮显式IT，不计未选择的Common旧XML。Cart显式cloudmall.redis.test.port=19968；User显式cloudmall.user.refactor.mysql.port=19967与cloudmall.user.refactor.redis.port=19968；Product显式cloudmall.product.refactor.mysql.port=19967；Stock使用裸cloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:19967/cloudmall_stock和cloudmall.stock.refactor.redis.port=19968。原夹具自行补JDBC参数，避免命令行&转义。

上一源码点已完成八模块编译/1933离线/八Jar检查且本轮源码无改动，不重复全矩阵或引入映射实现的新测试；各组Maven按标准布局自行编译。只在有修复时新增适用编译/单测/契约回归。13类方法须与冻结清单一致、无失败/错误/跳过，1117个backend/deploy文件及README字节保持。证据目录`target/backend-refactor-20261114/cross-module-data`，每组日志/XML/前后资源分开保存，不覆盖首轮失败记录。

通过后记录实际数据与验证限制，建立`refactor/cross-module-data-complete-20261114`；临时index逆补丁恢复父树，真实index/README保持。此阶段若仅更新文档，Git回退只撤文档，不回退MySQL/Redis运行事实；忽略证据和两个保留卷不受Git管理。后续Order/Pay持久事务/消息及真实TC/Nacos链继续另行冻结，不把55项数据回归当作全商城或性能验收。


### 50.4 实际验证结果与资源归档

四组按冻结顺序首次通过：13类55项，0失败、0错误、0跳过。每类实际方法名与selection.json完全一致，没有执行其他IT或把Common历史XML混入计数；生产、测试与配置均未修改。上一源码点的1933项/264类报告SHA核验结果独立保存，不与本轮55项累加为一次验证。

| 顺序 | 范围 | 测试项 | 本轮实际证明 |
| --- | --- | ---: | --- |
| 1 | Cart十个Redis IT | 42 | 真Redis/Lua、并发数量与checked、原JSON比较清理、Hash/商品身份/价格/数量校验和TTL |
| 2 | UserAccountRefactoringIT | 4 | 真MySQL/Redis、账号登录/资料/退出与Token TTL、鉴权错误顺序、地址归属和默认值、余额提交/回滚/重放/退款 |
| 3 | ProductRefactoringMySqlIT | 5 | 真MySQL及Spring事务、商品CRUD与outbox版本共同提交、插入/更新失败回滚、分类默认值、活动时间及DB成功早于Redis失败 |
| 4 | StockCoreRefactoringMySqlIT | 4 | 真MySQL/Redis、六请求竞争最后一件库存、预扣重放/拒绝/故障边界、提交后标记清理及外层回滚保留、Lua秒杀受理/outbox幂等 |
| 合计 | 13个现有显式IT类 | 55 | 四个独立Maven调用串行执行，真实数据操作仅进入新实验资源 |

Cart日志记录42个不同自有cart key均经owner条件删除；每组前后和最终Redis所有16个DB均为空。User/Product各自表内写入、故障注入与断言在随机owned库完成；Stock四个用例各用一个stock_core_UUID库。实际User1、Product1、Stock4共六个owned库全部删除，六对CREATE/DROP事件与User/Product输出的UUID、最终schema清单互证，没有新增失败数据库。七张Stock源表最终均0行，SHOW CREATE TABLE与checksum逐项和初始相同。

实际资源版本为MySQL8.0.46、Redis7.0.15；启动前核验新项目/新卷、IPAM与端口，核对渲染配置后仅启动mysql/redis，宿主19967的MySQL握手与19968的Redis认证/PING通过。原镜像ID/标签归属检查、十份initial/before/after/final快照、两个容器inspect/log、binlog建删事件和各组XML均归档。归档门通过后才down本轮容器/网络（不带-v），保留以下两卷：

- `cloud-mall-refactor-native-data-20261114_mysql_data`
- `cloud-mall-refactor-native-data-20261114_redis_data`

本轮无失败修复、无临时放宽测试保护条件；四组Maven按标准布局完成适用编译和测试，未重复上一轮整仓1933测试或八Jar打包。1117份已跟踪backend/deploy文件及README逐字节保持；历史/default资源未动。原夹具失败后也删库的限制仍成立，不能将本轮成功结果解释为后续失败现场必然保留。

### 50.5 通过口径、回退与后续

本轮通过说明四个模块在同一源码点、新隔离MySQL/Redis资源组下的持久化/事务/Lua契约回归通过。Product Redis故障为受控替身，Cart商品与各模块远端依赖仍按原夹具替换；并未启动实际Cart→Product、Order→Stock、Pay→User全服务链，也未验证真实MQ/ES、Nacos/Gateway、TC/RM资金或性能。

方案提交d11c3373，基线标签`refactor/cross-module-data-baseline-20261114`，完成标签`refactor/cross-module-data-complete-20261114`。证据入口`target/backend-refactor-20261114/cross-module-data/verification.json`，保存精确选择/方法、四组报告/日志、前后资源/事件、两个保留卷、源码保持和逆补丁证明。

```powershell
git revert refactor/cross-module-data-complete-20261114
```

本轮Git结果提交仅含方案记录，回退不撤销已运行测试或恢复MySQL/Redis数据。临时index逆补丁恢复父树，真实index/README保持，未使用clean/reset；忽略工件与保留卷不受Git回退管理，旧pycache和README未入提交。原bugfix_log.md已补完整结果。

下一步核对并冻结Order/Pay持久事务与消息回归清单，明确订单分片源表、支付意图/回执、Rabbit拓扑和各夹具URL/失败清理规则，再使用新的独立资源执行。真实TC资金与全商城全链仍单独规划，不从本轮55项数据验证推导完成。


## 51. Order/Pay持久事务与真实消息回归（2026-11-14）

### 51.1 范围与主Agent评审

从83e74d1f继续，主Agent单独执行；上一55项实库报告逐SHA核验并独立保存，不与本轮累加。冻结四组8类29项现有IT，顺序为OrderCreationShardingShadowMySqlIT全2项、OrderStateMySqlIT全3项、OrderSeckillMessageMySqlRabbitIT全4项与OrderTimeoutMySqlRabbitIT全5项、PayCreation/Success/Failure/CallbackMySqlIT全3/3/4/5项。精确方法与源码SHA保存selection.json，不运行巨型OrderCreationPrecision全矩阵、固定环境TCC/全商城或性能测试。

创建影子用同一原业务/事务逻辑分别走真实物理路由与Sharding-JDBC，四个月固定窗口202611/202612/202701/202702，验证独立intent先提交、订单/明细/幂等/outbox共同提交和outbox触发器故障时本地回滚/原补偿；Product/User/Stock/Cart为替身。状态测试验证paid/cancel与库存动作原子提交及失败回滚、confirm原autocommit写不因后读失败撤销。消息测试实际Rabbit投递/TTL+DLX/AUTO确认、原最多3次重试（1秒初始、2倍退避）、事务代理内失败回滚、死信原文/冲突证据及终态重放；不改变原Rabbit拓扑/消费者代码。

Pay四类验证创建模式/幂等与插入后失败回滚、成功入口模式优先级/监视器/回读、Legacy失败独立事务/旧RR当前读及TCC failure真实暂停恢复、回调独立claim/complete和恢复扫描。User资金、Order远端及TCC证明仍为受控替身，不把真实SQL或Rabbit理解为真实TC资金全链。

本轮预期只更新方案/验收记录，不改生产、测试、依赖或配置。失败先在bugfix_log记录并归档首轮，再定位和冻结必要最小修复；不删断言/跳过失败。源码、README和历史/default资源保持。

### 51.2 独立资源、模板与清理

新项目`cloud-mall-refactor-order-pay-data-20261114`，使用deploy/experiments/compose.yml的cm21 profile仅启动mysql/rabbit，候选MySQL19969、AMQP19970、管理HTTP19971，子网10.254.222.0/24，root/root，新mysql_data/rabbit_data卷。启动前核对资源不存在、端口bind/IPAM、镜像ID及渲染配置；保持pull_policy: never/restart: no，不启动现有broker或读取历史卷。

此broker专属于本轮，测试沿用其默认vhost `/`及原OrderMessagingConfiguration五队列/五业务交换机；不是复用默认业务broker。组前后归档管理API队列/交换机/绑定/连接，消息组前必须无消息，之后要求五原队列ready/unacked/消费者为0、无残留AMQP连接；禁止purge或清理未知消息绕过失败。拓扑可保留在本轮卷，成功归档后随自有broker下线。

源库只准备十张空表：Order三模板mall_order/mall_order_item/order_status_log及order_idempotency/reservation_intent/outbox/stock_action四台账；Pay三表pay_record/callback_log/debit_intent。SQL来自当前schema.sql，item/status模板取原202608定义仅替换表名，不创建多余业务月表。owned夹具再克隆模板并创建各自月份表，源表前后DDL/计数/checksum须相同。

预计32个owned库：创建影子4（cm17eh_）、状态3（order_state_refactor_）、秒杀消息4（order_seckill_msg_）、超时5（order_timeout_refactor_）、Pay16（creation4/success3/failure4/callback5；创建首方法分别验证LEGACY/TCC两个库）。创建影子、消息、Pay按原成功门归档后才删库；OrderState原close/setup失败会删库，此限制明确保留。通过前不删除失败现场，不扫描前缀批量删库。成功后用原生清理记录、实际CREATE/DROP事件、源指纹和剩余schema互证，先归档再down本轮容器/网络（不带-v），保留两卷。

### 51.3 顺序执行与回退

先提交本节与`refactor/order-pay-transactions-baseline-20261114`。四组各用原Java17/Maven `-o -pl backend/cloud-mall-order或pay -am test`，精确类选择器、surefire.failIfNoSpecifiedTests=false与user.timezone=Asia/Shanghai。创建影子独立使用cloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:19969/cloudmall_order?allowPublicKeyRetrieval=true（原boundedUrl自行补超时/时区）；状态和消息用裸cloudmall_order URL，消息另设cloudmall.rabbit.test.port=19970；Pay组用裸cloudmall_pay URL。各夹具要求不同，不为合并命令放宽URL守卫。

每组通过并完成前后资源检查才进入下一组；两消息类共用本轮broker但按默认非并行Surefire执行，各例按原代码停止消费者并核验空队列。创建影子的完整class-start/class-finish、物理/逻辑facts和连接关闭证据，Pay十六个UUID的before-cleanup/finished报告，消息原成功清理行和各组XML均归档到`target/backend-refactor-20261114/order-pay-transactions`。只统计本轮29方法，不混入历史XML。

源码无改动时不重复此前1933离线/55数据或八模块打包；四组Maven完成各自适用编译与原生测试。1117份backend/deploy文件及README按字节保持；新增修复则另补适用回归。完成后建立`refactor/order-pay-transactions-complete-20261114`，临时index逆补丁恢复父树、真实index/README保持，不clean/reset。文档回退不恢复运行数据或删除忽略证据/保留卷。MQ/ES商品同步、真实Gateway/Nacos/TC及商城链仍为后续独立范围。


51.3启动准入补充：本轮Rabbit服务已启动且管理HTTP认证正常，但原3秒rabbitmq-diagnostics ping健康探测连续超时。实测同一探测正常退出0、Ping succeeded，耗时约4.03秒；CPU/内存观察未见资源耗尽。业务测试尚未开跑，lab健康守卫阻断了DDL/测试。保存首个容器inspect/log与探测耗时后，仅在本轮compose.override.yml将Rabbit healthcheck.timeout改为10秒，保持原探测命令、间隔/次数、镜像/认证/资源限制和共享Compose不变。只重建本轮Rabbit容器，继续使用本轮刚建立且未投递业务消息的新卷，不访问历史卷；重新健康/协议/空队列准入后才执行原测试。


### 51.4 本轮执行结果与启动问题闭环

Rabbit启动准入问题经本轮局部10秒探测时限修正后，四组业务测试首次执行全部通过：8类29项，0失败、0错误、0跳过。开跑前按PayCreation双模式分支校正了资源清单，3个方法实际创建4个库；方法清单不变，最终Pay16库、全轮32库。未修改生产/测试/共享Compose、未降低原断言或重试守卫。启动门失败与业务测试结果分别记录，不能把首次容器准入写成一次通过。

| 组 | 类/测试项 | 实际验证 |
| --- | --- | --- |
| 创建影子 | 1类/2项 | 4个UUID夹具，物理表/真实Sharding-JDBC对照，固定四月窗口与毫秒时间；独立intent、订单/明细/幂等/outbox提交、outbox故障回滚及原补偿 |
| Order状态 | 1类/3项 | paid/cancel和stock action同事务；动作插入失败共同回滚；confirm原autocommit成功不因后读失败撤销 |
| Order消息 | 2类/9项 | 实际broker原5队列/5业务交换机、秒杀消费与死信、TTL/DLX关单、原1秒/2倍/最多3次重试、SQL失败回滚、非法消息与终态重放 |
| Pay公开事务 | 4类/15项 | 创建模式/幂等/并发和失败回滚；成功模式检查/回读；Legacy独立失败及实际TCC failure暂停/恢复；回调独立claim/complete及恢复扫描 |
| 合计 | 8类/29项 | 四组Maven串行，实际方法与冻结清单一致，无遗漏或额外IT |

创建影子的class-finish确认4库全部删除、source指纹保持、无preserveRequired/errors；物理/逻辑facts、连接关闭、delete-gate/cleanup和原生目录完整归档。Pay十六个UUID均有successful/deleted为true的finished报告；创建4、成功3、失败4、回调5。消息九例按原成功门清理，状态三例沿原close规则（失败也可能删库），这些不同清理语义未混写为同一保证。

实际32库的CREATE/DROP集合完全一致：cm17eh_ 4、order_state_refactor_ 3、order_seckill_msg_ 4、order_timeout_refactor_ 5、Pay16。十张源表最终0行且DDL/checksum与初始相同。Rabbit `/` vhost保留原5队列/5业务交换机/空路由键绑定，组结束及最终ready/unacked/消费者均0、AMQP连接0；没有purge消息或删除未知资源来通过检查。十份initial/before/after/final资源快照、原生报告和binlog事件互证。

本轮资源实际为MySQL8.0.46、RabbitMQ3.11.28。首个Rabbit实例服务正常，但3秒CLI探测超时；同命令实测约4.03秒退出0，管理认证和资源观察正常。本轮最终渲染配置与原渲染逐字段对照，唯一差异为rabbit.healthcheck.timeout从3s变10s。重建自有Rabbit后各阶段Docker健康检查均通过，宿主MySQL/AMQP/管理HTTP检查通过，9项真实消息测试及最终空队列检查通过。首次inspect/log、测时、局部覆盖及最终健康检查均保留；共享Compose未“顺手修复”，后续新实验需自行冻结适用健康时限。

归档成功后才down本轮MySQL/Rabbit容器及网络，保留：

- `cloud-mall-refactor-order-pay-data-20261114_mysql_data`
- `cloud-mall-refactor-order-pay-data-20261114_rabbit_data`

1117份已跟踪backend/deploy文件及README逐字节保持；现有broker、中间件及历史卷未动。四组Maven完成适用编译/测试，未重复此前1933离线/55实库或八Jar打包。没有新增业务测试失败或生产缺陷修复，启动时限调整仅属于本次隔离实验。

### 51.5 边界、回退与后续

真实消息验证覆盖Order的秒杀/超时/指定outbox发布路径；Pay验证本地持久化与事务，远端User/Order、Cart/Product/Stock及TCC资金证据仍按原夹具受控。没有部署完整Gateway/Nacos链或真实TC/RM资金，没有验证真实Stock端库存RPC、商品MQ/ES同步、全商城或性能。29项结果独立于此前55/1933，不相加为一次全链验证。

方案提交0bca9a2f，基线标签`refactor/order-pay-transactions-baseline-20261114`，完成标签`refactor/order-pay-transactions-complete-20261114`。证据入口`target/backend-refactor-20261114/order-pay-transactions/verification.json`，包含精确29方法、四组XML/日志、创建影子和16个Pay夹具、32库事件、10源表/Rabbit拓扑快照、首个启动门失败与局部修正、两个保留卷、源码保持和逆补丁证明。

```powershell
git revert refactor/order-pay-transactions-complete-20261114
```

Git结果提交仅含方案记录，回退不恢复运行数据库/消息、忽略覆盖配置或证据。临时index逆补丁恢复父树，真实index/README保持；旧pycache与README未入提交，未clean/reset。原bugfix_log.md记录启动问题修复前现象、根因、局部方案及实际验证。

下一步先梳理商品MQ/ES同步与真实服务链的现有入口和隔离要求，冻结新的有限验证范围；不直接启动历史CM21压测/guardian流水线，不把旧固定环境或未验证TC链当作本轮已完成。

## 52. Product 常规索引真实 MySQL/Rabbit/ES 有限回归（2026-11-14）

### 52.1 范围与调用链冻结

从e66d95a6继续，由主Agent单独执行。现有ProductRefactoringMySqlIT已覆盖管理入口与outbox同事务，但MQ/ES为替身；原ProductOnlineMetricsPipelineIT同时涉及CM21指标控制、负载和历史实验，不直接运行或改写。新增标准Maven测试ProductIndexSynchronizationIT，仅验证metrics.enabled=false的常规内容索引，不改生产源码、依赖、原消息/SQL/搜索配置与历史runner。

原调用链为事务中ProductIndexOutboxService.append → 原OutboxMapper → 原Dispatcher确认/returned → 原ProductMessagingConfiguration主队列 → 原Consumer → 真实MyBatis IndexMapper/Loader当前事实 → 原ElasticsearchProductIndexWriter → 原ConsumptionService事务内消费记录与outbox确认。死信沿原三次消费重试、原文落库/手工ACK与原DlqReplayer核对outbox后重放。沿用原两交换机、三队列、路由键及10秒死信证据重试TTL。

新增4个明确用例：

1. 事务回滚不留下事实/outbox；提交后真实发布，核对JSON/messageId和确认截止时间；消费当前事实、重复事件不重复记录、旧事件STALE及下架可见性；通过真实关键词搜索适配器检查索引查询结果。这里从事务/outbox seam进入，管理HTTP的结论仍独立引用第50节。
2. 真实ES external_gte单条/有界Bulk拒绝旧商品版本覆盖；已有内部版本且缺少可信productVersion的409必须报错，不能伪装成功。
3. 仅将本例自有索引设为write block触发真实ES写失败，核对三次消费尝试、RETRY及完整DLQ原文；恢复写入后原replayer发布同一事件并形成消费终态/outbox确认，不改重试参数。
4. 非JSON旧格式字节与主键不一致JSON通过真实队列，验证完整字节/摘要隔离；原replayer拒绝无可信outbox的JSON，不应用ES、不反序列化旧Java字节。

原消费/加载事务代理、Mapper SQL、Rabbit监听工厂与ES适配器全部实际执行。仅调度时钟入口受控，逐次调用原dispatcher/replayer，不测试周期时序；不启动重建任务、在线指标、Redis、Nacos、TC或商城HTTP链，不把这4项解释为所有历史索引场景通过。常规物理删除与V2 tombstone差异不在本轮改变。

### 52.2 隔离与失败现场

新Compose项目cloud-mall-refactor-product-index-20261114，仅启动deploy/experiments/compose.yml的mysql/rabbit/es；新三卷、候选端口19972/19973/19974/19975（MySQL/AMQP/管理HTTP/ES）、子网10.254.221.0/24。先确认项目/卷不存在、bind/IPAM、镜像ID和渲染配置，pull_policy=never、restart=no。依据第51节测时，本轮独立override预设Rabbit健康探测timeout=10s，共享Compose保持。

测试显式要求隔离高位端口、实验runId、预先核验的MySQL server_uuid及ES cluster.name，拒绝缺省环境。每例创建UUID数据库与索引；仅从当前schema.sql提取product、product_hot_stat、product_index_outbox、product_index_consumption、product_index_dlq_quarantine五表DDL，不需要源库/历史模板。broker独占，沿原默认vhost和原拓扑，每例前后检查空队列。初始不自动消费，首例读取实际发布消息验证envelope后原样投回；随后启动原监听器。

成功才归档SQL/ES/消息事实并清理该例已成功创建的精确UUID库/索引；任何失败关闭连接/消费者，保留库、索引及队列，不purge、不按前缀批量删除。全部通过后核对四库四索引建删、源代码保持、三队列ready/unacked/consumer=0及连接0，归档容器日志和binlog，再down本轮容器/网络而不带-v，三个新数据卷保留。

### 52.3 验证、提交与回退

先提交本节并建立refactor/product-index-sync-baseline-20261114。新增Java执行google-java-format；离线编译并精确运行ProductIndexSynchronizationIT，另跑Common/Product现有*Test回归；核对真实执行方法、0失败/跳过以及报告时间，旧1933/55/29结果不累加。证据统一归档target/backend-refactor-20261114/product-index-sync。

仅暂存新测试和本方案。原1117份backend/deploy及README按字节保持，保留已有README和两个pycache工作树变化。结果提交前git diff --check，临时index逆补丁恢复父树且真实index/README保持；完成标签refactor/product-index-sync-complete-20261114。回退只撤销测试/记录，不还原运行数据或忽略证据。测试新增缺陷/实验失败先在原bugfix_log记录，再最小修复并保留首轮证据。

### 52.4 实际验证结果

新增ProductIndexSynchronizationIT编译及4方法首次全过，0失败/错误/跳过。另一次独立Common/Product离线回归41类386项（Common25、Product361）首次全过，原生命周期测试的自有Java子进程退出/关闭证据已归档。两次执行各有精确方法清单、XML、日志及SHA，不与此前1933/55/29相加为单次全链验证。生产源码、依赖和原测试未改；新增文件只承载本轮原生集成测试。

| 方法 | 实际观察 |
| --- | --- |
| committedEventsKeepEnvelopeReplayCurrentFactsAndVisibility | 事实/outbox共同回滚；真实broker JSON与messageId匹配原outbox；发布后ACK截止时间存在，消费后清空；重复事件attempts仍1；旧事件读取当前version3并记录STALE；version4下架后status=1搜索0条、无status过滤1条 |
| externalVersionsRejectOldSingleAndBulkWritesAndUntrustedConflicts | 原ES writer单条和Bulk的旧写均保留version5、ES外部版本6及新内容；内部版本文档缺productVersion时，两路径都报错而非吞掉409 |
| blockedElasticsearchRetriesThenReplaysOriginalDeadLetter | 自有索引write block下真实失败3次，消费RETRY/attempts=3、原文/长度/SHA已持久化；解除阻断后原replayer同eventId重放，APPLIED/attempts=4、隔离记录REPLAYED、单条outbox确认完成 |
| invalidMessagesKeepOriginalBytesAndCannotBeBlindlyReplayed | 原始Java格式前缀测试字节及主键不一致JSON经过原主队列/死信；完整原文/SHA保持；JSON因无可信outbox标记unverifiable-envelope，非JSON保留dead-letter；0消费事实、0outbox、0ES文档 |

真实Spring代理负责Loader只读事务与Consumption记录/ACK事务；真实MyBatis/JDBC、原三次重试工厂、两监听器、原路由与ES7.17客户端均执行。只有TaskScheduler为不触发周期任务的替身，原dispatcher/replayer逐轮显式调用。搜索验证停留在真实关键词适配器，不把它扩写为完整HTTP分页/权限/最终MySQL页校验验收。

资源实际为MySQL8.0.46、RabbitMQ3.11.28、Elasticsearch7.17.0，runId为refactor-product-index-20261114-0b30033e。4份started/before-cleanup/finished均成功；四对MySQL建删binlog事件、ES四组创建/删除原生日志、最终无业务schema/index互证。原三队列/两交换机/三路由及TTL参数精确核对，最终ready/unacked/consumer均0且AMQP连接0；无purge。ES原生.geoip_databases系统索引UUID与33文档保持，未删除或关闭其内建功能。

实验脚本有三处准入/归档修正，均与首次Java测试结果区分：Docker默认网络IPAM.Config为null，改为按无子网处理后继续核验真实子网；新ES自动创建GeoIP系统索引，归档首个快照后改为核对其身份和业务索引为空；旧容器Mounts原按数组顺序比较，复查完整挂载字段相同，改为保存原始inspect并按Destination比较完整对象。第三处首个失败数组未保存，不能追溯当时顺序；最终完整字段核验通过。没有修改原生产保护或删除断言绕过业务失败。三处现象/方案/结果已写入原bugfix_log。

全部证据归档后，已移除本轮三个容器及网络，保留三个卷：

- cloud-mall-refactor-product-index-20261114_mysql_data
- cloud-mall-refactor-product-index-20261114_rabbit_data
- cloud-mall-refactor-product-index-20261114_es_data

原1117份backend/deploy文件与README逐字节保持；原容器ID、镜像、启动时间、重启次数及完整挂载保持。本轮只新增一份测试，未复跑八模块打包或更改业务代码。格式检查、适用编译、两组回归及git diff --check通过。

### 52.5 回退与下一步

方案提交e958afc9，基线标签refactor/product-index-sync-baseline-20261114，完成标签refactor/product-index-sync-complete-20261114。证据入口target/backend-refactor-20261114/product-index-sync/verification.json；测试位于backend/cloud-mall-product/src/test/java/com/cloudmall/product/ProductIndexSynchronizationIT.java，显式系统属性及实际命令归档native-command.json，不能无参数连接默认环境。

```powershell
git revert refactor/product-index-sync-complete-20261114
```

提交仅含本方案和新增测试；临时index逆补丁恢复父树，真实index与README保持。回退不会还原已清理的测试库/索引，也不会删除忽略证据或三个保留卷。Java测试全部通过，脚本首轮问题与修正保留，不宣称整个实验过程零失败。

当前已验证常规内容索引的上述四个有限路径。CM21在线指标、全量重建/周期恢复、confirm丢失等完整故障矩阵、Nacos/Gateway真实服务、真实TC/RM资金与商城全链仍未因此获得验收。下一步先盘点现有真实服务/TC最小启动与验收入口，冻结独立资源和有限链路，避免重启旧压测或无界扩展历史维护任务。

## 53. User LocalTCC / 官方 Fence 真实 TC 有限回归（2026-11-14）

### 53.1 入口盘点与范围冻结

从1e8ceac3继续，主Agent单独执行。检查当前真实链路入口：BalanceTccFenceSeataIT可显式指定MySQL与TC，实际UserApplication/NONE上下文加载原LocalTCC资源；BalanceTccRmFailureIT包含子JVM中断/链路故障，后置；PayUserTccBridgeIT仍硬编码127.0.0.1:18848及部分TC缺省环境，且启动两个User进程/代理，不直接跑到默认中间件。本轮选择最小、已有独立地址入口的User LocalTCC切片，不新增资金行为或修改旧测试守卫。

依据batch15_cm18_user_local_tcc_fence_review.md与当前BalanceTccResourceConfiguration，沿用useTCCFence=false、显式官方Fence与业务callback共享同一裸DataSource/本地REQUIRED事务。真实TM向TC begin，原代理注册User分支并Try；TC通过已注册RM网络连接调用Confirm/Cancel。冻结BalanceTccFenceSeataIT完整6方法：真实提交及Fence清理后重放；真实回滚只释放一次；空回滚永久tombstone/阻断迟到Try；取消插入失败时Fence/业务共同回滚且TC重试恢复；重复Try第二分支被拒而全部回滚收敛；余额不足无残余冻结。

部分重放和迟到Try沿原测试直接调用原适配器；初始决议与清算通过真实TC/RM。6项不覆盖Pay/Order/Stock、Nacos/Feign/Gateway、User HTTP入口、RM崩溃恢复、TC重启或全支付链。所有原分支/锁/状态/账本断言保持，未知状态不能推断成功。

### 53.2 独立资源与清理边界

新项目cloud-mall-refactor-user-tcc-20261114，在deploy/experiments/compose.yml基础上通过本轮独立override仅启动mysql及新增seata服务；MySQL19976、TC19977、子网10.254.220.0/24，新mysql_data/seata_data卷。MySQL沿原8.0.46；TC复用已存在的seataio/seata-server:1.5.2镜像ID，file注册/config/store、restart=no、pull_policy=never。TC显式SEATA_IP=127.0.0.1与SEATA_PORT=19977，容器同端口映射，回调走RM已建立连接；不发布控制台7091。镜像ParameterParser/ContainerHelper字节码已只读核验环境变量解析，保存到本轮证据目录，不依赖猜测旧默认TC地址。

启动前检查项目/卷不存在、端口bind/IPAM、镜像ID与渲染配置。TC健康检查本容器19977，只有健康及宿主TCP可达后运行；真实协议通过最终TM/RM验收，不把TCP探测当事务通过。新源库cloudmall_user仅准备当前schema.sql的五张空表mall_user、user_address、balance_ledger、balance_debit_attempt、balance_tcc_debit；不复制旧业务数据。原夹具创建一个cm15_tcc_it_<UUID>并克隆五表，原初始化器在owned库生成Fence/receive及本地admin。

原夹具每例清理本例库的旧事实，AfterEach等待冻结归零及真实TC终态，无法收敛设置preserveFixture并保留库。该守卫不等于“任何断言失败都保留”：若断言失败但后续已收敛，原AfterAll仍可能删库；本轮明确保留此原语义，不用删除后的现场冒充逐例快照。失败立即保存Maven/XML/TC日志和剩余库，禁止外部扫前缀删库或直接改余额结案。成功以本轮六项断言、TC全局/分支日志、UUID建删事件和源表指纹互证。

显式传入cloudmall.mysql.test.url=jdbc:mysql://127.0.0.1:19976/cloudmall_user?allowPublicKeyRetrieval=true、cloudmall.seata.test.tc=127.0.0.1:19977、Asia/Shanghai JVM时区。原Nacos发现/注册关闭；Redis与Nacos地址额外指向已核对未监听的19978/19979且禁Redis health，防止意外读取默认服务。本轮不启动Redis/Nacos/Rabbit。成功归档TC日志及file sessionStore后，再down本轮两容器/网络（无-v），保留两个卷；默认与历史资源保持。

### 53.3 执行、证据与回退

先提交本节并建立refactor/user-tcc-native-baseline-20261114。使用原Java17/Maven，精确选择-o -pl backend/cloud-mall-user -am test -Dtest=BalanceTccFenceSeataIT，surefire.failIfNoSpecifiedTests=false，串行完整6方法，无假成功跳过。源码无变化时不重复此前离线1933或Product386，编译由本次Maven完成。

证据target/backend-refactor-20261114/user-tcc-native包含源码/方法清单、显式执行命令、源DDL/前后指纹、镜像与资源准入、精确6项XML/日志、TC真实分支/全局终态、owned建删事件、TC文件存储及关闭结果。1118份既有backend/deploy文件与README逐字节保持；结果仅更新本方案，提交前git diff --check，临时index逆补丁恢复父树并保持真实index/README。完成标签refactor/user-tcc-native-complete-20261114；Git回退不恢复运行数据库或删除忽略证据/保留卷。实验失败先记录原bugfix_log，再定位最小必要修正。

53.3执行前置修正：首轮命令漏带README已明确要求的JDK17参数--add-opens=java.base/java.lang=ALL-UNNAMED，Seata1.5.2/CGLIB在GlobalTransactionScanner初始化阶段抛InaccessibleObjectException；6个业务方法尚未执行，Surefire为1个类级error。保留首轮日志/XML/命令与资源后，仅在本轮argLine补该参数，其他地址/方法/断言不变。原夹具已自动删除首轮启动失败UUID库，TC无业务事务；第二轮创建另一UUID，源五表保持。不能把后续通过写成首次启动通过。

### 53.4 本轮结果与真实调用证据

补齐README已有的Java17参数后，原BalanceTccFenceSeataIT完整6项全部通过，0失败/错误/跳过；初始启动失败单独归档，不能记为首轮全过。最终命令与首轮逐参数对照，唯一新增是--add-opens=java.base/java.lang=ALL-UNNAMED。没有改生产、测试、依赖、共享Compose、测试超时或断言。前一轮Product386/4报告SHA本轮核验但没有重跑，不计入本次6项。

| 原测试方法 | 本轮实际验证 |
| --- | --- |
| realTcCommitProducesOneLedgerAndReplaysSurviveFenceCleanup | 真实Try冻结10；TC提交后余额90、冻结0、唯一DEBIT账本、Fence=2；重复提交/迟到回滚及Fence清理后重放保持原资金 |
| realTcRollbackReleasesFreezeOnce | TC回滚后余额100、冻结0、0账本、Fence=3；重复回滚与相反提交不重复释放，Fence清理后仍幂等 |
| realTcEmptyRollbackWritesPermanentTombstoneAndBlocksDelayedPrepare | 真实注册未Try分支后TC空回滚；Fence=4、永久CANCELLED且无TCC冻结行；清Fence后迟到原分支Try仍被拒 |
| emptyCancelFailureRollsBackFenceAndTcRetryFinishesAfterRepair | 插入触发器使首次取消失败，Fence/attempt均0、资金不动；移除本例触发器后TC RetryRollbacking线程自动完成，永久取消与Fence=4一致 |
| repeatedTryCannotCreateASecondFundingBranchAndBothRollbacksConverge | 同请求第二Try分支被拒，没有第二次冻结；两个分支回滚收敛，单attempt、两Fence、余额100/冻结0/0账本 |
| insufficientFundsRollBackTryFenceAndRealTcCancellationLeavesNoFreeze | 101扣款大于100余额时Try整体回滚，原无Fence/attempt；随后真实TC取消形成终态，无冻结或账本 |

TC日志与客户端日志互证：实际Seata客户端/服务器均1.5.2，唯一User RM资源cloudmallUserBalanceDebit，6个全局事务、7个注册分支，1个全局提交与5个全局回滚终态覆盖全部已begin事务。受控取消故障有明确“fail and will retry”，随后同分支由RetryRollbacking线程成功清算；不存在仅以Docker healthy代替业务结论。实际UserApplication完成启动，使用重构后的service.impl.tcc资源，Hikari与TM/RM连接正常关闭。

本轮file客户端实际连接127.0.0.1:19977；虽然容器配置包含SEATA_IP=127.0.0.1，实际XID中的TC前缀是容器IP，不能从环境变量推导其生效地址。本次注册、决议与回调全部通过；将该实际差异保存在tc-transactions.json，未为“统一显示”更改TC或XID，更不将此有限结果扩写为跨容器/重启后的地址稳定性验收。

原夹具两轮共创建并删除2个UUID库：首轮初始化失败1个、第二轮成功6项共用1个。binlog两对CREATE/DROP与最终schema核对一致；五张空源表DDL/计数/checksum始终相同。原测试逐例会重置owned事实，因此归档的是实际断言结果、真实TC日志和建删事件，没有虚构逐例删除前快照或宣称任意失败都保留数据库。

本轮核验1118份既有backend/deploy文件及README字节保持，既有容器ID/镜像/完整挂载/启动时间/重启次数保持。源码无变化，本次Maven已完成适用编译，未重复全仓离线或打包。归档日志后正常停止自有TC，复制其sessionStore/root.data及摘要，再down本轮容器/网络；保留cloud-mall-refactor-user-tcc-20261114_mysql_data和cloud-mall-refactor-user-tcc-20261114_seata_data两卷，未清理历史/default资源。

### 53.5 回退与后续有限任务

方案提交a63a1f92，基线标签refactor/user-tcc-native-baseline-20261114，完成标签refactor/user-tcc-native-complete-20261114。证据入口target/backend-refactor-20261114/user-tcc-native/verification.json，包含首轮类级启动失败、修正前后命令、6方法XML、6全局/7分支及重试事实、两库建删、五源表指纹、TC文件存储、资源关闭与源码保持。

```powershell
git revert refactor/user-tcc-native-complete-20261114
```

结果提交只包含本方案；临时index逆补丁恢复父树且真实index/README保持。回退不恢复已清理测试库、TC运行态或忽略证据，不删除两保留卷。原bugfix_log已记录首轮漏参数的现象、根因、修正及回归结果。

下一步单独整理Pay/User跨进程验收夹具的显式Nacos/TC地址、子进程参数传播和独立资源清单，再冻结发现/Feign派发桥的有限验证；不直接使用旧硬编码环境。User RM本轮通过不代表Pay公开TCC入口、Order/Stock联动、RM进程中断/TC重启、Gateway商城链或性能已验收。

## 54. Pay/User 派发桥显式环境与真实发现回归（2026-11-14）

### 54.1 测试夹具重构范围

从e7dcd8b1继续，由主Agent单独执行，依据batch16_cm18_pay_user_tcc_bridge_review.md及当前代码。PayUserTccBridgeIT的NamingService固定18848，Pay/User应用没有显式传入同一Nacos/TC/Redis参数，无法安全用于独立Compose实例。本轮只重构该测试的环境装配：新增纯测试PayUserTccBridgeEnvironment及其参数拒绝/传播测试，原9个业务方法、事务/故障/断言/重试上限不变；不改生产、API/Feign、固定技术栈或其它历史runner。

环境必填cloudmall.mysql.test.url、cloudmall.nacos.test.address、cloudmall.seata.test.tc、cloudmall.redis.test.port。仅接受127.0.0.1和19000..65535隔离端口，MySQL限定cloudmall_user及可选allowPublicKeyRetrieval=true；拒绝缺省、非loopback、格式不符、服务端口或Nacos HTTP+1000/+1001碰撞/溢出，在任何建库/外部客户端创建前失败。统一生成父Pay/两个子User的Nacos认证/地址、TC地址、Redis配置，子JVM保留Java17 add-opens并显式Asia/Shanghai。内部凭证固定为本测试原有本地值；Pay不需要的Rabbit health关闭并指向未启用端口，防止误触默认broker。

原NamingService使用同一显式Nacos，保留随机cm16_<UUID>分组、两User原随机HTTP端口/代理、稳定User Seata application-id。增加自有子进程PID/端口/环境归属与结束回执；原强制中断测试语义保留，但等待结束必须成功才允许后续清理。原9方法正文逐一核验保持，不通过调整业务期望掩盖回归。

### 54.2 实验资源与验收矩阵

新项目cloud-mall-refactor-pay-user-bridge-20261114，在deploy/experiments/compose.yml上叠加本轮override，仅MySQL8.0.46、Redis7.0、Nacos2.2.0、Seata1.5.2；候选端口19980/19983/19982/19981分别对应MySQL/Redis/NacosHTTP/TC，Nacos gRPC20982/20983，子网10.254.219.0/24。新mysql_data/redis_data/seata_data/nacos_data/nacos_logs五卷，pull_policy=never/restart=no，默认与历史卷不接入。

Nacos沿原standalone、稳定hostname、认证与现有healthcheck.py，使用本轮只读挂载，健康后沿项目既有bootstrap流程在本轮实例建立root/root及原管理角色/权限，随后以root/root核验登录和发现；不关闭鉴权或改默认Nacos。TC file模式与第53节同类装配，显式宿主端口，实际XID前缀按日志记录，不从SEATA_IP猜测。四服务健康/端口/身份与新卷准入后才准备源表。

源库只准备当前schema.sql八张空表：User五表（mall_user/address、ledger、attempt、tcc_debit），Pay三表（record、intent、callback）。原测试创建一User、一Pay UUID库并自建其余派发表/Fence/receive；原九例逐笔快照继续归档。Redis初末16DB为空；Nacos新实例初始没有业务注册，结束原组无User实例，旧服务不注册到本组。

运行原9项：提交及重复派发只GET、回滚、POST响应截断后只读恢复、余额不足保持未知直至取消、双User并发只登记一分支、内部认证/身份与Legacy拒绝、载荷冲突、双Pay并发只消耗一次POST权限、User进程中断/重启与Nacos重选。实际Pay Spring上下文、User启动Jar、Nacos/OpenFeign、TC/RM、MySQL/Redis均独立，TccResponseProxy只注入原响应故障/计数；不代表Pay公开支付入口、双资源receipt、Order/Stock/Gateway或全商城完成。

### 54.3 验证与回退

先提交本节及refactor/pay-user-bridge-baseline-20261114。修改Java用google-java-format；执行新增环境守卫测试和原PayTccBridgeServiceTest、UserTccFeignRetryerTest、UserTccFeignErrorDecoderTest、UserTccStateJsonTest；再以原Java17/Maven离线打包User（skipTests），独立JVM精确运行PayUserTccBridgeIT全9项，add-opens与Asia/Shanghai显式传入。不得合跑其它TC IT污染SDK静态资源。源码/User Jar及原九方法正文对照、最终精确XML/日志均归档target/backend-refactor-20261114/pay-user-bridge。

原夹具仅未决/收尾失败设置preserve；已收敛的普通断言失败仍可能删库，此限制如实记录。失败先写原bugfix_log并保存首轮证据；不改余额或删未知库结案。全部通过后核对八源表指纹、2UUID建删、九笔事实、TC终态、三次自有User进程启动/停止、Redis空库与Nacos清理，归档日志及TC file store后down本轮容器/网络，无-v，五卷保留。

仅暂存三份测试文件与本方案，其他1117份既有backend/deploy及README保持。git diff --check及临时index逆补丁恢复父树、真实index/README保持后建立refactor/pay-user-bridge-complete-20261114。回退测试装配不恢复运行状态、不删除忽略证据或数据卷。

54.3首轮准入修正：旧bootstrap的resource=:*:*只覆盖空命名空间；本轮Nacos2.2实际gRPC注册授权资源namespaceId=public，首User注册被拒且9个业务方法未执行，cleanup注销也被拒，两个UUID库留下。先完整归档首轮Maven/XML、User进程/日志、TC无业务全局事务证明、Nacos脱敏授权日志及两库dump；失败库精确保留在本轮卷，不为重跑删库。仅在本轮新Nacos为同root/cloudmall_admin补public:*:*的rw权限，鉴权/用户/共享Compose保持，重跑命令和9方法不变。最终预计4个创建库：失败2保留、成功2按原夹具删除，分别报告；修正后的权限配置仅属本次隔离实验，不宣称默认环境已修复。

### 54.4 装配改动与实际结果

新增PayUserTccBridgeEnvironment集中读取四个必填属性、限制loopback/源库/高位端口及派生gRPC冲突；NamingService、Pay启动参数和两个User子JVM复用同一结果。PayUserTccBridgeIT先校验环境再建库，子JVM显式时区，启动/结束回执记录PID与准确端口；原强制中断语义保持，waitFor未成功会阻止后续清理。原9个@Test方法正文逐段SHA核验完全保持，没有业务断言更改。

新增环境测试5项与原四类派发/Feign/JSON测试合计5类33项首次通过。User离线打包通过，Jar内88个生产class逐字节匹配当前编译输出。首轮真实测试在Nacos注册授权处失败，9方法未执行；仅补本轮public权限后，同一命令的完整9项通过，0失败/错误/跳过。首轮Maven/XML、User日志/PID及两库dump独立归档；不将本次真实回归记为首次启动成功。

| 原9项范围 | 本轮事实 |
| --- | --- |
| 提交与重复派发 | 仅1次POST，后续只GET；冻结10到唯一DEBIT账本，User余额90/冻结0；桥不推进Pay记录PENDING |
| 取消 | 同一分支只释放一次，余额100/冻结0/0账本 |
| 丢失POST响应 | 保持TRY_UNKNOWN，恢复只GET；原receive不变，无第二POST或分支 |
| 余额不足 | 没有持久拒绝事实时保持未知；TC Cancel后CANCELLED且无资金副作用 |
| 双User并发入站 | 两实例争同一接收归属，只登记1分支、冻结一次 |
| 认证/身份与Legacy | 错凭证/用户/XID/匿名请求被拒，Legacy不产生派发/接收/资金事实 |
| 冲突载荷 | 明确PAY_TCC_DISPATCH_CONFLICT，保留POST_ATTEMPTED，不误写网络未知；TC回滚原11元冻结 |
| 双Pay并发派发 | 独立持久化权限只允许1次POST、1个分支 |
| User中断/服务重选/重启 | 只终止选中实例，Nacos剩余实例承接GET和TC提交；重启原端口/稳定appId，无第二POST |

实际调用为Pay dispatch→独立持久化POST权限→原UserTccClient经Nacos/OpenFeign到测试响应代理→真实User内部认证/receive认领→原LocalTCC资源与官方Fence→真实TC回调。代理只执行原截断/计数逻辑；未用mock替代Nacos、Feign、TC、SQL或User资金。Pay receipt/coordinator/公开入口未开启，不能凭桥接成功推导完整支付。

TC日志记录9个真实全局事务、8个User分支，3个提交与6个回滚终态覆盖全部9笔快照；认证拒绝场景没有资金分支。第二轮原生目录的九笔dispatch/receive/attempt/funds/ledger/fence事实逐一保留，冻结均归零，最多唯一attempt/Fence/账本。三个User PID与停止回执一致，沿原destroyForcibly故障/收尾策略退出，exitCode=1不冒称自然退出；三次均有Tomcat、RM与Nacos register finished证据。重启实例在最后Started UserApplication日志前被收尾停止，归档脚本首次错误要求该日志而中止，后按实际三项就绪证据核对并如实记录缺少最后日志，未改变测试等待条件或重发交易。

源八表DDL/计数/checksum保持，Redis16DB初末均为空；成功与失败Nacos分组最后均无User实例。4个UUID库创建、成功2个删除与binlog匹配，首轮失败2库保留，前后dump去除导出时间后完全相同。失败现场没有被清空换取成功；两库位于本轮mysql_data保留卷中。

另外1117份既有backend/deploy及README按字节保持；原中间件容器ID/镜像/完整挂载/启动时间/重启次数保持。三份Java格式、适用编译、33定向/9原生回归与diff检查通过。归档TC日志与停止后的sessionStore/root.data后，已移除本轮四容器/网络，保留以下五卷：

- cloud-mall-refactor-pay-user-bridge-20261114_mysql_data（包含首轮两个失败库）
- cloud-mall-refactor-pay-user-bridge-20261114_redis_data
- cloud-mall-refactor-pay-user-bridge-20261114_seata_data
- cloud-mall-refactor-pay-user-bridge-20261114_nacos_data
- cloud-mall-refactor-pay-user-bridge-20261114_nacos_logs

### 54.5 回退与后续

方案提交58bd2aeb，基线标签refactor/pay-user-bridge-baseline-20261114，完成标签refactor/pay-user-bridge-complete-20261114。证据入口target/backend-refactor-20261114/pay-user-bridge/verification.json，包含33/9报告、首轮失败与同命令重跑、原9方法保持、Jar指纹、九笔事实、TC9全局/8分支、父子环境/PID、源指纹、失败库dump和资源关闭。

```powershell
git revert refactor/pay-user-bridge-complete-20261114
```

仅提交三份测试与本方案；临时index逆补丁恢复父树，真实index/README保持。Git回退不恢复运行数据或删除证据/五卷；原bugfix_log持续记录固定环境问题、Nacos授权修正、归档日志判据修正及实际回归结果。旧README本地修改、pycache及忽略产物均未入提交。

下一步按同样方式审查Pay receipt双资源测试的环境/失败现场与必要源表，冻结独立的Pay/User双资源TC范围。派发桥九项通过不替代Pay receipt/coordinator、公开支付入口、Order/Stock联动、完整TC故障矩阵或商城验收；不自动启动历史CM21或压测。

## 55. Pay receipt / User 余额双资源真实 TC 回归（2026-11-14）

### 55.1 范围与兼容边界

从8c1148dd继续，主Agent单独执行，依据batch16b1_cm18_pay_receipt_review.md及当前service.impl.receipt实现。本轮只修改PayReceiptTccIT的环境装配、类职责说明和子进程退出证据；直接复用第54节PayUserTccBridgeEnvironment，不复制地址解析器、不修改该helper或生产代码。原完整11个@Test正文和交易辅助方法保持，只有setup/startUser/stopUser接入显式地址、Asia/Shanghai及准确PID退出核验。

原调用顺序保持：PayTccReceiptService.prepare→Bridge.prepare独立dispatch锚点→Store.claim独立CLAIMED→唯一winner通过LocalTCC代理注册Pay branch；随后测试以原XID调用Bridge.dispatch→真实Nacos/Feign/User Try注册User branch。TC统一决议两个资源；Pay官方Fence与receipt共享同一裸DataSource/本地REQUIRED事务，User官方Fence与原余额事务共同推进。Pay receipt自身不调用User或Order，不把receipt CONFIRMED改写为公开支付SUCCESS。

冻结原11方法：双资源提交及清Fence后重放；双资源取消及防复活；User余额不足回滚原Pay receipt；Pay空Cancel永久tombstone；Pay Try失败整体回滚后空取消；Confirm/Cancel写失败时Fence不得先提交、TC修复后重试；并发receipt prepare只登记一次；User响应丢失不覆盖Pay资源或二次POST；载荷/XID冲突拒绝重绑；被拒第二Pay分支只安全ACK、不触碰永久owner。部分重复/迟到调用仍按原夹具使用raw适配器；初始双资源决议和故障重试必须通过真实TC。

### 55.2 独立资源与现场规则

新项目cloud-mall-refactor-pay-receipt-20261114，基于deploy/experiments/compose.yml和本轮独立override，仅MySQL8.0.46、Redis7.0、Nacos2.2.0、Seata1.5.2；端口19984/19987/19986/19985分别为MySQL/Redis/NacosHTTP/TC，Nacos gRPC20986/20987，子网10.254.218.0/24。新mysql_data/redis_data/seata_data/nacos_data/nacos_logs五卷，pull_policy=never/restart=no；不接入第54节保留失败库或任何历史/default卷。

Nacos保持认证、稳定hostname及原healthcheck.py，初始化即向本轮root/cloudmall_admin授既有空命名空间及public:*:*的rw权限，避免重复第54节已定位的启动缺口；共享bootstrap不改。TC保持file配置和存储，实际XID地址按日志记录。先核对新项目/卷、镜像/端口/IPAM、健康及认证，再准备当前schema.sql的八张空源表（User五、Pay三）。原夹具克隆到两个cm16b1_<UUID>库，原初始化器创建receipt/dispatch/Fence等表。

Pay父上下文和两个User子进程使用同一显式MySQL/Nacos/TC/Redis环境，Java17 add-opens及Asia/Shanghai齐全；禁用无关Rabbit health并指向未启用端口。测试退出前原规则等待TC终态、User冻结归零及Pay无TRYING receipt，十一笔原始快照保存receipt/payFence和User资金事实。未决/收尾失败保留库；普通断言失败但之后已收敛仍可能删库，保留该原限制。失败先记bugfix_log和归档，不删库或改账本掩错。

### 55.3 验证与回退

先提交本节及refactor/pay-receipt-native-baseline-20261114。修改单Java执行google-java-format；定向运行PayUserTccBridgeEnvironmentTest、PayTccBridgeServiceTest、PayTccReceiptServiceTest、PayTccExecutionEvidenceTest。离线构建User启动包并核Jar/class一致；新独立JVM精确运行PayReceiptTccIT全11方法，不与其它TC IT合跑。原11方法及其余交易辅助方法逐段保持，证据归档target/backend-refactor-20261114/pay-receipt-native，旧33/9结果不累加或冒本轮回归。

成功后核验十一笔终态事实、两种真实RM资源/全局事务终态、两个自有User PID退出、两库建删、八源表指纹、Redis初末16DB空及Nacos原组无实例；归档日志和停止后的TC file store，再down本轮容器/网络，无-v，五卷保留。只提交PayReceiptTccIT与本方案，其它1119份既有backend/deploy及README保持。git diff --check及临时index逆补丁恢复父树、真实index/README保持后建立refactor/pay-receipt-native-complete-20261114。Git回退不恢复运行数据库/TC状态或删除证据/保留卷。

本轮不启用coordinator、公开Pay TCC入口、Order/Stock联动、进程故障全矩阵或商城/性能验收；双资源回执与余额一致不自动代表公开订单已经付款成功。

### 55.4 本轮结果与故障验证

本轮仅修改PayReceiptTccIT，复用上一轮环境helper；先读取全部必填隔离属性再建库，同一Nacos/TC/Redis参数传给父Pay与两子User。新增子JVM时区、PID/端口归属与准确退出回执，类说明明确双资源职责。原11方法正文、seed/settle/cleanup及全部交易辅助代码逐段核验保持；生产代码、环境helper、其它原测试和业务断言不变。

4类64项定向测试、User离线打包、11项真实TC测试均首次通过，0失败/错误/跳过；User Jar88个生产class与当前编译输出逐字节一致。上一轮33/9报告仅校验历史SHA，不计为本轮结果。初始化即授本轮Nacos public命名空间权限，未复现上轮注册失败，也未修改共享Nacos或旧失败库。

| 原用例组 | 项数 | 实际观察 |
| --- | --- | --- |
| 双资源提交/取消、永久终态与清Fence后重放 | 2 | receipt与User资金分别CONFIRMED/CANCELLED；同XID不同branch；提交只扣10一次，取消冻结归零；公开pay_record保持PENDING |
| User余额不足 | 1 | User Try未知时receipt仍TRYING；TC取消后原receipt取消、User空回滚无资金副作用 |
| Pay空Cancel与Try写失败 | 2 | CLAIMED独立事实保留，失败Try的Fence不提交；原TC空取消写永久receipt/Fence，迟到Prepare不能复活 |
| Pay Confirm/Cancel写失败 | 2 | 原触发器导致回执/Fence共同停在TRYING/TRIED；移除触发器后TC提交/回滚重试线程分别收敛，无半完成Fence |
| 并发receipt认领 | 1 | 两调用者只登记一个Pay分支，随后唯一User POST与TC提交形成一次资金事实 |
| User响应丢失 | 1 | 保留TRY_UNKNOWN及原Pay资源，仅GET恢复，无第二POST/Pay分支 |
| 载荷与XID冲突 | 1 | 拒绝重绑或错误回调，保留原owner；原TC取消后资金不动 |
| 被拒第二Pay分支 | 1 | 唯一receipt不被抢占；两个Fence分别保留原owner回滚与被拒分支SUSPENDED，安全ACK不操作User |
| 合计 | 11 | 原生方法清单与XML精确匹配 |

TC实际记录11个全局事务、19个分支（Pay资源cloudmallPayDebitReceipt共12，User资源cloudmallUserBalanceDebit共7），3个全局提交/8个全局回滚覆盖全部begin。部分用例只注册Pay，第二分支拒绝用例多一个Pay branch，不能说每个用例都恰有两个分支。十一笔原生快照分别保存receipt/payFence/dispatch/receive/attempt/funds/ledger/User Fence，receipt终态3 CONFIRMED、8 CANCELLED，User冻结均0，无TRYING残留。

Confirm故障在Pay RM日志返回PhaseTwo_CommitFailed_Retryable，TC RetryCommitting完成同一事务；Cancel故障本轮返回两次PhaseTwo_RollbackFailed_Retryable，TC RetryRollbacking最终成功。归档脚本首次误将提交/回滚统一匹配为TC的fail and will retry文字而停止；实际两类日志格式不同，修正为分别核验RM失败结果、两个TC重试线程终态及同一全局集合后通过。此归档修正未改业务测试、延时或重发交易，原日志保留；真实11项仍为首次通过。

两个User PID均有显式环境、Tomcat/RM/Nacos就绪及停止回执，沿原destroyForcibly收尾，exitCode=1不冒称自然退出。八张空源表前后DDL/计数/checksum一致；Redis16DB初末为空，原Nacos组最终无User实例。两个owned库均按原规则删除，两对CREATE/DROP事件与最终schema一致；本轮无失败库。原夹具普通断言失败后若已收敛仍可能删库的限制保留，没有虚构任意失败都保留现场。

其它1119份已跟踪backend/deploy文件与README按字节保持，旧容器ID/镜像/完整挂载/启动时间/重启次数保持。单Java格式、适用编译/测试、User包及diff检查通过。归档TC日志与停止后的sessionStore/root.data后，已down本轮四容器/网络，五卷保留：

- cloud-mall-refactor-pay-receipt-20261114_mysql_data
- cloud-mall-refactor-pay-receipt-20261114_redis_data
- cloud-mall-refactor-pay-receipt-20261114_seata_data
- cloud-mall-refactor-pay-receipt-20261114_nacos_data
- cloud-mall-refactor-pay-receipt-20261114_nacos_logs

### 55.5 回退与下一步

方案提交34fd2661，基线标签refactor/pay-receipt-native-baseline-20261114，完成标签refactor/pay-receipt-native-complete-20261114。证据入口target/backend-refactor-20261114/pay-receipt-native/verification.json，包括64/11精确报告、11方法/交易辅助保持、Jar指纹、十一笔双资源事实、TC分支与重试、两子进程回执、八源表/两库事件、TC file store、资源关闭及源码保持。

```powershell
git revert refactor/pay-receipt-native-complete-20261114
```

结果只提交本测试与方案；临时index逆补丁恢复父树，真实index/README保持。回退不会恢复已清理数据库/TC运行态或删除忽略证据/保留卷。原bugfix_log已记录固定环境根因、装配修正、归档日志判据修正和实际验证；README本地修改及旧pycache未入提交。

下一步审查Pay coordinator自动全局事务编排验收的显式环境、Pay子进程参数和故障场景，再冻结有限范围。双资源资源级事实通过不代表自动begin/决议恢复、公开支付成功、Order/Stock/Gateway、完整商城或性能已通过。

## 56. Pay coordinator 一次性编排与进程恢复有限回归（2026-11-14）

### 56.1 源码清单与修改边界

从9827d711继续，主Agent单独执行，依据batch16b2_cm18_pay_coordinator_review.md和当前实现。README旧清单为13项，当前PayTccCoordinatorIT实际15项，新增的旧NULL XID观察拒绝与同XID/NONE观察接受owner合法进展也纳入。本轮仅整理该IT的setup/startPay/killPay/startUser/stopUser：复用PayUserTccBridgeEnvironment，先校验全部地址再建库；NamingService、父Pay、两个User JVM和四次Pay故障子进程启动均接收同一隔离环境。子JVM显式Asia/Shanghai，保留Java17 add-opens，记录PID/端口/故障模式及准确退出回执，不在新回执输出控制凭证。

不改15个@Test正文、交易/并发辅助方法、ProbeTransactions、PayCoordinatorFaultProcess、原故障屏障/控制协议、生产编排与事务语义。Pay子进程继续使用原classpath和@args文件，不改成生产Jar或新增公网接口；User仍使用实际启动Jar。保留原短TC超时2500ms/普通120000ms、一次性claim/begin权限、独立持久决议与原等待上限，不能通过放宽断言“修复”结果未知。

### 56.2 有限验收范围与未知边界

原15项覆盖：自动begin与双资源提交、余额不足回滚、User响应丢失回滚、并发execute/recover不抢begin、commit/rollback请求丢失后同决议恢复、commit响应丢失依永久事实、begin响应丢失不新建XID、决议前中断仅等TC超时、COMMIT意图落库后杀Pay/原appId重启、只有XID落库时杀Pay/重启仍UNKNOWN、载荷/全局或本地上下文拒绝、begin等待期间的并发观察、NULL XID旧观察拒绝、同XID/NONE旧观察接受原COMMIT进展。

大部分用例将实际Spring Store/receipt/bridge/evidence与原SDK计数/故障包装器组装为生产Coordinator实例；并发用例的Mockito仅观察/暂停实际Store或receipt委托，并非用mock替代SQL、Feign或TC。两进程中断用例实际启动PayCoordinatorFaultProcess，各经历故障进程与恢复进程，共四个Pay PID，另有两个User PID。

业务未知与实验清理必须分开：begin响应丢失保持BEGIN_UNKNOWN且execution.xid为空；仅XID持久化而无Try/决议时，重启后保持UNKNOWN/NONE。原AfterEach掌握的testOnlyKnownXids只用于结束真实TC事务，不回填execution、不伪造业务成功。15笔快照不要求全部业务终态；TC终态、冻结归零、无TRYING receipt是原清理门。原普通断言失败但已收敛仍可能删库，未决/worker未退出设置preserve；保留此限制。失败先写原bugfix_log并归档，禁止新支付号、直接改账本或扫前缀删库掩错。

### 56.3 独立资源与运行前置

新项目cloud-mall-refactor-pay-coordinator-20261114，基于deploy/experiments/compose.yml及本轮override启动MySQL8.0.46、Redis7.0、Nacos2.2.0、Seata1.5.2；候选MySQL19988、TC19989、NacosHTTP19990及gRPC20990/20991、Redis19991，子网10.254.217.0/24。新mysql_data/redis_data/seata_data/nacos_data/nacos_logs五卷，pull_policy=never/restart=no。初始化即建立root/root、空/public命名空间权限，保留Nacos认证/原healthcheck；TC为file模式，实际XID地址以证据为准。共享配置、默认/历史资源与旧失败库不动。

新资源身份/镜像/端口/IPAM/健康/认证核验后，只从当前schema.sql准备User五表、Pay三表共八张空源表。原夹具克隆两个cm16b2_<UUID>库并通过原初始化器创建execution/receipt/dispatch等表。父子统一传入cloudmall.mysql.test.url、cloudmall.nacos.test.address、cloudmall.seata.test.tc、cloudmall.redis.test.port对应地址，禁用无关Rabbit health且不连默认broker。

### 56.4 验证、证据和回退

先提交本节并建立refactor/pay-coordinator-native-baseline-20261114。修改单Java执行google-java-format；运行PayUserTccBridgeEnvironmentTest、PayTccExecutionStoreTest、PayTccExecutionOwnerTest、PayTccExecutionSnapshotTest、PayTccExecutionEvidenceTest、PayTccExecutionWiringTest定向回归。离线构建User Jar核88个class，再单独JVM精确执行PayTccCoordinatorIT全15项，不能与其它真实TC类合跑污染SDK状态。

证据target/backend-refactor-20261114/pay-coordinator-native保存方法/辅助保持、XML/日志、十五笔事实、SDK故障与TC终态、两类原未知状态、两个User/四个Pay启动停止回执、子进程实际参数（控制凭证仅保留原本地受控args文件）、源指纹及User Jar。成功后核对两库建删、八源表保持、Redis初末16DB空/Nacos组无实例，归档TC日志及停止后的file store，再down四容器/网络，无-v，五卷保留。

仅提交PayTccCoordinatorIT与本方案，其它1119份既有backend/deploy及README保持。git diff --check、临时index逆补丁恢复父树且真实index/README保持后建立refactor/pay-coordinator-native-complete-20261114。Git回退不恢复实验数据或删除证据/保留卷。该范围仍不代表公开支付入口、定时自动恢复、Order/Stock交付、商城或性能验收。

### 56.5 本轮结果与未知语义核验

仅PayTccCoordinatorIT改为使用已有显式环境helper；父Pay、NamingService、两User和四次Pay故障子进程启动使用同一隔离地址。Pay保留原@args/classpath、控制凭证和故障屏障，新增时区和不含控制凭证的PID/模式/端口回执；User退出等待必须成功。15个业务方法正文、seed/settle/cleanup、ProbeTransactions、交易/并发/控制请求辅助方法逐段核验保持。PayCoordinatorFaultProcess、环境helper及所有生产代码均未改。

6类82项定向测试、User离线打包与15项真实回归首次全部通过，0失败/错误/跳过；User Jar88个生产class与编译输出一致。旧64/11报告SHA单独核验，不与本轮累加。当前源码确有15方法，未按README旧13项漏跑新增观察窗口；本轮没有新增启动或业务测试失败，也没有调整重试上限、故障屏障或断言。

| 验收组 | 项数 | 本轮结果 |
| --- | --- | --- |
| 正常编排、余额不足、User响应丢失 | 3 | 一次claim/begin/POST，明确双资源提交或原XID回滚，不重复资金；公开pay_record保持PENDING |
| 并发execute/recover | 1 | 原owner独占begin与Try，旧无绑定观察可被拒但不打断合法owner |
| commit/rollback请求丢失 | 2 | 持久COMMIT/ROLLBACK后只重试原XID同一决议，相反决议被拒 |
| commit响应丢失 | 1 | 通过永久资源事实判终态，清Fence后不补发第二次commit |
| begin响应丢失 | 1 | execution保持BEGIN_UNKNOWN/xid=NULL，无receipt/资金，不新begin |
| 决议前中断/TC超时 | 1 | recover不从TRYING猜COMMIT，decision保持NONE，原短超时TC回滚后可核验ROLLEDBACK |
| Pay在COMMIT意图后中断/重启 | 1 | 使用原appId/数据库/XID恢复COMMIT，不重复Try或POST |
| Pay仅持久XID时中断/重启 | 1 | 恢复保持UNKNOWN/NONE，无两端分支或资金，不接管Try权限 |
| 身份及已有全局/本地事务拒绝 | 1 | 错载荷/上下文在begin前拒绝，后续原载荷执行仍只一次begin |
| pending begin观察 | 1 | 观察BEGIN_UNKNOWN不剥夺原owner后续绑定/提交权 |
| NULL XID旧观察与同XID/NONE旧观察 | 2 | 前者明确拒绝跟随新绑定，后者接受原owner合法提交；不改最终execution事实 |
| 合计 | 15 | 冻结清单与本轮XML精确匹配 |

真实TC共15个全局事务：父Pay begin13次，故障Pay子进程begin2次；两种RM各13分支，共26分支。TC终态为9提交、6回滚，包含原2500ms超时用例。所有XID与十五笔快照的testOnlyKnownXids并集一致，没有第二次begin补造事务。

业务execution快照为9 COMMITTED、4 ROLLEDBACK、1 BEGIN_UNKNOWN和1 UNKNOWN。两条未知的decision均NONE、无receipt/dispatch/资金事实：BEGIN_UNKNOWN的xid仍NULL；UNKNOWN保留原XID。原测试清理层分别结束其已知TC事务，未将XID回填或把业务state写成成功；因此“TC全部结束”不等于“十五笔业务全部恢复”。这两条是负向验收的预期事实，完整原快照已归档，不能称作完整支付成功。另有一条ROLLEDBACK仍保留NONE，来自等待TC超时的原路径。

六个自有子进程均有启动/退出回执：两个User、四个Pay（BEFORE_COMMIT一次、BEFORE_RECEIPT一次、NONE两次）。四个Pay args文件核对同一显式地址、时区及稳定fault-pay appId，原控制凭证只留受控原文件，不在新摘要输出。各次真实RM注册日志、User Nacos注册与终止事实已核验；退出沿原强停方式，不能表述为自然退出。

八张空源表前后DDL/计数/checksum一致，Redis16DB初末为空，Nacos原组最终无User实例。两owned库按原清理门删除，两对CREATE/DROP与最终schema互证；本轮无失败库。普通断言失败但已收敛时原夹具仍可能删库的限制未改；原业务未知快照与测试专用清理依据已明确保留，不假称任意失败都留库。

其它1119份已跟踪backend/deploy及README逐字节保持，默认/历史容器ID、镜像、挂载、启动时间和重启次数保持。单Java格式、适用编译/测试、User打包和diff检查通过。归档日志和停止后的TC sessionStore/root.data后，已移除本轮四容器/网络，保留：

- cloud-mall-refactor-pay-coordinator-20261114_mysql_data
- cloud-mall-refactor-pay-coordinator-20261114_redis_data
- cloud-mall-refactor-pay-coordinator-20261114_seata_data
- cloud-mall-refactor-pay-coordinator-20261114_nacos_data
- cloud-mall-refactor-pay-coordinator-20261114_nacos_logs

### 56.6 回退与后续

方案提交c451e118，基线标签refactor/pay-coordinator-native-baseline-20261114，完成标签refactor/pay-coordinator-native-complete-20261114。证据入口target/backend-refactor-20261114/pay-coordinator-native/verification.json，包含82/15精确报告、原方法/辅助保持、User Jar、十五笔execution/资源事实、业务两种未知与TC清理区别、TC15全局/26分支、六子进程、源表/两库事件、TC文件存储和资源关闭。

```powershell
git revert refactor/pay-coordinator-native-complete-20261114
```

提交仅本测试与方案；临时index逆补丁恢复父树且真实index/README保持。Git回退不恢复运行数据库或TC状态，也不删除证据/五卷。原bugfix_log补充固定环境原因、装配修正及完整验证；README本地修改和旧pycache不入提交。

下一步审查定时自动恢复测试的显式环境、恢复扫描入口与Pay重启参数，冻结新的有限验证范围。本轮手动recover与指定进程窗口通过不替代定时扫描、公开支付入口、Order/Stock交付、完整商城或性能验收。

## 57. Pay 定时自动恢复与重启发现有限回归（2026-11-14）

### 57.1 修改与验证范围

从befdba2b继续，主Agent单独执行，依据batch16b3_cm18_tcc_recovery_scan_review.md及当前execution包恢复扫描实现。PayTccRecoveryAutomaticIT仍有固定Nacos地址和未传递的父子中间件参数；本轮只改该IT的setup/startPay/killPay/startUser/stopUser，复用既有PayUserTccBridgeEnvironment，建库前核验地址，统一NamingService、父Pay、两User及两次Pay子进程的环境/Asia/Shanghai。保留Pay原classpath/@args、控制认证、availablePort和原强停语义，补PID/恢复开关/模式/退出回执，不输出控制凭证。

原5个@Test正文、seed/settle/cleanup、due、finished、ProbeTransactions和其它交易/控制辅助全部保持；不改环境helper、PayCoordinatorFaultProcess、生产Scheduler/RecoveryStore/Mapper或原200ms测试调度间隔。扫描器仍按原真实@Scheduled运行，不用手动recover代替自动路径；原SQL租约、两阶段共享批量/启动预算和不可转移begin/Try权限不变。

### 57.2 五场景及证据边界

1. COMMIT请求在SDK边界丢失后，只通过原扫描器找到到期记录，恢复同XID/同决议并释放租约；不手动调用recover。
2. 余额不足且ROLLBACK请求丢失后，扫描器完成原取消，没有新Try/POST。
3. decision=NONE的原分支仅被观察为UNKNOWN，余额仍冻结且无新决议；之后测试明确调用原TC rollback，再由扫描器核对ROLLEDBACK/NONE。该显式rollback属于测试控制，不能报告为扫描器自行决定取消。
4. begin响应丢失的execution保持BEGIN_UNKNOWN/xid=NULL/恢复尝试0；同例另建原canary支付，证明合法COMMIT_REQUESTED仍可被扫描，不被未知记录挤占。本例含两个真实begin，不能把5方法误当5全局事务。
5. 父扫描器按原running标志暂时阻断；Pay子进程在COMMIT意图后被强停，新同appId进程启用原恢复调度自动发现并提交，不发/recover控制请求或第二User POST。

原due只将owned execution.next_recovery_at提前，不改状态/XID/决议；因此验证到期调度与真实恢复，不验证完整30秒初始等待或所有退避墙钟时间。原清理层根据testOnlyKnownXids结束TC，不回填BEGIN_UNKNOWN业务XID；业务未知与实验TC清理分开报告。未决/收尾失败保留库；普通断言失败但随后已收敛仍可能删库，此限制不变。

### 57.3 独立资源与前置

新项目cloud-mall-refactor-pay-recovery-20261114，基于deploy/experiments/compose.yml及本轮override启动MySQL8.0.46、Redis7.0、Nacos2.2.0、Seata1.5.2；候选MySQL19992、TC19993、NacosHTTP19994及gRPC20994/20995、Redis19995，子网10.254.216.0/24。新mysql_data/redis_data/seata_data/nacos_data/nacos_logs五卷，pull_policy=never/restart=no；Nacos保持认证、稳定hostname和原healthcheck，初始化root/root及空/public命名权限；TC file模式，实际XID前缀按日志核对。

先核对项目/卷不存在、镜像/端口/IPAM/健康/认证；仅从当前schema.sql创建User五表、Pay三表八个空源表，原夹具克隆到两cm16b3_auto_<UUID>库。父子显式MySQL/Nacos/TC/Redis与Java17 add-opens一致，无关Rabbit指向未启用端口且禁health，不连接默认broker。默认/历史资源、上轮保留卷和旧失败库不动。

### 57.4 检查、归档与回退

先提交本节并建立refactor/pay-recovery-native-baseline-20261114。修改单Java执行google-java-format；定向运行PayUserTccBridgeEnvironmentTest、PayTccRecoverySchedulerTest、PayTccRecoveryFailureTest、PayTccExecutionWiringTest、PayTccRollbackProjectionSchedulerTest。离线打包User并核88个class，然后新独立JVM精确运行PayTccRecoveryAutomaticIT全5项，不合跑其它TC类。以前的82/15或SQL租约14项不能当本轮新执行结果。

证据target/backend-refactor-20261114/pay-recovery-native保存5方法/辅助保持、定向及原生XML/日志、五例完整execution/资源快照（共六execution）、恢复attempt/token释放与未知不扫描、原生子进程参数及四个PID回执、TC终态、源指纹和User Jar。成功后核验两库建删、八源表保持、Redis初末16DB空及Nacos组无实例，归档日志与停止后的TC file store，再down四容器/网络，无-v，五卷保留。

只提交PayTccRecoveryAutomaticIT与本方案，其它1119份backend/deploy及README保持。git diff --check、临时index逆补丁恢复父树且真实index/README保持后建立refactor/pay-recovery-native-complete-20261114。失败先在原bugfix_log写现象并保留首轮证据，再定位最小必要修正，不改业务断言或盲目重发交易。Git回退不恢复运行数据或删除证据/保留卷；本轮不代表公开支付入口、Order/Stock交付、恢复每个窗口的独立崩溃矩阵、商城或性能验收。

### 57.5 本轮结果与自动动作边界

本轮只修改PayTccRecoveryAutomaticIT的环境/进程装配与职责说明，复用上一轮helper，父Pay、NamingService、两User与两Pay子进程地址一致。增加显式时区、PID/恢复开关/故障模式/准确退出回执；Pay控制凭证仍仅留原受控args文件。5方法正文、seed/settle/cleanup、due/finished、Probe及交易控制辅助逐段保持，生产Scheduler/Store/Mapper、FaultProcess和helper未改。

5类27项定向测试、User离线打包及5项真实自动恢复首次全过，0失败/错误/跳过；User Jar88个生产class逐字节匹配编译输出。旧82/15报告仅校验SHA，不计本轮。实际使用原@Scheduled与200ms测试间隔，没有以手工recover替代自动恢复，没有新增启动或业务测试失败。

| 原方法 | 本轮实际结果 |
| --- | --- |
| scheduledCommitUsesOriginalDecisionWithoutManualRecover | 原COMMIT RPC丢失后nextRecoveryAt仍符合初始延后检查；仅提前到期时间，扫描一次恢复同XID COMMITTED、attempts=1、租约清空、1次begin/POST |
| scheduledRollbackSettlesInsufficientFundsWithoutNewTry | 原ROLLBACK RPC丢失后扫描恢复ROLLEDBACK/ROLLBACK，余额100、冻结0、0账本，没有新Try |
| decisionNoneIsObservedWithoutAutomaticDecision | 第一轮只观察UNKNOWN/NONE，冻结仍10；测试随后明确请求原TC取消，扫描再核对ROLLEDBACK/NONE，未自行选择回滚 |
| unknownBeginDoesNotOccupyAutomaticRecoveryWindow | 原BEGIN_UNKNOWN/xid=NULL/attempts=0保持；同例canary记录被扫描提交，一次User POST、唯一账本，无未知记录抢占窗口 |
| restartedPayAutomaticallyFindsDurableCommitIntent | 父扫描器被原running门暂时阻断；第一Pay子进程recovery=false停在COMMIT屏障后被杀，第二进程recovery=true由定时任务完成原XID，无/recover控制调用或第二POST |

五例产生六条execution、六个真实全局事务，其中父Pay begin5次、故障子Pay begin1次；Pay/User各5分支共10个，TC终态3提交/3回滚。五份原生快照共六execution：3 COMMITTED、2 ROLLEDBACK、1 BEGIN_UNKNOWN；六行最终都无恢复owner/lease，五条可恢复记录均有attempt。NONE场景至少两次观察后形成ROLLEDBACK，但decision仍NONE；取消指令来自测试，不是扫描器擅自决议。

BEGIN_UNKNOWN的xid仍NULL、恢复attempts仍0、该支付无receipt/dispatch/资金事实；同例的账本属于已提交canary，不能把同用户余额变化归因于未知支付。测试掌握但未写回业务的原XID仅供清理，TC取消后execution仍BEGIN_UNKNOWN。因此不能把六笔TC结束表述为六条业务全部恢复。

四个自有子进程均有启动/停止回执：两User、两Pay；Pay模式/开关分别BEFORE_COMMIT+false和NONE+true，同一fault-pay appId/库/环境。新Pay日志有真实RM提交成功，原5方法无手工/recover调用；父扫描器阻断/恢复逻辑保持。退出沿原destroyForcibly方式，不冒称自然退出。

八空源表前后DDL/计数/checksum一致，Redis16DB初末为空、Nacos原组无User实例。两owned库删除与两对CREATE/DROP互证；本轮无失败库。原夹具未决保留、普通断言失败但已收敛可能删库的限制保持。提前调度时间只用于本轮夹具，不证明完整30秒初始等待/全部退避墙钟时间；租约过期每个窗口和全流程崩溃矩阵不在本次结论中。

其它1119份已跟踪backend/deploy与README字节保持，默认/历史容器身份、镜像、挂载、启动时间和重启次数保持。单Java格式、编译/定向/原生测试、User包与diff检查通过。归档日志与停止后的TC sessionStore/root.data后，已down本轮四容器/网络，五卷保留：

- cloud-mall-refactor-pay-recovery-20261114_mysql_data
- cloud-mall-refactor-pay-recovery-20261114_redis_data
- cloud-mall-refactor-pay-recovery-20261114_seata_data
- cloud-mall-refactor-pay-recovery-20261114_nacos_data
- cloud-mall-refactor-pay-recovery-20261114_nacos_logs

### 57.6 回退与后续

方案提交9515ba30，基线标签refactor/pay-recovery-native-baseline-20261114，完成标签refactor/pay-recovery-native-complete-20261114。证据入口target/backend-refactor-20261114/pay-recovery-native/verification.json，含27/5报告、原5方法与辅助保持、六execution/六TC全局/十分支、未知/canary及NONE控制边界、四子进程与恢复开关、源指纹/两库事件、User Jar、TC文件存储及资源关闭。

```powershell
git revert refactor/pay-recovery-native-complete-20261114
```

只提交本测试与方案；临时index逆补丁恢复父树、真实index/README保持。Git不恢复运行数据库/TC状态，也不删除证据或五保留卷。原bugfix_log记录固定环境原因、装配修正及验证；无关README和旧pycache不入提交。

下一步先审查Order/Stock交付测试的四服务拓扑、Rabbit隔离、Redis生命周期及故障清理，冻结下一有限切片。当前定时恢复结论不替代公开支付成功、Order/Stock交付/退款、完整商城或性能验收，不自动重启历史压测任务。


## 58. 四服务交付夹具显式环境与证据来源整理（2026-11-14）

### 58.1 当前范围和执行顺序

从a0c12a50继续，由主Agent单独实施/评审。依据16C交付、16L租约、16N扫描公平性及batch16t247真实建单/预留恢复规格，当前PayTccDeliveryIT是12项而非README旧8项：1真实建单与预留未知恢复、2库存回滚前禁止退款、3禁用用户退款响应丢失与Pay重启、4paid响应丢失、5回滚execution隔离、6租约竞争、7本地事务回滚、8扫描公平、9/10切回Legacy进程、11/12超时消息return与重投。第1项包含真实两分钟年龄门和独立Order helper，不能沿早期8项假定运行。

本阶段只完成真实验证前的夹具环境重构和离线/本机受控HTTP验证，不启动容器、不运行十二项真实交付。业务服务、状态机、SQL、消息/金额/Feign契约和生产配置均保持。后续独立阶段再冻结新Compose五组件六卷、完整53表迁移及12项运行与精确失败收尾；不自动启动历史压测或全矩阵。

### 58.2 已确认环境问题与修改清单

原夹具固定Nacos18848、Rabbit AMQP15673和管理15674，直接docker run/rm临时Redis；父Pay及不同子JVM没有统一传播连接参数。只改业务启动地址仍不够：PayTccTcBranchEvidence固定读取共享cloud-mall-seata，PayTccStageEvidence固定读取Rabbit15674，会使事务与证据来源不一致。

新增测试专用PayTccDeliveryEnvironment，组合既有PayUserTccBridgeEnvironment的MySQL/Nacos/TC/Redis准入规则，不改旧helper。新增必需cloudmall.rabbit.test.port、cloudmall.rabbit.management.test.port和cloudmall.seata.test.container-id（完整64位小写十六进制ID）；解析必须在任何数据库/网络/进程操作之前完成。所有八个直接/派生端口互异，高位loopback规则保持；TC容器ID只作为明确只读来源，实际归属/镜像/端口对应关系必须由下轮Compose前置核验，不能仅靠字符串校验冒充资源归属证明。

统一NamingService、父Pay、替代Pay、协议切换Pay和User/Order/Stock（含Order测试helper）的连接参数；保持原group/vhost、appId、功能开关和故障控制。测试只使用外部已准备Redis，不创建、删除、清空Redis；生命周期归独立Compose runner。保留原数据库/vhost清理门和未决preserve，不扩大失败删库范围。

TC和阶段证据helper新增显式环境重载，交付夹具全部调用新入口；其它调用者继续沿原入口，不改变其历史默认来源。TC日志命令绑定本次容器ID，并将实际目标写入证据模板；原同源时钟、容器身份SHA、INFO解析、时间/大小上限、重读次数及ACK_UNOBSERVED规则保持。Rabbit阶段采集仍只读原vhost/queues两类GET，不消费、purge或重投。

精确允许修改：PayTccDeliveryIT、PayTccTcBranchEvidence、PayTccStageEvidence；新增PayTccDeliveryEnvironment、PayTccDeliveryEnvironmentTest、PayTccDeliveryEvidenceEndpointTest。12个@Test方法保持；业务辅助仅允许显式证据参数和MQ连接目标替换，不能调整断言、延时、退款/paid/建单/清算/故障控制。新增子JVM时区；既有控制凭证不进入新环境摘要。

### 58.3 验证、记录与回退

先提交本节并建立refactor/pay-delivery-environment-baseline-20261114。实施前在原bugfix_log记录现象，保存三文件和README前像、1120份backend/deploy SHA。使用实际google-java-format，仅格式本次Java；对比12个方法及其它辅助的词法标记，格式变化不算业务变化，允许差异必须逐项审计。

新增准入测试验证缺项/非法地址/默认端口/端口碰撞（包含Nacos gRPC）/非法CID/vhost、参数不可变且无重复默认Rabbit配置；受控loopback HTTP测试验证阶段采集使用指定管理端口及只GET所属vhost，不冒充真实Rabbit。运行原bridge环境、TC/Stage evidence、Delivery service/identity/evidence/wiring/scheduler相关定向测试，编译全部Pay测试。之后离线打包User/Pay/Order/Stock四模块及依赖，核生产Jar class与编译输出一致、测试helper未进入Jar。

证据target/backend-refactor-20261114/pay-delivery-environment。记录准确XML方法/计数、编译/打包、源码差异/保留、受控HTTP边界及后续前置清单。diff检查和临时index逆补丁恢复父树、真实index/README保持后提交七文件并建立complete标签。Git只回退源码/方案，不回退数据；本阶段没有创建中间件或执行真实支付，完成环境前置不能表述为四服务链已验收。

58.2实施前置补充：Stage sourceBinding还有固定MySQL13307校验。显式capture必须同时传入本轮准确MySQL端口，所有四源仍逐个核对精确catalog/凭据/URL选项；旧capture和原两参数sourceBinding保持13307，不放宽为任意端口。新增离线准入反例后重新跑定向组/四包；初次78项与包证据单独保留，不与最终数量累加。


### 58.4 本轮结果与调用链复盘

本阶段新增一个不可变测试环境对象和两个测试类，修改交付IT及两个只读证据helper；共六个测试Java文件，生产代码/配置未改。环境对象先组合原bridge准入，再检查Rabbit两端口与其余六端口互斥和TC完整CID；setup第一条语句即解析配置，任何缺项在建库前拒绝。父Pay及替代/协议Pay经payArguments使用同一环境，三服务和Order helper经startService使用同一环境；原Nacos group、Rabbit vhost、appId、功能开关和控制凭证语义保持。

调用链为：System properties → PayTccDeliveryEnvironment.read → setup/进程启动；同一个environment → TC markWindowStart/capture与Stage capture。TC读本轮完整容器ID，Stage的四个owned数据库必须匹配本轮精确MySQL端口和catalog，Rabbit写入/发布与只读管理采集分别使用同一组AMQP/management端口。旧collector入口仍绑定原13307/15674及共享TC，不将其它历史夹具悄悄切换到本轮实验。新的fixture.json记录Redis/Rabbit端口和TC CID，不记录控制凭证。Redis的docker run/inspect/rm入口已从该IT移除，后续只由Compose runner归档/停止，并保留卷。

最后源码复核发现Stage原MySQL13307守卫未随显式入口传递，已在真实运行之前补齐精确端口参数。保留旧两参数sourceBinding和旧capture的13307约束；新增测试证明本轮20000可解析、其它端口或错误catalog拒绝、旧入口仍拒绝高位地址，整个测试不打开数据库连接。没有把守卫改成接受任意端口。

| 本轮最终验证 | 数量 | 结果与边界 |
| --- | --- | --- |
| Delivery环境、证据端点、原bridge环境 | 7+3+5=15 | 缺项/地址/端口碰撞/CID/vhost拒绝、不可变参数、2项受控HTTP和1项无连接数据库来源验证；不是中间件集成 |
| TC/Stage证据原单测 | 8+10=18 | 原可信日志解析、只读来源/金额/未知与不完整证据语义保持 |
| Delivery service/identity/evidence/wiring/scheduler | 18+7+9+2+10=46 | 原交付、租约/身份、证据、装配与扫描单测 |
| 合计 | 10类79项 | 最终0失败/错误/跳过；此前78项绿轮单独归档，不累加为157项 |
| User/Pay/Order/Stock离线package | 4个业务Jar | User88/Pay187/Order123/Stock88，共486生产class与各自target/classes逐字节一致；所有测试helper未进入Jar |

四服务package使用-DskipTests，只证明编译/测试源码编译/打包；不能把四包当四服务运行成功。此次没有执行PayTccDeliveryIT十二项，没有启动中间件、Seata事务或支付/退款，也没有操作默认或历史Docker资源。

JDK Javac AST比对确认12个原@Test规范正文完全一致；三个既有文件共142个方法规范文本保持。六个业务辅助只增加environment或替换AMQP目标，八个collector实现仅传递来源参数/旧默认包装，逐段去除明确参数变更后正文一致；其余七处IT变更均为setup/cleanup/启动/HTTP参数，原settle、断言、等待/回退年龄门和故障注入保持。全部1120份原跟踪backend/deploy中只改变上述三个既有测试文件，其余1117份和README前像保持。

实施脚本首轮因同样JVM选项实际三处而前置拒绝，限定两处缺时区的启动段后完成；AST归档两次误判根因是Windows Javac输出CRLF，读回确认并统一换行后完成，不是业务测试失败。相关首轮测试/打包、前像、最终XML、方法/源码/Jar审计保留；原bugfix_log记录原因、修正与结果。六Java格式与git diff --check通过。

### 58.5 回退与下一阶段前置

方案提交e61ad81b，基线refactor/pay-delivery-environment-baseline-20261114，完成标签refactor/pay-delivery-environment-complete-20261114。证据入口target/backend-refactor-20261114/pay-delivery-environment/verification.json。仅提交六Java与本方案；临时index逆补丁必须恢复本基线树，真实index/README保持。回退命令：

```powershell
git revert refactor/pay-delivery-environment-complete-20261114
```

下轮从此检查点开展有限真实交付回归，先冻结资源和清理范围再执行：

1. 新Compose项目部署MySQL/Redis/Rabbit/Nacos/Seata五组件六新卷，核镜像、健康、root/root、本机端口、IPAM和TC容器完整ID与TC地址实际对应；Nacos空/public命名权限沿前轮已验方案。参数字符串合法不等于实例独占或CID与端口一致，必须用实际Compose事实核对。
2. 必需显式属性为cloudmall.mysql.test.url、cloudmall.nacos.test.address、cloudmall.seata.test.tc、cloudmall.redis.test.port、cloudmall.rabbit.test.port、cloudmall.rabbit.management.test.port、cloudmall.seata.test.container-id。四服务Jar与Order测试helper均重新核同源；source schema/启动迁移和原53表断言按当前月份实际核定，不能为过关删表或放宽断言。
3. 复核12项完整顺序及第1项原两分钟年龄门、MQ helper、Legacy切换、只读TC/Stage采集。定时器、故障注入、消息信封/键/确认和UNKNOWN保护保持；本轮仅离线79项不替代这些真实行为。
4. 原夹具仍有“普通断言失败但随后已收敛可能删库”的历史限制，不能宣称任意失败都保库。运行前审清setup部分失败与所有自有子进程/代理的有限退出，保存每项资金/库存/TC状态及首个失败证据；未知业务不靠手工改账或删库清场。
5. 成功后核四owned库、原vhost、Redis精确键/空库、Nacos实例、子进程及源指纹；归档MySQL/Rabbit/Redis/TC原生证据和停止后的TC file store，再down本轮容器/网络，无-v保留六卷。完整商城、全部历史风险与性能仍需另定范围。


## 59. 四服务交付有限真实回归（2026-11-14）

### 59.1 冻结范围

从04f84a5e继续，主Agent单独执行，沿第58节已完成的显式环境接线。依据16C交付、16L租约及16T247建单/预留恢复规格，本阶段执行当前PayTccDeliveryIT完整12项，不按README早期8项裁剪，不重复历史性能/维护矩阵。先不改Java；发现实际阻断时先记原bugfix_log、保存首次证据，再作最小必要修正并回归。

原12项包括真实A建单/事务可见性/支付交付与B首次预留响应未知后原Recovery释放，库存回滚前禁止退款，禁用账户退款及Pay替代进程，paid响应未知，ROLLEDBACK不交付，租约竞争，状态事务原子性，扫描公平，默认/显式Legacy新进程恢复，以及两类超时消息return/重投。第1项保留原2分钟年龄门及30秒调度，不改时间或手工改终态。其Product/Cart仅原只读metadata double，真实User/Order/Stock/Pay与Feign/Nacos路径不替换；不是完整商城或全部Product/Cart集成。

### 59.2 新资源与原始数据

新Compose项目cloud-mall-refactor-pay-delivery-20261114，基于deploy/experiments/compose.yml和本轮ignored override；MySQL20010、TC20011、Nacos HTTP20012/gRPC21012和21013、Redis20013、Rabbit AMQP20014/management20015，子网10.254.215.0/24。新mysql_data/redis_data/rabbit_data/nacos_data/nacos_logs/seata_data六卷，原MySQL8.0.46镜像、Redis7.0、Rabbit3.11、Nacos2.2.0、Seata1.5.2；pull never/restart no，root/root，Nacos保持鉴权并初始化空/public权限，Rabbit健康探针只在本轮override采用既有已验证10秒超时。

先核项目/卷不存在、端口/IPAM/镜像/健康与标签，再创建四个空源库。只提取当前schema.sql中User7/Pay9/Order15/Stock9共40条CREATE TABLE（含原LIKE依赖顺序），不运行商品或示例数据。原fixture克隆四个cm16c_<UUID>库，Order按当前202611生成202610..202701四个月12表、Stock创建审计表，共53表；原表数与全部checksum断言保持。默认资源、旧实验卷和旧失败库不动。

### 59.3 执行、证据和失败边界

先提交方案与refactor/pay-delivery-native-baseline-20261114。核第58阶段79项报告/源SHA及四Jar生产class、Order测试helper排除；在当前同源代码上离线package四服务，随后单独Maven JVM仅运行PayTccDeliveryIT。显式传入七项环境属性、Java17 add-opens/Asia/Shanghai，TC属性使用本次Compose实际完整CID。旧79不算本轮新测试项。

新证据目录target/backend-refactor-20261114/pay-delivery-native。保存命令/退出码/XML、原fixture阶段包和事务/资金/订单/库存/消息证据、每个测试子JVM的PID/实际存活代际与环境摘要（不输出控制key）、源表前后DDL/checksum、TC原生注册/全局方向、Rabbit元数据及Redis键事实、Docker归属/旧资源身份、Jar和源码SHA。原collector同源时钟/INFO解析与ACK_UNOBSERVED规则保持，不以全局COMMIT伪造逐分支commit ACK。

原fixture的AfterEach可能经原业务调用收敛普通断言失败，而后删库；未决preserve保持。运行前已审查清理路径，不扩大删除范围、不用手工写资金/库存状态收尾。外部runner先保留首轮日志/XML/生成目录；若进程或业务未知，仅精确停止本轮可证归属进程、归档并保留数据库/卷，不把重跑成功覆盖首轮失败。部分setup失败清理也必须按实际返回和资源清单记录，不假定任意失败均留库。

成功后核四库CREATE/DROP、vhost删除及零连接、Nacos精确组实例退出，源40表不变，Redis剩余键逐个归属记录（测试可能保留库存缓存，不以FLUSHDB制造空库）。归档五组件日志、SQL/缓存/消息事实及停止后的TC file store后，down本轮五容器/网络，无-v保留六卷；不清共享资源。只有实际12项全通过且资金/库存/TC核验通过才报告本有限交付闭环，完整商城/全部崩溃窗口/性能仍未验收。

### 59.4 提交策略

无生产改动时结果提交仅更新本方案；必要测试修正单列准确文件/原因。源码保持检查、适用格式及git diff --check通过，临时index逆补丁恢复父树并核真实index/README保持，再建立refactor/pay-delivery-native-complete-20261114。Git回退不恢复运行数据，也不删除本轮证据或六保留卷。


59.3首轮阻断及最小修正：首个真实支付已COMMITTED/COMMIT、User余额90/冻结0/DEBIT10，Order仍PENDING_PAYMENT、库存9/1/0；在首次Stage采集的initialOwnership前抛出source target does not belong to fixture。SourceBinding.verifyUnchanged重调旧两参数sourceBinding，丢失显式20010端口回退13307。53表断言已通过，TC原生双REGISTER/全局COMMIT证据已落盘；B创建尚未开始，后11项跳过，AfterAll按preserve拒绝删库。首轮13条XML含12方法与1条class收尾失败，不称13个业务场景。

本轮追加只改PayTccStageEvidence的SourceBinding记录保存mysqlPort并在所有重检中沿用，及PayTccDeliveryEvidenceEndpointTest补“合法高位绑定再次验证通过、随后改端口拒绝”反例；先红后绿，旧默认13307/来源对象身份/URL严格相等及catalog守卫保持，不改业务IT/断言/清算。先归档首轮四库dump、业务事实、TC file store/消息/缓存与五容器日志并停止本轮自有进程，down首项目保留六卷和未交付样本，不手工清算或删数据。修复后第二次完整12项使用全新项目cloud-mall-refactor-pay-delivery-r2-20261114及六新卷；MySQL20016/TC20017/Nacos20018(gRPC21018/21019)/Redis20019/Rabbit20020/management20021，子网10.254.214.0/24，证据独立pay-delivery-native-r2目录。首轮未知交付事实不因新轮成功改写。

r2前置端口探测发现20017已由既有PID27368监听，尚未创建资源；不操作该进程，仅将本轮TC端口改为已核可用20022，其余地址/新项目/新卷不变。拒绝与改选证据保存在r2/preflight-port-rejection.json。


### 59.5 实际结果、缺陷修正与失败保留

首轮真实测试在第1项阶段采集处失败，保留1方法error、11方法skip和1条AfterAll保留现场failure的原XML；不把13条XML记录称为13个业务用例。SourceBinding初次解析用20010，但verifyUnchanged调用旧两参数入口回退13307，导致正确隔离源被误拒。新增高位绑定重检断言先稳定复现同一异常；仅在私有SourceBinding保存mysqlPort，所有重检沿用，随后修改URL端口仍被拒绝。原对象/URL身份、catalog、凭据/选项和旧默认13307规则不变。

修复只涉及PayTccStageEvidence与PayTccDeliveryEvidenceEndpointTest；PayTccDeliveryIT原12方法及全部业务辅助逐字节保持，生产代码、SQL、业务调度/状态机未改。修复后10类79项定向测试0失败/错误/跳过，四服务离线package通过；User88/Pay187/Order123/Stock88共486生产class匹配编译输出，测试helper不入Jar。红轮3项中的1项预期失败不混入绿轮79项计数；第58阶段旧79也不重复累加。

首项目cloud-mall-refactor-pay-delivery-20261114的四个cm16c_5e17a7db7a8d42fbb32f357cbdcba780库保留在其mysql_data卷，并保存完整dump。该失败样本明确为TC COMMITTED、余额90/冻结0、一次DEBIT10，Pay仍PENDING、Order PENDING_PAYMENT、Stock9/1/0，尚未交付；B尚未创建。TC无未决全局事务，不等于业务交付完成。原vhost及五队列保留在该项目Rabbit卷，自有JVM已退出、容器/网络已移除、六卷保留，未手工结账/取消/删库。后续第二轮成功不改写这个失败事实。

r2使用全新项目/卷及TC20022，其余端口按59.3。全12方法第二轮0失败/错误/跳过，外部runner405.6秒；第1项177.77秒，包含原真实恢复等待。没有重试同一失败交易或复用首轮Redis/数据库。具体结果：

| 现有场景 | 本轮真实结果 |
| --- | --- |
| A实际建单与B首次reserve响应未知 | A的订单/item/idempotency/outbox提交前不可见，提交后原TCC支付与库存确认成功；B保持原orderNo/intent/SKU/快照，由原30秒Recovery等待真实年龄后释放 |
| 库存回滚未完成、禁用用户退款/响应丢失 | 库存未成功回滚不退款；明确取消/回滚后唯一退款，禁用用户同样结清；新Pay进程读取原退款事实，不二次增加余额 |
| paid响应丢失、已回滚execution | 前者原paid只一次，按Order/Stock只读事实判成功；后者不建delivery、不扣款/退款，原AfterEach取消订单释放库存 |
| 过期租约、交付状态写故障、扫描公平 | 过期owner不能覆盖；delivery写失败连同Pay状态回滚，去故障后原状态成功；种子查询故障不阻塞已有交付 |
| 默认/显式Legacy新JVM | 两种新进程均恢复原TCC意图，再各接受一笔新LEGACY支付；原持久模式与资金键不改变 |
| Rabbit return后重试、提交后ACK前中断 | 原信封与eventId保持，真实return/重投/显式重复最终各一笔退款，无第二次库存动作或退款 |

B的realWaitMillis=139595、ageAtReleaseMillis=139631，超过原120000ms年龄门，interval仍30000ms。RESERVE_UNKNOWN与RELEASED包的order/intent/key/items/SKU身份一致，四个成单表均0、Pay身份为空，User/Pay事实保持。其结果是未成单预留被释放，不能算第二笔支付。

去重最终快照共14笔支付：12笔TCC（11 COMMITTED、1 ROLLEDBACK），2笔新LEGACY；支付状态7 SUCCESS、6 REFUNDED、1 PENDING，13条APPLIED DEBIT、6条APPLIED REFUND；最终样本冻结余额和预留库存均0。PENDING属于原ROLLEDBACK负例，它没有扣款/退款/交付，不代表公开失败状态投影已验收。真实TC有12个全局事务、Pay/User各12分支共24分支，11提交1回滚；XID摘要与12个TCC样本一一匹配。首轮另1个TC提交单列，不混入成功轮。

31份Stage包四个数据库和MQ来源均OBSERVED，仍保留EVIDENCE_INCOMPLETE；它们不是跨服务原子快照，也没有凭空补全TC分支ACK/目标消息因果。两份MQ专项包为VERIFIED/eventCausalityProven=true，brokerAckHistoryProven仍false；普通A未来30分钟超时消息仅验证真实outbox发布，不声称已自然到期消费。3份TC原生final包保持双REGISTER/全局COMMIT且逐分支COMMIT ACK不可见的原保守规则。

外部只读进程观察记录13个子JVM（User1、Stock1、Order各类6、Pay各类5），全部明确退出；真实环境参数/Asia/Shanghai一致，无控制key输出。退出沿原强停/有限等待流程，不能称自然退出。Maven父进程另记，不把子JVM当Agent。

### 59.6 资源归档与验收边界

r2四库CREATE/DROP与最终schema互证；每项目40张空源表DDL/计数/checksum不变，r2原vhost已删除、队列/连接0、五类Nacos服务在原组均无实例。r2 Redis DB0保留恰好两个balance:debit:7:<payNo>字符串Key，逐个匹配两笔新LEGACY支付，TTL仍为原30天范围，其余DB空；它们是原扣款缓存，已保存DUMP/TTL/SHA并留卷，未FLUSH/删Key。首轮Redis初末空。

两轮均已归档日志、SQL/消息/缓存事实和停止后的TC sessionStore/root.data，然后仅down自己的五容器/网络，无-v。两个项目的mysql_data/redis_data/rabbit_data/nacos_data/nacos_logs/seata_data共12卷保留；首轮六卷metadata和证据SHA保持、未被r2挂载。

r2关闭门曾因旧容器StartedAt变化停止。实际另一个Compose项目cloudnest的minio/redis/mysql于17:43:26从停止变运行；Docker事件只证明启动，不能确定调用者。本任务未操作这三个容器；35个旧容器ID/镜像/挂载/RestartCount均保持，其余32个StartedAt保持。差异与事件已单列，关闭门按精确三项外部变化记录后继续自有资源清理，未把所有旧资源启动状态描述为不变。

1123份原跟踪backend/deploy中仅两个测试文件变化，其余1121份及README保持；原12业务方法/工具调用链、生产与既有Feign/JSON/SQL契约未改。两Java格式、适用编译/测试/打包与diff检查通过。主证据target/backend-refactor-20261114/pay-delivery-native/verification.json指向首轮红/绿测试、失败保留和pay-delivery-native-r2成功证据，两个实验目录均独立保留。

本轮结论仅为当前12项四服务有限交付回归通过。Product/Cart仍是原只读metadata替身，未走Gateway/前端；首轮未交付样本、ROLLEDBACK负例公开PENDING、Stage保守缺证和未覆盖的网络/崩溃组合均按真实边界保留，不宣称完整商城、所有历史风险或性能验收完成。

### 59.7 回退与后续

方案提交40c60754，基线refactor/pay-delivery-native-baseline-20261114，完成标签refactor/pay-delivery-native-complete-20261114。结果提交仅本方案及上述两测试Java；临时index逆补丁验证恢复方案基线树，真实index/README保持。

```powershell
git revert refactor/pay-delivery-native-complete-20261114
```

回退只恢复源码/方案，不执行任何资金清算，也不恢复或删除数据库、TC、证据和12卷。原bugfix_log已补根因、红绿回归、两轮真实结果、外部环境变化和归档事实。

下一步汇总本轮逐模块重构的全局验收矩阵，逐项区分结构目标已完成、当前真实覆盖和剩余取消/公开入口风险；只对结构调整直接影响且尚缺证的部分冻结最小补验，不自动重跑全部历史IT或开启性能维护任务。


## 60. 全局验收矩阵与剩余工作收敛（2026-11-14）

### 60.1 冻结范围与实施判断

从5798b822继续，主Agent单独完成本轮汇总。第13至48节已经完成八模块中等深度结构任务，第49至59节补充了离线、实库/消息/索引、TCC与四服务交付的分层验证。当前先核源码和证据，再判断收尾，不用历史报告数量推断所有场景已完成。

本阶段只更新本方案，并新增可独立阅读的.codex/specs/backend_refactoring_acceptance.md：逐模块职责与迁移/最近通过点、当前统一离线结果、既有实库证据及限制、规范保留项、失败现场/回退边界、必要剩余工作。生产/测试Java、配置/POM、SQL、部署脚本、README及两份只读参考文档不改；不启动或变更Docker实验，不运行旧真实IT/压测，不重启历史实验。

### 60.2 当前源码与验证范围

已盘点265个*Test类，另沿第49节明确选择StockSeckillFactFeignIT和PayOrderStateFeignIT两个本机HTTP契约类，预期267类。第49节后只有13个测试文件新增或修改，生产源码/资源/POM/数据库/部署配置没有变化；当前统一测试用于纳入新增环境准入与端口重检修正。一次根工程离线test和package（package跳过重复测试）按实际XML统计，不把第49节1933或第59节79/12叠加为本轮计数；预计1948项，最终以实际报告为准。

使用已核JDK17/Maven3.9.16，原add-opens/Asia-Shanghai参数；不执行clean，保留target历史证据。核八Jar与编译class字节/SourceFile，并保存Product原测试自有子JVM正常退出和Pay指标新runId证据。Java子进程是原测试运行资源，不是多Agent。

对八模块生产Java进行只读结构清单及JDK语法树检查：语义类名、Controller继承/职责、非空Service、Controller/Service直接JDBC/SQL、嵌套模型、必需JavaDoc/成员说明和短变量；Google Java Format仅dry-run，不批量改代码。规范扫描命中必须复核真实含义与既有兼容保留规则，不将文本匹配自动当业务Bug，也不把未检查项写成全绿。

### 60.3 历史证据、剩余项与回退

逐组校验第49至59节verification、成功XML SHA和Git标签/提交可达；区分本轮新执行与过去已执行并本轮复核。只读取现存隔离资源metadata，核有记录的实验容器/网络已移除、卷保留；不要求其他会话/项目的全机状态不变，不挂载旧失败卷或清算首轮未交付样本。

重点裁定：哪些结构目标已完成、哪些规范项仍需小范围收尾、取消/公开失败入口已有何种层级证据，真实Gateway/Product/Cart全链和性能为何不属于当前通过口径。第59节首轮四失败库及12卷、旧bridge失败库、保守UNKNOWN/EVIDENCE_INCOMPLETE均保留来源；不因汇总删除证据或改写旧风险状态。

证据target/backend-refactor-20261114/refactoring-acceptance。先提交本节并建立refactor/backend-acceptance-baseline-20261114；最终仅提交本方案与验收汇总Markdown，临时index逆补丁恢复基线树且真实index/README不变后建立refactor/backend-acceptance-complete-20261114。发现真实缺陷先在原bugfix_log记录，再冻结最小修正范围，不在汇总中隐去失败。


### 60.4 当前统一验证与历史证据复核

本轮未改Java/配置。当前265个*Test及两个指定本机HTTP契约IT逐类匹配冻结清单，最终267类1948项，0失败/错误/跳过；无遗漏、额外IT或旧报告混入。各模块Common25、Gateway21、User284、Product361、Cart35、Order341、Stock209、Pay672。相较第49节1933，新增15项均来自三组测试环境/证据准入测试；不是业务功能增量。本轮没有重跑真实SQL/Redis/MQ/ES/Nacos/TC实验。

根工程test和package首次通过；package跳过重复测试。八Jar共628个本模块生产class，逐个与target/classes及现存src/main/java的SourceFile/内部类型对应，无测试helper或无源旧class。Product原测试自有子JVM自然退出，任务/执行器关闭、AMQPConnections=0；Pay指标runId证据单独归档。构建保留既有ANTLR4.7.2/4.8提示，不把提示当失败或擅自改依赖。

复核12组历史verification/成功XML SHA及Git完成标签、提交可达/树和历史逆补丁记录：第46/47两组取消/公开入口局部验证，以及第49至59主要离线/真实组。第46的34项MySQL、第47的7项本地事务是各组713/688的子集，不能重复计数。本轮只校验历史报告，没有把它们相加为一次新回归。校验脚本首次因Windows反斜杠manifest键查找失败，仅在新工具中规范化路径后通过，旧证据字节未改。

只读核对这些证据关联的12个实验项目，容器/网络均无残留，43个记录中的命名卷仍存在；这是指定集合，不是全部历史或整机卷数。没有挂载/重启/删除卷，也未要求其它会话/项目保持全机静态。第54首轮两库、第59首轮四库/未交付样本与两轮12卷原记录保持。

### 60.5 结构通过与规范待办分开判定

641份生产Java含包说明，33个Controller、41个Service接口。类型/继承/注解及限定源码扫描没有Cm编号生产类型、Controller继承ServiceImpl、空Service接口、Service HTTP映射、Controller/Service直接JDBC或指定SQL字面量候选，也没有DTO/VO/PO后缀嵌套模型。Mapper内现有JDBC、Feign局部record、表/Bean/消息/指标的cm18/cm21标识按既有兼容规则保留。这是结构检查，不替代完整语义/端到端证明。

全641文件执行只读JavaDoc/短名AST检查和Google Java Format dry-run后，不能将“结构完成”误记成“规范全部完成”：

| 模块 | 必需说明绑定项 | 格式文件 | 非id短名 | 模板化说明行 | 去重待办文件 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Common | 0 | 4 | 1 | 0 | 5 |
| Product | 34 | 16 | 6 | 23 | 21 |
| User | 17 | 2 | 0 | 0 | 5 |
| Stock | 9 | 12 | 0 | 0 | 16 |
| Cart/Gateway/Order/Pay | 0 | 0 | 0 | 0 | 0 |

60个AST说明项中21个已有JavaDoc但放在注解后，另39个需补独立说明（部分已在匿名类/成组字段上层说明），不是60个完全无注释。模板初筛16条，再核明确的“执行X相关操作”补7条，最终23条、四个Product文件，去重文件总量由初步46修正为47。103个短声明中96个id保留其主键/查询/传输语义，另7个ex/e/rs需语义命名；禁止机械改动PO/JSON/SQL字段。数量单位不同且有重叠，不相加为缺陷总数。完整逐文件列表、原行号及分类已写入新增backend_refactoring_acceptance.md与reviewed-followup-inventory.json。

这些是当前发现、仍待修正的规范问题，不是本轮新引入的运行回归。第49节后backend/deploy变更恰13个测试文件，生产/配置/schema/部署没有变动；本轮1123份原跟踪backend/deploy及README前像全部保持。四模块规范项未通过收尾，不在本轮修改Java或把dry-run返回1记作格式通过。

### 60.6 剩余工作与有限收尾顺序

新增汇总文档独立列出模块职责/检查点、当前1948项、分层历史证据、47文件规范清单、取消/公开入口限制、失败现场和精确回退边界。下一步先Common五文件：AuthContext、ErrorCodes、SeckillRequestFingerprint、GlobalExceptionHandler仅格式/import，AuthInterceptor仅异常形参ex语义命名；预先冻结差异，保HTTP/JSON/鉴权/异常顺序，执行Common及适用契约后独立提交。随后Product21、User5、Stock16按模块分别收尾，不一次性全库重排。

实际取消/公开失败并非完全无证据：本轮已有对应单测，§46有34项局部MySQL、§47有7项本地事务/代理；但它们不替代真实User/TC的完整公开取消链。旧PayTccCancelIT仍固定地址/自建Redis及RM代理SHA准入，不能原样连接默认环境；若后续要声明该全链验收，另定最小隔离方案，不绕过pin或自动运行旧大矩阵。Gateway/Product/Cart全商城与性能亦列为独立范围，不延长本次规范任务队列。

本轮classpath再确认Gateway3.1.4与规格3.1.5既有差异，作为独立规格/依赖核对事项保留，不升级。Stage EVIDENCE_INCOMPLETE、BEGIN_UNKNOWN/UNKNOWN及§59回滚负例PENDING、首轮未交付样本均不改写。

### 60.7 本阶段回退

方案提交53d41be1，基线refactor/backend-acceptance-baseline-20261114，完成标签refactor/backend-acceptance-complete-20261114。本阶段只提交backend_refactoring_plan.md与新增backend_refactoring_acceptance.md；临时index逆补丁恢复方案基线树且真实index/README不变。证据target/backend-refactor-20261114/refactoring-acceptance/verification.json。

```powershell
git revert refactor/backend-acceptance-complete-20261114
```

该回退只撤销本次文档汇总，不撤销模块实现，也不更改数据库/缓存/消息/TC、失败现场或保留卷。本阶段“完成”仅指验收矩阵/当前统一验证和剩余任务定位完成；整个规范收尾仍待上述四个批次，不能使用此标签宣称全项目完成。
