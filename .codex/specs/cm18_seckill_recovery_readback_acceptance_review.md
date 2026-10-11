# CM-18 16T53：秒杀 recovery CAS 读回后的受理来源证明

## 1. 结论与范围

本切片 GO，补足 T38 CAS readback 通过后仍可能缺少原 `seckill_reservation` 来源的边界。仅在 `markReleased!=1` 且 `releasedReadbackMatches(original,current,actualLuaReceipt)` 成立后，才允许 recovery 将本次 handled 计为 true。

本批不改变 Lua、SQL、lease、transaction、markReleased=1、markProjectionBlocked/retry、T44–T52、API、DDL、普通库存或 recovery 状态机。

## 2. Readback/source 顺序

顺序固定为：

1. Lua 返回正 receipt；
2. `markReleased` 返回 0；
3. 复用 T38 immutable/receipt readback proof；
4. 仅 proof 成立后调用 T46 `findMatchingAcceptanceId(original)`；
5. source id 非 null 才返回 handled=true。

若 readback proof 失败，立即沿 CAS_UNKNOWN retry(original) 返回 false，不查询 source。source 返回 null，沿同一 CAS_UNKNOWN retry(original) 返回 false；source 查询异常沿现有 catch retry(original) 返回 false。不得用 source 查询结果修复或回滚事实，不读取 raw source 字段。

`markReleased=1` 直接保持原成功路径，不新增 source query。两次非锁观察不宣称 source frozen/fresh RR、跨 SQL/Redis fencing 或完整受理/资金因果；已执行 Lua 的 Redis 效果保留。

## 3. 不变边界与验收

- source getter 沿 T46 的唯一 order/BINARY 三态/tuple/幂等 SQL，不加 FOR UPDATE、锁或新事务方法；Recovery 只传原始扫描 candidate。
- Unit：readback legal/坏/缺失、source null、source exception、markReleased=1；坏 readback不查 source，source缺证沿 retry，原 operation/revision/lease 语义保持。
- Owned MySQL+Redis：复用 T37/T38 fixture，核 real markReleased=0 后合法 source 才 handled；17 source faults/source missing 导致 retry false；source checksum、DB/Redis/线程资源 finally 清理。
- 保留 T44–T52、普通/秒杀/Order fallback 回归；不修改 SQL/Lua/DDL/API，不启动 App/RM/TC/固定端口。

本切片只冻结 CAS readback 后的 source acceptance 门，不处理 source 查询窗口、full provenance、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
