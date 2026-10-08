# CM-20260924-20：购物车 Preview 数量数据完整性评审

## 1. 评审结论与范围

结论：**GO，限定修复 `CartServiceImpl.preview` 对已选购物车项非正 quantity 缺少预检的问题。**

BU 只保护 add 合并分支，历史 Redis 项仍可能保存 quantity=0/负数。preview 当前筛出 selected/checked 项后直接调用 Product、回写摘要/TTL并计算金额，可能产生负/零金额或刷新损坏项。

本轮只增加 preview 选中集的数量预检，不修改历史数据、readMap 身份门、add/PUT、Redis key/JSON/Lua、Product/BV、并发 RMW、持久清理重试或其他 Cart 机制。

## 2. Preview 预检契约

保留现有 selected/checked 筛选后，在任何 Product 查询、Redis write/expire、金额计算前检查本次 `items` 全集：

- 任一 selected/checked item.quantity < 1：抛既有 INTERNAL/500“购物车数量数据损坏”；
- 预检失败时不调用 Product、不写/刷新 Redis、不计算金额；
- 未被选中的坏 quantity 不参与本次预检，不阻断合法 selected subtotal；
- 空选中集保持原空结果语义，不发 Product 查询。

预检通过后，Product 失败、摘要刷新、invalidItems 和金额汇总沿现有逐项 preview 语义；本批不把整个 preview 变成事务，也不回滚前序合法 SKU 的已有写入。

## 3. 保持与排除边界

- BU add 的非正存量/溢出门、PUT 正数校验和 BV Product response identity 保持。
- quantity=Integer.MAX_VALUE 是合法值；不新增任意业务数量上限或错误码，不改变 JSON/API 字段。
- Redis 503、Product 404/5xx、field/PO identity、Cart key、TTL、Lua 和并发 RMW 语义保持。
- 不自动修复历史坏 quantity；PUT 可按原接口写入合法正数后再 preview。
- 不扩展到 all/refresh 或 Cart 持久重试、CM15 闭环。

## 4. API 契约补充

`POST /api/cart/settlement/preview` 在 selected/checked 集合包含非正存量 quantity 时返回既有 INTERNAL/500 数据损坏错误，不写/刷新当前购物车项，也不调用商品服务；未选中的坏项不影响本次预览。该条款不改变请求字段、响应结构或 Product 失效项语义。

## 5. 验收矩阵

1. 真实 Redis 隔离库覆盖 selected 明确选择和默认 checked 选择下 quantity=0/-1/MIN：返回 500，hash/PTTL 不变，Product 调用为 0。
2. mixed 合法 SKU 7 + 已选坏 SKU 8：预检先失败，不先刷新 SKU 7；未选坏 SKU 不阻断合法 subtotal；PUT 将坏 quantity 改为正数后 preview 可恢复。
3. quantity MAX、空 selected、selection/checked 组合、金额字符串和 BU/BV/Feign/Redis503 原语义回归。
4. unit 验证多行 selected 预检零 Product/HSET/expire；缺失 SKU/Redis 故障保持旧测试分类；不宣称整个 preview 事务化。

## 6. 明确不承诺

本轮只阻止 selected/checked preview 使用非正数量，不清洗未选历史数据，不提供全局 Cart 数据修复、并发原子性、持久重试或整批事务回滚。
