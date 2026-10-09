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

购物车新增/合并数量要求请求 quantity 为正 int；存量项数量为 0 或负数时按购物车数据损坏返回 500 并保持原项/TTL，合并结果超过 `Integer.MAX_VALUE` 返回 400 且不写入或刷新 TTL。该条款不改变请求字段、响应结构或 PUT 数量契约。

购物车向商品服务读取 SKU 时，仅接受 code=`0` 且响应 `skuId` 与请求 SKU 精确一致的事实；响应 data 缺失继续按原 404，非 0 或错/缺 SKU 身份按原 INTERNAL/500 处理，不写入或刷新当前购物车项，也不把错误价格计入结算金额。

购物车 Redis hash 每条 field 必须与 JSON 项的 `skuId` 精确一致；字段身份缺失、类型错误、别名或错 SKU 按原购物车数据损坏 500 处理，不继续读写或刷新 TTL。显式删除接口仍可按请求 SKU field 删除指定坏项。

购物车 settlement preview 会先校验 selected/checked 项数量；任一已选项为非正数时返回既有数据损坏 500，不调用商品服务、不写入或刷新 Redis。未选中的坏项不影响本次预览，合法项的既有失效项与金额响应结构保持。

该商品事实的 unitPrice 必须是可由 BigDecimal 解析的原始金额字符串；null、空白或不可解析值按原 INTERNAL/500 处理，不写入或刷新当前项、不计入 preview 金额。合法字符串不做 scale/格式归一化，本条不定义金额范围上限。

购物车 add 在三轮已确认并发 compare-write mismatch 后返回既有 COMMON_CONFLICT/409；脚本结果未知沿既有 INTERNAL/500，Redis 依赖故障沿既有 503，均不自动重试或猜测写入。请求和响应字段保持不变。

购物车 PUT/checked 对目标 field 使用有限 compare-write；目标缺失返回 404，三轮确认冲突返回 COMMON_CONFLICT/409，脚本结果未知为 INTERNAL/500，Redis 依赖故障保持既有 503。请求和响应字段保持不变。

clearChecked 仅删除读取时 checked=true 且原 JSON bytes 未变化的项；并发变更保留最新项，比较失败仍返回原成功 null。脚本结果未知返回 INTERNAL/500，Redis 依赖故障保持既有 503，不自动重试或猜测删除结果。

订单创建入口在地址、商品、库存预扣、意图和订单本地写入前使用内部 snapshot GET 预采样；提交后的 afterCommit 仅使用原 DTO 调用 cleanup POST，不再 GET。已命中订单幂等投影的请求保持原顺序并不执行该 GET；投影缺失而进入既有 intent fallback 时允许只读 GET，但不得产生 POST/DELETE、Stock 调用或事实变更。GET 缺项返回 code=0/data=null，POST 仅在原 JSON bytes 精确匹配时返回 data=true，否则返回 data=false；请求 proof 失败为 400，结果未知为 500，Redis 依赖故障为 503。该内部契约不改变公开 Cart DELETE、订单 JSON 或错误码。

订单创建的 Cart 清理快照在地址、商品、库存预扣、意图和订单本地写入之前只读预采样。请求基础校验后，所有 `skuId` 必须非空、`quantity` 必须为正 int；同 SKU 数量仅以 long 聚合用于证明，不改变原订单/Stock 请求，聚合超过 int 上限时不授权该 SKU 清理。快照 JSON 的 `skuId` 和正整数 `quantity` 必须分别精确匹配 SKU 与聚合购买数量；证明失败只保留购物车项。成功采样的 DTO 在提交后仅用于一次条件 cleanup POST，回调不再 GET；无事务、幂等命中或回滚不产生该清理调用。

订单服务的 Feign 身份透传遵循显式参数优先：当 `RequestTemplate` 已有大小写不敏感匹配的 `X-User-Id`（或 `X-User-Role`）时，拦截器必须保留显式值，不追加线程上下文中的晚期值；无显式值时才注入当前 `AuthContext`，空上下文不注入。该规则用于避免订单 afterCommit 的原用户身份被晚期线程上下文覆盖，不改变其他服务鉴权、Gateway 或公开 API。

购物车 all/preview 的商品摘要刷新采用有限 compare-write；确认冲突返回 COMMON_CONFLICT/409，脚本结果未知为 INTERNAL/500，Redis 依赖故障保持既有 503。Product 原 404/失效项语义和请求/响应字段保持不变。

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

普通订单创建在使用商品价格、构造订单明细或库存请求前，必须精确核对每个 Product 响应 `data.skuId` 与请求 `skuId`；null、错 SKU 或身份无法确认按既有 INTERNAL/500 失败，不得用请求值猜测、继续写订单或预扣库存。匹配时仍使用原请求 SKU 顺序/数量和 Product 返回的原价格；本条不扩展秒杀、价格范围或 Product 其他字段校验。

普通订单创建在上述 SKU 身份门之后，使用商品价格前还必须确认 `unitPrice` 非 null 且 `signum() >= 0`；null 或负数按既有 INTERNAL/500 失败，不得继续金额、库存或订单写入。零值、科学表示、尾随零和原 scale 保持原金额语义，不新增金额上限或支付可用性承诺。

普通订单创建在价格非空非负门之后，还必须确认商品单价可精确表示到数据库金额的两位小数：`scale() <= 2 || stripTrailingZeros().scale() <= 2`。非零三位及以上小数按既有 INTERNAL/500 失败；尾随零、整数、科学表示和零值保持原 BigDecimal 表示，不修改 DECIMAL(18,2) DDL、不四舍五入或新增金额上限。

普通订单创建在单价分精度门之后还必须满足既有金额列容量 `MAX_ORDER_AMOUNT=9999999999999999.99`。单价先于 multiply 比较，单项金额和累计总额按原精确 multiply/add 后再比较；任一超容量按既有 INTERNAL/500（单价）或 INVALID/400（行/总额）处理，且均不得继续 intent、库存或订单写入。不四舍五入、不新增 quantity 上限或改变原金额对象。

普通订单创建在上述金额门之后，写入订单明细前必须确认 Product 的 `productId` 非 null、`productName` 非 null 且 Unicode code point 数不超过 255；不满足按既有 INTERNAL/500，不得继续 intent、库存或订单写入。空串、空白、尾空格和合法 255 code points 原样保留，不 trim、截断或新增父商品关系校验。

