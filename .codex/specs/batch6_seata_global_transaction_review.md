# CM-20260924-18：Seata 全局事务落地边界评审

## 1. 评审结论

本规格评审结论为：**不允许直接给现有订单创建、支付成功或关单方法添加 `@GlobalTransactional`**。当前业务已经采用“本地事实先提交 + 跨服务操作意图 + Feign 结果未知恢复 + RabbitMQ/outbox 交付”的一致性模型；直接套用 Seata AT 会把可恢复的已提交事实变成全局回滚候选，破坏已有未知结果和幂等语义。

CM-18 的最小安全目标是先补齐一条有明确边界的、只涉及可由 MySQL AT 回滚的数据写入场景，证明 Seata TM/TC/RM、XID 传播、undo_log 和故障回滚确实可用；订单/库存/支付现有主流程继续由现有补偿模型负责。只有完成该纵向场景和评审后的迁移方案，才可讨论把 Seata 引入业务主链路。

## 2. 当前实现证据

- `cloud-mall-order`、`cloud-mall-pay` 声明了 Seata 1.5.2、AT、`cloudmall_tx_group`、file registry/config 和 `127.0.0.1:8091`；`cloud-mall-stock` 当前没有 Seata starter 和 Seata datasource 配置。
- 全仓源码未发现 `@GlobalTransactional`、`@GlobalLock`、`RootContext` 或显式 Feign `TX_XID` 传播拦截器。Seata starter 可能提供部分自动集成，但必须用运行时请求头和日志证明，不能凭依赖声明推断已经传播。
- Seata Server 由 Compose 以 `STORE_MODE=file` 单实例启动，宿主端口为 `8091`，session store 使用 Docker volume；这是本地练习环境，不提供集群 TC 的高可用保证。
- 当前普通下单调用链是：订单本地事务提交 `INIT` 预扣意图 → Feign 调用 stock 本地事务预扣 → 订单事务保存订单、明细、幂等记录、超时 outbox 和 `COMMITTED` → 失败时按原订单号执行 stock rollback 或由恢复任务收敛。
- 支付调用链是：支付意图本地提交 → user 余额账本本地事务扣款 → pay 持久化 `ORDER_SYNC_UNKNOWN` → order 状态 CAS 和 stock confirm 通过操作键恢复；订单/库存动作结果未知时不得猜测回滚或退款。
- stock 的普通库存写入使用 MySQL 条件更新、`stock_flow`、`stock_reservation_request` 和 `stock_cache_repair`；Redis 只是带版本投影，RabbitMQ 是 outbox 交付通道。

## 3. 数据边界

### 3.1 可由 Seata AT 回滚的范围

只有在参与方使用 Seata 代理数据源、表存在合法 `undo_log`、事务内没有提前提交，并且 SQL 满足 AT 快照/回滚限制时，以下 MySQL 行变更才可作为 AT 的候选：

- order 数据库中的一组订单测试表行；
- stock 数据库中的库存事实、预扣请求和流水行；
- pay/user 数据库中的支付记录或余额账本行，但必须先评审账本唯一键和金额不变量。

每个参与数据库都必须单独初始化与业务表同库同引擎的 Seata `undo_log`，并验证回滚前后唯一键、版本字段、金额和状态机约束。分库事务不能只在 order 数据库安装 `undo_log`。

### 3.2 不由 Seata AT 直接回滚的范围

- Redis Lua 预扣、限购、登录态、购物车和库存缓存投影。Redis 的恢复只能使用既有版本投影、幂等操作键和对账/补偿任务。
- RabbitMQ 发布、TTL/DLX 消息、publisher confirm、outbox 状态和消费副作用。Seata 回滚 MySQL 行不能撤回已经投递或已被消费的消息。
- ES 写入、Zipkin/Prometheus 指标、日志和外部 HTTP 副作用。
- 已在独立本地事务中提交的 `pay_debit_intent`、余额 `DEBIT`、订单 reservation intent 或 stock flow。它们一旦成为事实，必须由原操作键继续恢复，不能因为后续全局事务失败而用 AT 伪造删除。

因此，任何采用 Seata 的新流程仍必须把 outbox 写入纳入本地事实事务，并为消息、Redis 和远程写操作保留幂等补偿；“全局事务回滚成功”不能作为跨中间件原子性的证明。

## 4. 最小纵向场景

### 4.1 推荐场景：独立演练表的 order/stock AT 协调

