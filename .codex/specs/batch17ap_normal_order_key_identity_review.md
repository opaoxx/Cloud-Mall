# CM-20260924-20：普通订单幂等键精确身份校验评审

## 1. 评审结论与范围

结论：**GO，限定修复普通订单幂等查询受 MySQL `CI/PAD SPACE` 比较影响而误重放的问题。**

`order_idempotency` 的现有查询只返回 `order_no`。当数据库按大小写不敏感或忽略尾随空格比较时，大小写、重音或尾随空格不同的请求可能被当作同一幂等请求，直接返回旧订单。普通订单预扣意图的重复回退也存在相同缺口：`createIntent` 返回重复后，查询到 `COMMITTED` 意图时没有再次核验持久化的 `idempotency_key`。

本轮只补持久化行与入参的精确字符串身份核验，不迁移索引或 collation，不修改 schema、API、状态机、金额/时间、Seata、库存恢复和其他服务。

## 2. Mapper 契约

### 2.1 订单幂等记录查询

在 `OrderSqlMapper` 增加语义化方法：

```java
List<OrderIdempotencyPO> findIdempotentOrders(Long userId, String idempotencyKey)
```

SQL 仍使用既有 `user_id=? AND idempotency_key=?` 查询，以便捕获数据库 collation 命中的候选行；不得改为 `BINARY` 过滤后让别名请求伪装成“没有历史”。投影至少包含 `id、user_id、idempotency_key、order_no、created_at`，按现有唯一约束最多返回一行。

保留既有 `findIdempotentOrderNos(Long,String)` 公共签名供旧测试/调用方兼容；它只能由新查询结果映射 `orderNo`，不能成为普通创建流程的身份判断入口。普通创建流程必须使用完整 PO 查询并在 Service 中执行精确校验。

### 2.2 预扣意图查询

`OrderReservationIntentMapper.findByUserAndKey` 保持既有查询和返回类型，继续返回数据库 collation 命中的候选意图；不新增预查询、不改变事务传播或锁语义。调用方负责比较 `OrderReservationIntentPO.idempotencyKey` 与当前入参。

## 3. Service 身份与响应契约

### 3.1 已有订单候选

普通创建入口完成 key 的既有 null、blank、128 code point 校验后，读取完整幂等 PO。对每个候选执行 `Objects.equals(incomingKey, storedKey)` 的 Java 精确比较：区分大小写，保留重音、尾随空格及其他字符差异，不 trim、不大小写折叠、不 Unicode 归一化。

- 精确相等：沿现有流程返回原订单，保持原响应、原订单号、原创建时间，不发 Product、User、Cart、Stock 或消息调用。
- 数据库比较命中但精确不等：立即抛出既有 `ErrorCodes.DUPLICATE`、HTTP 409；不得读取旧订单作为成功结果，不得创建新订单、写 intent、调用任何外部服务。
- 缺失或异常的持久化 key：按不精确匹配处理并返回同一 409，不能猜测、修正或覆盖历史行。

精确身份判断必须发生在读取旧订单及所有外部 RPC 之前。

### 3.2 预扣意图重复回退

当 `reservationIntentService.createIntent` 返回 `false` 时，沿现有 `findByUserAndKey` 查询候选意图，并先精确比较当前 key 与持久化 `idempotencyKey`：

- 精确相等且状态为 `COMMITTED`：沿现有流程返回原订单；不再次调用 Stock reserve，不写第二条订单/intent，不发送重复消息。允许保留现有地址和 Product 只读查询时序，但不得新增外部调用。
- collation 命中但 key 不精确相等，或持久化 key 缺失/异常：直接抛 `ErrorCodes.DUPLICATE`、HTTP 409；不得把该意图当作已完成订单，不得再次 reserve、写新 intent 或修改旧事实。
- 精确相等但状态不是 `COMMITTED`：保持原有 409/处理中语义，不扩展状态机。

两条路径均使用区分大小写的 `String.equals`/`Objects.equals`，不改数据库比较规则。

## 4. 事务与兼容边界

- 保留现有 `@Transactional`、RR/Seata 编排、intent 独立事务、预扣恢复和异常映射。
- 不新增 intent 预查，不改变 `insertIfAbsent` 的严格插入契约和唯一键处理。
- 不迁移 `order_idempotency`、`order_reservation_intent` 的索引、字符集或 collation；不自动修复历史别名/截断行。
- 不改变相同精确 key 的不同 payload、金额、商品或地址规则；这些仍沿现有业务流程处理。
- 不扩展到秒杀 dedup、Stock、Pay、Cart 或其他服务的幂等键。

## 5. 验收矩阵

1. 真实 MySQL 中已有 `order_idempotency` 记录时，大小写、重音、尾随空格三类 alias 均返回 409；原行、订单、创建时间和 checksum 不变。
2. 删除隔离库中的 `order_idempotency` 记录、仅保留 `COMMITTED` intent 时，三类 alias 仍返回 409；精确 key 返回原订单，且不发第二次 Stock reserve、不新增订单或消息。
3. 精确重放返回原订单；不同用户使用相同 key 仍可独立创建；128 ASCII 和 128 code point 非 BMP key 保持原值和既有结果。
4. alias 首路径在任何旧订单读取、Product/User/Cart/Stock/RPC 前失败；intent fallback 允许既有地址/Product 只读调用，但必须在 COMMITTED 返回前完成精确校验且不进行第二次预扣。
5. 回归普通订单创建时间精度、intent strict insert、Stock UNKNOWN/恢复和原库 checksum；数据库查询错误继续原异常路径，不伪装成幂等成功。

## 6. 明确不承诺

本轮只阻止新的 alias 请求误重放，不保证历史数据清洗、索引 collation 迁移、分布式 exactly-once 或其他服务的幂等身份修复。
