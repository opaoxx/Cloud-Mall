# CM-20260924-20：购物车商品响应身份评审

## 1. 评审结论与范围

结论：**GO，限定修复 `CartServiceImpl.currentSku` 接受错误商品响应并回写购物车/结算金额的问题。**

当前只检查 response/data 非 null；非 0 code 携带 data，或 data.skuId 与请求 SKU 不一致/缺失时仍可能被用于 add 合并、all 刷新和 preview 价格/摘要回写。

本轮只保护商品响应的 code 与 SKU 身份，不修改 Product Feign/API/JSON、Cart key、金额时间、BU 数量门、并发 RMW、Redis Lua、购物车持久清理或其他服务。

## 2. currentSku 证明契约

在既有 response/data null 的 404 门之后，`currentSku(requestSkuId)` 仅当同时满足以下条件返回 data：

1. response.code 精确为 `0`；
2. request SKU ID 非 null；
3. `Objects.equals(requestSkuId, response.data.skuId())` 精确相等。

以下情况均抛既有 INTERNAL/500（不新增错误码）：

- response code 非 0 且 data 存在；
- data.skuId 为 null；
- data.skuId 与请求 SKU 不一致，包括大小写/重音/尾空格不适用于 Long，但数值别号/错 SKU 必须拒绝；
- 其他不可信响应结构。

response 为 null 或 data 为 null 继续既有 NOT_FOUND/404；Feign/依赖异常沿既有异常传播。

## 3. 调用路径边界

- add：不可信 Product 响应在 Redis hash put/expire 前失败，不改变数量、摘要或 TTL。
- all/refresh：错误响应按既有 >=500 传播，不把不可信 SKU 当作普通失效项吞掉；此前已成功回写的其他 SKU 不在本批回滚。
- preview：错误响应按既有 >=500 传播，不把错误价格加入金额汇总；前序合法 SKU 的已有回写不在本批事务化回滚。
- 合法 code=0 + exact skuId 继续原 productId/name/price/snapshot 更新和 response 结构。

## 4. 兼容与排除边界

- 不校验 productId、productName、unitPrice、skuSnapshot 的其他字段，不新增 DTO、错误码、API 字段或 JSON 结构。
- 不改变 Cart/Redis key、TTL、Lua、依赖 503、BU 数量门、PUT/checked/preview 其他规则。
- 不把本批描述为整批 refresh 事务回滚，不修历史错误摘要，不承诺 Product/Cart 真实网络或持久性闭环。

## 5. 验收矩阵

1. 真实 Redis 隔离 key/user + Product 替身覆盖 add/all/preview 的 code=0 错 SKU/null skuId 与非 0 code data：均 500，不写 HSET/expire，不污染价格/金额。
2. response null/data null/404 保持原 404；Feign 异常保持原传播/依赖语义。
3. 正确 code=0 + exact skuId 的 add、all、preview 保持摘要、金额、Redis hash/TTL 和响应结构；前序合法 SKU 已写入时后续错误不声称回滚。
4. 回归 BU 数量、Cart API/JSON/key、订单 afterCommit 清理、原 fixture owner 清理；不 FLUSH 或修改其他 Redis key。

## 6. 明确不承诺

本轮只阻止不可信 Product 响应污染当前 SKU 的 Cart 事实，不提供全量购物车事务、Product 服务校验、并发 RMW 或持久清理重试闭环。