普通订单在全部商品身份、价格、金额和必填 metadata 校验通过后，必须在创建预扣 intent、调用库存和订单 SQL 前，使用既有 ObjectMapper 一次性准备地址 JSON 与全部 SKU snapshot JSON；后续持久化只使用缓存结果。任一序列化失败沿既有 INTERNAL/500 返回（保持现有不携带 cause 的 BizException 语义），不留下 intent、库存或订单事实；直接幂等投影重放仍不执行该准备，晚期 intent fallback 保持原 Product/金额顺序，合法输入结果不变，不可序列化输入可在 fallback 前拒绝且不新增查询。

订单本地事务实际提交后，才清理原用户的去重 SKU 购物车快照；事务回滚时不得删除购物车。清理使用提交前捕获的用户与 SKU 快照逐 SKU执行一次。Cart 返回 null、非 0 或 RuntimeException 时逐项告警并继续后续 SKU，不回滚已提交订单或库存；本批不提供持久重试或并发新增 SKU 的条件删除保障。该条款不改变本节既有 JSON、错误码、订单与库存步骤。

订单超时 outbox 失败记录的 `attempts` 在既有 INT 列范围内饱和递增：非负计数低于 INT_MAX 时加一，INT_MAX 保持不变并继续原 nextRetryAt/错误记录；负计数、非 PENDING 或缺失事件号不更新。该 SQL 保护不改变 Rabbit 重试策略、消息字段或公开 API。

订单超时 outbox dispatcher 处理候选前必须检查已读 PO 的 `attempts` 非 null 且非负；坏快照只告警并跳过，不解析、不发送、不完成或写失败，后续候选继续。该 guard 不改变扫描 SQL、数据库字段或其他并发/字段校验语义。

订单 outbox 的扫描、发布完成和失败记录 SQL 必须对 `status='PENDING'` 使用 `CAST(status AS BINARY)=CAST('PENDING' AS BINARY)` 精确匹配；dispatcher 对已读 PO 也要求精确 PENDING，别名/其他状态只告警跳过，不 parse、不发送、不写完成/失败。0 行完成继续沿现有 publication proof，0 行失败保持原返回语义。event_type 的既有 BINARY 条件不在本条扩大。

订单超时 outbox 扫描在既有状态和到期时间条件后、LIMIT 前增加 `attempts>=0` eligibility；负计数不得占满候选窗口，也不被自动修复或删除。排序、limit 归一、Rabbit 和失败退避语义保持不变。

订单 timeout outbox 扫描在 LIMIT 前还必须排除明显早于合法 DATETIME 年份下界的历史时间行：`next_retry_at`、`created_at`、`updated_at` 均满足 `>= '1000-01-01 00:00:00.000'`，`published_at` 必须为 NULL 或满足同一条件。四个谓词使用 SQL 字面量、不增加参数，原 due/status/type/attempts/identity、全 row 投影、排序、limit 和错误传播保持；该 eligibility 只避免零日期进入 JDBC 时间映射，不修复/删除/发送坏行，不宣称完整日历校验或处理所有 invalid-date/zero-date driver 组合。合法 NULL、1000/9999 时间、DF/DE/DG/DH/DI/DJ、SQL、Schema、API 和 message 语义不变。

在上述 17DK 下界之后，订单 timeout outbox 扫描还必须在 LIMIT 前以 `MONTH(col)>0 AND DAYOFMONTH(col)>0` 排除 `next_retry_at`、`created_at`、`updated_at` 的零月/零日值，并对非空 `published_at` 使用同一两个函数谓词；`published_at IS NULL` 继续合法。四个原 prepared 参数、全 row 投影、排序、limit、原资格和错误传播不变，不修复/删除历史行，不声明完整日历或 `ALLOW_INVALID_DATES` 校验；只保护 JDBC 映射免受部分零日期阻断，DF/DG/DH/DI/DJ、SQL、Schema、API 和 message 语义保持。

在 17DL 零月/零日门之后，订单 timeout outbox 扫描还必须在 LIMIT 前按 `DAYOFMONTH(col)<=DAYOFMONTH(LAST_DAY(col))` 分别校验 `next_retry_at`、`created_at`、`updated_at`，并对非空 `published_at` 使用同一比较；`published_at IS NULL` 继续合法。保留四个原 prepared 参数、全 row 投影、排序、limit、原资格和错误传播；不修复/删除历史非法日期，不宣称完整 Gregorian、1582、timezone、编码或 `ALLOW_INVALID_DATES` 闭环。若函数产生新 warning 或语义不符，验收保持失败，不放宽测试。

订单 timeout outbox 的 `findPendingOutbox` RowMapper 对 `next_retry_at`、`published_at`、`created_at`、`updated_at` 必须使用 JDBC typed `ResultSet.getObject(column, LocalDateTime.class)` 直接读取；`published_at` SQL NULL 继续映射 Java null。不得恢复 `Timestamp.toLocalDateTime()`、引入 Calendar/字符串解析 fallback、转换时区或修改 JVM 默认时区。SQL、DK/DL/DM 资格、四参数、全 row 投影、排序、limit、scan 错误传播、Service、writer、proof、API/message 保持；本条只处理 Connector/J GregorianCalendar cutover 读取边界，不宣称全服务时间兼容。

订单超时 outbox 扫描还必须在 LIMIT 前以 BINARY 精确筛选 `event_type='ORDER_TIMEOUT'`；dispatcher 对当前 PO 再做精确类型 guard，别名/其他类型只告警跳过，不解析、发送或写完成/失败。其他事件类型需独立 handler，不由 timeout exchange 处理器猜测。

订单 outbox 扫描 SQL 故障继续向外传播；单候选 DataAccessException 只隔离当前候选并继续后续候选。`markOutboxPublished` 或 failure 写入的数据库异常不得被普通发送 catch 转换为第二次 failure 写入，不猜测已确认消息状态；非数据库发送/解析异常保持原一次退避记录，Error 不捕获。

订单 timeout dispatcher 在发送/解析/confirm 失败后仍只调用一次 `recordOutboxFailure`，保留原 error 裁剪和上海 `nextRetryAt`。调用返回 `1` 时仅告警“失败退避已记录”并带原 error/nextRetryAt；返回 `0` 时告警“失败退避更新 0 行，本次未确认记录退避，保留当前事实”，0 可能来自并发状态变化、缺行或匹配但 SET 无变化，不能据此推断完整事实，不新增 SELECT、UPDATE、failure 二写、重发、proof 或修复。返回非 `0/1` 抛带原发送/解析异常 cause 的 `OptimisticLockingFailureException`（`DataAccessException`），沿既有单候选 CS 隔离后项。failure writer 自身的 DataAccessException、RuntimeException、Error 及 cause interruption 继续原通道；调用后的 CU 当前中断检查不撤回已提交写。返回 `1` 只表示本次原 SQL 影响一行，不是 full-row 证明，也不承诺未来调度；返回值语义不承诺未来一定重试/完成或 MQ 与 SQL 原子性，本条不扩展全局 JDBC affected-rows 模式支持，SQL、退避、payload、消息和公开 API 不变。

