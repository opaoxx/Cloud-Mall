# CM-18 16T51：秒杀 pending replay 受理来源证明

## 1. 结论与范围

本切片 GO，补足同向 `RELEASE_PENDING/PROJECTION_BLOCKED + ROLLBACK` replay 仍可在 source 缺失/错 tuple 时返回 pending 200 的问题。它只增加 pending replay 的 T46 source recheck，不改变 transition/CAS、GET、终态 noop、recovery、DDL、API、Redis/MQ 或事务边界。

## 2. 同向 pending source recheck

在 T32 identity、`validateSettlement`、T34/T35 key/receipt 门通过后，仅以下分支调用既有 `findMatchingAcceptanceId(originalLockedSettlement)`：

- status=`RELEASE_PENDING` 且 action=`ROLLBACK`；
- status=`PROJECTION_BLOCKED` 且 operation key 与 action 同为 rollback。

source getter 沿 T46 三受理状态、精确 order/status/tuple/幂等 SQL；非 null 才保持原 pending response `{rolledBack:false,pending:true,status:"UNKNOWN"}`。null 抛原 `unknownCas(orderNo)` 503；查询异常原样传播，不写/clear lease/诊断字段，不修 source。

## 3. 优先级与不变边界

- `CONFIRM` 对 RELEASE_PENDING 保持原 409；PROJECTION_BLOCKED 的 confirm direction 继续 T45 transition，rollback opposite 409，均不查询 source。
- OPEN initial transition 仍沿 T45 source EXISTS，不新增本批 source query；终态 GET/noop、legacy、普通库存、recovery/Lua/DDL/API 不变。
- 保留 INSERT→FOR UPDATE/REQUIRED、identity/dimensions/key/receipt 优先级；同事务 source 只读查询不宣称 fresh RR、source freeze、完整因果或 fencing。

## 4. 验收计划

- Unit：source null、source fault、query error、identity/dimension/key priority、pending directions；NULL source 必须显式 stub，避免 Mockito Long 默认 0 误判。
- T34 owned SQL/Controller：17 bad source × 两 pending states 同 rollback 返回 503、facts 不变；三合法 source 状态 × 两 pending states 保持原 200；查询数从原 2 增至 3。反向 action 仍 409/两 SQL。
- Controller/Order fallback 不把 source 缺证当完成；source checksum、owned DB/资源 finally 清理。保留 T32/T34/T45–T50 回归，不启动 App/固定端口/RM/TC。

本切片只冻结 pending replay source recheck，不处理 source 查询窗口、跨 SQL/Redis 原子、full provenance、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
