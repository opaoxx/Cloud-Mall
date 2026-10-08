# CM-20260924-20：订单购物车清理快照评审

## 1. 评审结论与范围

结论：**GO，限定保护普通订单创建提交后 Cart 清理免受捕获快照之后的并发变更影响。**

BT 已将 Cart 删除延迟到订单事务 afterCommit，但仍只捕获 userId/SKU，回调无条件 delete，可能误删下单快照之后被 unchecked、PUT、add 或重新加入的购物车项。

本轮只增加 Order/Cart 之间的原始 JSON 快照和提交后条件删除契约；不覆盖捕获前的购物车生命周期、同字节 ABA、客户端 timeout、Cart 持久清理重试或 CM15 闭环。

## 2. 跨服务 DTO 与 Cart 内部契约

在 Cart 与 Order 各自模块增加镜像 immutable record：

CartCleanupSnapshotDTO(Long skuId, String expectedJson)

不嵌套 DTO/VO/PO，不改变现有公开业务响应字段。

### 2.1 Snapshot GET

内部接口：

GET /api/cart/items/{skuId}/cleanup-snapshot

携带既有 X-User-Id，返回 ApiResponse<CartCleanupSnapshotDTO>：

- 同次 HGETALL 复用 BW 全 hash identity 校验并读取目标 field 原始 JSON；
- 缺项返回 code=0、data=null；
- 存在且 field/PO.skuId 精确绑定时返回不可变 DTO，expectedJson 保留原始 JSON 文本；
- 不调用 Product，不写 Redis，不刷新 TTL；
- 坏 hash/身份返回既有 INTERNAL/500，Redis 依赖故障保持 503。

### 2.2 Snapshot conditional cleanup

内部接口：

POST /api/cart/items/{skuId}/cleanup

携带 X-User-Id，body 为 CartCleanupSnapshotDTO。Cart 在执行 compare-delete 前校验：

- body、expectedJson 非 null/blank；
- body.skuId 与 path 精确相等；
- expectedJson 可解析为 object，原始 PO.skuId 为数值且精确匹配 path；
- 不从失配响应拼接 proof，不 trim/归一化/数值别名转换。

校验失败返回既有 INVALID/400，零 Redis 写；通过后执行一次原始 bytes CAS delete：

- HGET 等于 expectedJson bytes 才 HDEL，返回 code=0、data=true；
- field 缺失或 bytes mismatch 返回 code=0、data=false；不重读、不无条件 fallback、不续 TTL；
- unknown/null/non1 返回 INTERNAL/500，Redis DataAccessException 返回 503，Error 不吞。

显式 DELETE /api/cart/items/{skuId} 保持原契约，不改其他 Cart writer。

## 3. Order 预采样与 afterCommit 编排

保持 BT 的捕获位置：普通订单本地订单/intent COMMITTED 成功后、outer 事务提交前，在实际 transaction/synchronization 上下文中立即逐 SKU GET snapshot 并验证。事务未实际 active 或 synchronization 不 active 时不 GET/POST，只告警跳过。

预采样阶段使用原 userId 和去重 SKU 快照：

1. GET code 非 0、data null、SKU 不精确、expectedJson proof 非法或 Runtime 失败时逐项告警并跳过；Error 不吞；
2. 合法 DTO 由 `List.copyOf` 等不可变快照保存；
3. 注册 afterCommit 回调后，回调只 POST 原 DTO 一次，不再 GET，不从 Cart 重建或替换快照；
4. POST code=0（data true/false）均视为本次调用完成，false 表示已缺失/已变化，正常跳过；
5. POST null/非 0/Runtime 逐项告警继续后续 DTO；Error 不吞；不调用旧 deleteItem fallback，不反补偿已提交订单/库存，不重试。

捕获位点之后发生的 Cart 变更由 bytes CAS 保护；本批不保证捕获位点之前的购物车全生命周期、同字节 ABA 或持久重试。

## 4. 兼容与排除边界

- 不改变 Order 正常 JSON、状态、价格、订单/intent/outbox/Stock、事务和 afterCommit 返回语义。
- 不新增模块间公共业务 API；新增接口仅内部 Feign/Cart 契约，必须有标准 JavaDoc 和原 X-User-Id 传递。
- 不改 Cart Redis key、JSON 字段、TTL、Lua compare-write/delete、BU/BV/BW/BX/BZ/CA 行为。
- 不引入客户端字段、数据库表、消息、迁移或持久清理重试；不承诺捕获前全生命周期或同字节 ABA 的检测。
- 不宣称真实跨 HTTP 集成：Cart Redis IT、Order MySQL/事务 IT、Feign/DTO/JSON 反射合同分别验证，不建立模块间新测试依赖。
- Order 的 Feign 身份拦截器必须尊重方法参数显式身份：对大小写不敏感地检测 `RequestTemplate` 已有 `X-User-Id` 时，保留该显式值，不再追加 `AuthContext` 的晚期 userId；已有 `X-User-Role` 时同样不追加晚期 role。没有显式身份时，继续注入当前线程上下文的 userId/role；上下文为空时不注入。该规则只约束 Order 配置，不改变 Cart 鉴权、Gateway 或公开 API。

## 5. API 契约补充

新增的内部 cleanup snapshot GET/POST 供 Order 创建成功末尾预采样和 afterCommit 条件清理使用：GET 缺项为 code=0/data=null；POST 精确快照删除返回 code=0/data=true/false；校验失败 400、结果未知 500、Redis 依赖故障 503。现有公开 Cart DELETE、订单 JSON/错误码和其他接口保持不变。

## 6. 验收矩阵

1. Cart 真实 Redis：预采样后另一实例 unchecked/PUT/add/metadata 更新、删除重加或同字段变化时 POST false/保留最新项；未变化 snapshot true；空 hash 后自然消失；无 TTL 刷新。
2. Order 真实 MySQL/实际事务 + Cart 替身：commit 前允许预采样 GET 但零 POST/DELETE；rollback 可完成只读 GET但零 POST/DELETE；commit 后不再 GET，只使用原 user/DTO 去重快照逐项 POST，前项失败不阻断后项，Error 保持传播。
3. 验证坏 DTO/proof 不 fallback、不拼接、不二次请求；原订单/intent/status/afterCommit 语义保持。
4. Feign 方法、路径、header、DTO JSON 字段通过反射/合同测试；独立 Cart Redis 与 Order MySQL fixture 可清理，原 checksum 保持。
5. Feign 身份透传单测：显式 `X-User-Id=7` 与晚期上下文 `99` 时最终仅保留 `7`，显式身份存在时不追加晚期 role；无显式身份时保持原上下文注入，空上下文不注入。验证请求模板 header 不形成多值或合并值。

## 7. 明确不承诺

本轮只保护捕获后购物车变更，不提供捕获前全生命周期快照、同字节 ABA 检测、Cart 持久重试、客户端 timeout exactly-once 或真实跨 HTTP 故障演练。