订单 timeout dispatcher 在 failure writer 前以同一次上海 `now` 计算 `retry-delay-seconds` 的 `nextRetryAt`，保留原纳秒，不 floor、clip、替换时间或补造值。`ArithmeticException`/`DateTimeException` 或计算结果年份不在 `1000..9999` 时，抛 `InvalidDataAccessApiUsageException`（`DataAccessException`），原发送/解析异常作为 cause，计算异常作为 suppressed 证据；沿 CS 隔离后续候选，不调用 failure writer、不查 proof、不重发、不改 metadata。计算后至原 writer 前保留 CU 中断检查，Error 不捕获；可表示的大正数继续原退避。该边界只对应 `DATETIME(3)` 年份可表示性，不增加 positive cap、batch/confirm 上限、配置纠正、SQL/API/message/routing/proof 语义，也不扩展 DATETIME(3) 舍入边界。

订单 timeout future 正余量转换必须使用订单模块 `util.OrderTimeoutTtl.remainingMillis(Duration)`：positive Duration 按毫秒向上取整，整毫秒不变，有纳秒余量用 `Math.addExact(wholeMillis,1)`；zero/negative 仍返回 0 并沿既有 DLX/清除 expiration 路由。null 参数保持 NPE，`toMillis` 或 ceil 溢出保持 ArithmeticException，沿原 failure 不发送/不完成；Dispatcher 的 Shanghai/offset 解析、expireAt 文本、envelope、future route、confirm/returned、CU/CS、17DD proof、DB floor、配置、topology、Service consumer 和公开 API 不变。本条只改变 future TTL 数学取整，不承诺 broker/clock/queue/consumer/FIFO 实际延迟闭环。

订单 outbox failure writer 在 JDBC 调用前必须先将调用方 `nextRetryAt` 与一次上海 `now` 生成的 `updatedAt` 分别向下截断到毫秒（`ChronoUnit.MILLIS`），再按原参数位置绑定；不 timezone 转换、不四舍五入、不重采样 retry 时间、不修改调用方对象。两个时间均须非 null 且年份在 `1000..9999`，否则入口抛 `InvalidDataAccessApiUsageException`（`DataAccessException`），即使行不存在、非 PENDING、type/attempts 不合资格也不得发起 JDBC。该应用 floor 语义不依赖全局 `TIME_TRUNCATE_FRACTIONAL` 或 sql_mode；clipError、CQ 饱和、DF row count、DH raw 计算/log、DD proof、DE route、SQL、Schema、API 和 message 保持不变。

订单 timeout `markOutboxPublished` 在 JDBC 调用前必须对调用方 `publishedAt` 执行同一毫秒 floor helper：非 null 且年份 `1000..9999`，否则首次 JDBC 前抛 `InvalidDataAccessApiUsageException`，不因缺行或完成资格不符而跳过；合法归一结果同一实例按原参数位置双绑定 `published_at`、`updated_at`。不采新时钟、不转换 offset、不修改调用方对象；Mapper SQL、WHERE、eventId 精确身份、返回行数、Dispatcher raw 时间/CU、17DD proof、DF/DE/DG 及 API/message 保持，不扩展 insert/scan/其他时间路径或 DDL。

订单 timeout dispatcher 构造阶段读取既有 `batch-size`、`confirm-timeout-ms`、`retry-delay-seconds` property 及其原类型/默认值后，必须在 `RabbitTemplate.setMandatory(true)` 前统一校验三值均 `>0`；`0`、负数或对应类型 `MIN_VALUE` 立即抛包含 property 名和原值的 `IllegalArgumentException`，不扫描、不发送、不执行 SQL、不修改 RabbitTemplate。默认 50/5000ms/10s 和所有合法正数继续原样用于 scan limit、confirm 等待和 retry 退避；Mapper 的非正 limit 归一、dispatch interval、未来时间溢出、Schema、API、消息、路由和 proof 语义不变。本条只做 constructor fail-fast，不引入 Bean Validation、上限或全局配置/affected-rows 模式声明。

订单 outbox publisher confirm 后，`markOutboxPublished` 仅返回 1 才直接完成；返回 0 只允许通过唯一只读 publication facts proof 确认 peer 已完成。`findPublicationFacts(eventId)` 方法签名不变，仍只按原 eventId 读取 partial PO，投影为 `event_id,status,event_type,order_no,attempts`；proof 必须同时核对唯一行、原 eventId 精确相等、`status=PUBLISHED`、原 orderNo 精确相等、原 `event_type=ORDER_TIMEOUT`，以及 attempts 非 null 且非负。attempts 不要求保持原值，peer 合法增长 1 或饱和为 INT_MAX 均可接受。缺行、非唯一、任一事实不精确或 attempts null/负均按 Optimistic(DataAccess) 并发证明失败隔离，不写 failure、不重发、不修事实；非 0/1 不查询。`attempts` 使用 nullable 精确读取，NULL 仅为 unit seam，因为 SQL 列仍 NOT NULL；该 proof 不保证 MQ 与数据库原子性。

订单 timeout dispatcher 解析 `expireAt` 后，若上海当前时刻已到达或超过该时间，剩余 TTL 为 `0` 且发送路由改用既有 `OrderMessagingConfiguration.DLX`、空 routing key，并由 message postprocessor 清除 `expiration=null`，直接进入既有 DLX→DLQ→timeout consumer 链路；未来时间继续使用 `TIMEOUT_EXCHANGE`、空 routing key 和至少 `1ms` 的原 per-message TTL。`OffsetDateTime` 与无 offset 的上海 `LocalDateTime` 解析兼容；到期分支须在 `Duration.toMillis()` 前判断非正值，未来解析/溢出错误沿原 failure/退避语义。两路由共用 mandatory publisher confirm/returned 检查，只有 ACK 且无 returned 才完成 PUBLISHED；direct DLX 无 binding 时 mandatory return 保持 PENDING 并走原 failure。envelope、eventId、orderNo、expireAt、occurredAt、traceId、payload、CorrelationData、17DD proof、CU/CS 和 API 字段不变。本条只覆盖已到期快照，不承诺未来 TTL FIFO 队头阻挡或订单事实时间窗口全部解决。

