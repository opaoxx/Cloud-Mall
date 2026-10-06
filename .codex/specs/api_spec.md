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

热度统计响应保留 `productId/viewCount/searchCount/hotScore`，新增 `sourceType/sourceRef` 来源标记；缺失统计行时来源为 `UNKNOWN/null`。当前 `LEGACY_IMPORT/hm-item.item` 表示历史目录快照：`viewCount` 保存源目录 `sold`、`searchCount` 保存源目录 `comment_count`，不能解释成本地实时浏览或搜索。`sales` 和 `hot` 排序当前依据历史快照，前端应明确显示“历史销量排序”“历史热度排序”；实时指标及独立版本协议待 CM-21 后续阶段实施。

商品写接口中的商品参数 `ProductParameter` 统一为 `{name: string, value: string}`；SKU 的 `specJson` 统一为 `Record<string, string>` 对应的 JSON 对象，不使用字符串化 JSON。数据库内部列名仍为 `param_name`/`param_value`，仅由后端负责 API 字段映射，无需修改 `db_schema.md`。商品列表 `sort` 支持 `hot`、`sales`、`latest`、`price_asc`、`price_desc`，其中 `sales` 使用商品热度统计中的源销量计数排序；缺省或未知 `sort` 按当前运行契约使用 `latest`，不得由性能修复隐式切换为热门排序。前端展示库存是参考值，结算必须重新校验。`page <= 0` 归一为 1，`pageSize <= 0` 归一为 1，`pageSize > 100` 归一为 100；归一化后的 `(page-1)*pageSize` 溢出或 `offset > 100000` 返回 `COMMON_INVALID_ARGUMENT`（400）。

商品写接口成功只表示 MySQL 商品事实本地事务提交；索引同步是异步最终一致。所有影响公开索引的商品、SKU、参数和上下架写操作必须递增 `product.version` 并同时写入 `product_index_outbox`，由提交后的 dispatcher 使用 RabbitMQ publisher confirm/returned 可靠投递。索引消费者按 `productId` 重新读取当前 MySQL 事实并写入 ES，不能使用消息快照直接覆盖；`productVersion` 小于已处理版本的事件只能记录 `STALE`。上架/修改生成 `PRODUCT_INDEX_UPSERT`；下架、停用和删除生成 `PRODUCT_INDEX_HIDE`，查询不可见，保留版本 tombstone 防止旧消息复活。消息投递或 ES 暂时失败不回滚商品写入，后台持续重试并告警。分类接口仍按原契约执行，但分类变更不生成商品索引事件；商品索引 outbox 不接收 `CATEGORY_CHANGED`，避免分类事件被消费者静默忽略。

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

订单列表的 `page`、`pageSize`、`startTime`、`endTime` 和可选 `status` 共同确定查询窗口；服务端先把带任意合法 ISO-8601 offset 的 `startTime/endTime` 按 same instant 规范到 `Asia/Shanghai`（`+08:00`），再计算月份范围，并使用左闭右开 `[start,end)` 时间条件，避免非 `+08:00` 请求在月初漏查前一月。`page` 与 `pageSize` 使用正数，服务端以 `long` 计算 `(page-1)*pageSize` 并在溢出或超出可接受深度时返回 `COMMON_INVALID_ARGUMENT`。响应必须保持 `items/page/pageSize/total` 结构，`total` 是所有命中月份的精确计数。由于不同物理月份的 `created_at` 区间按分片不变量严格不重叠，跨月查询按月份倒序遍历：先逐月 `COUNT`，根据计数跳过完整月份，仅对承载目标页的月份执行有界 `LIMIT/OFFSET` 批次，并在批次内使用 `created_at DESC, order_no DESC` 稳定排序；内存只保留目标页和当前批次，不得先把所有月份订单读入 JVM 后排序或 `subList`。若检测到订单落在错误月份，作为数据异常告警并使查询失败，不通过全量扫描默默兜底。任一命中月份不存在、不可访问或查询失败时，整个请求返回 `ORDER_QUERY_SHARD_UNAVAILABLE`（HTTP 503），不得返回部分 `items` 或部分 `total`。读路径不得为缺失历史月份偷偷建表；未来月份由定时任务预建，具体规则见数据库和架构规格。

