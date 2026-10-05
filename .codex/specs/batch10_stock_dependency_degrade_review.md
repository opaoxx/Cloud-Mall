# CM-20261005-27：Stock 内部 Redis/MySQL 异常与慢调用熔断规格

## 1. 评审范围与结论

本规格只处理 Stock 服务内部对 Redis、MySQL 的依赖故障和慢调用保护，作为
CM-20261005-27 的后续阶段。Order→Stock 的 Feign 熔断、Stock 秒杀 flow 限流、
Seata 示例事务和数据库初始化 DDL 均不在本规格内。

评审结论：**GO，前提是按本规格实现整操作边界并先完成 AOP/事务链路测试。**
不得把 Mapper 方法逐条套入熔断，也不得把一次 Redis Lua 的业务拒绝结果当成依赖
异常。所有未知结果必须保留现有订单号、原意图、动作类型和幂等键。

## 2. 当前真实调用边界

### 2.1 HTTP 前台入口

`StockController extends StockServiceImpl`，因此实际 Spring MVC 入口是以下继承来的
public 方法，而不是 Controller 自己声明的方法：

| 入口 | 真实方法 | 当前关键边界 |
| --- | --- | --- |
| 普通库存读 | `StockServiceImpl.get(Long)` | 先读 Redis 投影；只有 miss/Redis fallback 时才进入 `stock-mysql-read`，缓存命中不进入 MySQL gate |
| 普通预扣 | `StockServiceImpl.reserve(StockReservationDTO)` | `@Transactional(noRollbackFor=StockReservationRejectedException.class)`，先 `StockReservationRequestService.claim` 独立事务，再在同一业务事务读写库存、流水、修复意图和请求状态 |
| 确认 | `StockServiceImpl.confirm(String, StockActionDTO)` | `@Transactional`；秒杀结算先进入持久状态机，普通库存再读写请求、流水和修复意图 |
| 回滚 | `StockServiceImpl.rollback(String, StockActionDTO)` | `@Transactional`；秒杀只提交释放意图，普通库存写回 MySQL 并排队投影 |
| 秒杀受理 | `StockServiceImpl.seckill(SeckillReservationDTO)` | 先鉴权和参数校验，再进入现有 flow 规则；随后 claim、Redis Lua、受理事实和 outbox |
| 预扣状态 | `StockServiceImpl.reservation(String)` | 只读持久事实；不得将依赖故障转成 NOT_FOUND 或终态 |

`confirm`/`rollback` 内部调用 `SeckillQuotaSettlementService.settle`，普通路径调用
`StockSqlMapper`；`seckill` 内部调用 `SeckillRequestClaimService.claim`、
`redisOperation` 和 `SeckillAcceptanceService.accept`。不能依赖从同一个类内部调用
触发 Spring AOP；实现必须通过 Spring 代理对外包住入口，或把整操作移到独立代理 Bean。

### 2.2 后台恢复与对账入口

以下是独立的调度入口，按一次批次或一次恢复操作定义保护边界：

- `SeckillQuotaSettlementRecovery.scheduledRecover()` / `recoverDue()`：保留
  settlement 的 `intent/operationKey/revision/lease/cursor`，Redis 释放结果未知时
  只写 retry/block 事实，不能重复释放或扩大写入。
- `SeckillOrderOutboxDispatcher.dispatch()`：保留 outbox 的 eventId、owner、lease、
  retry 时间；Redis marker 恢复或 Rabbit publisher confirm 不确定时不能生成新订单号。
- `StockCacheReconciler.initialize()`、`retryIncompleteCalibration()`、
  `repairPending()`、`unblockResolvedLegacyStocks()`：Redis 投影故障只暂停或保留
  `PENDING/BLOCKED` 修复意图，MySQL 事实不回写为虚假数量。
- `StockConsistencyReconciler.scheduledAudit()`：租约、审计游标和批次失败时
  fail closed，不能推进游标或把未知观察当作一致；普通库存事实仍以 MySQL 为准。
- `SeckillAcceptanceService.accept()`、`SeckillRequestClaimService.claim()`、
  `SeckillQuotaSettlementService.settle()` 的 `@Transactional` 方法由前台入口调用，
  不单独再开一层依赖熔断，避免 claim 后分叉。

恢复器可按一批次进入 Redis/MySQL 的内部资源，但每条 lease/intent 的状态迁移必须
在明确成功或明确可重试异常后进行；熔断只阻止本轮继续，不得清除证据。