订单 outbox 的完成写与失败写在既有 `event_id=?` 候选条件上追加 `CAST(event_id AS BINARY)=CAST(? AS BINARY)`，同一原 eventId 绑定两次；大小写、重音或尾空格别名不得改写其他事件。0 行完成继续 publication proof，0 行失败保持原返回语义，不改变扫描、消息或公开 API。

订单 timeout outbox 扫描在 LIMIT 前还必须分别筛选 eventId 与 orderNo 两列各自至少包含一个非 Java whitespace 字符；Java17 固定正则以 UTF-8 HEX prepared 参数传入，SQL 用 `UNHEX` 恢复 pattern 并显式 `COLLATE utf8mb4_bin`，不依赖 JDBC/session 反斜杠配置，不 trim/归一化。pattern 保持 UTF-8 文本而非 binary datatype，不改列排序规则；空白身份候选不占窗口，其他扫描、写入、proof 和公开 API 语义保持。

订单 timeout dispatcher 在当前 PO 的 attempts、event_type、status 门之后，还必须要求 eventId 与 orderNo 非 null 且非空白；不满足时只告警跳过，不解析、不发送、不完成或失败写，后续候选继续。该 guard 不新增扫描过滤、接口字段或历史修复。

订单 timeout dispatcher 在解析 payload 后、发送前仅核验 payload 已存在的 eventId、eventType、orderNo 是否为 String 并分别精确等于 outbox row 身份；缺失字段保持旧兼容，失配按原 failure/退避且不发送。不得补写、trim、转换或新增消息字段。

订单 timeout consumer 对历史 DLQ Message 的 body 继续先按原 JSON string/envelope 规则解析；若整份 UTF-8 文本 `trim()` 后严格匹配 ASCII `[0-9]+` 且 root node 为 integral number，则仅将原 `rawPayload.trim()` 作为订单号委托既有 `consumeTimeout(String)`，不 long/double/asText、不重序列化、不猜 content type。负数、小数、指数、数字前缀或附加 JSON/非空字符不进入该分支，原格式拒绝/fallback、quoted JSON、object envelope、月份/精确身份/到期/CAS/action/事务/ACK-retry 语义不变；不改 outbox TTL、SQL、DDL、proof、公开 API 或对象内数字字段规则，不自动重放历史消息。

订单 timeout object envelope 必须要求 `eventType` 为 textual 且精确等于 `ORDER_TIMEOUT`，并要求 `eventId`、`orderNo`、`expireAt` 均为非空白 textual 节点；缺失、JSON null、空白、数字、布尔、数组、对象或 type 别名均沿原格式错误返回，不委托 String legacy、不查询订单/Stock/RPC。`orderNo`、`expireAt` 使用原 textValue，不 trim/转换；catch 仍只处理 `JsonProcessingException`，不把坏 object 送入 DP fallback。订单格式/月路由、精确身份、到期/CAS/action/事务、ACK/retry、TTL、outbox SQL/DDL/proof/API/message 保持。

订单 timeout Message 入口必须使用 per-call `mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(rawPayload)` 拒绝尾随有效 JSON；不得全局修改共享 ObjectMapper。`MismatchedInputException` 转原格式 `IllegalArgumentException` 并携带 cause，不进入 raw legacy 或业务查询；其他 `JsonProcessingException` 仍沿原 `rawPayload.trim()` fallback。合法尾随空白、DP 整数 raw String、quoted/object 原生字段门和 String 业务路径保持，ACK/retry、事务、TTL、outbox SQL/DDL/proof、API/message 不变。

该 per-call reader 还必须启用 `DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY`；同一 JSON object 任意深度的重复键（包括相同值、必填字段、optional/nested/array object）均转原格式错误并保留 cause，不进入 DP/raw legacy/业务 Mapper/RPC；不同 object 各自使用同名字段继续合法。不得全局 enable、启用其他 duplicate-key/POJO 解析策略或改变 JSON 宽度；单 root、DQ、quoted/raw、String 业务、ACK/retry、TTL、SQL、DDL、proof、API/message 保持。

订单 timeout Message 在上述 JSON reader、legacy 和业务解析前必须使用每次新建的严格 UTF-8 decoder，并以 REPORT 处理 malformed/unmappable input；`CharacterCodingException` 转带 cause 的原格式 `IllegalArgumentException`，不访问订单/Stock/RPC，不改变原 body bytes。合法中文、emoji、真实 U+FFFD（EF BF BD）保持可用；不猜 content type/encoding、不改全局 charset、不改变 DP/DQ/DR/DS、String 业务、SQL、事务、ACK、TTL、outbox、proof 或 API/message。

订单 outbox 发布完成 UPDATE 还必须在执行时精确确认 `event_type=ORDER_TIMEOUT` 且 `attempts>=0`；失败记录 UPDATE 精确确认 event_type，保留既有 attempts 非负条件。扫描后类型或计数变化时返回原 0 行语义，不改写异常行；不新增 proof 投影或公开 API。

订单 outbox dispatcher 在入口、扫描/候选检查、发布确认等待和完成/失败写入前检查线程中断；明确中断恢复标志并停止当前及后续候选，不新增 failure/complete、不撤回已发送消息。普通网络超时仍沿既有退避，不把所有 InterruptedIOException 当作取消。

订单列表的 `page`、`pageSize`、`startTime`、`endTime` 和可选 `status` 共同确定查询窗口；服务端先把带任意合法 ISO-8601 offset 的 `startTime/endTime` 按 same instant 规范到 `Asia/Shanghai`（`+08:00`），再计算月份范围，并使用左闭右开 `[start,end)` 时间条件，避免非 `+08:00` 请求在月初漏查前一月。`page` 与 `pageSize` 使用正数，服务端以 `long` 计算 `(page-1)*pageSize` 并在溢出或超出可接受深度时返回 `COMMON_INVALID_ARGUMENT`。响应必须保持 `items/page/pageSize/total` 结构，`total` 是所有命中月份的精确计数。由于不同物理月份的 `created_at` 区间按分片不变量严格不重叠，跨月查询按月份倒序遍历：先逐月 `COUNT`，根据计数跳过完整月份，仅对承载目标页的月份执行有界 `LIMIT/OFFSET` 批次，并在批次内使用 `created_at DESC, order_no DESC` 稳定排序；内存只保留目标页和当前批次，不得先把所有月份订单读入 JVM 后排序或 `subList`。若检测到订单落在错误月份，作为数据异常告警并使查询失败，不通过全量扫描默默兜底。任一命中月份不存在、不可访问或查询失败时，整个请求返回 `ORDER_QUERY_SHARD_UNAVAILABLE`（HTTP 503），不得返回部分 `items` 或部分 `total`。读路径不得为缺失历史月份偷偷建表；未来月份由定时任务预建，具体规则见数据库和架构规格。

