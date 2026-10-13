# CM-18 16T114：User operation global key uniqueness

## 1. 结论与范围

GO。operation GET primary/source 与 linked 集合当前依赖 selectOne/集合 kernel，历史无 UNIQUE 或 wrong-origin 同键行可 500，或被漏掉后返回 200。

本批只在 operation GET 的同一只读 RR 快照内补已有 operation-key count proof；不修历史、不枚举全域、不扩 fresh/refund/replay 写路径。

## 2. Primary/source uniqueness

caller 校验后、primary point read 前调用既有 `countOperationCandidates(primaryKey)`：

- count=0 → 原 NOT_FOUND/404；
- count=1 → 执行原 point read，foreign/NULL/metadata/tuple proof 保持原 404/409；
- count null、负数或非 1 → COMMON_CONFLICT/409 固定“余额账本事实待核对”，不 point read；
- DAO/counter 异常原样传播。

REFUND primary proof 通过后，对 originalOperationKey 同样 count=1 才读取原 DEBIT source；0/非1沿 COMMON_CONFLICT，不伪造 404。primary 唯一但 foreign 仍由原 point read 返回 404。

## 3. Linked key membership

原 primary/source/metadata、linked/payment 集合、cap、selector、owner proof 全部通过后，对每个已观察 linked operationKey 调用 count，必须恰好 1；count 异常原样，0/非1 COMMON_CONFLICT。按现 bounded 集合上限控制 count 预算：DEBIT primary + 100 refunds 或 REFUND source/primary + 99 其它 refunds，最大 101 次 count。

不以 count 代替原字段/kernel proof，不过滤 wrong-origin/alias；当前 observed keys 只做 bounded membership。fresh/refund/replay 写路径不增加 count。

## 4. 保持项与验收

不改 API/VO/SQL 写、锁、RR、TCC、wallet、marker、owner/freeze；source/linked/metadata/kernel/marker 错误优先级保持，新增 count 不得 mask 旧错误。

- Native owned MySQL：测试库临时移除 UNIQUE 后制造 duplicate primary/source/refund、wrong-origin/other-pay/case alias；GET 从 500/隐蔽 200 变 COMMON_CONFLICT，四表 no-write，finally 删除额外行并恢复 UNIQUE/index。
- 健康无重复、100 集合、caller/404、RR 同连接、count 预算/DAO 通过；资源 cleanup 与 source checksum 保持。
- 不访问全域 key、不修历史、不宣称 source fencing。

## 5. 交付边界

实现仅允许 operation GET 复用既有 count Mapper、增加 bounded count helper/必要 JavaDoc/测试；不得改写入、其它 API 或资金状态机。
