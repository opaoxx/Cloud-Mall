# CM-18 16T41：秒杀恢复写 SQL lease/身份 fence

## 1. 结论与范围

本切片 GO，基线为 `0354356`，修正 recovery 三条 UPDATE 在 MySQL CI 比较和缺少 lease 条件下可能清除/写入其它 owner 租约的问题。`markReleased`、`markProjectionBlocked`、`retry` 只应更新本 owner 当前仍持有的同一 lease 和同一状态事实。

本批只修改三条现有 Mapper SQL 的 WHERE 谓词；方法签名、Recovery 调用、字段 SET、DDL、claim/renew、T37–T40、扫描/API、Redis/MQ 和事务边界保持。

## 2. 三条 UPDATE 的 fence 条件

在三条现有 UPDATE 保留原 revision 条件，并追加相同的身份/租约约束：

- `BINARY order_no = BINARY #{orderNo}`；
- `BINARY operation_key = BINARY #{operationKey}`；
- `BINARY lease_owner = BINARY #{owner}`；
- `BINARY status` 必须精确匹配原允许状态：`RELEASE_PENDING` 或 `PROJECTION_BLOCKED`；
- `lease_until IS NOT NULL AND lease_until > NOW(3)`；
- 原有 `revision`/operation/status 条件保持。

不使用默认 CI 比较，不 trim/casefold，不增加参数，不改变 SET 字段。`NOW(3)` 使用项目 Shanghai `DATETIME(3)` session 语义，判断语句执行时 lease 仍有效；不宣称跨 SQL/Redis 原子，也不提供等待期间的强 SLA。

具体语义保持：

- `markReleased` 只在同 owner、未过期 lease、原 pending/blocked 状态和 revision 条件同时满足时写 `RELEASED`；
- `markProjectionBlocked` 只在同 owner、未过期 lease 和原 pending/blocked 状态下保留阻塞写入；
- `retry` 只在同 owner、未过期 lease 和原 pending/blocked 状态下更新重试字段；影响行数 0 仍沿现有 false/CAS_UNKNOWN 处理。

## 3. 与 T40/剩余风险的关系

T40 claim 后点读仍负责当时的 owner/lease/immutable proof；本 SQL fence 负责 UPDATE 执行瞬间的 CI 身份和 lease 条件。它不比较全部 immutable tuple，不阻止已开始的 Lua 投影，也不关闭 readback 到 Lua、Lua 到 UPDATE 之间的 fencing 窗口；完整 fencing 需独立切片。

markReleased=0 继续使用 T38 immutable readback proof；blocked/retry 影响行数为 0 不创建新 owner、不延期租约、不修事实。claim、renew、findDue/findDueAfter、T39 游标、恢复批次 50、T37 candidate gate 和 T40 readback 不变。

## 4. 验收矩阵

- Direct Mapper owned MySQL：三方法覆盖 10 组 order/operation/owner alias 与失效 lease，以及 operation_key=NULL、lease_owner=NULL、不同 owner、`CONFIRMED` status 等 4 个控制；源 `order_no`/`status` 保持生产 NOT NULL，不 ALTER 以声称可持久化其 null。每次 affected rows=0，owned 全 facts 不变。健康 pending/blocked 且 lease 有效时，mark/retry 沿原 SET 变化；其它 NULL 参数自然按 SQL 结果处理，不新增 mirror 单元。
- 真实 T37/T40 MySQL+Redis fixture：在 post-point Lua hook 修改 owner/expiry，观测 recovery SQL 不写错 lease；Lua 已执行不伪称无 Redis 动作，事实/回执按原协议核验。
- 原 source Stock checksum、owned DB/Redis/lease 资源 finally 清理；不改 DDL/索引/参数，不启动业务 App/RM/TC/固定端口。
- 保留 T31–T40、ordinary/legacy/settlement、Lua/retry/CAS/scan 回归；不宣称跨 SQL/Redis 原子或完整资金 tuple/CM18 M1–M4。

本切片只冻结执行时的写 fence，不扩展 API、PO、状态机或分布式 fencing 设计；CM20/CM21 仍等待 CM18 整体完成。