## 3. 故障分类与 API 契约

### 3.1 Redis

纯 Redis 调用必须从统一包装入口进入，包括 `StringRedisTemplate` 普通读写、Lua
`execute`、`scan/keys`、锁租约和投影修复调用。包装入口应在实际 Redis 调用之前
执行 Sentinel entry，并在 `finally` 中退出。

计入 Redis 依赖异常/慢调用的情况：连接拒绝、连接池获取失败、认证/协议错误、读写
超时、`RedisSystemException`/`DataAccessResourceFailureException` 等明确连接资源
故障，以及超过配置慢阈值的成功调用。异常分类要保留 cause 类型，但响应不得暴露
连接信息。

不计入依赖异常的情况：秒杀 Lua 返回 `0/2/4/5`（售罄、限购、未开始、已结束）、
其它已经定义的业务拒绝码，以及 Redis 中明确表示业务状态的正常返回。Lua 返回值
缺失、类型错误、marker 与订单号不一致属于结果未知/协议故障，应计入依赖异常并返回
Redis 不可用；不能伪造售罄或限购。

前台契约：

- 秒杀写路径和依赖 Redis 的受理路径返回既有 `REDIS_SERVICE_UNAVAILABLE`，HTTP
  503；保留原用户幂等键、claim 行和订单号，不能自动新建第二次请求。
- 普通库存 `get` 的 Redis 投影读发生异常或熔断时回退 MySQL 事实；MySQL 读成功则
  返回真实数量。该只读 fallback 不写 `stock_cache_repair`，也不隐式刷新 Redis；投影
  修复由 `StockCacheReconciler`/`StockConsistencyReconciler` 后台流程完成。若 MySQL
  也不可用，返回统一 Stock 依赖不可用，不返回 0 或过期 Redis 值。
- Redis 故障发生在普通预扣事务的投影清理阶段时，MySQL 事务结论保持原语义，修复
  状态保留 `PENDING`；不能为补偿 Redis 重新扣减库存。

### 3.2 MySQL

MySQL 保护单位是一次完整的业务操作，而不是一个 Mapper 方法。对 `reserve`、
`confirm`、`rollback`，Sentinel entry 必须覆盖事务实际 begin 之前的入口、业务读写、
commit/rollback 完成和异常映射。必须用真实 Spring transaction advisor/AOP 测试
证明 entry 的进入和退出覆盖该范围；不能在 `findStockForUpdate`、`update...` 等
单条 SQL 之间打开/关闭熔断。

计入 MySQL 依赖异常/慢调用的情况：连接拒绝、连接池耗尽、SQL/事务超时、死锁/连接
断开、`DataAccessException` 中的资源故障，以及完整操作超过慢调用阈值。业务库存不足、
幂等冲突、版本冲突、状态冲突属于确定业务结果，不计异常；事务回滚后的结果未知仍
按依赖异常处理，不能猜测库存已扣或未扣。

API 契约：

- MySQL 不可用、完整操作被熔断或事务结果未知，统一返回 `STOCK_SERVICE_UNAVAILABLE`
  HTTP 503；不伪造确定失败、不伪造库存值。
- `reserve` 必须保留已有 request claim/原订单号语义。若事务在 claim 独立事务后
  未完成，返回结果未知并由现有查询/恢复链路收敛；禁止自动调用 rollback、换订单号
  或写一条假的 REJECTED。
- `confirm`/`rollback` 被熔断时保留原 `operationKey` 和动作类型，返回未知/503，
  不执行相反动作。后台恢复器保存 lease/cursor 并退避。
- `reservation` 读失败返回 503/未知；只有读到明确持久状态才返回 RESERVED、
  CONFIRMED、ROLLED_BACK、REJECTED 或 NOT_FOUND。

## 4. Sentinel 资源、规则和动态配置

新增 Stock 内部依赖规则 Data ID：`cloudmall-stock-degrade-rules`，Group：
`CLOUDMALL_DEV`。本 Data ID 只包含本规格资源的完整 JSON 快照；不复用已有
`cloudmall-stock-flow-rules`，避免 flow 与 degrade 更新相互覆盖。

固定资源：

