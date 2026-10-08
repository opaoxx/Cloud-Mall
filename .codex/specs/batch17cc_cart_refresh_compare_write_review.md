# CM-20260924-20：购物车 All/Preview 摘要 Compare-Write 评审

## 1. 评审结论与范围

结论：**GO，限定修复 Cart all/refreshItems 与 preview 摘要刷新使用旧 PO 直接 write、覆盖并发最新字段的问题。**

当前 all/refresh 和 preview 基于初读 PO 更新商品摘要、价格后直接写回，可能覆盖最新 quantity/checked/add，或在读后删除/重加时复活旧项。

本轮只改 all/refresh 与 preview 的摘要 compare-write，不改 add/PUT/checked/clearChecked/显式 delete/订单 afterCommit、全 Cart 并发、ABA、客户端 timeout、持久清理或 CM15。

## 2. 读取与摘要刷新契约

### 2.1 同次 raw snapshot

all/refreshItems 与 preview 使用同次 HGETALL，复用 BW 全 hash identity 校验，并保留每个目标 field 的原始 JSON bytes。preview 继续先按 selected/checked 选择并执行 BX quantity 全集预检；不得用重序列化旧 PO 伪造 expected bytes。

### 2.2 每项 Product 和局部更新

每项只调用一次既有 BV/BY currentSku；成功事实只更新 productId、productName、unitPrice，保留该快照中的 quantity、checked、addedAt 及其他字段。目标 field 必须在初读存在，compare-write 不使用 expectedAbsent；读后删除、缺失、重加或 bytes 变化均视为冲突。

金额和 response 语义保持：preview 仅在该项 CAS 成功后将当前合法价格乘 quantity 加入 total/payAmount。

## 3. Compare-write 结果边界

- 每项只执行一次 BZ raw JSON compare-write；成功才 HSET + PEXPIRE 30 天。
- 返回 0 代表已确认冲突：立即抛既有 COMMON_CONFLICT/409，停止本次 all/preview 后续项，不重读、不自动合并、不刷新 TTL、不补偿前序成功项。
- script null/非0/非1 结果未知：返回既有 INTERNAL/500，不重试、不猜测；Redis DataAccessException 沿既有 503。
- Product 的原 404/其他 BizException<500 仍按 all/preview 原 invalidItems/失效语义处理；CAS、序列化和金额语法异常在该 catch 外按 409/500 传播，不能被吞成 invalidItems。
- 前序项已成功刷新不整批回滚，后续项停止。

## 4. API 契约补充

购物车 all/preview 摘要刷新在 compare-write 已确认冲突时返回 COMMON_CONFLICT/409；脚本结果未知返回 INTERNAL/500，Redis 依赖故障保持既有 503。Product 原 404/失效项响应语义保持，请求和响应字段不变。

## 5. 保持与排除边界

- 保留 BW hash identity、BX quantity、BV/BY 商品响应、BU 数量、金额字符串、TTL、Redis key/JSON 和 Feign 字段。
- 不新增 DTO/模块/SQL/schema，不改其他 write 路径或 refresh 的全量事务语义。
- 不保证 CAS 之后的并发变更、同字节 ABA、客户端 timeout、历史坏项或持久清理闭环。

## 6. 验收矩阵

1. 真实 Redis DB15：Product 读取期间另一 Cart update/checked/add/delete/re-add，原 bytes/最新 quantity/checked 保持；冲突返回 409，不续 TTL、不复活、不继续后项。
2. raw metadata、Long productId、金额字符串、原选择集合和合法商品响应保持；preview 金额只计入 CAS 成功项。
3. 每项一次 CAS、0/unknown/503/Error 边界：前项成功后后项冲突不补偿、不发第三次 Product/CAS；原 Product 404/失效项仍可见且保持原金额语义。
4. 回归 BU/BV/BW/BZ/CA、Cart API/JSON/key、订单 afterCommit 和 Redis fixture；Stock/Product 使用替身时不宣称真实网络事故。
