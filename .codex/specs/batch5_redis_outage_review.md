# CM-20260924-14 Redis 故障边界评审

## 1. 范围与现状证据

本规格处理 Redis 短时不可用期间的 Gateway Bearer 校验、用户登录、购物车读写和秒杀入口，以及 Redis 恢复后的事实核对。购物车仍保持 Redis-only 的既有设计；Redis 丢失后的数据恢复能力属于 CM-20260924-15，本规格不新增购物车持久化源。

当前实现证据：

- Gateway `AuthenticationFilter` 通过 `ReactiveStringRedisTemplate` 读取 `auth:token:{token}`，Redis 访问异常没有转换为稳定的业务错误；无效令牌和 Redis 故障都可能无法区分。
- 用户登录成功需要写入 `auth:token:{token}`，登录写失败不能返回可用 Token。
- Cart 服务的 `readMap`、`write`、删除和勾选操作直接使用 Redis；部分异常当前会被包装为 500。
- Stock 秒杀入口先读取 `seckill:meta:{activityId}:{skuId}`，再执行 Lua 修改 `seckill:stock:*`、`seckill:user:*` 和 `seckill:accepted:*`，并将 MySQL 受理台账与 outbox 作为已修复后的持久事实。
- 商品公开 GET 路径由 Gateway 直接放行，不应因为登录 Token Redis 故障而被拦截。

## 2. 统一错误契约

新增并冻结错误码：

| 错误码 | HTTP | 适用场景 | 语义 |
| --- | ---: | --- | --- |
| `REDIS_SERVICE_UNAVAILABLE` | 503 | Gateway 校验 Token、user 登录写 Token、cart 读写、stock 秒杀依赖 Redis 时连接超时/断连/命令失败 | 本次请求结果不能由 Redis 可靠确定；客户端可等待后按原业务键重试 |

响应保持统一 JSON 结构：`{"code":"REDIS_SERVICE_UNAVAILABLE","message":"依赖服务暂时不可用，请稍后重试","data":null}`。不得把 Redis 异常、连接串、堆栈或账号密码返回前端。

规则：

1. 连接失败、超时、命令异常统一返回 503；Redis 中明确不存在的 Token 仍返回 401，不能把“Key 不存在”误报成 503。
2. 写操作在结果未知时不得返回成功。尤其是秒杀 Lua 执行结果未知时不得返回 `accepted=true`，也不得凭 Redis 读取失败直接回补库存；必须返回 503 并保留/查询同一幂等键对应的 MySQL 受理事实。
3. 503 不改变原有 400/401/403/404/409/429 业务映射。匿名商品读取不访问 Token Redis，商品和分类公开 GET 在 Gateway 可路由且 product 可用时仍返回原业务结果。

## 3. 分接口契约

### 3.1 Gateway Bearer 校验

- 需要登录的请求读取 `auth:token:{token}` 时，Redis 连接/命令异常直接返回 503 `REDIS_SERVICE_UNAVAILABLE`，不得继续转发到后端，也不得降级为匿名请求。
- Redis 正常且 Key 不存在、格式不合法或已过期时，返回 401 `COMMON_UNAUTHORIZED`。
- 公开接口集合维持现有规则：`POST /api/auth/login`、`POST /api/auth/register`，以及商品、分类、秒杀活动的公开 GET 不读取 Token Redis。公开 GET 的后端业务失败仍按后端自己的错误契约返回。
- Gateway 503 响应必须包含 `Cache-Control: no-store`，避免浏览器或代理缓存故障结果。

### 3.2 用户登录

- 用户名/密码校验成功但 Redis Token 写入失败时，接口返回 503；不得返回 Token，不得让前端保存一个未经 Redis 确认的登录态。
- 数据库校验失败、账号不存在或密码错误继续返回现有认证错误，不因 Redis 故障改变这些已确定结果。
- Token 写入成功后才返回登录成功；Token TTL 仍为 2 小时，Key 仍为 `auth:token:{token}`，value 仍为用户 ID 与角色的既有格式。
- 客户端在收到 503 时保留当前未登录状态，使用同一用户名密码重新发起登录即可；服务端不得为失败请求生成可用但不可验证的 Token。

### 3.3 购物车

