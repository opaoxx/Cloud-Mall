# CloudMall 云购微商城 API 契约规格

## 1. 契约范围

本文定义 React/Vite 前端经 Gateway 访问各微服务的 HTTP/JSON 契约。外部统一前缀为 `/api`；Gateway 监听 8080，user/product/cart/order/stock/pay 分别监听 8081/8082/8083/8084/8085/8086，Vite 前端监听 5173。所有接口都必须经 Gateway 暴露，服务内部地址不作为前端契约。

## 2. 通用请求与响应

### 2.1 请求头

| Header | 说明 |
| --- | --- |
| `Content-Type: application/json` | JSON 请求 |
| `Authorization: Bearer <token>` | 需要登录的接口；Token 是 Redis 不透明 Token，登录态 TTL 2 小时 |
| `X-Request-Id` | 可选请求标识；服务端生成或沿用客户端值，并与 Trace ID 关联，不承担幂等语义 |
| `Idempotency-Key` | 创建订单、支付、秒杀等需幂等保护的写操作必填 |

### 2.2 成功响应

```json
{
  "code": "0",
  "message": "OK",
  "data": {},
  "requestId": "trace-or-request-id"
}
```

`code=0` 表示成功；`data` 可为对象、数组或分页对象。字段命名统一使用 lowerCamelCase。金额在 JSON 中统一使用字符串，时间统一使用 ISO-8601 并带 `+08:00` 偏移。

### 2.3 分页响应

```json
{
  "code": "0",
  "message": "OK",
  "data": {
    "items": [],
    "page": 1,
    "pageSize": 20,
    "total": 0
  },
  "requestId": "..."
}
```

### 2.4 错误响应

```json
{
  "code": "ORDER_STOCK_NOT_ENOUGH",
  "message": "库存不足",
  "data": null,
  "requestId": "..."
}
```

HTTP 状态码表达协议层结果，业务 `code` 表达可处理原因；不得把所有业务失败都返回 200 或把堆栈返回前端。

## 3. Gateway 路由

| 路由 | 目标服务 | 鉴权 |
| --- | --- | --- |
| `/api/auth/**`、`/api/users/**` | `cloud-mall-user` | 登录/注册按接口区分 |
| `/api/products/**`、`/api/categories/**` | `cloud-mall-product` | 浏览公开；管理接口需要管理员 |
| `/api/cart/**` | `cloud-mall-cart` | 必须登录 |
| `/api/orders/**` | `cloud-mall-order` | 必须登录 |
| `/api/stock/**` | `cloud-mall-stock` | 仅内部服务调用；前端不得直接调用 |
| `/api/payments/**` | `cloud-mall-pay` | 必须登录；回调为内部受控入口 |
| `/api/seckill/activities/{id}` | `cloud-mall-product` | 公开读取活动状态；下单仍需登录 |
| `/api/seckill/orders` | `cloud-mall-order`/`cloud-mall-stock` 协作 | 必须登录，Sentinel 保护 |

Gateway 负责 Nacos 服务发现、基础 Redis 不透明 Token 鉴权、跨域、黑名单/过滤和限流预处理；跨服务使用 Spring Cloud OpenFeign，连接超时 3 秒、读取超时 5 秒，仅对幂等 GET 允许有限重试。服务仍需做资源归属校验。

## 4. 用户服务接口

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | `{username,password}` | `data={userId,username}` |
| POST | `/api/auth/login` | `{username,password}` | `data={token,user}`；Token 写入 Redis，TTL 2 小时 |
| POST | `/api/auth/logout` | 无/当前令牌 | `data=null`，令牌失效 |
| GET | `/api/users/me` | 无 | 当前用户资料 |
| PUT | `/api/users/me` | `{nickname,phone,avatarUrl}` | 更新后的资料 |
| GET | `/api/users/me/addresses` | 无 | 地址数组 |
| POST | `/api/users/me/addresses` | 收货人、电话、详细地址、默认标志 | 地址对象 |
| PUT | `/api/users/me/addresses/{id}` | 可修改地址字段 | 地址对象 |
| DELETE | `/api/users/me/addresses/{id}` | 无 | `data=null` |

本期不提供管理员用户管理和密码找回接口；本地练习允许预置 `admin/admin123`，普通注册用户默认 `USER`，管理接口要求 `ADMIN`。

