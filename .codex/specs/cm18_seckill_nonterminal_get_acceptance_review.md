# CM-18 16T48：秒杀非终态 GET 受理来源证明

## 1. 结论与范围

本切片 GO，将 T46 终态 GET source gate 扩展到 OPEN、RELEASE_PENDING、PROJECTION_BLOCKED，防止 source 缺失/错 tuple 时仍报告 RESERVED/UNKNOWN 明细并进入 Order fallback。

本批只扩大既有 `findMatchingAcceptanceId(settlement)` 只读 gate 的适用状态；不新增 query 方法、DDL、API、锁、事务、写状态、settle noop/CAS、recovery、legacy 或普通库存规则。

## 2. 五状态 GET gate

T31 identity、`validateSettlement`、T34/T35 operation key/receipt 门通过后，OPEN、RELEASE_PENDING、PROJECTION_BLOCKED、CONFIRMED、RELEASED 五种状态均调用同一 source getter。source SQL 保留 T46：唯一 order 候选后 BINARY order/status 三态、activity/sku/user、idempotency exact 条件。

- source id 非 null：沿原状态映射与 items；OPEN 可 RESERVED，RELEASE_PENDING/PROJECTION_BLOCKED 仍 UNKNOWN 但保原合法 items 语义，终态保持原确认/回滚映射；
- source 缺失或 tuple/status/alias 不匹配：请求 orderNo echo、`UNKNOWN/items=[]`，不落普通 fallback、不写/repair、不回显 source；
- source 查询异常原样传播，不伪装 UNKNOWN 或 success；
- unknown/CONFLICT 状态及 legacy route 不扩展 source gate，保持原行为。

两点读仍不宣称单 RR、source window、fencing、全来源/Order/资金因果或跨 SQL/Redis 原子；不使用 FOR UPDATE。

## 3. 验收计划

- Unit：source missing、原 snapshot、query fault、identity/dimensions/key/receipt 优先级、unknown/CONFLICT/legacy 不查询和五状态映射；不弱化坏字段协议。
- Owned SQL/Controller/Order fallback：真实 17 bad-source × OPEN/RELEASE_PENDING/PROJECTION_BLOCKED/CONFIRMED/RELEASED 验证 UNKNOWN/items0 与 facts 不变；三合法 source 状态 × 三新状态、两终态控制保持，GET 查询由 1 增至 2。
- PAID/CANCELLED Order fallback 不得把 source 缺证映射完成；source checksum、owned DB/资源 finally 清理。保留 T31–T47、transition/settle/recovery/普通库存回归，不启动 App/固定端口/RM/TC。

本切片只冻结五状态 GET source 观察，不处理 transition/write gate、终态 replay source、跨 store 窗口、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
