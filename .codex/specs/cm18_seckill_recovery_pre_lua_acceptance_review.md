# CM-18 16T54：秒杀 recovery Lua 前受理来源门

## 1. 结论与范围

本切片 GO，修正 T44 claim source 通过后、T40 claim readback 仍有效时 source 被删除/改变，Recovery 仍执行 Lua 并造成配额回补而 T52 final SQL 被拒的问题。

本批只在 `claimReadbackMatches` 通过后、Lua 前复用 T46 `findMatchingAcceptanceId(original scan settlement)`；不改 SQL、Lua、lease、transaction、API、T44–T53 或其它状态机。

## 2. Lua 前顺序与失败语义

顺序固定为：candidate gate → claim → T40 current/owner/lease/immutable readback → source acceptance getter → Lua。source id 为 null 时直接 false：不调用 Lua、markReleased、retry，不清 lease、不修 facts；保留 claim 已获 lease 的当前事实，后续沿既有 lease/扫描语义。

source 查询异常沿现有 catch/retry(original) false，且不调用 Lua；不把异常伪装为 source missing 或成功。合法 source 才进入原 Lua/mark/retry。

T53 的 markReleased=0 后 readback/source acceptance 仍独立执行，因此合法 CAS0 可能发生第二次 source query；本批不合并查询、不提供 fresh RR/source freeze，不关闭 source 两点窗口、Lua 前后窗口或跨 SQL/Redis fencing。Lua 已执行的 Redis 效果保留，不伪造回滚。

## 3. 不变边界与验收

- 复用 T46 source SQL（三受理状态、精确 tuple/幂等），不新增 SQL/DTO/DDL/API/锁/事务；T37–T53、扫描预算、claim/retry、普通库存和 source facts保持。
- Unit/SQLIT 覆盖 source missing/fault、claim readback priority、合法 source；确认 source 缺证无 Lua/mark/retry，异常仅旧 retry。
- 真实 MySQL+Redis：claim 后 source fault 先于 Lua 阻断；healthy candidate 正常释放；T53 CAS0 正常再做 source check。source checksum、owned DB/Redis/线程 finally 清理。

本切片只冻结 Lua 前 source acceptance，不处理 query 耗时导致 lease 过期、readback 后窗口、分布式 fencing、full provenance/资金因果或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