## 5. 商品服务接口

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| GET | `/api/categories` | `parentId/status` 可选 | 分类树/列表 |
| POST | `/api/categories` | `{parentId,name,sortNo}` | 管理员创建分类 |
| PUT | `/api/categories/{id}` | 分类可变字段 | 分类对象 |
| DELETE | `/api/categories/{id}` | 无 | 停用/删除结果 |
| GET | `/api/products` | `keyword,categoryId,status,page,pageSize,sort` | 分页商品摘要 |
| GET | `/api/products/{id}` | 无 | 商品详情、SKU、参数 |
| GET | `/api/products/skus/{skuId}` | 无 | SKU 商品摘要、价格和规格快照 |
| POST | `/api/products` | 商品及 SKU/参数 | 管理员创建 |
| PUT | `/api/products/{id}` | 商品可变字段 | 管理员更新 |
| POST | `/api/products/{id}/publish` | 无 | 上架 |
| POST | `/api/products/{id}/unpublish` | 无 | 下架 |
| GET | `/api/products/{id}/hot-stat` | 无 | 热度统计/排序信息 |

商品写接口中的商品参数 `ProductParameter` 统一为 `{name: string, value: string}`；SKU 的 `specJson` 统一为 `Record<string, string>` 对应的 JSON 对象，不使用字符串化 JSON。数据库内部列名仍为 `param_name`/`param_value`，仅由后端负责 API 字段映射，无需修改 `db_schema.md`。商品列表 `sort` 支持 `hot`、`sales`、`latest`、`price_asc`、`price_desc`，其中 `sales` 使用商品热度统计中的源销量计数排序，未知值按默认热门顺序处理。前端展示库存是参考值，结算必须重新校验。

## 6. 购物车服务接口

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| GET | `/api/cart` | 无 | 当前用户购物车项数组 |
| POST | `/api/cart/items` | `{skuId,quantity}` | 新增/合并后的购物车项 |
| PUT | `/api/cart/items/{skuId}` | `{quantity}` | 更新数量 |
| DELETE | `/api/cart/items/{skuId}` | 无 | 删除 |
| PUT | `/api/cart/items/{skuId}/checked` | `{checked}` | 勾选状态 |
| DELETE | `/api/cart/checked-items` | 无 | 清理已勾选项，是否需要保留待确认 |
| POST | `/api/cart/settlement/preview` | `{skuIds}` | 价格、库存、失效项和应付金额预览 |

购物车只操作 Redis；每个用户购物车 Key TTL 为 30 天，value 使用 JSON，不锁定最终库存；结算预览结果不能替代订单创建时的再次校验。

## 7. 订单服务接口

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| POST | `/api/orders` | `{items:[{skuId,quantity}],addressId}`；Header 必须有 `Idempotency-Key` | 创建待支付订单，返回订单摘要 |
| GET | `/api/orders` | `status,page,pageSize,startTime,endTime` | 当前用户订单分页；时间条件支持分片路由 |
| GET | `/api/orders/{orderNo}` | 无 | 订单详情和明细快照 |
| POST | `/api/orders/{orderNo}/cancel` | `{reason}` 可选 | 仅待支付订单可取消 |
| POST | `/api/orders/{orderNo}/confirm` | 无 | 收货确认/完成；具体业务范围待确认 |
| POST | `/api/seckill/orders` | `{activityId,skuId,addressId}`；Header 必须有 `Idempotency-Key` | 秒杀异步下单结果/订单状态；地址快照随消息写入订单 |

订单创建时服务端重新读取商品价格、上下架、库存和地址；禁止信任前端金额。顺序固定为：鉴权与 `Idempotency-Key` 绑定 → 校验地址归属及商品快照 → 在独立已提交本地事务中创建 `INIT` 预扣意图 → 调用库存预扣 → 在独立本地事务中标记 `RESERVED` → 在本地事务中保存订单、超时 outbox 并标记 `COMMITTED`。地址校验失败不得调用库存。若库存调用成功但进程在写入 `RESERVED` 前崩溃，恢复任务使用原 `orderNo` 重试同一预扣请求取得幂等结果，再回滚；若订单已提交则只推进/确认 `COMMITTED`。库存已预扣但订单保存失败时，接口必须按原 `orderNo` 发起回滚，并由预扣意图恢复任务兜底。订单状态：`PENDING_PAYMENT`、`PAID`、`CANCELLED`、`COMPLETED`。

订单状态写操作必须返回并检查条件更新影响行数。支付成功、手工取消和超时关单只有在自身 CAS 成功时才能调用库存确认/回滚；CAS 失败必须读取当前状态，已达到目标状态时作为幂等成功返回，处于其他终态时返回 `ORDER_STATUS_INVALID`，不得再次操作库存。

## 8. 库存服务接口

库存写接口原则上仅供内部服务调用，不作为普通前端公开 API。

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| GET | `/api/stock/skus/{skuId}` | 内部请求 | 可售库存摘要；不经 Gateway 向前端公开 |
| POST | `/api/stock/reservations` | `{orderNo,items,scene}` | 预扣结果，失败返回库存不足；同一 `orderNo` 返回同一结果 |
| GET | `/api/stock/reservations/{orderNo}` | 无 | 查询预扣最终状态和明细，不改变库存 |
| POST | `/api/stock/reservations/{orderNo}/confirm` | 幂等请求 | 确认扣减 |
| POST | `/api/stock/reservations/{orderNo}/rollback` | 幂等请求 | 回滚预扣 |
| POST | `/api/stock/seckill/reservations` | `{activityId,skuId,userId}` | Redis 预扣/异步入队结果 |