订单状态写操作必须返回并检查条件更新影响行数。支付成功、手工取消和超时关单先在本地事务中完成订单 CAS 与 `order_stock_action` 意图落库，提交后才调用库存确认/回滚；远程响应未知时动作进入 `UNKNOWN`，恢复任务查询库存最终状态并用同一 `operationKey` 重试。CAS 失败必须读取当前状态，已达到目标状态时作为幂等成功返回，处于其他终态时返回 `ORDER_STATUS_INVALID`，不得创建相反库存动作。支付成功的 `PAID` 状态一旦本地提交，不得因为后续 stock.confirm 超时回滚为 `PENDING_PAYMENT`；取消/超时只有在订单仍为 `PENDING_PAYMENT` 时才能提交 `CANCELLED` 与回滚意图。

## 8. 库存服务接口

库存写接口原则上仅供内部服务调用，不作为普通前端公开 API。

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| GET | `/api/stock/skus/{skuId}` | 内部请求 | 可售库存摘要；不经 Gateway 向前端公开 |
| POST | `/api/stock/reservations` | `{orderNo,items,scene}` | 预扣结果，失败返回库存不足；同一 `orderNo` 返回同一结果 |
| GET | `/api/stock/reservations/{orderNo}` | 无 | 查询普通或秒杀预扣的当前/最终状态和明细，不改变库存；秒杀 `RELEASE_PENDING`/`PROJECTION_BLOCKED` 对兼容调用方映射为 `UNKNOWN`；`NOT_FOUND` 仅表示尚无持久化请求认领 |
| POST | `/api/stock/reservations/{orderNo}/confirm` | `{operationKey}` | 按稳定操作键幂等确认；秒杀只确认持久配额事实，不写普通 `stock_flow`；响应丢失可通过 GET 查询最终状态 |
| POST | `/api/stock/reservations/{orderNo}/rollback` | `{operationKey}` | 按稳定操作键幂等回滚；秒杀只在受理 op marker、活动 revision 和 quota key 完整时恢复活动 quota，不减少用户限购计数；缺失投影进入 `PROJECTION_BLOCKED`，不得直接回补 |
| POST | `/api/stock/seckill/reservations` | `{activityId,skuId,userId}` | Redis 预扣/异步入队结果 |

接口必须带订单号或幂等键，所有数量为正整数；库存服务不得返回负库存。

库存依赖不可用时保持结果未知语义，不改变上述请求结构、订单号、`operationKey` 或
`Idempotency-Key`：秒杀 Redis 连接、超时、命令/协议错误或熔断返回
`REDIS_SERVICE_UNAVAILABLE`（HTTP 503）；普通预扣、确认、回滚及库存事实读取的
MySQL 连接、事务、查询/提交超时或熔断返回 `STOCK_SERVICE_UNAVAILABLE`（HTTP 503）。
该响应不表示库存不足、已回滚或已确认，调用方必须使用原订单号/操作键查询或由恢复任务
继续处理。普通 `GET /api/stock/skus/{skuId}` 在 Redis 投影不可用时允许回退读取
MySQL 事实；只有 MySQL 事实也不可用时返回 `STOCK_SERVICE_UNAVAILABLE`，不得返回
`0`、过期 Redis 值或隐式写入缓存修复记录。Redis Lua 明确返回售罄、限购、未开始或
已结束时仍返回既有业务错误码，不映射为 `REDIS_SERVICE_UNAVAILABLE`。