第一阶段不得写入 `stock_sku`、`stock_reservation_request`、`stock_flow`、`stock_cache_repair`、订单月表、余额账本或支付记录。原因是这些表包含真实库存数量、版本投影和已提交补偿事实；即使只回滚 `stock_flow` 而不回滚 `stock_sku` 也会破坏事实约束，若先触发缓存投影则还可能留下高版本 Redis 投影，使数据库回滚后无法安全回补。

第一阶段固定使用两个独立演练表，只验证 Seata 的跨库分支协调，不声称已经把真实订单/库存业务迁入 Seata：

```sql
CREATE TABLE IF NOT EXISTS seata_cm18_order_demo (
  tx_id VARCHAR(64) PRIMARY KEY,
  tx_xid VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS seata_cm18_stock_demo (
  tx_id VARCHAR(64) PRIMARY KEY,
  tx_xid VARCHAR(128) NOT NULL,
  marker VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB;
```

迁移脚本必须按数据库分别执行：order 库创建第一张表，stock 库创建第二张表；脚本只允许 `CREATE TABLE IF NOT EXISTS`，不得插入测试行、修改 `stock_sku` 或清空现有表。两个库还必须安装 Seata 1.5.2 官方 MySQL `undo_log` DDL，关键定义冻结如下（字段类型和唯一键不得简化）：

