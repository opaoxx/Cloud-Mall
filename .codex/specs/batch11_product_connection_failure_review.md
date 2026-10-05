# CM-20261005-16 商品读数据库连接故障归因与错误映射评审

## 1. 评审结论

本轮只收敛商品公开读接口在数据库依赖故障下的异常分类、响应契约和观测，不调整连接池容量、生产超时阈值、SQL 语义、金额/时间字段或商品写接口。

当前商品读异常处理器仅直接捕获 `DataAccessResourceFailureException` 和 `QueryTimeoutException`。`ProductServiceImpl.withReadTransaction` 通过 `TransactionTemplate` 开启只读事务，连接获取失败可能被包装为 `CannotCreateTransactionException`；详情和 SKU/参数读取还可能出现 `RecoverableDataAccessException`、JDBC `SQLTransientConnectionException`、`SQLTimeoutException` 或 MySQL Connector/J 通信异常。因异常包装层级不同，当前历史形态的 500 不能仅凭顶层类型判断为 SQL 缺陷或连接池故障。

GO 条件：新增商品模块内部、无状态的标准 cause 分类工具；由商品读 `ControllerAdvice` 对公开 GET 使用分类结果决定 503/500；补齐异常包装、连接获取、SQL 超时、通信中断、坏 SQL 和未知异常测试；通过真实隔离网络/池耗尽/SQL socket 超时演练后，再更新 Bug 日志的根因结论。未完成注入验证前，历史 2026-09-05 的唯一根因仍标记为未定位。

## 2. 现有证据和范围

### 2.1 现有调用链

公开入口为 `ProductController` 继承 `ProductServiceImpl` 的映射：

- `GET /api/products` → `products` → `withReadTransaction` → `countProducts`、`findProducts`、列表商品事实映射；
- `GET /api/products/{id}` → `product` → `requireProduct` → `findProduct`、`findSkus`、`findParameters`；
- `GET /api/products/skus/{skuId}` → `sku` → `findSku`；
- `GET /api/products/{id}/hot-stat` → `hotStat` → 商品热度读取。

`ProductSqlMapper` 使用 `JdbcTemplate` 执行上述读取。事务开始失败发生在 Mapper 调用之前，因此不能只在 Mapper 方法中捕获。异常分类必须沿 `Throwable.getCause()` 链检查，处理 Spring 事务包装异常和 JDBC 原始异常。

### 2.2 历史与当前证据

2026-09-05 的历史记录显示约 30 秒后集中出现 10 个 500，但当时未保存异常栈，无法区分连接获取、数据库断连、SQL 执行超时或应用自身异常。CM-20260924-16 已完成索引、列表长尾和观测改进，但没有进行真实断连或连接池耗尽演练，不能把性能改善表述为历史连接故障根因已消除。

### 2.3 本轮明确不做

- 不扩大 Hikari `maximumPoolSize`，不降低或提高真实环境的 `connectionTimeout`、`socketTimeout` 或 SQL 超时；
- 不改变数据库 schema、商品排序、分页、JSON 金额字符串、ISO-8601 时间和既有 DTO/VO 字段；
- 不把所有 `SQLException`、所有 `DataAccessException` 或所有事务异常一律映射为 503；
- 不修改 common 全局异常处理器，不把商品读契约扩散到其他服务；
- 不把非 GET 写接口的失败包装为“结果未知”或依赖不可用。

## 3. 最小异常分类契约

分类工具建议放在 `backend/cloud-mall-product/src/main/java/com/cloudmall/product/util/`，例如 `ProductReadFailureClassifier`。它只接收异常和上下文，返回内部枚举/值对象，不执行 SQL、不访问连接池、不修改响应，不放入 `cloud-mall-common`。

分类器必须先完整收集 cause 链，再按下列优先级做最终判定；不能因为外层先出现 `RecoverableDataAccessException` 就截断扫描，导致内层 `SQLTimeoutException` 或 JDBC 通信异常被漏分。获取连接上下文与查询执行上下文必须由调用入口明确传入或由可验证的事务异常类型推断，不能仅凭异常类名猜测。

