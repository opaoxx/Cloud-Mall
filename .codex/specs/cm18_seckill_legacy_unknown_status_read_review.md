# CM-18 16T57：秒杀 legacy 未知状态明细 fail-closed

## 1. 结论与范围

本切片 GO，修正 Stock outer legacy Map 在 order/SKU/context 合法但 status 非 `ACCEPTED`/`REJECTED` 时仍返回 `UNKNOWN/items=[SKU]` 的明细泄漏。它只收窄未知 legacy 状态输出，不改变主 reservation、普通 flows、SQL、Redis、DTO/API、事务或状态机。

现有 Order fallback 对 UNKNOWN 已保持不完成；本批不宣称旧路径曾错误完成，只保证未经协议/source 证明的 legacy 明细不继续外泄或被误用。

## 2. Status gate

在 T31 order identity、T33 SKU exact guard 和 T49 shared context validator 通过后，读取既有 `acceptedStatus`：

- `ACCEPTED` 保持 `RESERVED/items=1`；
- `REJECTED` 保持 `REJECTED/items=1`；
- 其它非 null 状态（PENDING、PUBLISHED、ORDER_CREATED、UNKNOWN、大小写/空白 alias 等）直接使用原请求 orderNo 返回 `UNKNOWN/items=[]`；
- null/缺失 status 也返回 `UNKNOWN/items=[]`，不规范化、不回显原 status，不落普通 fallback。

主 `SeckillQuotaSettlementService.reservation` 的非 ACCEPTED null 语义、主 ACCEPTED 500/context 门、T31/T33 优先级保持；本批不把 PUBLISHED/ORDER_CREATED 改为 RESERVED，也不新增 source query。

## 3. 验收与边界

- Unit：未知状态/NULL matrix、合法 ACCEPTED/REJECTED、SKU/context/identity priority、Controller/legacy no-source；断言未知无额外 SQL、items0、Order UNKNOWN 控制。
- Owned T33 SQL/Controller/Order fallback：PENDING/PUBLISHED/ORDER_CREATED/UNKNOWN/alias 等状态在真实 Map/SQL 中返回 UNKNOWN/items0，facts/checksum 不变；正常两状态 items1，主 ACCEPTED 保持，资源 finally 清理。
- 不改 source status 存储、SQL/Redis/消息/普通库存写、DTO/API/事务；生产 NOT NULL status 的 null 仅 unit/owned 准备演练，不 ALTER。

本切片只冻结 legacy 未知状态明细输出，不处理 full request/hash/provenance/fencing、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
