# CM-18 16T43：秒杀 RELEASED 回执证据门

## 1. 结论与范围

本切片 GO，补充 `RELEASED` settlement 的 Redis release receipt 只读证明。当前 GET/写路径在 order、维度和 operation key 合法时未核 `redisRevision`，null、0、负数或超范围值仍可能被报告为 `ROLLED_BACK` 或允许 rollback noop。

本批只增加共享 private receipt validator；不新增 Redis GET、SQL、DDL、事务、状态机或接口，不证明 DB receipt 必然来自原 ACCEPTED/当前 Lua 因果，不改变 T31–T42 其它边界。

## 2. Receipt 范围

释放 Lua 的新释放/已 APPLIED 回执范围为正整数 `1..1000000000000`。`SeckillQuotaSettlementPO.redisRevision` 为 nullable BIGINT；仅对 status 精确 `RELEASED` 的 facts 要求：

```text
redisRevision != null
&& redisRevision >= 1
&& redisRevision <= 1000000000000
```

其它状态不新增 receipt 限制，保留 OPEN、RELEASE_PENDING、PROJECTION_BLOCKED、CONFIRMED、未知/CONFLICT 的既有 operation/status 规则。不得 trim、转换字符串、浮点或猜测回执来源。

## 3. GET 与写优先级

在 T31 order identity、既有 `validateSettlement`、T34/T35 operation key 门之后，执行 RELEASED receipt validator：

- receipt 缺失、0、负数或大于上限返回请求 orderNo echo、`UNKNOWN/items=[]`；不落普通库存路径、不回显 raw receipt、不写 Redis/DB/MQ；
- exact RELEASED receipt 保持 `ROLLED_BACK` 和既有明细；其它状态沿原输出。

写路径同样在 identity/dimensions/operation key 后、状态 noop/反向冲突/CAS 前执行：bad RELEASED receipt 抛既有 `SECKILL_SETTLEMENT_CORRUPT` 500，固定消息 `秒杀释放回执缺证`，不含 raw receipt/order/error。合法边界保留同向 rollback noop和反向 confirm 409。

不新增 Redis 查询；本门只检查持久化 `redisRevision` 数值，不声称证明原 APPLIED marker 或完整 ACCEPTED 因果。

## 4. 不变边界与验收

- 不改 SQL/DDL/事务/锁/Mapper、Redis Lua、恢复器、legacy、普通库存、API、T31–T42；不校验非 RELEASED receipt。
- Unit 覆盖 RELEASED null/0/负数/大于上限/边界 1 与 1e12、GET/rollback 两方向和 dimensions/opkey 优先；其它状态控制保持。
- 复用 T34 MyBatis/SQLIT/Controller/Order fallback fixture，验证坏 receipt GET 为 UNKNOWN/items0、写为固定 500，源/owned facts 不变；合法边界保持 noop/409。
- 资源 finally 清理，不启动业务 App/RM/TC/固定端口；不宣称 Redis receipt 与数据库事实跨系统原子或全资金因果。

本切片只冻结 RELEASED 回执数值门，不扩大其它状态或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