| 内部原因 | 命中条件 | 指标 reason | 公开 GET 响应 |
| --- | --- | --- | --- |
| `CONNECTION_ACQUIRE` | `CannotGetJdbcConnectionException` 直接表示获取连接失败；或 `CannotCreateTransactionException`/同等事务开始包装异常的 cause 链包含 `SQLTransientConnectionException`，并有明确 Hikari acquisition timeout/连接池获取证据。查询执行阶段出现的 `SQLNonTransientConnectionException`、`SQLRecoverableException` 或普通 `SQLTransientConnectionException` 不得归入本类 | `connection_acquire` | `PRODUCT_QUERY_UNAVAILABLE` / 503 |
| `SQL_TIMEOUT` | `QueryTimeoutException`、`SQLTimeoutException`，或明确的数据库查询执行超时包装；必须处于已知 JDBC/DAO 查询执行上下文且不属于连接获取上下文。普通请求超时、TCP `ConnectException`、连接建立失败不能推断为 SQL 超时 | `sql_timeout` | `PRODUCT_QUERY_UNAVAILABLE` / 503 |
| `DB_CONNECTION` | `RecoverableDataAccessException`、数据库通信链路异常、`CommunicationsException` 或 SQLState `08` 的 JDBC 异常，且不属于已识别的连接获取/SQL 超时；查询执行期间的 `SQLNonTransientConnectionException`、`SQLRecoverableException` 和未明确为池获取的连接异常归入本类 | `db_connection` | `PRODUCT_QUERY_UNAVAILABLE` / 503 |
| `UNKNOWN` | 坏 SQL、映射错误、约束错误、未知事务失败、普通 `SQLException` 无 SQLState `08`/超时证据或业务异常 | 不增加依赖失败计数 | `COMMON_INTERNAL_ERROR` / 500 |

分类优先级必须保证：明确的连接获取失败优先于其他通信异常；明确的查询 SQL 超时优先于泛化的可恢复 DAO 异常；其余 SQLState `08`/数据库通信异常归入 `DB_CONNECTION`；未知 `SQLException` 不得因为实现了 `DataAccessException` 就自动成为 503。工具只使用稳定的类型、SQLState、调用上下文和受控的 JDBC/连接池异常标识，不依赖本地化日志全文作唯一证据。Redis/ES 的 IO 异常不属于本轮数据库分类。

## 4. ControllerAdvice 与 API 契约

`ProductReadExceptionHandler` 继续限定在 `com.cloudmall.product.controller`，并覆盖事务包装异常、读路径已知 Spring DAO 异常及必要的 JDBC 包装入口。处理器调用分类工具后：

1. 请求方法为 GET 且分类为 `CONNECTION_ACQUIRE`、`SQL_TIMEOUT` 或 `DB_CONNECTION`：返回 HTTP 503，错误码 `PRODUCT_QUERY_UNAVAILABLE`，消息保持“商品查询暂时不可用”；
2. 非 GET，即使底层原因属于上述三类：返回 HTTP 500，错误码 `COMMON_INTERNAL_ERROR`；不得暗示写入结果确定成功或失败；
3. 任意方法分类为 `UNKNOWN`：交给既有全局错误处理语义，公开响应为 HTTP 500/`COMMON_INTERNAL_ERROR`，记录完整服务端堆栈；
4. 参数错误、商品不存在、商品下架等业务异常继续保持 400/404/409，不被读依赖处理器吞掉；
5. 响应不得包含异常类名、SQL、账号、连接地址或堆栈。

指标保留 `cloudmall.product.read.failures`，但仅对公开 GET 的三类确定性依赖失败增加 `reason=connection_acquire|sql_timeout|db_connection`；写请求即使底层异常属于上述类别，也不增加该 read.failures 指标。分类为 UNKNOWN 的请求由全局错误日志/既有错误指标记录，不伪造依赖失败计数。日志必须带 path、requestId、traceId、分类、异常类型和完整堆栈；不得打印 SQL 参数、凭据或完整 JDBC URL。

`api_spec.md` 中的 `PRODUCT_QUERY_UNAVAILABLE` 语义补充为：仅适用于公开商品 GET 在数据库连接获取、数据库通信或 SQL 执行超时且本次结果不可可靠确定的情形，非 GET 和未知实现错误仍使用 `COMMON_INTERNAL_ERROR`。字段结构、金额、时间和既有成功响应不变。

## 5. 实现边界

### 5.1 分类工具

- 使用明确的 `ProductReadFailureReason`，至少包含 `CONNECTION_ACQUIRE`、`SQL_TIMEOUT`、`DB_CONNECTION`、`UNKNOWN`；
- 提供 cause 链遍历，设置最大深度，处理异常自引用，避免异常链损坏时死循环；
- 支持 `TransactionSystemException`/`CannotCreateTransactionException` 等事务包装异常，并以其 cause 链的 JDBC/DAO 证据分类；
- 不在工具中创建计数器、写日志或生成 `ApiResponse`，以便纯单元测试覆盖；
- 对 MySQL Connector/J 的 `CommunicationsException` 使用可选依赖类型或稳定父类/SQLState 识别，不能为了单一驱动把业务代码绑定到驱动内部实现；
- 不将 `SQLTransientException` 的全部子类直接归为连接故障，必须先区分 SQL timeout、通信和未知情形。

### 5.2 Advice 入口

允许按现有 Spring MVC 异常解析方式增加多个 `@ExceptionHandler` 入口，但最终必须归一到同一个分类和响应函数，避免同一异常被不同入口重复计数。事务开始失败、详情多次 Mapper 调用失败、列表 count/find 任一路径失败均必须使用相同契约。