普通预扣接口对同一 `orderNo` 必须返回同一业务结果，并以规范化 `items_hash` 绑定请求内容。库存服务先在独立事务中提交不可变的 `stock_reservation_request=INIT`，再在第二个事务中锁定该请求行并处理整笔 SKU 集合；不能只按 SKU 查询 `stock_flow` 判断订单是否已经预扣。库存不足时第二个事务整体回滚，随后独立持锁事务将原请求标记为持久化 `REJECTED`。库存服务必须记录 `RESERVE`、`CONFIRM`、`ROLLBACK` 的唯一幂等键；回滚/确认请求在找不到未完成预扣时返回幂等已处理结果或明确的状态冲突，不能按新请求再次改变数量。`POST` 的 Feign 超时不得自动换订单号重试；调用方将结果标记为未知，使用原订单号调用本查询接口，再决定回滚/确认。`GET` 返回 `NOT_FOUND` 时只表示没有观察到持久化请求认领，不能据此直接回滚或生成新订单；恢复任务可在保护等待后用相同订单号和相同快照重试原 POST。`REJECTED` 必须持久化，不能用 `NOT_FOUND` 代替库存不足或参数冲突。Redis 记录丢失不等于未执行，查询必须以请求台账、`stock_flow` 和库存状态为准。正常库存以 MySQL 提交结果为事实，Redis 只是带版本的可售投影；提交后缓存更新失败由持久化修复任务重试。

## 9. 支付服务接口

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| POST | `/api/payments` | `{orderNo,payAmount}`；Header 必须有 `Idempotency-Key` | 创建模拟支付记录 |
| GET | `/api/payments/orders/{orderNo}` | 无 | 支付状态 |
| POST | `/api/payments/{payNo}/mock-success` | 无 | 模拟成功并触发订单同步 |
| POST | `/api/payments/{payNo}/mock-fail` | `{reason}` 可选 | 模拟失败 |
| POST | `/api/payments/callback` | 模拟回调载荷 | 内部回调，幂等处理 |

支付金额必须与订单应付金额一致。支付成功流程必须先以 `payNo` 作为唯一操作键完成用户余额扣款，再同步订单；扣款已完成而订单同步结果未知时，支付记录进入 `ORDER_SYNC_UNKNOWN`，由查询/重试/对账任务继续处理。只有确认订单处于 `CANCELLED` 且没有 `PAID` 成功事实时，才允许发起退款。不得因为一次 Feign 超时直接退款；若订单状态仍未知，必须保持扣款并继续对账。真实第三方支付和售后不在本期范围，但模拟余额退款属于本批一致性闭环。

支付记录和扣款意图都持久化 `debit_mode`，取值为 `LEGACY` 或未来实现的 `TCC`；数据库迁移把历史行写为 `LEGACY`，由创建支付记录时的配置冻结，后续配置变化不得改写既有意图。Java 旧对象的 null 只作 LEGACY 兼容；持久化列为 `NOT NULL`，空串、小写值、尾随空格和未知模式均不支持。当前阶段运行配置只允许 `LEGACY`；`TCC` 或未知模式必须启动失败。支付恢复器在 claim 前以及调用 User/Order 前都要校验支付记录与意图的模式一致性。模式为 `TCC`、未知或不一致时返回 `PAY_DEBIT_MODE_UNAVAILABLE`（HTTP 503），不调用 LEGACY 扣款/退款，也不更新租约、尝试次数或状态；调用方必须继续使用原 `payNo` 等待后续恢复，不能换新的 `Idempotency-Key`。恢复扫描在 SQL 层以两表 `BINARY debit_mode='LEGACY'` 精确筛选，不能先取固定窗口再在 Java 过滤，否则隔离记录会造成合法支付饥饿。

