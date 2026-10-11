# CM-18 16T47：秒杀终态 replay 受理来源证明

## 1. 结论与范围

本切片 GO，补足 T46 终态 GET source gate 后，同向 terminal noop 仍未复查原 `seckill_reservation` 来源的问题。当前同 `CONFIRMED+CONFIRM` 或 `RELEASED+ROLLBACK` 可直接 200，即使 source 缺失/错 tuple。

本批只增加同向 terminal noop 前的只读 source recheck；不改变 transition/CAS、反向 409、pending、GET、recovery、DDL、API、Redis/MQ 或事务边界。

## 2. Noop source recheck

在 `settle` 已通过 T32 identity、`validateSettlement`、T34/T35 operation key 和 T43 RELEASED receipt 后，只有以下两个分支执行 T46 同一 `findMatchingAcceptanceId(originalLockedSettlement)`：

- `status=CONFIRMED` 且 action=`CONFIRM`；
- `status=RELEASED` 且 action=`ROLLBACK`。

返回非 null 才沿原 200 idempotent noop；返回 null 抛原 `unknownCas(orderNo)`，即 `SECKILL_SETTLEMENT_UNKNOWN` 503，不写状态。查询异常原样传播，不伪造成功或 UNKNOWN。不得对反向 action/409 分支查询 source，保留原两条 SQL 和 409。

Source getter 继续使用 T46 的唯一 order 候选、BINARY order/status 三态/幂等、activity/sku/user tuple；不加 FOR UPDATE、不新增锁/事务方法、不声称同一 RR、source frozen、fencing 或完整 Order/Redis/APPLIED 因果。

## 3. 不变边界与验收

- 保留 insert-before-lock、REQUIRED 事务、identity/dimension/key/receipt 优先级、transition/CAS/recovery/GET/legacy/普通路径和 API。
- Unit：source null 必须显式 stub，覆盖同向 noop source 缺失/异常/合法；反向 action 不调用 source，仍 409/两 SQL。
- 复用 T34 owned MySQL/Controller/Order fallback：17 bad source × 两终态同向 noop 返回 503、facts 不变；三合法 source 状态 × 两合法 noop 仍 200；查询数从原 2 增至 3。原 T32/T34 fixtures 补同源 source，不弱化坏字段/协议断言。
- 真实 source 缺证/错 tuple 不写 settlement、stock_flow、stock_sku、request/cache、Redis/MQ；source checksum、owned 资源 finally 清理。无 App/固定端口/RM/TC。

本切片只冻结终态 replay 的 source recheck，不处理 source 查询窗口、跨 SQL/Redis 原子、全资金因果或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