写接口的现有事务行为、回滚和状态语义不在本轮修改；写接口底层连接失败仍由原有全局异常路径返回 500 并记录堆栈。

## 6. 验证计划

### 6.1 单元与 MVC 契约

`ProductReadDependencyFailureTest` 扩展为至少覆盖：

- `CannotGetJdbcConnectionException` 直接出现在 count/list/detail：GET 返回 503，计数为 `connection_acquire`；
- `CannotCreateTransactionException` → `SQLTransientConnectionException` 且明确 Hikari 获取超时证据：GET 返回 503，计数为 `connection_acquire`；查询执行期间相同 JDBC 类型必须按 SQLState/上下文归为 `db_connection`；
- `QueryTimeoutException`、`SQLTimeoutException` 和其事务/DAO 包装：GET 返回 503，计数为 `sql_timeout`；
- 外层 `RecoverableDataAccessException` → 内层 `SQLTimeoutException`：在已知查询上下文归为 `sql_timeout`；外层 `RecoverableDataAccessException` → MySQL 通信异常或 SQLState `08`：GET 返回 503，计数为 `db_connection`；
- 普通 JDBC `SQLException` 无 SQLState `08`、`BadSqlGrammarException`、映射异常和未知事务异常：GET 返回 500，且不增加三类依赖失败计数；
- 同一确定性异常通过 POST/PUT/DELETE 入口：返回 500，不返回 `PRODUCT_QUERY_UNAVAILABLE`，且不增加 `cloudmall.product.read.failures`；
- 分类工具的 cause 链深度、循环引用和优先级。

### 6.2 真实隔离演练

所有演练使用测试 profile 和临时测试数据/网络，完成后恢复容器、端口、配置和测试夹具。不得停止或删除共享生产式数据卷，不得把测试阈值写入默认生产配置。

1. **TCP 代理阻断**：在 product 到 MySQL 的独立测试网络路径上阻断连接，执行列表和详情 GET，验证 503、`db_connection`/`connection_acquire` 归因；恢复路径后执行成功请求，确认连接池和服务恢复。
2. **池耗尽**：仅测试 profile 使用 `maximumPoolSize=2`、`connectionTimeout=30000`，通过冻结短 socket 窗口模拟历史“10 个请求同时进入、约 30 秒后失败”的形态；验证获取连接失败映射、日志/指标和释放连接后的恢复。不得用该 profile 数值推导生产容量。
3. **SQL 响应超时**：仅测试 profile 使用 `socketTimeout=1000` 或等效可控延迟，验证 SQL 执行超时归为 `sql_timeout`；恢复数据库后确认正常 GET。

原 2026-09-05 日志只有 HTTP 500 时间分布，没有异常栈；即使上述演练复现相同外部形态，也只能证明候选故障路径和恢复契约，不能宣称唯一历史根因已经证明。根因结论必须记录实际异常类型、SQLState、cause 链、指标 reason 和恢复证据。

### 6.3 回归与统计口径

- 商品五种排序、关键词、分类、状态、分页边界和详情/SKU 既有结果回归；
- 统计 503 时按一次 HTTP 请求计数；同一异常链不得因多个 Advice 入口重复计数；
- 连接获取、SQL 超时、数据库通信分别统计，不将 UNKNOWN 纳入依赖失败率；
- 报告测试请求数、HTTP 错误数、三类 reason 数、500 UNKNOWN 数、恢复后成功数，并注明仅测试 profile 和本机环境；
- 清理临时代理、测试网络、容器、连接池配置、测试商品/锁/长事务，确认默认配置和共享服务状态恢复。

## 7. 验收标准

1. 所有已知连接获取/数据库通信/SQL 执行超时在公开 GET 上稳定返回 `PRODUCT_QUERY_UNAVAILABLE`/503，并带对应 reason 指标；
2. 坏 SQL、映射错误、未知事务异常和普通未分类 `SQLException` 保持 500，不增加依赖失败指标；
3. 所有非 GET 写接口不因底层连接异常返回“商品查询不可用”503；
4. 日志能根据 requestId/traceId 还原完整 cause 链，响应不泄露内部细节；
5. 真实三类隔离演练至少完成 TCP 阻断、池耗尽、SQL 超时中的前两项，最好三项均完成，并验证恢复；
6. 既有商品结果、API 字段、金额/时间格式、连接池默认阈值、schema 和索引不发生无关变化；
7. Bug 日志明确区分“候选路径已复现”和“2026-09-05 历史唯一根因已证明”，没有证据时保持未定位。

## 8. 后续开发交接

本规格评审通过后由 backend 角色实现分类工具、Advice 和测试；qa 角色按 6.2 执行只读/隔离验证并提供证据。实现提交不得包含测试临时配置、代理脚本、凭据、日志或数据卷内容。实现完成前不得修改商品 API 成功响应契约，也不得顺带调整 Hikari 生产参数或 common 全局异常处理。
