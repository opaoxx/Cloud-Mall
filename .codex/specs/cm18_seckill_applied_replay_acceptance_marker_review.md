# CM-18 16T50：秒杀 APPLIED replay 受理 marker witness

## 1. 结论与范围

本切片 GO，修正 release Lua 在 `APPLIED|...` marker 分支提前返回 saved revision、未验证当前 accepted marker 的问题。缺失、错类型、别订单或 CI alias accepted marker 不能证明本次 APPLIED replay 属于当前 order。

本批只调整 release Lua 的检查顺序；KEYS/ARGV、APPLIED/RESERVED 格式、数值范围、所有写顺序、stock/meta 规则和 recovery/DB/API 不变。Java 侧不新增独立 Redis 请求，但 APPLIED 路径会在既有 Lua 内部增加 accepted TYPE/GET witness 检查。受理 Lua 将 `seckill:accepted:*` marker 设置后不 TTL，注释约定保留至 audited archival；只有 `seckill:user:*` 使用 EXPIRE，因此 live accepted witness 门不破坏合法留存，历史 marker 缺失/过期沿未知边界阻塞。

## 2. APPLIED witness 顺序

在 operation marker TYPE/GET 与基本 ARGV/opkey 校验后、APPLIED early return 前，必须先：

- acceptedKey TYPE 精确为 string；
- acceptedKey GET 精确等于 ARGV orderNo，大小写/重音/尾随空格 alias 不接受。

accepted witness 缺失、错类型、别订单或 alias 时，Lua 返回 0；不读取/修改 stock、meta、activity revision、user marker，不补/删/改 accepted marker。只有 witness 通过后，APPLIED marker saved revision 才按原 `1..1000000000000` 规则返回；APPLIED replay 不要求当前 stock/meta/revision 存在，不重复 INCR。

source witness 丢失、归档或过期属于可用性缺证边界，按上述返回 0/阻塞处理；不把历史 APPLIED marker 单独提升为当前可释放证明。合法 accepted witness 下，即使其它 stock/meta/revision projection 丢失，APPLIED replay 仍可返回正 receipt 且只读。

非 APPLIED 的 RESERVED 分支继续使用同一 accepted TYPE/GET witness，随后沿原 stock/meta/activity revision 检查和写入顺序；可删除原后段重复 accepted 检查，但不得改变有效 release 内容。

## 3. 恢复与风险边界

direct Lua 返回 0 时 recovery 沿现有 projection blocked/未知处理，不伪造成功；不新增 SQL/DDL/Redis GET、Java DTO、API 或恢复状态。合法 APPLIED marker + accepted witness 继续正回执和 read-only replay；marker 缺失/过期或 witness 不可用保留 availability 阻塞。

本批不证明 APPLIED marker 完整 tuple/原 request hash/Order 因果，不关闭 SQL→Lua 或跨 store fencing 窗口，不改变其它受理来源/四服务/TC 语义。

## 4. 验收矩阵

- Direct Lua：8 类 accepted source fault（missing、hash/list/set wrong type、foreign value、case alias、accent alias、trailing-space alias）均返回 0，Redis 内容不变。
- Real recovery：8 类 source fault 均执行既有 Lua 并返回 0，沿原 `markProjectionBlocked`/租约/诊断更新语义处理；不改变 Redis 内容、不调用 `markReleased`、不伪造完成。恢复 owned 原 marker 后同 APPLIED replay 收敛为 1，quota 不二次增加、user count 不减少。
- 合法 APPLIED 缺其它 stock/meta/revision 时仍返回正 receipt 且只读；健康 pending/blocked、T37–T49 回归保持。
- 使用真实 Redis/Lua 与 owned MySQL/随机 keys，source Stock checksum、owned DB/Redis keys finally 清理；无 App、固定端口、RM/TC。仅新增三组真实 SQL/Redis 验证，不新增 mirror SQL unit、DDL 或生产 API。

本切片只冻结 APPLIED replay 的 accepted witness，不宣称 APPLIED provenance、全资金因果、fencing 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