CM-18 第 14 轮只批准 User 侧本地 TCC 就绪状态机：新增冻结余额和不可变 `payNo` 尝试归属，Legacy 归属最终只能为 `APPLIED/REJECTED`，TCC 归属只能为 `TRYING/CONFIRMED/CANCELLED`。归属同时冻结最终 `balance_ledger.operation_key`：TCC 使用 `ledgerKey=payNo`，Legacy 使用请求 operationKey；Confirm 只能使用归属行的不可变 ledgerKey，防止不同 payNo 抢占同一账本键。同一 User 资金资源固定为一个 XID、一个 branchId 和一个 payNo；余额不足的 TCC Try 整笔回滚且不写失败归属。Confirm/Cancel 是 User 内部事务方法，当前没有 HTTP/Feign 入口；Pay 仍只使用 LEGACY，Seata Fence 和真实 TCC 编排待后续评审。

支付服务与用户服务之间的内部接口契约：

| 方法 | 路径 | 请求/响应 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/internal/users/{userId}/balance/debits` | `{operationKey,payNo,orderNo,amount}` → `{operationKey,state,balance}` | `operationKey=payNo`；同键只允许一次扣款，重复请求返回同一结果 |
| GET | `/api/internal/users/{userId}/balance/operations/{operationKey}` | → `{operationKey,type,state,amount,refundOperationKey}` | 查询扣款/退款持久化结果，供支付恢复任务判断最终结果 |
| POST | `/api/internal/users/{userId}/balance/refunds` | `{refundOperationKey,originalOperationKey,payNo,orderNo,amount}` → `{refundOperationKey,state,balance}` | 仅对已应用的原扣款退款；退款键幂等且不得超过原扣款金额 |

订单服务提供支付恢复查询接口：

| 方法 | 路径 | 响应 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/internal/orders/{orderNo}/payment-state` | `{orderNo,status,payAmount,userId,stockActionStatus}` | `stockActionStatus` 为 `NONE`/`PENDING`/`UNKNOWN`/`SUCCEEDED`/`CONFLICT`；只读查询订单与库存动作最终状态。订单服务不持有 `payNo`，支付服务使用自身 `pay_record` 将订单号映射到支付操作键；对超时/断连只能查询或重试，不得换订单号 |

支付状态新增 `ORDER_SYNC_UNKNOWN`、`REFUND_PENDING`、`REFUNDED`。`SUCCESS` 只表示扣款已持久化、订单为 `PAID` 且 `stockActionStatus=SUCCEEDED`；`ORDER_SYNC_UNKNOWN` 表示扣款事实存在但订单或库存动作结果未确认。支付恢复任务按 `orderNo` 查询订单：只有 `PAID + SUCCEEDED` 才推进支付成功；只有 `CANCELLED + SUCCEEDED` 才创建唯一退款操作。历史存量终态若没有 `order_stock_action` 行，订单服务必须先查询 stock reservation 的持久状态：`PAID` 仅在库存为 `CONFIRMED` 时兼容返回 `SUCCEEDED`，`CANCELLED` 仅在库存为 `ROLLED_BACK` 时兼容返回 `SUCCEEDED`；查询失败/无记录返回 `UNKNOWN`，订单与库存状态不匹配（如 `CANCELLED + CONFIRMED` 或 `PAID + ROLLED_BACK`）返回 `CONFLICT`。不得盲信终态或直接退款。非终态或动作 `PENDING`/`UNKNOWN`/`CONFLICT` 不得结清或退款。所有状态迁移和余额操作都必须带当前状态/操作键条件。

## 10. 管理与秒杀接口

秒杀活动基础信息由 `product` 管理；`order/stock` 负责 Redis 预扣、异步下单和库存一致性交易路径。管理员接口要求 `ADMIN`。