- `GET /api/cart`、`POST /api/cart/items`、`PUT /api/cart/items/{skuId}`、删除、勾选和结算预览的任何 Redis 读/写失败均返回 503 `REDIS_SERVICE_UNAVAILABLE`。
- Redis 正常但购物车 Key 不存在时，继续返回空数组或现有业务语义；不得把空 Key 当作 Redis 故障。
- 购物车写结果未知时不得返回成功响应，也不得伪造删除、勾选或数量更新结果。客户端应使用原 SKU 和原请求重试；幂等性以当前 Redis 操作语义为准，不新增 MySQL 购物车表。
- 商品 Feign 的商品不存在、价格校验等确定性业务错误保持原错误映射；只把 Redis 依赖异常映射为 503。

### 3.4 秒杀入口

- `POST /api/stock/seckill/reservations` 和经 Gateway 暴露的秒杀下单入口必须先完成鉴权、参数/幂等键校验，再访问 Redis。
- 读取活动元数据、执行 Lua 或读取受理标记时 Redis 明确不可用，返回 503 `REDIS_SERVICE_UNAVAILABLE`，响应中不得出现 `accepted=true`。
- Lua 返回明确业务结果时维持现有 `SECKILL_NOT_STARTED`、`SECKILL_ENDED`、`SECKILL_SOLD_OUT`、限购和幂等冲突映射。
- Lua 执行结果未知时：保留同一 `Idempotency-Key`/`orderNo` 的 MySQL 受理查询路径；若 MySQL 已有 `ACCEPTED` 且 outbox 完整，返回该已持久化结果；若无法确认，返回 503 或现有恢复中语义，禁止生成新订单号、重复扣减或直接回补 Redis。
- 秒杀入口在 MySQL 幂等认领后重试时，必须以认领行保存的原始 `orderNo`、`request_payload` 和 `request_hash` 为准；不得继续使用本次请求新生成的 candidate orderNo 或新序列化载荷。Redis marker 命中后只能用该不可变原始载荷调用受理闭环，防止“新载荷配旧订单号”。
- 只有 Redis 预扣成功且 MySQL 受理台账、`seckill_order_outbox` 在本地事务中提交后，才允许返回 `accepted=true`。Redis 失败期间不得把“请求已到达”当作“秒杀已受理”。

## 4. 恢复后的核对顺序

Redis 恢复后按测试数据的同一 `userId`、`activityId`、`skuId` 和 `Idempotency-Key` 执行以下核对：

1. Gateway：用已知有效 Token 请求受保护接口应恢复为 200；过期/不存在 Token 仍为 401；匿名商品列表、详情和分类 GET 在无 Token 时仍可用。
2. User：成功登录返回的 Token 能在 Gateway 验证；Redis 中 `auth:token:{token}` 的 value 与用户 ID/角色一致，TTL 在预期范围内；登录失败请求不产生可用 Token。
3. Cart：故障前已存在的 Key 只按 Redis 当前实际保留结果返回；故障期间收到 503 的写请求不得被报告为成功。验证 GET、增量、数量更新、勾选和删除的真实结果。不得声称 Redis 丢失的购物车可从 MySQL 恢复。
4. Stock：以 MySQL `seckill_reservation`/受理台账、`seckill_order_outbox` 和 Redis 的 `seckill:stock:*`、`seckill:user:*`、`seckill:accepted:*` 对账。每个幂等键最多对应一个受理订单号；MySQL 已 `ACCEPTED` 的记录必须能恢复同一 Redis marker 或进入既有恢复任务；没有 MySQL 受理事实的 Redis marker 不得生成订单。
5. 重复发送同一秒杀请求应返回原订单号/原状态；更换幂等键才允许成为独立请求，库存数量不得因恢复重试额外减少。

## 5. 最小实现边界

- Gateway 增加 Redis 异常到 503 JSON 的映射，并为公开商品 GET 保持无 Redis 鉴权依赖。
- User、Cart、Stock 仅在依赖 Redis 的边界捕获连接/超时/命令异常并映射统一错误；不扩大到 CM15 的数据恢复设计。
- 复用已存在的秒杀 MySQL 受理台账、outbox、幂等键和恢复任务，不新增第二套受理状态机。
- 补充 Gateway filter、user login、cart read/write、stock seckill 的故障注入测试；补充至少一条恢复后的真实 MySQL/Redis 对账验收记录。

## 6. 评审结论

本规格通过，允许 backend 按上述错误码、HTTP 状态和“不误接受”语义实现，允许 qa 执行 Redis 断开/恢复故障注入。任何新增缓存持久化源、购物车数据迁移或改变购物车 Redis-only 承诺的方案必须另行评审为 CM-20260924-15。
