# CM-18 16T35：秒杀非终态 operation key 精确校验

## 1. 结论与范围

本切片 GO，基线为 `0e29576`，延伸 16T34 的 operation key 证明到非终态 settlement。当前 OPEN 的 Mapper CAS 允许 `operation_key IS NULL OR operation_key=#{operationKey}`，CI 列中的原 alias 可能被同向写覆盖；GET 也可能把 OPEN 错 key 报为 RESERVED。RELEASE_PENDING 应只接受 rollback 方向，PROJECTION_BLOCKED 需保留既有 confirm/rollback 两方向兼容。

本批只增加共享 private validator，覆盖 OPEN、RELEASE_PENDING、PROJECTION_BLOCKED；T34 的 CONFIRMED/RELEASED 规则继续生效，未知状态/CONFLICT 不扩展规则。SQL、DDL、锁序、事务、恢复、Redis/MQ、API、普通库存和 legacy T33 不变。

## 2. 非终态 validator

T31/T32 原 order identity guard 和既有 `validateSettlement` 通过后，以 settlement 原始 orderNo 精确构造允许键：

```text
CONFIRMED          -> orderNo:CONFIRM
RELEASED           -> orderNo:ROLLBACK
OPEN               -> null 或 orderNo:CONFIRM 或 orderNo:ROLLBACK
RELEASE_PENDING    -> orderNo:ROLLBACK
PROJECTION_BLOCKED -> orderNo:CONFIRM 或 orderNo:ROLLBACK
```

所有非 null key 比较使用 `Objects.equals`；不 trim、normalize、hash、按 collation 接受 alias，也不以请求 orderNo 替换原 operation key。未知状态和 `CONFLICT` 返回 validator 通过，保持原行为。

## 3. GET 语义

GET 在 T31 identity 与原 `validateSettlement` 后执行共享 validator：

- OPEN、RELEASE_PENDING、PROJECTION_BLOCKED 的 key 缺失/错误/方向不符，返回请求 orderNo echo、`UNKNOWN/items=[]`；不落普通库存路径、不泄露原 key、不写数据库/Redis/MQ；
- OPEN 的 null key和两种精确方向保持原状态 `RESERVED`；RELEASE_PENDING 精确 rollback key 保持 `UNKNOWN`/pending 外部映射；PROJECTION_BLOCKED 两种精确方向保持原既有映射；
- CONFIRMED/RELEASED 沿 T34；未知状态/CONFLICT 沿 T31 原输出。

## 4. 写语义

T32 写 guard 在锁定 settlement、精确 order identity 和 `validateSettlement` 之后，operation key 派生、状态 noop 和 CAS 之前执行共享 validator：

- 非终态 bad key 固定抛既有 `SECKILL_SETTLEMENT_CORRUPT` 500，不含 raw key/order、XID、维度或数据库错误；
- OPEN null 或 exact confirm/rollback key 保持原 Mapper CAS：同向允许 CAS，跨向因原 SQL 条件不命中继续原 `SECKILL_SETTLEMENT_UNKNOWN` 503，不改成 409；
- RELEASE_PENDING 只允许 exact rollback key，合法 rollback 继续 pending UNKNOWN；confirm 仍为既有 409；
- PROJECTION_BLOCKED exact confirm/rollback 两方向保留现有兼容，当前 action 与 stored operation key 不同仍由原状态逻辑冲突；
- bad key 不得触发 noop、CAS、普通库存写或 Redis/MQ；既有 INSERT/锁定在同一 REQUIRED 事务中的尝试仍按原回滚语义处理。

不改 insert-before-lock、`@Transactional(REQUIRED)`、Mapper SQL、锁顺序、revision、operation key 写入、迁移、recovery、Lua/Redis、Controller/API 或错误传播。T34 terminal validator 继续覆盖 CONFIRMED/RELEASED。

## 5. 验收矩阵

- Unit：OPEN、RELEASE_PENDING、PROJECTION_BLOCKED 各覆盖 null、foreign、相反方向、case/accent/trailing-space alias；GET 和 confirm/rollback 均覆盖 T31/T32 identity 与维度优先级。OPEN 两方向 exact CAS、跨向原 503；pending rollback/confirm 及 blocked 两方向旧行为保持。
- Owned MySQL：现有 VARCHAR operation key 直接准备 bad alias，不 ALTER；GET bad key 不 CAS/不写，write bad key 终止于 500；exact positive 允许 settlement 按原协议状态/CAS/revision/operation 变化，source Stock checksum 与 owned 事实按预期核验。
- 复用 T34 SQLIT fixture，验证 autocommit=false、同 Spring transaction manager、无普通 stock_flow/stock_sku/request/cache、Redis/MQ/RM/TC 写；Controller 可 standalone MVC + real tx proxy，无需监听；如走 HTTP 只用随机 loopback。
- 保留 T31/T32/T33/T34、legacy、普通 flows、Order fallback 和 Gateway 回归；修正正例仅补真实 OPEN/terminal key，不能改变旧 OPEN/pending/projection 控制语义。owned 资源 finally 清理。

本切片不处理其它 tuple/provenance、未知/CONFLICT 状态、库存余额、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