| 方法 | 路径 | 请求 | 响应/说明 |
| --- | --- | --- | --- |
| GET | `/api/seckill/activities/{activityId}` | 无 | 活动状态、SKU 标识和剩余展示库存；商品摘要通过 `/api/products/skus/{skuId}` 读取 |
| POST | `/api/seckill/activities` | `{skuId,startAt,endAt,stockLimit,perUserLimit}` | 管理员创建；时间为 ISO-8601 `+08:00` |
| PUT | `/api/seckill/activities/{activityId}` | 可变活动字段 | 管理员修改 |
| POST | `/api/seckill/activities/{activityId}/publish` | 无 | 预热 Redis 并发布 |
| POST | `/api/seckill/activities/{activityId}/start` | 无 | 管理员手动开始；活动时间窗口同时决定有效状态 |
| POST | `/api/seckill/orders` | `{activityId,skuId,addressId}`；Header 必须有 `Idempotency-Key` | 受 Sentinel 保护的秒杀入口；受理 Lua 必须同时写 quota、用户累计计数、accepted marker、`seckill:quota:op:{orderNo}=RESERVED` 和活动全局 revision；返回 `{accepted,reservationStatus,orderNo,eventId}`，仅在 Redis 预扣和秒杀 outbox 已持久化后返回 `accepted=true`，不代表订单已落库 |
| GET | `/api/internal/stock/seckill/reservations/{orderNo}` | Header `X-Internal-Service: cloud-mall-order` | 仅供 order DLQ recovery 通过内部 Feign 调用，Gateway 不路由、前端不可访问；返回 `{orderNo,eventId,reservationStatus,requestHash}`，只读验真，不改变库存 |

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
| `PAY_DEBIT_MODE_UNAVAILABLE` | 持久化扣款模式为未实现/未知值或支付记录与意图不一致，支付结果待恢复 | 503 |
| `ORDER_RESERVATION_UNKNOWN` | 库存预扣结果未知，等待按原订单号查询 | 202 |
| `ORDER_RESERVATION_KEY_REUSED` | 同一订单号携带了不同的库存快照 | 409 |
| `ORDER_STOCK_RESERVATION_REJECTED` | 库存预扣已持久化拒绝 | 409 |
| `ORDER_STOCK_ACTION_UNKNOWN` | 库存确认/回滚结果未知，等待恢复 | 202 |
| `ORDER_STOCK_ACTION_CONFLICT` | 库存动作发现相反终态，需要对账 | 409 |
| `ORDER_QUERY_SHARD_UNAVAILABLE` | 订单列表涉及的月份表缺失或查询失败，结果不可完整确定 | 503 |
| `PRODUCT_QUERY_UNAVAILABLE` | 公开商品 GET 在获取数据库连接、数据库通信或执行 SQL 超时且本次结果不可可靠确定；非 GET 和未知实现错误使用 `COMMON_INTERNAL_ERROR` | 503 |
| `STOCK_CACHE_REPAIRING` | 库存事实已提交，缓存修复处理中 | 202 |
| `STOCK_SERVICE_UNAVAILABLE` | Stock 的 MySQL 连接、事务、查询/提交超时或依赖熔断；库存结果不可可靠确定 | 503 |
| `SECKILL_NOT_STARTED` | 秒杀未开始 | 409 |
| `SECKILL_ENDED` | 秒杀已结束 | 409 |
| `SECKILL_LIMIT_REACHED` | 超过用户限购 | 429 |
| `SECKILL_SOLD_OUT` | 秒杀库存耗尽 | 409 |
| `SECKILL_IDEMPOTENCY_CONFLICT` | 同一用户跨活动幂等键对应不同请求载荷 | 409 |
| `SECKILL_DELIVERY_UNKNOWN` | 秒杀事件已持久化但投递结果未知 | 202 |
| `SECKILL_ORDER_RECOVERY_PENDING` | 秒杀订单进入死信恢复 | 202 |
| `SECKILL_RESERVATION_NOT_VERIFIED` | 秒杀受理事实不存在或暂时无法确认 | 202 |
| `GATEWAY_RATE_LIMITED` | 网关/Sentinel 限流 | 429 |
| `GATEWAY_SERVICE_UNAVAILABLE` | 服务熔断/降级 | 503 |
| `REDIS_SERVICE_UNAVAILABLE` | Redis 连接、超时、命令/协议失败或 Stock 内部 Redis 依赖熔断；本次请求结果不可可靠确定 | 503 |
| `COMMON_INTERNAL_ERROR` | 未预期服务错误 | 500 |

