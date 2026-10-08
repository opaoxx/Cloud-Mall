# CM-20260924-20：购物车 Redis Hash 字段身份评审

## 1. 评审结论与范围

结论：**GO，限定修复 Cart Redis hash field 与 JSON PO.skuId 不一致导致错 SKU 读写的问题。**

当前 `readMap` 只把 field 转成字符串并解析 PO，没有核对 field 与 PO.skuId。损坏行可能以 field=7、PO.skuId=8 进入 update/checked/all/preview/clearChecked，进而写/删 SKU 8、污染价格金额或清理错误项。

本轮只保护 `readMap` 读出的 hash identity，不修改 Redis key/JSON、Lua、API 字段、BU 数量、BV 商品门、并发 RMW、持久清理重试或其他 Cart 机制。

## 2. readMap 行身份契约

每条 Redis hash entry 完成 JSON 解析后，必须同时满足：

1. parsed PO 非 null；
2. `item.skuId` 非 null；
3. Redis field 原始对象必须是 `String`；
4. `Objects.equals(field, String.valueOf(item.skuId))` 精确相等。

不接受非 String field、null PO、null skuId、前导 0、尾随空格、别名 field、field/PO 别 SKU 或其他转换形式；不 parse 数字、不 trim、不归一化、不改历史 hash。

身份失败沿原 `BizException(INTERNAL, “购物车数据损坏”, 500)` 路径抛出。`readMap` 必须在完整校验所有 entries 后返回；任何失败不得进入下游 write、expire、delete、错误 SKU Product 查询或金额计算。add 当前会在 readMap 前按请求 SKU 调用一次 currentSku，该既有顺序本批不撤回或重排；其他读路径的校验失败不得调用 Product。

显式 `DELETE /api/cart/items/{skuId}` 继续按请求 field 删除，不读取坏 JSON、不经过 readMap；本轮保留该清理坏项能力。

## 3. 调用路径边界

- add/update/checked/all/preview/clearChecked 继续复用 readMap/readItems，因此坏 hash 行统一 500 且不污染其他 SKU。
- 合法 canonical field（`String.valueOf(item.skuId)`）保持原 CRUD、商品刷新、摘要/金额和 TTL 语义。
- 真实 Redis 故障继续原 503/依赖错误；JSON 解析坏数据继续原 500。
- 不保证坏行之前已完成的历史写可撤回，不对其他 hash/key 做全局扫描或 repair。

## 4. 兼容与排除边界

- 不新增字段、API、错误码、Redis Lua、锁、schema 或购物车持久化机制。
- 不改变 Product、BU/BV、Cart afterCommit、并发 RMW、checked/preview 其他规则。
- 不校验 PO 的 productId/name/price/snapshot，不把 identity 失败转换成普通失效项。
- API 响应字段和正常 JSON 结构保持；显式 delete 的请求语义保持。

## 5. 验收矩阵

1. 真实 Redis 隔离库覆盖 field=7/PO.skuId=8、null PO/null skuId、前导 0/尾空格 String field 与 healthy canonical 行；add/update/checked/all/preview/clearChecked 均 500，整 hash/PTTL 不变，无错误 SKU 调用。StringRedisTemplate 正常读取的非 String field 不作为真实 Redis 场景伪造，由 unit raw seam 覆盖；add 允许保留一次原请求 SKU 的 Product 查询。
2. canonical field 的正常 CRUD/preview 保持；显式 delete 可删除指定坏 field，不影响 healthy SKU 8。
3. unit raw seam 验证非 String field、解析坏数据、Redis 故障的分类和无 HSET/HDEL/expire/其他 SKU 调用。
4. 回归 BU 数量、BV Product identity、Cart API/JSON/key、afterCommit 清理、owner fixture 清理；不宣称并发原子或持久性闭环。

## 6. 明确不承诺

本轮只校验 Cart Redis hash field 与 PO.skuId 的局部身份，不修历史坏 hash、不提供全量数据迁移、并发 RMW、持久重试或其他 Cart 事实一致性闭环。