| resource | 入口 | 规则 | 默认慢阈值/比例 | 默认异常比例 | timeWindow |
| --- | --- | --- | --- | --- | --- |
| `stock-redis-seckill-write` | 秒杀 claim 后 Redis 元数据读取、Lua、marker 读取/写入 | SLOW_REQUEST_RATIO + EXCEPTION_RATIO 各一条 | 500ms / 50% | 50% | 10s |
| `stock-redis-projection` | 普通库存投影读写、校准、对账和租约 Redis | SLOW_REQUEST_RATIO + EXCEPTION_RATIO 各一条 | 500ms / 50% | 50% | 10s |
| `stock-mysql-reservation` | `reserve/confirm/rollback` 完整数据库事务 | SLOW_REQUEST_RATIO + EXCEPTION_RATIO 各一条 | 1000ms / 50% | 50% | 10s |
| `stock-mysql-recovery` | 后台 settlement/outbox/reconcile 一次恢复批次 | SLOW_REQUEST_RATIO + EXCEPTION_RATIO 各一条 | 2000ms / 50% | 50% | 10s |
| `stock-mysql-read` | reservation/internal fact 等纯 DB 读，以及普通 `get` 的 MySQL fallback | SLOW_REQUEST_RATIO + EXCEPTION_RATIO 各一条 | 500ms / 50% | 50% | 10s |

实现可把每个 resource 的两类规则组合为严格快照，共 10 条；规则中的 `grade=0`
表示慢调用比例，`count` 为最大响应毫秒，`slowRatioThreshold` 为比例；`grade=1`
表示异常比例，`count` 为 0 到 1 的比例。`minRequestAmount >= 1`、
`statIntervalMs >= 1000`、`timeWindow >= 1`，禁止 cluster mode 和 EXCEPTION_COUNT。
慢阈值上限 60000ms，异常比例范围 `(0,1]`。资源、grade、规则数量重复或未知字段
不匹配时整份快照拒绝。

默认值必须由 `application.yml` 可覆盖，Nacos 新快照只在完整严格校验通过后替换；
空内容、Nacos 超时、非法 JSON、缺资源、重复资源、阈值非法时保留最近有效快照和
本地安全默认，并按现有配置订阅重试。替换时只替换本 Data ID 对应的 10 条规则，
保留同 JVM 内其它 flow/param/degrade 规则；禁止 `loadRules(empty)` 清空全局保护。
Nacos 刷新不得覆盖已有 `cloudmall-stock-flow-rules`。

规则 JSON 只携带资源、规则字段和版本摘要，不携带账号、密码或 token。发布顺序为
先 Nacos，再启动/刷新 Stock；断开 Nacos 时继续用最近有效规则。两实例应最终收到同
一合法快照，不能把本地实例阈值宣称为全局限额。

## 5. 推荐实现边界（给 backend）

优先新增 Stock 专用依赖保护组件，例如 `StockDependencySentinelConfiguration` 和
`StockDependencyGuard`，而不是把 `SphU.entry` 散落在 Mapper 中。MySQL 保护使用高优先级
Spring AOP advisor（`Ordered.HIGHEST_PRECEDENCE + 100`，必须早于
`BeanFactoryTransactionAttributeSourceAdvisor`），让 entry 在事务 advisor 前进入、在
事务 commit/rollback 完成后退出。若调用线程已经持有同一 MySQL guard，则通过
ThreadLocal 深度计数跳过嵌套 entry，不重新开启 gate，也不改变已有事务传播属性；外部
已有事务不得被新 gate 拆分或新开事务。

前台 Controller/业务 pointcut：

```text
execution(public com.cloudmall.common.api.ApiResponse
  com.cloudmall.stock.service.impl.StockServiceImpl.get(..))
execution(public com.cloudmall.common.api.ApiResponse
  com.cloudmall.stock.service.impl.StockServiceImpl.reserve(..))
execution(public com.cloudmall.common.api.ApiResponse
  com.cloudmall.stock.service.impl.StockServiceImpl.confirm(..))
execution(public com.cloudmall.common.api.ApiResponse
  com.cloudmall.stock.service.impl.StockServiceImpl.rollback(..))
```

上述 `reserve/confirm/rollback` 只进入 `stock-mysql-reservation`，必须覆盖
`StockController` 继承来的 MVC 调用及真实事务 begin/body/commit/rollback。`seckill`
整个 HTTP 方法不得进入 MySQL gate：它是 Redis 与独立 MySQL 事务的组合，Redis 故障
不能被统计成 MySQL 故障。其独立事务由下列精确方法进入同一个 resource，并由嵌套
ThreadLocal 规则避免重复统计：