错误码命名、HTTP 映射和是否返回可展示 message 需在 common 实现前冻结；不得把内部异常、账号密码或堆栈返回前端。

## 12. 跨服务事件契约（逻辑）

RabbitMQ 事件至少包括：订单创建/超时、库存预扣/回滚/确认、支付成功/失败、秒杀订单请求、商品浏览统计和商品索引同步。商品索引事件使用显式 JSON converter 和 `content_type=application/json` 的 JSON envelope，至少包含 `eventId`、`eventType=PRODUCT_INDEX_UPSERT|PRODUCT_INDEX_HIDE`、`occurredAt`、`productId`、`productVersion`、`source`、`traceId` 和 `payloadVersion`；商品事务必须把完整 envelope 原样保存为 `product_index_outbox.payload_json`，dispatcher 和重试器只能原样发布，不能用重试时的 `OffsetDateTime.now` 或当前 MDC 重建 `occurredAt/traceId`。事件不承载可直接覆盖事实的商品快照。商品 outbox 必须先持久化，再由 dispatcher 使用 publisher confirm 和 returned message 检查投递结果；确认未知、退回或 outbox 状态更新失败时持续重试同一 `eventId`。消费者完成 ES 写入及 `product_index_consumption` 持久 ACK 后才确认消息，重复消息按 `eventId` 幂等，旧版本写入标记 `STALE`。ES 停机、读取 MySQL 失败或 bulk 部分失败进入退避重试；不可解析或无法识别商品的旧消息进入隔离。DLQ listener 必须先将完整原始 payload 写入 MySQL 隔离表并校验 SHA-256，应用上限为 1 MiB；超限、写入失败或校验失败不得 ACK。当前两条 DLQ 消息来自 `cloudmall.product.exchange`，原错误为 JSON 解析 Java 序列化字节失败（`Unexpected character code 172`），载荷是 Base64 `application/x-java-serialized-object`（`rO0ABXNy...`），不得按 JSON 盲目重放或使用不受限 Java 原生反序列化。新 JSON 消息先持久化后写消费 `RETRY` 并自动重放；若缺少 outbox，校验稳定字段后用同一 `eventId` 创建恢复 outbox，无法从当前事实安全恢复则 `QUARANTINED`。旧消息优先根据商品事实、outbox 或 rebuild 重建，只有受控解析并核验稳定 `productId` 后才可生成 `LEGACY_REPLAY`。

商品索引存量重建使用 `product_index_rebuild_job` 按商品 ID 游标限速直接读取当前 MySQL 事实并调用有界 ES Bulk，不为每条存量商品生成 outbox 和 RabbitMQ 消息；实时变更仍使用 `product_index_outbox`。不得随服务启动无界全量发送。默认单批 200 条，速率由配置限制；Bulk 成功后推进持久游标，部分失败、进程重启或 ES 中断不推进游标并重试同一批，依靠 ES external version 幂等。重建验收核对 MySQL 商品数/上架数、实时 outbox 状态、消费 ACK、ES 可见文档和 tombstone 数，并记录 Bulk 部分失败；ES 查询切换仍属于 CM-20260924-19。

## 13. 契约冻结说明

本轮已冻结 `/api` 前缀及本地端口、Redis 不透明 Token 与 2 小时 TTL、Bearer 认证、金额/时间格式、`Idempotency-Key`、库存内部接口边界、管理员角色、秒杀活动归属、RabbitMQ TTL + DLX、OpenFeign 超时/重试和错误 HTTP 映射。所有错误统一使用 200/400/401/403/404/409/429/500；回调为内部受控入口，服务必须执行幂等和归属校验。