订单状态写操作必须返回并检查条件更新影响行数。支付成功、手工取消和超时关单先在本地事务中完成订单 CAS 与 `order_stock_action` 意图落库，提交后才调用库存确认/回滚；远程响应未知时动作进入 `UNKNOWN`，恢复任务查询库存最终状态并用同一 `operationKey` 重试。CAS 失败必须读取当前状态，已达到目标状态时作为幂等成功返回，处于其他终态时返回 `ORDER_STATUS_INVALID`，不得创建相反库存动作。支付成功的 `PAID` 状态一旦本地提交，不得因为后续 stock.confirm 超时回滚为 `PENDING_PAYMENT`；取消/超时只有在订单仍为 `PENDING_PAYMENT` 时才能提交 `CANCELLED` 与回滚意图。

订单 timeout `consumeTimeout(String)` 在读取订单并确认 `PENDING_PAYMENT` 后、任何 CAS 或 `order_stock_action` 写入前，必须要求订单事实 `expire_at` 非 null；缺失时返回既有状态错误/409“订单超时期限缺失，暂不关单”，不从 created_at、Message envelope 或当前时间推导 deadline。非待支付状态即使 expire_at NULL 仍沿原 no-op；非空 future 继续原 409，已过期继续原 CAS/rollback action。该门不改变 expire_at nullable DDL、API、消息、SQL、Stock、ACK/retry、TTL 或其他日期异常语义。

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

CM-18 第 15A 只接入 User `service.BalanceTccDebitLocalResource` 的真实 LocalTCC RM：`prepare(BusinessActionContext,@BusinessActionContextParameter(paramName="request") BalanceTccBranchDTO) boolean`、`commit(BusinessActionContext) boolean`、`rollback(BusinessActionContext) boolean`。DTO 只包含 `userId/payNo/orderNo/amount`，XID、branchId、operationKey、ledgerKey 由框架上下文和冻结规则绑定，调用者不能覆盖。资源使用 `useTCCFence=false`，由适配器在同一 User 本地事务显式调用官方 Fence 和业务 callback；不新增 Confirm/Cancel HTTP。Fence `SUSPENDED` 空回滚、被拒第二 branch 安全 ACK和 Fence 清理后业务终态幂等属于 15A；进程中断和 TC 暂失属于 15B。

CM-18 第 16A 只允许显式内部测试/迁移入口使用 TCC bridge。Pay 必须先核对原 `pay_record` 的 payNo、userId、orderNo、amount、`debit_mode=TCC` 和 `status=PENDING`；公开支付创建仍只产生 LEGACY。Pay `dispatch(PayTccDispatchDTO)` 从 RootContext 读取 XID，POST 只允许一次；响应未知后只能调用 User GET。User 通过 `X-Internal-Service`、`X-User-Id`、现有 callback token 和 `TX_XID` 校验内部请求，并用 `user_tcc_receive` 唯一认领。Gateway/Auth 必须阻断包含 `tcc-debits` 的服务前缀、编码和路径参数变体。GET 返回精确 XID/branchId 供内部机器核验，`TRYING` 不表示支付成功；Confirm/Cancel 没有 HTTP/Feign 接口。

16A 的 User receive 只持久化不可变 `CLAIMED`；TRY_UNKNOWN、TRYING、CONFIRMED、CANCELLED 从 attempt/TCC/ledger/Fence 一致快照只读推导。余额不足 `PAYMENT_FAILED`、资金未知 `BALANCE_TCC_UNKNOWN` 和普通 HTTP 4xx 不能直接映射为支付拒绝；没有持久拒绝事实时 Pay 必须保持 `TRY_UNKNOWN`。Feign 专属错误解析仅将完整 JSON 中的 `IDEMPOTENCY_CONFLICT`、`USER_TCC_STATE_CONFLICT` 映射为派发冲突，不能按 HTTP 409 整体分类。

CM-18 第 16B1 新增 Pay 自有 `pay_tcc_receipt` LocalTCC 资源，action name 固定为 `cloudmallPayDebitReceipt`，状态为 `CLAIMED/TRYING/CONFIRMED/CANCELLED`；它不是 User 状态镜像。`PayTccReceiptService.prepare(PayTccDispatchDTO)` 先调用 16A dispatch 的持久 anchor，再由 CLAIMED 唯一 winner 注册 Pay receipt branch；不自动调用 User、不创建新 XID。配置 `cloudmall.pay.tcc-receipt.enabled=false` 默认关闭，开启时必须同时启用 Pay bridge、Seata 并关闭 AT 自动数据源代理。Pay receipt Confirm/Cancel 没有 HTTP/Feign，Pay 对外支付仍为 LEGACY。

CM-18 第 16B2 只提供内部 `execute/recover` Service，配置 `cloudmall.pay.tcc-coordinator.enabled=false` 默认关闭；一次性 execution 以 payNo、XID、operationKey、ledgerKey 唯一绑定，超时上限 120000ms。已有 dispatch/receipt 但无 execution 时禁止新 begin；XID 丢失时保持 BEGIN_UNKNOWN。recover 不新增 decision、不接管 Try，只能使用原 XID 重试同一 COMMIT/ROLLBACK；终态必须同时有 Pay receipt 与 User 完整持久事实，TC Unknown 不能作为结论。

CM-18 第 16C 只提供内部 `PayTccDeliveryService.deliver(String payNo)` 和 `PayTccDeliveryScheduler.recoverDue()`，配置 `cloudmall.pay.tcc-delivery.enabled=false` 默认关闭。READY 只表示从本地 COMMITTED execution 派生的待核验意图；每次 Order/退款 RPC 前必须重新核对 Pay proof 与 User live TCC GET。ROLLEDBACK 不建 job；User DEBIT 已有任何 refundOperationKey 时禁止再次 paid，只能在 CANCELLED+库存回滚成功后用固定 `refund:<payNo>` POST/GET 结清。

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

秒杀消费首次创建订单时，Product 响应必须返回 code=`0`、非空 data，且 `data.skuId` 与消息 `skuId` 精确一致；null/错 SKU、null envelope/data 或非 0 code 在订单、明细、outbox、COMMITTED/Resolved 写入前按原未知失败流程重试或进入隔离，不得用消息 SKU猜测 Product 身份。已 COMMITTED 的重复事件仍在 Product 查询前按原幂等路径收敛；本条不改变普通下单、消息字段、价格范围或历史数据。