```text
execution(public * com.cloudmall.stock.service.impl.StockReservationRequestService.claim(..))
execution(public * com.cloudmall.stock.service.impl.SeckillRequestClaimService.claim(..))
execution(public * com.cloudmall.stock.service.impl.SeckillAcceptanceService.accept(..))
execution(public * com.cloudmall.stock.service.impl.SeckillQuotaSettlementService.settle(..))
```

等价实现可以使用 `@Transactional` 方法注解 pointcut，但必须排除
`Cm18SeataStockDemoServiceImpl` 及其 orchestrator。claim/accept 的独立事务各自只在
数据库事务边界进入 gate；不可把 Redis Lua、HTTP 参数校验或 `seckill` 外层耗时纳入
MySQL 统计。

为避免漏掉新增的 Stock 事务服务，允许使用以下受限注解表达式作为补充，但必须在
启动时打印/测试最终匹配集合：

```text
@annotation(org.springframework.transaction.annotation.Transactional)
&& within(com.cloudmall.stock.service.impl..*)
&& !within(com.cloudmall.stock.service.impl.Cm18SeataStockDemoServiceImpl)
&& !within(com.cloudmall.stock.service.impl.Cm18SeataStockDemoOrchestratorImpl)
```

该表达式只归入 `stock-mysql-reservation`，不会把无 `@Transactional` 的
`StockServiceImpl.seckill` 整体包进去；最终仍须由 Spring proxy/AOP 测试确认没有把
调度初始化或 Seata 方法误纳入。

纯 MySQL 读使用 `stock-mysql-read`，覆盖 reservation/internal fact 查询和由 `get`
调用的 fallback Supplier；Redis 命中时不得提前进入该资源。实现可以
将 fallback 抽为 `StockFactReadService` public 方法，以便代理和注入测试可见，但不改变
HTTP/API 契约。Mapper 方法本身仍不设 Sentinel pointcut。

后台恢复 pointcut：

```text
execution(public * com.cloudmall.stock.service.impl.SeckillQuotaSettlementRecovery.recoverDue(..))
execution(public * com.cloudmall.stock.service.impl.SeckillOrderOutboxDispatcher.dispatch(..))
execution(public * com.cloudmall.stock.service.impl.StockCacheReconciler.repairPending(..))
execution(public * com.cloudmall.stock.service.impl.StockConsistencyReconciler.scheduledAudit(..))
```

这些方法进入 `stock-mysql-recovery` 的一次完整批次边界。该资源的慢调用表示整批
恢复/审计耗时，不表示单个 JDBC 查询耗时；批次内 Redis 异常应分类为 Redis 失败，
不得再计一次 MySQL 异常。调度器被熔断时保留 lease、cursor、intent、receipt 并等待
下一轮，不能把未执行批次标成成功。

但由于 `StockController` 继承 `StockServiceImpl` 且 Controller 方法可能绕过服务
代理，实际实现必须通过 Spring 容器测试验证代理类型、`@Transactional` advisor 顺序、
entry 是否覆盖 commit/rollback、异常是否转成统一 503。若继承结构使 pointcut 无法
安全工作，应把业务实现抽到独立 `StockFacade` Bean，由 Controller 委托；此结构调整
必须保持 HTTP、Feign、JSON、幂等键和状态机契约不变。

纯 Redis 方法建议由 Stock 专用 `StringRedisTemplate` 子类/Bean 统一覆盖
`execute(RedisCallback, exposeConnection, pipeline)` 等执行入口，保持 Bean 类型为
`StringRedisTemplate`、连接工厂、序列化器和事务配置不变，从而覆盖 ValueOps、HashOps、
脚本、`keys`、SCAN 和锁租约调用。若当前 Spring 版本无法安全替换该 Bean，使用等价
Stock 专用 guard 包装器，但不得把 Redis 保护点散落到 Mapper。

Redis 场景通过 ThreadLocal 标记：`StockServiceImpl.seckill` 在进入纯 Redis 子调用
前设置 `stock-redis-seckill-write`，离开时在 finally 清除；其它 Redis 调用默认使用
`stock-redis-projection`，包括 release/renew lock、普通库存读写、校准和对账。场景
标记本身不是数据库操作，不得触发 MySQL gate；嵌套 Redis 调用沿用当前场景，不重复
打开另一个 Redis resource。

