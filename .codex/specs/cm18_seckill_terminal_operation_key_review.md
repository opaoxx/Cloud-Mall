# CM-18 16T34：秒杀终态 operation key 精确校验

## 1. 结论与范围

本切片 GO，基线为 `1dd7d66`，只修正秒杀 settlement `CONFIRMED`/`RELEASED` 终态对 operation key 的来源和方向校验。当前正确 orderNo/维度的终态行即使 operation key 为 null、错误 order、其它方向或 case/accent/trailing-space alias，也可能被 GET 报为终态并接受同方向写 noop。

本批只覆盖两个终态；`OPEN`、`RELEASE_PENDING`、`PROJECTION_BLOCKED`、未知状态和 `CONFLICT` 保持原行为。revision、Redis receipt、用户/活动/actor 全 tuple、其它状态维度和 CM18 M1–M4 继续留待后续范围。

## 2. 共享终态 key validator

新增或复用一个 private boolean validator，只对非 null terminal facts 使用：

```text
CONFIRMED -> Objects.equals(originalOrderNo + ":CONFIRM", operationKey)
RELEASED  -> Objects.equals(originalOrderNo + ":ROLLBACK", operationKey)
```

`originalOrderNo` 必须来自 settlement 原始行；null、空串、别订单、其它方向、大小写/重音/尾随空格 alias 均不匹配。不得 trim、normalize、hash、SQL 过滤或以请求 orderNo echo 替换原字段。非 terminal 状态不进入本 validator。

## 3. GET 语义

T31 order identity guard 和现有 `validateSettlement` 通过后，GET 对 `CONFIRMED`/`RELEASED` 执行 key validator：

- key 不匹配返回请求 orderNo echo、`UNKNOWN/items=[]`；
- 不落入普通库存路径、不回显原 operation key、不写数据库/Redis/MQ；
- exact terminal key 保持原 `CONFIRMED`/`ROLLED_BACK` 状态和明细；
- `OPEN`、`RELEASE_PENDING`、`PROJECTION_BLOCKED`、未知/CONFLICT 不新增本批规则，保持 T31/T33 原输出。

## 4. 写语义

T32 写 guard 锁定并精确校验 settlement 原 orderNo、通过既有 `validateSettlement` 后，在 operation key 派生、状态 noop 判断和 CAS 前执行 terminal key validator：

- `CONFIRMED` 必须是 `orderNo:CONFIRM`；`RELEASED` 必须是 `orderNo:ROLLBACK`；
- terminal key 缺失、错误、其它方向或 alias 沿既有 `SECKILL_SETTLEMENT_CORRUPT` 500，消息固定且不含 raw key/order、XID、维度或数据库错误；
- bad terminal key 不能触发同方向 noop、相反动作冲突判断、CAS、普通库存写或 Redis/MQ；已有 `INSERT ... SELECT`/锁定在同一 REQUIRED 事务中的尝试按既有回滚语义处理；
- exact terminal key 的同方向 noop、相反动作 409 和其它 T32 状态保持。

不改变 insert-before-lock、`@Transactional(REQUIRED)`、Mapper SQL、锁顺序、revision、operation key 写入、迁移、recovery、Lua/Redis、普通库存、Controller/API 或错误传播。

## 5. 验收矩阵

- Unit：`CONFIRMED`/`RELEASED` 各覆盖 null、空串、foreign order、相反方向、case/accent/trailing-space alias，以及 exact confirm/rollback key；GET 与 confirm/rollback 两动作均覆盖。验证 terminal bad key 在既有 identity/`validateSettlement` 之后、operation/noop/CAS 之前阻断；维度损坏仍保持原优先错误。OPEN/RELEASE_PENDING/PROJECTION_BLOCKED 控制组保持旧语义。
- Owned MyBatis/MySQL：在现有 VARCHAR key 上直接准备 bad terminal facts，不 ALTER；GET/写前后 source Stock 全 checksum、owned 事实、状态、时间、数量保持。exact positive 按原协议允许状态/CAS/revision/operation 变化。
- 真实 Stock Controller/事务代理验证 409/500 不落普通 `stock_flow`、`stock_sku`、request/cache、Redis/MQ/RM/TC；可用 standalone MVC + real tx proxy 无监听，或 finally 清理的随机 loopback；不启动业务 App/固定端口。
- 修正旧 positive fixtures：`CONFIRMED`/`RELEASED` 使用真实预期 key；保留 OPEN 等控制 fixture 原行为。Order fallback 消费 bad terminal GET 必须 UNKNOWN，不得 `SUCCEEDED`。

本切片不处理终态完整 tuple、其它状态 operation key、普通库存、TC/MQ 或 CM18 M1–M4；T31/T32/T33 既有身份、数值和事务边界保持。
