# CM-20260924-20：购物车 Checked Compare-Delete 评审

## 1. 评审结论与范围

结论：**GO，限定修复 Cart clearChecked 读后无条件 HDEL 导致并发变更被误删的问题。**

当前 clearChecked 读取 checked 项后直接按 SKU 删除；读后 unchecked、PUT/add 更新或重新加入可能被旧快照误删。

本轮只改 clearChecked 的 compare-delete，不扩展显式 delete、add/PUT/checked、refresh/preview、ABA、客户 timeout、全 hash 原子性或 CM15 持久清理闭环。

## 2. Compare-Delete 契约

### 2.1 快照与候选

clearChecked 使用同次 HGETALL，复用 BW 全 hash identity 校验并保存每个候选 field 的原始 JSON bytes。只把初读 PO.checked 精确为 true 的项作为删除候选；unchecked 项不删除。

若整次 hash 身份校验失败，沿原数据损坏 500 返回，零删除；不重读、不修复、不继续其他候选。

### 2.2 Lua 条件删除

新增私有 static compare-delete script，不使用 cjson：

- 只有当前 field bytes 与初读 expected bytes 精确相等时 HDEL，返回 1；
- field 缺失或 bytes mismatch 返回 0，不 HDEL、不续 TTL、不重读、不追删并发变更；
- 脚本不调用 PEXPIRE，最后 field 被删空时沿 Redis 原生行为移除 hash key；
- null/非0/非1 脚本结果沿既有 INTERNAL/500“结果未知”，DataAccessException 沿既有 503，均不重试；Error 不吞。

已删除前序项不因后续候选失败回滚；每个候选最多一次 compare-delete。

## 3. 保持与排除边界

- clearChecked 成功响应继续 null；compare mismatch 也不新增 409，保持原成功响应。
- 显式 delete 继续按请求 field 删除，不经过 compare-delete；add/PUT/checked 使用既有 BZ/CA；refresh/preview 不改。
- 保持 Redis key/JSON/HTTP/Feign 字段、金额时间、BU/BV/BW/BY 和错误分类。
- 不提供全 hash 原子清理、ABA 同字节 delete-readd 识别、客户 timeout exactly-once 或持久清理重试。

## 4. API 契约补充

clearChecked 对已读取的 checked 项只在原 JSON bytes 未变化时删除；并发变更保留最新项，compare mismatch 仍返回原成功 null。脚本结果未知返回 INTERNAL/500，Redis 依赖故障保持既有 503，不能自动重试或猜测删除结果。

## 5. 验收矩阵

1. 真实 Redis DB15、独占 owner key：初读后另一 Cart 实例将项 unchecked/改 quantity/add/重新加入，compare-delete 返回 0 并保留最新 field/TTL。
2. 真正 DELETE 后重加且 addedAt/JSON bytes 不同，旧 clearChecked 不删除新项；未变 checked=true 项正常删除，unchecked 项保留，空 key 依 Redis 原生行为处理。
3. 整 hash 坏 identity 阻止所有删除；unit 覆盖非 String/raw、mismatch 一次不重读、unknown/503 不重试、Error 不吞和无 Product 调用。
4. 回归 CA/BU/BV/BW/BZ、Cart API/JSON/key、订单 afterCommit 清理、Redis fixture 清理；不宣称全 Cart 并发或持久性闭环。

## 6. 明确不承诺

本轮只保护 clearChecked 的有限 compare-delete，不修复其他删除入口、ABA 同字节问题、客户端 timeout、并发全局原子性或购物车持久清理重试。