纯 Redis 方法采用 guard 包装而非 AOP Mapper pointcut，至少覆盖 `StockServiceImpl`
的 `redisOperation`、普通库存 quantity 读、`StockCacheReconciler.redisRead`、
`StockConsistencyReconciler` 的锁/批量读写，以及 settlement recovery 的 release
Lua。普通 MySQL Mapper 不加入逐方法 pointcut；整批恢复只在调度 service 边界进入
`stock-mysql-recovery`。

## 6. 必须排除的路径

- `Cm18SeataStockDemoServiceImpl` 的 Seata 演练事务不套本规则；它单独验证 Seata
  begin/branch/commit/rollback。
- `StockSqlMapper.ensure*Table()`、启动初始化和 DDL 不进入业务熔断统计，DDL 失败应让
  启动/初始化明确失败，不被转换为库存 API 503。
- `SeckillSentinelConfiguration.RESOURCE` 的 flow 规则保持现有
  `seckill-stock-reservation`，不得由新 Data ID 替换。
- 业务 Lua 的售罄、限购、活动状态和幂等分支保持现有业务码；不为“让熔断触发”修改
  Lua 返回码。
- 不新增 Feign 自动重试、换订单号、补库存、多级缓存或跨服务全局熔断。

## 7. 测试与现场验收

### 7.1 单元/集成

1. 用 Spring 测试加载真实 `StockController`、`StockServiceImpl`、transaction
   advisor 和 guard；断言 reserve/confirm/rollback 的 entry 在事务 commit/rollback
   完成后才 exit，内部自调用不能绕过保护。
2. 注入 Redis connection refused、timeout、Lua malformed result；断言得到
   `REDIS_SERVICE_UNAVAILABLE`，业务拒绝 0/2/4/5 不增加异常计数。
3. 注入 MySQL connection refused、慢 SQL、事务 commit 异常；断言统一
   `STOCK_SERVICE_UNAVAILABLE`，不新增错误 orderNo、不伪造库存、不把 claim 后结果
   写成确定 REJECTED。
4. 普通库存 Redis 读失败时注入 MySQL 正常，断言返回 MySQL 数量；双故障时为 503。
5. recovery 注入 Redis/MySQL 双异常和慢调用，断言 intent、receipt、lease、cursor
   保留，周期审计 fail closed，下一轮仍用同一 operationKey/eventId。
6. 规则测试覆盖新 Data ID/Group、10 条完整快照、未知字段、缺失/重复/非法阈值、
   Nacos 断开保留旧快照，以及刷新后已有 flow/param/degrade 规则仍在。

### 7.2 真实故障注入

- 启动 Stock、Redis、MySQL 和 Nacos；通过 Testcontainers 或受控代理把 Redis 端口
  暂时拒绝、延迟 600ms，再恢复，观察秒杀 503、普通库存 MySQL 回退和无重复 Lua 扣减。
- 通过 MySQL 受控代理或测试 profile 注入 1500ms/3000ms 延迟及一次连接断开，验证
  `reserve/confirm/rollback` 的完整事务边界、503 和恢复查询；不得直接 kill 正式
  数据库而不保留备份。
- 用 JMeter 逐步执行 1/5/10/20 并发，分别记录放行、业务 409/429、Redis 503、
  MySQL 503、Sentinel block、stock_flow、reservation request、settlement 和
  outbox 计数；确认每个 orderNo/operationKey/eventId 至多一条事实。
- Nacos 发布一份合法完整快照，确认两实例生效；随后发布非法/空快照，确认旧规则
  保留；恢复合法快照后再次确认。不得用 Dashboard 临时修改代替 Nacos 验收。

## 8. GO/NO-GO 与提交门槛

当前规格对“实现最小依赖保护组件 + 真实 AOP/事务测试 + Redis/MySQL 故障注入”
为 **GO**。若 Spring 继承 Controller 的代理链无法证明 entry 覆盖完整事务，或无法
区分业务 Lua 拒绝与 Redis 依赖异常，则对该子项 **NO-GO**，先调整 Bean 边界并补测试，
不得直接上线部分熔断。

提交前必须通过 Stock 相关 Maven 测试、适用的全仓构建、规则静态检查、
`git diff --check` 和上述现场注入；bugfix_log 同一条 CM-27 记录补根因、方案和
验证证据。不得提交本地 Nacos token、JMeter 临时脚本、数据库备份或故障代理配置。