秒杀首次消费在 SKU 身份门之后，Product `unitPrice` 必须非 null 且数值非负；null 或负数在订单/明细/outbox、COMMITTED/Resolved 写入前进入原 retry/DLQ 流程，新增 claim 事务回滚，Stock/Cart 不调用。合法零和正数保持原价格表示；本条不定义价格上限、scale 或后续支付成功。

秒杀首次消费在价格非空非负门之后，同样要求 Product 单价满足两位小数精度规则 `scale() <= 2 || stripTrailingZeros().scale() <= 2`；不满足时沿原 retry/DLQ 处理并回滚新增 claim 事务。该规则不改变已 COMMITTED 重放、消息字段或数据库 DDL。

秒杀首次消费在单价分精度门之后要求单价不超过 `9999999999999999.99`；超容量在订单、明细、outbox、COMMITTED/Resolved 写入前沿原 retry/DLQ 处理并回滚新增 claim，已 COMMITTED 重放不重新核价。

秒杀消费者解析消息的 `userId`、`activityId`、`skuId` 必须无损转换为 long：Number 使用精确 `BigDecimal/BigInteger.longValueExact` 语义，String 保持 `Long.parseLong`（允许 `+` 和前导零），小数、溢出和非字符串非法。解析失败必须早于指纹、claim、Product 和订单写入；userId 沿既有冲突证据路径，核心维度沿既有 INVALID_PAYLOAD 路径，不得截断或猜测身份。

秒杀冲突台账同时保留既有结构化 `payload` 和可空 `raw_payload` 原文证据；新记录两列绑定同一次 ObjectMapper JSON 字符串，历史 `raw_payload=NULL` 明确为 legacy JSON 视图。该内部证据列不改变冲突唯一键、ACK、状态、消息或公开 API；迁移/列缺失/双写失败不得降级确认消息。

秒杀冲突证据必须先成功序列化一次再写台账；序列化失败不得伪造 `serializationError` JSON 或确认消息，沿原 retry/DLQ 保留 cause。稳定事件号优先原 eventId、其次 orderNo 派生、最后使用同一 payload 的 SHA-256，不再次序列化或使用 Map.toString；正常创建和已 COMMITTED 重放不进入该冲突取证路径。

秒杀首次订单、明细和 outbox 写入后，dedup `markCommitted` 的当前条件 UPDATE 必须返回恰好 1 才允许继续 `markResolved` 和完成消费；非 1 整数进入本批受控失败，Mapper Runtime/SQL 异常沿现有外层异常包装和 retry/DLQ，Error 不包装，且本地事务回滚。不新增二次状态查询，不猜测或改写已提交 held/COMMITTED 事实；已 COMMITTED 重放仍按原幂等路径收敛。

秒杀首次消费在金额容量门之后，写入 Order/明细前同样要求 Product `productId` 非 null、`productName` 非 null 且不超过 255 Unicode code points；失败沿原 retry/DLQ 处理并回滚新增 claim。该条不改变 skuSnapshot、消息字段或数据库 DDL。

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
| `PAY_CALLBACK_CONFLICT` | callbackId 已绑定其他支付单或相反回调方向/载荷，不能静默复用 | 409 |
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

### CM-20261007-18 公开支付 TCC 路由（16D）

`POST /api/payments`、`/{payNo}/mock-success`、`/{payNo}/mock-fail` 和 `/callback` 的公网路径、Gateway Bearer 鉴权、字段和 Idempotency-Key 不变。新支付显式配置 TCC 时，success/callback-success 仅经已有 coordinator → delivery；首次无 execution 时先核验订单 `PENDING_PAYMENT`，已有 execution 直接 recover。16D 默认下 fail/callback-failure 只本地读取并返回 `PAY_ORDER_SYNC_UNKNOWN`/202，不发 RPC、不 cancel、不写 FAILED；16E cancel opt-in 对无 execution 且完整取消证据场景补充 200/FAILED。callback 首次并发使用独立事务 `INSERT IGNORE` 预绑定原 payload，`callback_status=RECEIVED_SUCCESS|RECEIVED_FAILURE` 且 `processed_at=NULL`；只有真实处理完成才转 `SUCCESS|FAILED`。旧 `payload={}` 仅 same payNo、same 方向且当前 Pay 字段已校验时兼容；重放仍走真实结果，不能凭日志 200。跨 payNo 或方向冲突返回 `PAY_CALLBACK_CONFLICT`/409。`ROLLEDBACK` 在 decision 为 NONE/COMMIT/ROLLBACK 均可，仅凭本地快照 + User live GET 的完整 CANCELLED/no-ledger 证据在短事务 CAS FAILED，不改 decision。

### CM-20261007-18 TCC 主动取消（16E）

新增独立 opt-in 开关 `cloudmall.pay.tcc-cancel.enabled=false`，不属于 16D TCC 创建必需依赖；不新增公网路径，复用受保护 `/{payNo}/mock-fail` 与 callback failure。取消表无条件初始化，所有 execution/bridge/receipt 入口即使开关关闭也必须查询 gate。仅当持久模式为 TCC、支付仍 PENDING 且无 execution/dispatch/receipt 时，先在独立本地事务持久化取消台账，再在事务外调用 Order cancel。Order 明确 `CANCELLED` 且库存 `SUCCEEDED` 后，若再次短事务确认无 execution/dispatch/receipt，台账 `COMPLETED` 与 Pay `FAILED` 同一 CAS 提交，主动失败返回 HTTP 200/FAILED；处理中/未知返回 `PAY_ORDER_SYNC_UNKNOWN`/202。已有 execution 统一 202，不 recover、不改 decision。Legacy fail 保持原事务语义，TCC cancel 使用 `NOT_SUPPORTED` 外层 + 短 `REQUIRES_NEW` 台账事务，所有 Order RPC 在本地事务提交后执行。

### CM-20261007-18 TCC 已回滚失败入口收尾（16F）

已有 TCC execution 为 `ROLLEDBACK` 时，`mock-fail`/callback failure 可在 `NOT_SUPPORTED` 边界读取 Pay receipt/Fence 快照并调用 User live GET，完整证明同一 XID/branch 的 `CANCELLED` 与 no-ledger 后，以短事务 CAS Pay `PENDING|ORDER_SYNC_UNKNOWN→FAILED`；成功返回 HTTP 200/FAILED，callback 同步完成 `RECEIVED_FAILURE→FAILED`。缺证据、未知 execution、TC Unknown、COMMITTED 或其他非 ROLLEDBACK 状态均返回 202，不 recover、不 begin、不调用 TC/Order/User 写接口、不创建 cancel 台账、不改 decision；本轮不加自动投影 scanner。

### CM-20261007-18 TCC 后台 ROLLEDBACK 失败投影（16G）