```sql
CREATE TABLE IF NOT EXISTS undo_log (
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT NOT NULL,
  log_created DATETIME(0) NOT NULL,
  log_modified DATETIME(0) NOT NULL,
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`rollback_info` 必须是 `LONGBLOB`，不能写成 JSON 或长文本；官方约束是 `UNIQUE KEY (xid, branch_id)`，不能改写成 `PRIMARY KEY (branch_id, xid)`。实际执行前仍需以项目锁定的 Seata 1.5.2 发布包 DDL 做版本核对，并用 `information_schema` 检查表引擎和字段类型。

受控内部 API 固定为：

```text
POST /api/internal/cm18/seata/demo
Header: X-Internal-Service: cloud-mall-cm18-test
Body: {"txId":"cm18-<uuid>","failurePoint":"NONE|ORDER_AFTER_WRITE|STOCK_AFTER_WRITE"}
```

该 API 只能在 `cloudmall.seata.cm18.enabled=true` 且非生产 profile 下注册；`txId` 必须匹配 `cm18-[a-z0-9-]{1,50}` 的受控格式并且不可使用真实订单号，`failurePoint` 只能取三个枚举值。请求 body 不携带可信 XID，XID 只能由 Seata `RootContext` 和 Feign `TX_XID` 传播。只有 `NONE` 成功提交的 `txId` 允许重复查询并返回第一次提交结果；任何回滚结果的 `txId` 不得再次重试，测试 harness 必须生成全新的 `txId`。本第一阶段不建立永久尝试账本，因此不能声称服务重启后能识别一个已回滚的旧 ID；若产品化需要该语义，必须另行增加持久尝试账本并评审其事务边界。响应只包含 `txId`、脱敏后的 `xid` 标识和 `status=COMMITTED|ROLLED_BACK|UNKNOWN`，不得返回内部数据库连接信息。

调用顺序固定如下：

1. order 的独立 Orchestrator Service Bean 以 `@GlobalTransactional(name="cm18-seata-demo", rollbackFor=Exception.class)` 开启全局事务，生成并记录 Seata XID；不能在同类内部调用绕过代理。
2. Orchestrator 调用另一个独立 Bean 的 `@Transactional` 方法，仅向 `seata_cm18_order_demo` 插入 `(tx_id, tx_xid, 'CREATED', created_at)`；该本地分支方法返回后，必须确认 order 分支本地事务已提交/注册完成，再继续调用 stock。
3. order 通过专用 Feign `POST /api/internal/cm18/seata/demo/branch` 调用 stock，body 固定为 `{txId}`，Header 带 `X-Internal-Service: cloud-mall-cm18-test`、`TX_XID: <RootContext.getXID()>` 和 `X-CM18-Failure-Point: NONE|ORDER_AFTER_WRITE|STOCK_AFTER_WRITE`。stock 独立 Bean 的 `@Transactional` 分支方法校验 Header 与当前 Seata `RootContext` 相等后，仅向 `seata_cm18_stock_demo` 插入 `(tx_id, tx_xid, 'CREATED', created_at)` 并返回；该本地分支方法返回后，才允许在 stock 分支外注入 `STOCK_AFTER_WRITE` 异常，以真实验证 AT 二阶段回滚。
4. `ORDER_AFTER_WRITE` 在 order 分支 Bean 返回、调用 stock 前抛出受控业务异常；`STOCK_AFTER_WRITE` 在 stock 分支 Bean 返回、其本地事务已提交后抛出受控业务异常；`NONE` 正常返回。`X-CM18-Failure-Point` 只在演练 profile 和 `cloudmall.seata.cm18.enabled=true` 时解析，其他环境必须拒绝；三个 Bean 都不得在该事务内访问 Redis、RabbitMQ、ES 或真实业务表。
5. 正常提交时两张演练表各有一行；任一故障点回滚时两张表均无该 `tx_id`。回滚前不得启动缓存修复器或发送消息，因而不存在“Redis 先写后 AT 回滚”的版本问题。

XID 传播必须由运行时证据证明：Feign 请求日志仅记录脱敏 XID 哈希和 txId，stock 日志记录 `RootContext.getXID()`；测试必须断言两者等于同一个 TC XID。不能只断言请求头存在，也不能把用户传入 body 的 `xid` 当作可信事务上下文。

### 4.2 不推荐的直接改法

以下做法均不通过评审：

- 在 `OrderServiceImpl.create` 上增加 `@GlobalTransactional`，同时保留订单事务内的 `reservationIntentService` 独立提交。
- 在 `PayServiceImpl.create` 或支付恢复任务上增加全局事务，试图把 user 余额、订单状态和 stock confirm 变成一次 AT 回滚。
- 仅给 order/pay 加注解而不让 stock 使用 Seata 代理数据源和 `undo_log`。
- 把 Redis Lua、RabbitMQ 发布或已经提交的余额账本当作 Seata 回滚资源。
- 用全局回滚替代 `ORDER_SYNC_UNKNOWN`、`STOCK_ACTION_UNKNOWN`、`REFUND_PENDING` 等现有恢复状态。

### 4.3 第一阶段的清理与生命周期

演练使用的 `txId` 必须写入一次性测试清单；测试结束后仅按清单执行：

```sql
DELETE FROM seata_cm18_order_demo WHERE tx_id IN (...);
DELETE FROM seata_cm18_stock_demo WHERE tx_id IN (...);
```

不得执行 `TRUNCATE`、`FLUSH TABLES`、`FLUSHDB` 或按时间范围删除，避免误伤其他 Agent 的测试数据。清理前必须查询两张演练表和 `undo_log`，确认所有 XID 已进入终态；清理失败应保留 txId 清单并报告，不能强制删除未完成事务的 undo_log。故障注入开关关闭后再启动正常业务服务。

## 5. XID、配置与数据库契约

实现受控场景前必须冻结并验证：

- 所有参与服务使用同一 `tx-service-group=cloudmall_tx_group`，且 file 模式映射到实际可达的 TC 地址；IDEA 本地进程使用 `127.0.0.1:8091`，容器内进程不能错误使用该回环地址。
- order、stock（以及场景确实需要时的 pay/user）均开启 AT，并把业务 datasource 包装为 Seata proxy；不能只在 YAML 中声明 `seata.enabled=true`。
- order → stock Feign 请求必须能在 stock 侧读到同一个 `TX_XID`。需记录脱敏 XID、事务分支注册、提交/回滚结果；不得把 XID 放入前端响应或业务幂等键。
- 演练 profile 必须提供 `cloudmall.seata.cm18.pause-after-stock-write-ms`，默认 `0`，测试最大值 `30000` 毫秒；只有 `cloudmall.seata.cm18.enabled=true` 且 profile 为演练时允许读取。该暂停发生在 stock 本地分支事务已经返回/提交之后、全局事务最终决议之前，用于可重复地终止 stock 进程并验证 TC 对已注册分支的提交/回滚；生产 profile 必须拒绝非零值并不注册暂停钩子。
- 每个参与库存在正确版本的 `undo_log`，表结构和主键/索引初始化可重复执行；`undo_log` 清理策略必须可观测，不能依赖手工删除正在恢复的记录。
- `@Transactional` 与 Seata 全局事务的边界必须经过代理调用链验证，避免同类内部调用绕过 Spring/Seata 代理；全局入口应位于独立的 Service Bean。
- 超时、TC 不可达、分支注册失败、业务异常和进程崩溃分别验证。TC/file 恢复期间不得把客户端的未知状态直接映射为业务失败。

## 6. 故障注入与验收门槛

### 6.1 必须通过的数据库 AT 验收

- 正常提交：order、stock 两库均有预期测试行，XID 只对应一个全局事务，重复请求由测试幂等键返回原结果。
- order 分支本地提交后、调用 stock 前注入 `ORDER_AFTER_WRITE`：order 分支已注册但全局事务回滚后两个库均无测试行；`undo_log` 没有残留未处理回滚记录。
- stock 分支本地提交后、分支方法返回后注入 `STOCK_AFTER_WRITE`：order 与 stock 均回滚；不存在只创建订单或只占用库存的测试残留。
- stock 进程在分支写入后、全局提交前终止：重启后 RM/TC 完成回滚或提交，最终状态可由 XID 查询确定，不能靠人工删行。
- 使用 `pause-after-stock-write-ms=5000` 卡在 stock 分支已提交、order 尚未完成全局决议的窗口，终止 stock 进程后再启动并等待 TC/file 恢复；演练表最终必须与全局决议一致，不能只依赖 HTTP 响应判断。
- TC 暂时不可达：入口返回可识别的事务未知/服务不可用结果，不能把局部已提交事实当作全局失败并直接补偿；恢复 TC 后状态收敛。
- 并发两个不同 XID 使用不同 `txId` 写入演练表：验证分支注册和提交互不串线；同一 `txId` 的并发请求只能有一个结果，不能生成第二行。真实 SKU 并发、库存版本和流水唯一性不属于第一阶段，因为第一阶段明确不写 `stock_sku`。

### 6.2 与现有补偿链路的回归

完成 AT 演练后，必须重新执行现有普通下单、支付、超时关单和结果未知测试，证明：

- `INIT/RESERVED/COMMITTED` 意图不会被测试事务误回滚；
- Redis 投影仍由版本 Lua 修复，RabbitMQ outbox 仍由 publisher confirm/恢复任务负责；
- 余额 `DEBIT` 不会因为订单同步异常重复扣款或被错误退款；
- 订单与库存动作的 `UNKNOWN/CONFLICT` 状态仍按原操作键恢复。

全仓 Maven test/package、`git diff --check`、Seata 配置静态检查和一次真实 Compose Seata 端口/健康检查均为提交门槛。未完成上述门槛前，不得宣称“订单、库存、支付三方已由 Seata 原子一致”。

## 7. 分阶段实施建议

1. 先只补齐配置审计、`undo_log` 初始化检查、XID 日志与受控测试场景，不触碰普通订单和支付主入口。
2. 完成 order/stock 两库受控场景的提交、回滚、进程终止和 TC 恢复验收。
3. 由 `pm_arch` 重新评审是否将任何真实业务步骤迁入 Seata；若迁移，必须删除该步骤对应的独立提交/补偿假设，重新定义 Redis、RabbitMQ 和未知结果边界。
4. pay/user 余额扣款暂不纳入 AT；余额账本的操作键、退款和恢复状态属于资金事实，继续沿用本地事务与持久化补偿模型。

## 8. 第一阶段状态与主业务缺口

上述独立演练表场景是 CM-18 的**第一阶段闭环**：它可以证明本地环境中 Seata AT 的 TM/TC/RM、XID 传播、undo_log 和数据库回滚链路可工作，但不等价于真实订单、库存、支付三方已经接入 Seata。第一阶段完成后，Bug 台账应记录“Seata 受控演练通过；主业务仍未迁移”，不能把该测试标成 CM-18 全部完成。

CM-18 的完整闭环仍有独立缺口：

- 若把真实库存纳入 AT，必须重新评审 `stock_sku` 数量、`version`、`stock_flow`、`stock_cache_repair` 的同事务回滚和缓存投影抑制机制；不能只加一个 Seata 注解。
- 若把订单纳入 AT，必须重新定义已提交 reservation intent、订单 outbox、超时消息和结果未知恢复，不能与现有独立提交事务并存。
- pay/user 余额账本暂不迁移；余额扣款和退款继续沿用唯一操作键与持久化恢复，直到资金事实的回滚/退款边界单独评审。
- 真实主链路故障注入（业务异常、进程崩溃、TC 不可达、Feign 断链、Redis/RabbitMQ 已产生副作用）必须在上述迁移设计获批后另行验收。

## 9. 最终评审意见

当前项目可以宣称“已部署 Seata 1.5.2 file 模式并定义了不污染真实业务事实的第一阶段受控演练”，不能宣称“订单、库存和支付已使用 Seata 全局事务保证三方原子回滚”。本规格允许 backend 只实现独立演练表纵向场景；禁止以注解覆盖现有主流程来制造表面接入。