接口必须带订单号或幂等键，所有数量为正整数；库存服务不得返回负库存。

普通预扣接口对同一 `orderNo` 必须返回同一业务结果。库存服务必须记录 `RESERVE`、`CONFIRM`、`ROLLBACK` 的唯一幂等键；回滚/确认请求在找不到未完成预扣时返回幂等已处理结果或明确的状态冲突，不能按新请求再次改变数量。`POST` 的 Feign 超时不得自动换订单号重试；调用方将结果标记为未知，使用原订单号调用本查询接口，再决定回滚/确认。Redis 记录丢失不等于未执行，查询必须以 `stock_flow` 和库存状态为准。正常库存以 MySQL 提交结果为事实，Redis 只是带版本的可售投影；提交后缓存更新失败由持久化修复任务重试。

## 9. 支付服务接口

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| POST | `/api/payments` | `{orderNo,payAmount}`；Header 必须有 `Idempotency-Key` | 创建模拟支付记录 |
| GET | `/api/payments/orders/{orderNo}` | 无 | 支付状态 |
| POST | `/api/payments/{payNo}/mock-success` | 无 | 模拟成功并触发订单同步 |
| POST | `/api/payments/{payNo}/mock-fail` | `{reason}` 可选 | 模拟失败 |
| POST | `/api/payments/callback` | 模拟回调载荷 | 内部回调，幂等处理 |

支付金额必须与订单应付金额一致。支付成功流程必须先以 `payNo` 作为唯一操作键完成用户余额扣款，再同步订单；扣款已完成而订单同步结果未知时，支付记录进入 `ORDER_SYNC_UNKNOWN`，由查询/重试/对账任务继续处理。只有确认订单处于 `CANCELLED` 且没有 `PAID` 成功事实时，才允许发起退款。不得因为一次 Feign 超时直接退款；若订单状态仍未知，必须保持扣款并继续对账。真实第三方支付和售后不在本期范围，但模拟余额退款属于本批一致性闭环。

支付服务与用户服务之间的内部接口契约：

| 方法 | 路径 | 请求/响应 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/internal/users/{userId}/balance/debits` | `{operationKey,payNo,orderNo,amount}` → `{operationKey,state,balance}` | `operationKey=payNo`；同键只允许一次扣款，重复请求返回同一结果 |
| GET | `/api/internal/users/{userId}/balance/operations/{operationKey}` | → `{operationKey,type,state,amount,refundOperationKey}` | 查询扣款/退款持久化结果，供支付恢复任务判断最终结果 |
| POST | `/api/internal/users/{userId}/balance/refunds` | `{refundOperationKey,originalOperationKey,payNo,orderNo,amount}` → `{refundOperationKey,state,balance}` | 仅对已应用的原扣款退款；退款键幂等且不得超过原扣款金额 |

订单服务提供支付恢复查询接口：

| 方法 | 路径 | 响应 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/internal/orders/{orderNo}/payment-state` | `{orderNo,status,payAmount,userId}` | 只读查询订单最终支付状态；订单服务不持有 `payNo`，支付服务使用自身 `pay_record` 将订单号映射到支付操作键；对超时/断连只能查询或重试，不得换订单号 |

支付状态新增 `ORDER_SYNC_UNKNOWN`、`REFUND_PENDING`、`REFUNDED`。`SUCCESS` 只表示扣款已持久化且订单已确认 `PAID`；`ORDER_SYNC_UNKNOWN` 表示扣款事实存在但订单结果未确认。支付恢复任务按 `orderNo` 查询订单：确认 `PAID` 后推进支付成功并确认库存；确认 `CANCELLED`/未支付终态后创建唯一退款操作；仍未知则保留扣款并重试，进入告警而不退款。所有状态迁移和余额操作都必须带当前状态/操作键条件。

## 10. 管理与秒杀接口

秒杀活动基础信息由 `product` 管理；`order/stock` 负责 Redis 预扣、异步下单和库存一致性交易路径。管理员接口要求 `ADMIN`。

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| GET | `/api/seckill/activities/{activityId}` | 无 | 活动状态、SKU 标识和剩余展示库存；商品摘要通过 `/api/products/skus/{skuId}` 读取 |
| POST | `/api/seckill/activities` | `{skuId,startAt,endAt,stockLimit,perUserLimit}` | 管理员创建；时间为 ISO-8601 `+08:00` |
| PUT | `/api/seckill/activities/{activityId}` | 可变活动字段 | 管理员修改 |
| POST | `/api/seckill/activities/{activityId}/publish` | 无 | 预热 Redis 并发布 |
| POST | `/api/seckill/activities/{activityId}/start` | 无 | 管理员手动开始；活动时间窗口同时决定有效状态 |
| POST | `/api/seckill/orders` | `{activityId,skuId}`；Header 必须有 `Idempotency-Key` | 受 Sentinel 保护的秒杀入口 |