复用 `cloudmall.pay.tcc-recovery.enabled=false` 和既有 recovery lease/退避，不新增 flag/table。Recovery scheduler 第二阶段只筛选已持久 `ROLLEDBACK`、已有 XID/dispatch/receipt、TCC tuple 一致、Pay 为 PENDING/ORDER_SYNC_UNKNOWN 且无 cancel/Legacy intent 的 execution；claim 使用独立随机 token。User live GET 完整证明同 XID/branch 的 CANCELLED/no-ledger 后，短事务锁 Pay+execution 并核验 token/lease，再 CAS Pay FAILED。未知/冲突退避且不写 Pay；不调用 recover/TC/Order/User 写入、不改 decision、不自动完成 callback。

### CM-20261007-18 COMMITTED TCC failure 结果语义（16H）

COMMITTED execution 的 failure 不实现主动取消。Pay `REFUND_PENDING` 明确返回 `PAY_REFUND_PENDING`/202；Pay `PENDING`/`ORDER_SYNC_UNKNOWN` 返回 `PAY_ORDER_SYNC_UNKNOWN`/202；SUCCESS/REFUNDED/FAILED 保持既有 409/幂等。入口不调用 Order cancel/paid、User refund、TC/recover，不创建 16E cancel 台账，不生成新退款键；真正的 COMMITTED 取消与原 delivery 退款编排另行评审。

### CM-20261007-18 COMMITTED TCC 未发起交付窗口取消（16O）

16O 不新增公网路径，复用受保护的 `/{payNo}/mock-fail` 和 callback failure；仅在 `tcc-cancel.enabled=true` 且原 execution 为 `COMMITTED/COMMIT`、Pay 为 `PENDING/ORDER_SYNC_UNKNOWN`、delivery 尚未发起（无台账，或 `READY` 且 `attempts=0`、无 owner/lease）时，在短本地事务持久化 delivery `CANCEL_REQUESTED`，随后返回 `PAY_ORDER_SYNC_UNKNOWN`/202。该入口不调用 TC、Order、User，不写 Pay FAILED；已有 `CANCEL_REQUESTED` 只做 tuple 校验和幂等 202。已 claim、`ORDER_SYNC_UNKNOWN`、`REFUND_PENDING`、SUCCESS、REFUNDED 或与 16E `pay_tcc_cancel`/Legacy `pay_debit_intent` 冲突的记录保持原结果，不改向。

`CANCEL_REQUESTED` 一旦持久化，即使随后关闭 cancel flag，仍由既有 delivery scheduler 恢复；新 failure 请求在 flag 关闭时继续保持 16H 的 202。delivery worker 必须在 claim 后重读状态，CANCEL_REQUESTED 只能 GET Order 并在 `PENDING_PAYMENT` 调用同一 orderNo 的 cancel，禁止调用 paid。Order/资金未知或 `CANCELLED` 但 stock 未 `SUCCEEDED` 时保持 CANCEL_REQUESTED 并退避；仅 `CANCELLED + SUCCEEDED` 才进入原 `REFUND_PENDING` 和固定 `refund:<payNo>` 唯一退款，最终沿原 `REFUNDED`/已处理 409 语义。Order `PAID/COMPLETED`、stock `CONFLICT` 或 User/Pay 证据冲突不退款、不 paid、不 FAILED。callback failure 保持 `RECEIVED_FAILURE`，不因取消意图伪造完成。

取消请求与 delivery worker 统一使用 `pay_record → pay_tcc_delivery` 锁序；claim 先显式锁 Pay、delivery 再执行 delivery CAS，避免多表 JOIN UPDATE 隐式反向锁导致死锁。该范围不支持已发起交付、已成功、已退款或其他 COMMITTED 主动取消场景。

### CM-20261007-18 已 claim READY 的 COMMITTED TCC 取消（16P）

16P 将 16O 的取消窗口扩展到 delivery 仍为 `READY`、但 worker 已正常释放 lease（owner/lease_until 均为空）或 lease 已明确过期（owner 与 lease_until 同时存在且 `lease_until<=now`）的记录；保留 `attempts`、旧 owner 和旧 lease 时间，不接管有效 lease。owner/lease 只有一侧存在、有效 lease、`ORDER_SYNC_UNKNOWN`、`REFUND_PENDING`、SUCCESS、REFUNDED 或其他状态均拒绝改向。所有正常 TCC paid 必须先完成 READY→ORDER_SYNC_UNKNOWN 本地 stage，再调用 Order.paid；取消先获 Pay→delivery 锁时，旧 worker 的 READY stage 必须失败且不得 paid；旧 worker 先 stage 时，取消请求拒绝。

16P 继续复用 `/{payNo}/mock-fail` 与 callback failure，无新增 endpoint；接受取消只返回 `PAY_ORDER_SYNC_UNKNOWN`/202，不调用 TC/Order/User、不写 Pay FAILED。`CANCEL_REQUESTED` 由 delivery recovery 继续处理，即使 cancel flag 后续关闭；worker 只能调用 Order.cancel，待 `CANCELLED+stock SUCCEEDED` 后进入既有 `REFUND_PENDING`/固定 refund key/REFUNDED 链。该扩展不处理已取得 ORDER_SYNC_UNKNOWN、已退款、已成功或仍有有效 lease 的 COMMITTED 记录。

16P 补充约束：attempts 必须非空且不小于 0；owner 与 lease_until 只有同时为空，或 owner 去空白后非空且 lease_until 已到期时才可接受。接受改向仅以同一 DATETIME(3) 精度的 now 将 next_retry_at 提前为 LEAST(原值,now)，保留 attempts、旧 owner 和旧 expiry；已有 CANCEL_REQUESTED 重放不得修改 metadata。部分 lease、空白 owner、null/负 attempts 和有效 lease 均拒绝改向。Java 条件、CAS 与 LEAST 必须使用同一次 now。

### CM-20261007-18 有效 lease READY 的 COMMITTED TCC 取消（16Q）

16Q 在 16P 之上允许 READY delivery 仍持有有效 lease 时改向 `CANCEL_REQUESTED`；要求 attempts 非空且非负、owner 去空白后非空、lease_until 非空。取消 CAS 只改 state 并用同一 DATETIME(3) now 将 next_retry_at 提前为 LEAST，保留 attempts/owner/lease_until，不重领、不清除、不接管有效 lease；重复 CANCEL_REQUESTED 不改 metadata。取消先提交时旧 worker 的 READY stage 必须失败且不得 paid，旧 worker 可用自己的 token finish；新 worker 等 lease 释放或过期后再执行 cancel/退款。旧 worker 先 stage 则取消拒绝。所有正常 TCC paid 仍须先 READY→ORDER_SYNC_UNKNOWN 再 Order.paid；CANCELLED+stock SUCCEEDED 才进入唯一退款链，PAID/COMPLETED/stock CONFLICT 不退款、不 paid、不 FAILED。复用现有 failure/callback 返回 202，无新增 endpoint。

