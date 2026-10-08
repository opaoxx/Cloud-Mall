# CM-20260924-20：订单购物车清理前置快照与购买数量证明评审

## 1. 结论与范围

结论：**GO，作为 17CD 的最小时序扩展。**

17CD 在订单创建末尾采样会把采样期间加入购物车的最新项误认为下单原项，也无法区分购买 2 件与购物车已有 5 件。本批只把只读快照采样前移到订单创建的外部副作用和本地持久化之前，并把快照中的数量绑定到本次购买请求。afterCommit 仍沿 17CD 的原 DTO 条件删除。

不改变订单/库存/意图/价格/状态、Stock 请求原 `items`、金额计算、事务、消息和公开 API；不处理采样前用户视图、同字节 ABA、客户端超时或持久清理重试。

## 2. 请求预检与购买数量

在任何地址查询、Product RPC、库存预扣、意图写入或订单本地写入前，先执行本批预检：

- 保留原请求非空和既有错误码；`items` 中每个 `skuId` 必须非空，`quantity` 必须为正 `int`。不满足时沿既有 400 返回，不访问 Cart/Product/Stock，也不写订单事实。
- 仅为快照授权聚合同一 SKU 的购买数量，使用 `long` 求和；溢出 `Integer.MAX_VALUE` 时该 SKU 不取得可删除证明并保留购物车项，不新增订单数量上限或改变后续原始请求语义。
- 聚合只用于证明，不修改传给 Product、Stock 或订单持久化的原始 `items`、顺序、数量和金额。
- 已有订单幂等投影命中返回路径保持原顺序，直接返回且不执行本批 Cart 快照 GET/POST。若该投影缺失而进入既有 `createIntent=false` 与 `findByUserAndKey` 的 COMMITTED/alias fallback，则保持原业务查询顺序，允许入口执行前置只读 GET；该 fallback 必须仍然零 POST/DELETE、零 Stock 调用，且不改变订单、intent、Cart 或其他 fixture 事实。不得新增查询来把该 fallback 提前成另一种幂等路径。

## 3. 前置 Cart 快照

预检通过后，在 `findAddress`、Product、库存预扣、意图写入和订单本地写入之前，对去重 SKU 每项最多 GET 一次 17CD snapshot。只有存在实际事务和 synchronization 上下文时才采样；无实际上下文只告警并跳过，不 GET/POST。

每个 GET 必须同时满足：

1. response 非空且 code=`0`，DTO 非空，DTO `skuId` 与请求 SKU 精确相等；
2. `expectedJson` 是完整 JSON object，原始 JSON 的 `skuId` 为可精确表示的整数并与 SKU 相等；
3. 原始 JSON 的 `quantity` 为整数、可表示为正 `int`，且等于该 SKU 本次购买数量的 `long` 聚合值。

缺项、数量缺失/非正/超范围、字符串或小数数量、SKU 身份不符、证明解析失败、聚合溢出均不授权删除，只告警并保留该项。单项 Runtime/无证明不得阻断其他 SKU；Error 继续传播。GET 仍只读，不调用 Product、不写 Redis、不刷新 TTL。

前置采样允许后续地址、商品、库存或数据库操作失败；此时事务回滚，绝不 POST/DELETE Cart。采样成功后保存 `List.copyOf` 不可变 DTO 列表，并在订单创建末尾只注册 17CD afterCommit 回调；回调不再 GET 或重建快照。

## 4. 提交后清理

afterCommit 仅使用原 userId 和原 DTO 对每个 SKU 调用一次 cleanup POST：

- code=`0` 且 data 为 true/false 均视为已确认处理；false 表示缺失或发生变化，正常跳过；
- null、非 0、Runtime 逐项告警并继续，不重试、不调用旧 DELETE、不反向补偿；Error 继续传播；
- 未提交、回滚、无实际事务或无 synchronization 时不 POST。

## 5. 兼容与验收边界

- 不新增 DTO、Redis Key、Feign 字段、数据库表、SQL、消息或状态；复用 17CD `CartCleanupSnapshotDTO` 和 GET/POST 契约。
- 不改变 Order 公共 JSON、错误码、原始 Stock/Order 流程、金额、时间和事务语义；数量证明失败只影响 Cart 清理授权。
- Cart Redis、Lua CAS、身份拦截器和 17CD 失败隔离规则保持不变。
- Cart Redis IT 验证数量/原 JSON proof；Order MySQL/事务测试使用 Cart 替身验证采样顺序、回滚和提交；Feign/DTO/JSON 合同独立验证，不宣称真实跨 HTTP 集成。
- 必须覆盖：Stock reserve 前后 Cart 变化仍使用旧 proof；购物车数量 5、购买数量 2 不删除；重复 SKU 聚合匹配时允许清理；缺项/坏数量/聚合溢出不授权；采样后新增项不产生晚期 GET；订单幂等投影命中零 GET/POST，投影缺失的 intent fallback 允许只读 GET但严格零 POST/DELETE/Stock且事实不变；后续失败和 rollback 零 POST/DELETE；原订单精度与 checksum 回归。

## 6. 明确不承诺

本批不保护前置采样以前的购物车变化，不识别同字节 ABA，不提供 Cart 持久重试或客户端 exactly-once，也不把只读采样失败转换为订单失败。
