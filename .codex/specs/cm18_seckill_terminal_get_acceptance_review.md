# CM-18 16T46：秒杀终态 GET 受理来源证明

## 1. 结论与范围

本切片 GO，修正 T45 已保护 transition 写入但 GET 仍可能把无 `seckill_reservation` 来源的 `CONFIRMED`/`RELEASED` settlement 报为已知终态，进而影响 Order fallback。只补终态 GET 的 source read gate，不改变 transition、settle noop/CAS、recovery、legacy、普通库存或 API。

## 2. 只读 source query

新增语义化 Mapper `findMatchingAcceptanceId(@Param("settlement") SeckillQuotaSettlementPO settlement)`，返回匹配受理行 id 或 null。SQL 保留 source `order_no` 唯一索引候选，再以精确条件核验：

- BINARY source.order_no = BINARY settlement.orderNo；
- BINARY source.status IN `ACCEPTED/PUBLISHED/ORDER_CREATED`；
- source.activity_id/sku_id/user_id = settlement 对应维度；
- BINARY source.idempotency_key = BINARY settlement.idempotencyKey。

只在 `CONFIRMED`/`RELEASED` 且 T31 identity、原 dimensions、T34/T43 operation key/receipt 门均通过后调用；匹配非 null 才输出原 terminal/items。source 缺失、状态/tuple/alias 不匹配返回 `UNKNOWN/items=[]`，阻止普通 fallback，不写/repair、不回显 source。查询异常原样传播，不伪装 UNKNOWN 或 success。

该点读使终态 GET 的既有一次查询变为两次；不宣称单 RR、source window 或跨 SQL/Redis 原子，不验证 request hash、full Order 因果或 Redis APPLIED 来源。Mapper 无新 DTO/DDL/公开 API，T45 source gate 仍用于 transition。

## 3. 不变边界

- OPEN、RELEASE_PENDING、PROJECTION_BLOCKED、未知态保持原 GET 语义；终态写 noop/CAS、T31–T45、legacy、普通库存、Redis/MQ、DDL/API 不改。
- 合法 source 三态 `ACCEPTED/PUBLISHED/ORDER_CREATED` 与两终态保持原 status/items；source 缺失或坏事实不修复、不改变 settlement。
- 只读查询不使用 FOR UPDATE，不加入事务或锁传播，不扩大 source 条件为全表扫描；不承诺物理扫描/并发性能。

## 4. 验收计划

- Unit：source 缺失、原 snapshot 传递、查询异常传播、identity/dimensions/key/receipt 优先级和 OPEN/非终态路径；断言 UNKNOWN/items0 或异常原样传播，不虚构数据库 alias/tuple 能力。
- 复用 T34 owned MySQL/Stock Controller/Order fallback：真实 SQL 验证 17 个 bad-source（wrong status、CI order、数字维度/幂等不一致）× CONFIRMED/RELEASED 均只读、facts 不变且 Order fallback 不 SUCCEEDED；三合法 source 状态 × 两终态保持边界，查询数由 1 增至 2。
- 健康 fixture 补同源 accepted；source checksum、owned DB/资源 finally 清理。保留 T34/T45/receipt/普通库存/legacy 回归；不宣称全 source/Order/资金因果闭环。

本切片只冻结终态 GET 的受理来源观察，不处理 transition/write gate、跨 SQL/Redis 窗口、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
