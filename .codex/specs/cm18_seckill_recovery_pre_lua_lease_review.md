# CM-18 16T55：秒杀 recovery Lua 前 lease deadline 复核

## 1. 结论与范围

本切片 GO，修正 T54 source getter 成功后查询耗时导致原 claim lease 已到期，Recovery 仍执行 Lua/mark 的边界。

本批只在 source getter 返回合法后、Lua 前再次核验 claimed.leaseUntil；不改变 SQL/Lua/query 次数、lease 长度、事务、API、T40/T54 顺序或其它状态机。

## 2. Deadline gate

顺序固定为 candidate → claim → T40 identity/owner/lease readback → T54 source acceptance → fresh lease deadline check → Lua：

- `claimed.leaseUntil.isAfter(fresh now())` 才允许 Lua；
- equal、过期或 null deadline 返回 false，不调用 Lua、markReleased、retry，不清 lease、不改 facts；
- T40 owner/immutable/initial lease 失败、T54 source null/异常优先保持原语义；
- fresh lease 合法继续原 Lua/mark/retry。

使用私有 `Clock recoveryClock=Clock.systemUTC()`，`now` 以项目 Shanghai ZoneId 转换；默认生产时间语义不变，不公开新 API。测试通过 ReflectionTestUtils 注入可推进 Clock，不能修改 DB lease、系统时间或声称模拟 SQL NOW/GC 实际超时。

## 3. 风险边界与验收

- Unit/真实 SQLRedis：source SELECT 完成后推进 app Clock 至 claim deadline 或 +1ms，断言 no Lua/no mark/no retry、facts/Redis keys 不变；未到期 healthy pending/blocked 三态/两方向保持。
- 这是应用观察耗时测试，不解决 query 后到最终 Lua、最后检查到 Lua 的窗口、clock skew 或分布式 fencing；不宣称 SQL lease 与 app Clock 原子。
- 保留 T37–T54、scan/query budget、candidate/readback/source fences、Lua/retry/API/DDL/lease length；资源 finally 清理，无 App/固定端口/RM/TC。

本切片只冻结 Lua 前 deadline 复核，不处理完整 fencing、TC/MQ、资金因果或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