## 11. 错误码

| 错误码 | 含义 | HTTP |
| --- | --- | --- |
| `0` | 成功 | 200 |
| `COMMON_INVALID_ARGUMENT` | 参数格式/范围错误 | 400 |
| `COMMON_UNAUTHORIZED` | 未登录或令牌无效 | 401 |
| `COMMON_FORBIDDEN` | 无权限或资源不属于当前用户 | 403 |
| `COMMON_NOT_FOUND` | 资源不存在 | 404 |
| `COMMON_CONFLICT` | 状态或版本冲突 | 409 |
| `USER_DUPLICATE_USERNAME` | 用户名已存在 | 409 |
| `USER_LOGIN_FAILED` | 登录凭据错误 | 401 |
| `PRODUCT_OFF_SHELF` | 商品已下架 | 409 |
| `PRODUCT_SKU_NOT_FOUND` | SKU 不存在 | 404 |
| `CART_ITEM_INVALID` | 购物车项失效 | 409 |
| `ORDER_STOCK_NOT_ENOUGH` | 库存不足 | 409 |
| `ORDER_STATUS_INVALID` | 订单状态不允许当前操作 | 409 |
| `ORDER_DUPLICATE_REQUEST` | 创建/操作请求幂等冲突 | 409 |
| `ORDER_EXPIRED` | 订单已超时 | 409 |
| `PAY_AMOUNT_MISMATCH` | 支付金额不一致 | 409 |
| `PAY_ALREADY_PROCESSED` | 支付已处理 | 409 |
| `PAYMENT_FAILED` | 模拟支付失败 | 409 |
| `PAY_ORDER_SYNC_UNKNOWN` | 扣款已持久化，订单支付结果未知 | 202 |
| `PAY_REFUND_PENDING` | 已确认需要退款，退款处理中 | 202 |
| `ORDER_RESERVATION_UNKNOWN` | 库存预扣结果未知，等待按原订单号查询 | 202 |
| `STOCK_CACHE_REPAIRING` | 库存事实已提交，缓存修复处理中 | 202 |
| `SECKILL_NOT_STARTED` | 秒杀未开始 | 409 |
| `SECKILL_ENDED` | 秒杀已结束 | 409 |
| `SECKILL_LIMIT_REACHED` | 超过用户限购 | 429 |
| `SECKILL_SOLD_OUT` | 秒杀库存耗尽 | 409 |
| `GATEWAY_RATE_LIMITED` | 网关/Sentinel 限流 | 429 |
| `GATEWAY_SERVICE_UNAVAILABLE` | 服务熔断/降级 | 500 |
| `COMMON_INTERNAL_ERROR` | 未预期服务错误 | 500 |

错误码命名、HTTP 映射和是否返回可展示 message 需在 common 实现前冻结；不得把内部异常、账号密码或堆栈返回前端。

## 12. 跨服务事件契约（逻辑）

RabbitMQ 事件至少包括：订单创建/超时、库存预扣/回滚/确认、支付成功/失败、商品浏览统计和商品索引同步。新超时消息使用 JSON envelope，至少包含 `eventId`、`eventType=ORDER_TIMEOUT`、`occurredAt`、`orderNo`、`expireAt`、`traceId` 和 `payload`；超时关单使用 TTL + DLX，消费失败按有限重试后进入死信队列。消费者兼容存量 String `orderNo` 消息，并将其规范化为 `legacy-timeout:{orderNo}`，按订单读取的 `expireAt` 走同一 CAS 关单路径。订单超时消息必须先落 order outbox，再由提交后 dispatcher 使用 publisher confirm 投递；confirm 成功但 outbox 状态更新失败时，必须重试同一 `eventId`，重复投递由消费者 CAS 幂等。必须支持未确认记录重试、消费者按事件号/订单状态幂等和死信告警。Trace ID 由 Micrometer Tracing 透传。

## 13. 契约冻结说明

本轮已冻结 `/api` 前缀及本地端口、Redis 不透明 Token 与 2 小时 TTL、Bearer 认证、金额/时间格式、`Idempotency-Key`、库存内部接口边界、管理员角色、秒杀活动归属、RabbitMQ TTL + DLX、OpenFeign 超时/重试和错误 HTTP 映射。所有错误统一使用 200/400/401/403/404/409/429/500；回调为内部受控入口，服务必须执行幂等和归属校验。