### CM-20261007-18 ORDER_SYNC_UNKNOWN TCC 取消重协调（16R）

16R 为已进入 `ORDER_SYNC_UNKNOWN` 的 COMMITTED TCC delivery 增加状态 `CANCEL_RECONCILING`，与严格 `CANCEL_REQUESTED` 分离。mock-fail/callback failure 在完整 TCC tuple、receipt/Fence、16E/Legacy gate 下，只在 Pay→delivery 锁内将 ORDER_SYNC_UNKNOWN 改为 CANCEL_RECONCILING，返回 `PAY_ORDER_SYNC_UNKNOWN`/202，不调用 TC/Order/User、不写 FAILED；READY 仍走 CANCEL_REQUESTED，REFUND_PENDING/SUCCESS/REFUNDED/BEGIN_UNKNOWN 不改向。Order cancel 与 paid 均只接受 PENDING_PAYMENT，并在同一本地事务以状态 CAS 和唯一 stock action 决定最终胜者。

### CM-20261007-20 Order 路由模式启动审计（阶段 A）

阶段 A 不新增业务 API。Order 的 `dynamic-monthly` 为默认运行模式；新增的只读运行信息通过既有 `/actuator/info`、按当前 Actuator exposure 与访问控制提供，字段限于 routing mode、清洗后的 profile、DataSource/事务管理器简单类型和 Shanghai 月份窗口，不输出 URL、账号、密码、连接属性或订单数据。激活未接入的 sharding profile、未知 routing mode、实际 ShardingDataSource 或 JdbcTemplate/TM 数据源不一致时在启动早期失败，不返回业务 HTTP 错误；未来 shadow/逻辑路由另行评审。

17A 的 `/actuator/info` 月份字段为 `expectedMonths`，复用 OrderTableInitializer 的目标窗口计算，只表示计划窗口，不查询或承诺物理表存在；可报告 observed/underlying/TM 的简单类型、清洗后的 profile 和 routing mode。profile/mode 不支持时由启动早期 BeanFactory 校验失败，不产生业务 HTTP 响应；runtime DataSource 绑定审计必须在订单 DDL 前完成，且不能通过新业务 endpoint 或 getConnection 探测绕过现有 Actuator 访问规则。

`CANCEL_RECONCILING` worker 只能在完整资金 proof 后 GET Order：PENDING_PAYMENT 时同态校验 lease 后 cancel；CANCELLED+stock SUCCEEDED 进入原 REFUND_PENDING/唯一 refund key；PAID/COMPLETED+stock SUCCEEDED 且无退款事实时进入 SUCCESS；Order/Stock/User/Pay 未知或冲突保持 CANCEL_RECONCILING 退避，不 paid、不 refund、不 FAILED。旧 paid RPC 无法撤回，旧 worker 不得再写 ORDER_SYNC_UNKNOWN 覆盖取消意图。callback failure 保持 RECEIVED_FAILURE，最终 SUCCESS/REFUNDED failure 继续 409。本轮不做售后 SUCCESS 取消或 BEGIN_UNKNOWN。

### CM-20261007-18 Legacy failure 结果语义（16I）

Legacy `mock-fail`/callback failure 先按 Pay 持久状态和 debit intent 完整 tuple 分类：SUCCESS/REFUNDED 返回既有 409；REFUND_PENDING 在 intent 缺失或完整匹配时返回 `PAY_REFUND_PENDING`/202 且不 RPC/写入，intent 矛盾返回 503；FAILED 在 intent 缺失或匹配 `FAILED` 时幂等 200 且不重复 Order.cancel；PENDING 且 intent 缺失/FAILED、以及 ORDER_SYNC_UNKNOWN 且 intent 完整匹配 FAILED 时保留既有 Order.cancel+markFailed 基本路径，写 FAILED 后返回/complete 200；PENDING intent 非 FAILED/未知、ORDER_SYNC_UNKNOWN intent 缺失/非 FAILED 返回 202。notifyFailure 原有 BizException 与其他异常 500 映射保持不变，不新增取消未知台账。intent mode/tuple 矛盾或未知状态不得 complete/猜测失败，按既有 503/202 处理。TCC 契约不变。

### CM-20261007-18 Legacy intent/failure 竞态（16J）

Legacy 首次 intent 与 failure 统一使用独立当前读和 Pay→intent 锁序：ensureIntent 先锁 Pay，再锁已有 intent；仅锁内 Pay `PENDING` 且无 intent 才插入 INIT。mock-fail 与 callback failure 委托共同的 `PayLegacyFailureService.fail(PayVO)`，在 `REQUIRES_NEW` 内锁内重读并返回最新 PayVO，不能使用外层旧快照授权取消；PayServiceImpl.fail 移除外层 `@Transactional`，只保留鉴权、模式读取和委托，TCC failure 的 `NOT_SUPPORTED` 保持不变。success 已提交 INIT 时 failure 不 cancel/FAILED，failure 先完成时 success 不新建扣款意图。PENDING 允许状态沿原 Order.cancel+markFailed，ORDER_SYNC_UNKNOWN 仅完整 FAILED intent 允许该路径；数据库锁等待沿既有 MySQL/事务机制，Order RPC 沿现有 Feign 超时和异常契约。

### CM-20261007-18 TCC inflight abort（16K）

复用 `cloudmall.pay.tcc-cancel.enabled=false`，仅对原 execution 已绑定 XID、decision NONE、Pay receipt/Fence TRYING 与 User live TRYING/no-ledger 完整匹配的 failure 请求，使用内部 `PayTccAbortProofVO` 竞争同一 execution 行锁 CAS `NONE→ROLLBACK_REQUESTED`；CAS 可将 `next_recovery_at` 提前至当前时间但保留 owner/lease/attempts。不 begin、不补 Try、不 recover、不调用 COMMIT/Order/User 写接口；初次/最终本地 TRYING 快照复用 Evidence 一致读，User live GET 位于两事务之间，最终不锁 receipt/Fence 形成反向锁序。COMMIT 先选则拒绝改向，ROLLBACK 先选则由既有 recovery 重试。TC 超时并发不在本入口判 FAILED，最终 Pay FAILED 仍由 16F/G 双端永久取消证明投影。
