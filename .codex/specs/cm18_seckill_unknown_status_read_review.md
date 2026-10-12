# CM-18 16T56：秒杀未识别状态 GET fail-closed

## 1. 结论与范围

本切片 GO，修正五状态 source gate 之外的未知 settlement status 仍输出 `UNKNOWN/items=[明细]` 的问题，防止未经状态协议与 source 证明的 SKU 明细泄漏/误用。现有 Order fallback 已保持 UNKNOWN 控制；本批不宣称旧路径曾错误完成，只收窄 reservation GET 输出，不改写路径、状态机或 source 事实。

## 2. GET 顺序与未知状态门

保留既有顺序：order identity → `validateSettlement` dimensions → operation key/RELEASED receipt 门。随后只对五个已知状态 `OPEN/RELEASE_PENDING/PROJECTION_BLOCKED/CONFIRMED/RELEASED` 调用 T46 source getter；未知、CONFLICT、null、大小写/空白 alias 等非五状态直接返回请求 orderNo echo、`UNKNOWN/items=[]`。

未知状态短路不得执行 source SQL、不得落普通 fallback、不得修复/迁移/写数据库；不规范化状态文本，不回显原状态。维度损坏仍保持原 `SECKILL_SETTLEMENT_CORRUPT` 500，identity 错误仍优先 T31 UNKNOWN，operation/receipt 坏门仍优先原 UNKNOWN。

合法五状态继续 T48 source gate 与原 status/items；legacy 缺 settlement、settle/noop/CAS、recovery、Lua/API/DDL 和写协议不改。

## 3. 验收矩阵与边界

- Unit：null/unknown/case/space status matrix、valid source/mock fault、identity/dimension/operation priority、known controls 和 legacy no-source；断言未知无 source interaction、UNKNOWN/items0。
- Owned SQL/Controller/Order fallback：未知状态含 source 有/缺、source tuple 坏只在真实 SQL 中验证；GET/outer 只返回 UNKNOWN/items0、不查询 source，Order fallback 保持 UNKNOWN 控制且不完成，facts/SQL 无写，资源 finally 清理。生产 NOT NULL status 的 null/非法值仅 unit 或 owned 准备阶段演练，不 ALTER。
- 五状态合法 source、T31–T55、普通库存、legacy、transition/settle/recovery 回归保持；不声称 unknown source provenance 或全资金因果已证明。

本切片只冻结未知状态 GET 的 fail-closed 明细，不处理 full tuple、TC/MQ、跨 store 窗口或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
