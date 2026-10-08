# CM-20260924-20：购物车数量合并边界评审

## 1. 评审结论与范围

结论：**GO，限定修复 `CartServiceImpl.add` 存量项合并的 int 溢出和非正存量数量污染。**

当前合并分支执行 `existing.quantity += request.quantity`。两个正 int 相加可能溢出为负数；存量 Redis JSON 中已有 0/负数量时也会继续参与追加，并写回和刷新 TTL。

本轮只保护 add 的合并写入，不修改 Cart API 字段、Redis key/JSON、Lua、锁、schema、其他写路径、并发 RMW、Cart 持久清理重试或 CM15 持久性闭环。

## 2. add 合并契约

保留现有 request `quantity < 1` 的 INVALID/400 校验和新项路径。读取存量 item 后、任何 Redis 写入/TTL 刷新前：

1. 若存量 `item.quantity < 1`，抛既有 INTERNAL/500 数据损坏错误；不自动修复、不合并、不写 hash、不刷新 TTL；
2. 使用 `long mergedQuantity = (long) item.quantity + request.quantity`；
3. 若 mergedQuantity > `Integer.MAX_VALUE`，抛既有 INVALID/400 数量错误；不写 hash、不刷新 TTL；
4. 合法结果转换为 int，继续原商品摘要更新、同一 PO 写入和 response 结构。

不新增业务数量上限；request 的正数 int、首次插入的 `Integer.MAX_VALUE` 和合法合并值均按原 API/JSON 语义处理。不存在 trim、归一化或 Redis repair。

## 3. 保持与排除边界

- 新 item、PUT update、checked、preview、refresh 和其他写路径保持现有行为；PUT 的原 quantity<1 仍 INVALID/400。
- 合法合并仍调用原商品查询、Redis hash put 和 TTL 刷新；拒绝路径不调用 write，因此不刷新 TTL。
- 不处理并发读改写原子性；不新增 Lua、分布式锁或 CAS。
- 不改 Redis key、JSON 字段、Cart Feign/API、商品错误映射、schema 或持久清理重试。
- Redis 故障仍沿原 503/依赖错误语义传播，不吞异常。

## 4. 验收矩阵

1. 真实 Redis 隔离库验证存量数量 0、-1、Integer.MIN_VALUE 时 add 正数：返回 500，hash 原值保持，TTL 不刷新；请求 quantity<1 仍 400。
2. 验证存量正数 + request 正数超过 INT_MAX：返回 INVALID/400，hash、摘要、TTL 原值保持；不调用 HSET/expire。
3. 验证合法 1..INT_MAX 合并、首次 INT_MAX、Redis JSON/摘要和 TTL 正常；API response 字段不变。
4. Redis/商品依赖故障保持原 503/错误边界；并发 RMW、PUT/其他路径、Cart afterCommit 清理和 CM15 持久性问题不在本批。
5. 测试使用真实 Redis 隔离 key/user，不 FLUSH 或修改其他 key；fixture 创建/owner 清理不影响业务容器。

## 5. 明确不承诺

本轮只防止 Cart add 合并的非正存量和 int 溢出，不提供并发合并原子性、全量购物车数据修复、持久重试或其他数量字段统一校验。
