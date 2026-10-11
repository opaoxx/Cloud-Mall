# CM-18 16T52：秒杀恢复 final write 受理来源 fence

## 1. 结论与范围

本切片 GO，补足 T44 claim source gate 后，accepted 来源在 claim/Lua 之后被删除或改变时，三条 final UPDATE 仍可能清 lease、标 RELEASED/PROJECTION_BLOCKED 或写 retry 的问题。

本批只扩展 `markReleased`、`markProjectionBlocked`、`retry` 三条 UPDATE 的 WHERE，保留 T41 lease fence、T42 original tuple、标量参数、SET、签名、Recovery/Java/Lua、claim、SQL/DDL/API 和现有事务边界。

## 2. Final write source EXISTS

三条 UPDATE 均在原条件末尾追加与 T44/T45 相同的相关 `EXISTS`：

- `seckill_reservation` 按原唯一 order_no 候选；
- BINARY source.order_no 同时等于请求 orderNo 与当前目标 orderNo；
- BINARY source.status 精确为 `ACCEPTED/PUBLISHED/ORDER_CREATED`；
- source activity_id/sku_id/user_id 与当前目标 exact 相等；
- BINARY source.idempotency_key 与当前目标 exact 相等。

保留 T41 的 owner/lease/status/revision 条件和 T42 的 original snapshot 条件。source 不符时 affected rows=0，不清 lease、不造 RELEASED/blocked 完成、不写 retry metadata；不新增独立 query、参数、锁序或 repair。关联 EXISTS 可能改变 InnoDB 读取/锁足迹，不承诺 scan/性能/并发锁压力不变。

## 3. Lua 已执行与剩余窗口

本门不阻止 Lua 已经执行后的 Redis 变化；若 SQL 被 source fence 拒绝，Redis stock/revision/marker 已发生的事实按原规则保留，不伪造回滚。不能宣称跨 SQL/Redis 原子、DB/Redis fencing 或 source 变动窗口已关闭。

## 4. 验收计划

- Direct real MySQL：三写方法 × 17 source fault（missing/status/order alias/tuple/idem）验证 affected rows=0、6 张 owned facts 不变；source Stock checksum 不变。
- post-Lua release/block/lost-response hook 在 Lua 后改变 source SKU/维度，三 UPDATE 均被 fence 拒绝；Redis 已执行的 release 事实按原规则保留，不 mark 错 DB 行。
- 三种合法 source 状态 × 三写方法按原 SET/receipt/retry 协议成功；T37–T51、claim/readback/lease/tuple、healthy replay 和资源 finally 清理保持。
- 不新增 unit mirror SQL/签名/API/DDL；无业务 App、固定端口、RM/TC，不宣称 full source/Order/资金因果或跨 store 原子。

本切片只冻结 recovery final write 的 accepted source fence，不处理 Lua 后窗口、完整分布式 fencing、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
