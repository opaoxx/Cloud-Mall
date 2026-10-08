# CM-20260924-20：购物车 Add Compare-Write 评审

## 1. 评审结论与范围

结论：**GO，限定修复 Cart add 同一 Redis hash field 的并发读改写丢增量问题。**

当前 add 读取整张 hash 后在 Java 中合并并直接 write；两个 Cart 实例可能基于同一旧 PO 互相覆盖，首次 add 也可能覆盖同时创建的项。

本轮只改 add 的目标 SKU compare-write，不扩展其他 Cart 写路径、全 Cart 并发/ABA、客户端 timeout 幂等、持久清理重试或 CM15 持久性闭环。

## 2. Compare-Write 契约

### 2.1 前置事实与快照

add 保留原 Product currentSku、BU 数量、BV/BW/BY 校验。新增 private readMap(cartKey, rawMap) 等价重载：在同一次 Redis HGETALL 结果上完成 BW 全 hash identity 校验，并保留目标 field 的原始 JSON bytes；不得通过 Java PO 重新序列化来生成 expected bytes。

本次候选计算使用 Java 现有 ObjectMapper 序列化完整 PO，保持 Long、金额字符串、时间、null 和 JSON 字段原语义。首次目标 field 不存在时使用 expectedAbsent；存在时使用首次读取的原始 bytes。

### 2.2 Lua 条件写

仅 add 使用私有静态 Lua script，不使用 cjson 解析业务 JSON：

- expectedAbsent：仅当 HGET field 仍不存在时 HSET 新 JSON 并 PEXPIRE 原 30 天 TTL；
- expectedBytes：仅当 HGET field bytes 与原始 expected bytes 精确相等时 HSET 新 JSON 并 PEXPIRE；
- 条件不满足返回 0，不写 field、不刷新 TTL；
- 成功返回 1；null、非 0、非 1 或 Redis 执行异常均视为结果未知，不能自动重试或猜测是否已经应用。

Redis script 参数只承载 field、expected marker/bytes、新 JSON 和 TTL，不用 cjson 改写业务数字或时间。

### 2.3 重试预算

最多尝试 3 轮：每轮 compare-write 返回 0 且已确认未写入时，重新 HGETALL、执行 BW 全 hash identity 校验、读取当前目标 PO、重新计算数量和摘要、保留新的 expected bytes，再执行下一轮 compare-write。

三轮均发生已确认的 compare mismatch 时，返回既有 COMMON_CONFLICT/409；不写、不刷新 TTL、不调用额外 Product/Cart RPC。脚本返回 null/非0/非1的结果未知沿既有 INTERNAL/500“结果未知”语义，Redis DataAccessException 沿既有 503；二者均不自动重试或猜测是否已写。

## 3. 其他边界

- 合法合并继续 BU/BV/BW/BY 校验和原响应结构；数量溢出/坏源仍沿 BU 400/500，不进入脚本写。
- 其他 add 失败、Product 读取、Redis 错误、空/非正存量、字段身份失败不猜测写入结果。
- 其他 Cart write 路径继续使用原 write；可抽取 serializeCartItem 只为复用序列化，不改变其他路径行为。
- 不新增 DTO、模块、schema、Redis key、API 字段或错误码；不承诺客户端 timeout 后的 exactly-once。

## 4. API 契约补充

购物车 add 在三轮已确认并发 compare-write mismatch 后返回既有 COMMON_CONFLICT/409；脚本结果未知沿既有 INTERNAL/500，Redis 依赖故障沿既有 503，均不自动重试或猜测写入。请求/响应字段保持不变。

## 5. 验收矩阵

1. 真实 Redis DB15、随机 owner key、两个 Cart 实例和屏障使两线程首次 HGETALL 读到同一事实：缺行首次 CAS 只有一个直接成功，另一方重读后合并重试；竞争不再持续时两请求均成功，最终数量为两次增量之和。若三轮耗尽才返回 409；MAX-1 并发 +1/+1 时一方成功到 MAX，另一方重读后按 BU 溢出返回 400，不产生负数/覆盖。
2. compare mismatch 预算最多 3 轮；每个 0 结果确认未写才可重读，不等待一分钟、不重试 unknown/error/null/non1 script 结果。
3. Redis 故障注入验证 hash/TTL/JSON 字段原语义；MAX 数量/金额 Long、BW identity、BU/BV/BY、Product 和 Cart 依赖边界回归。
4. 首次合法 add 的 HSET 脚本参数保存原始 JSON bytes；无 FLUSH、不改其他 key；回归现有 Cart IT/unit 和 API 契约。

## 6. 明确不承诺

本轮只解决 add 目标 field 的有限 compare-write 竞争，不提供全 Cart 并发/ABA、客户端超时幂等、持久清理重试或 CM15 持久性闭环。
