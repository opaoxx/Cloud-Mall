# CM-18 16T119：User REJECTED GET receive exclusion

## 1. 结论与范围

GO。REJECTED operation GET 已完成主行/集合/marker 与 Legacy owner/freeze proof，但仍忽略同 pay `user_tcc_receive`；CLAIMED/坏 receive 可被返回 200。

本批只补 REJECTED GET 的 same-pay receive existence gate，不改 APPLIED/T112、写服务或其它来源 proof。

## 2. Gate 位置与规则

在原 selector/current membership、REJECTED owner/freeze proof 通过后、global key count/VO 前读取一次现有 `UserTccReceiveMapper.findByPayNo(original.payNo)`：

- 任意 non-null row（CLAIMED、unknown state、CI/foreign、坏 metadata）→ `ErrorCodes.CONFLICT/409` 固定“余额账本事实待核对”；
- 仅 null 才保留 REJECTED GET 成功；
- DAO/DataAccess 异常原样传播，不按 state/meta/flag 过滤。

同一 read-only RR；复用 T117 表初始化。不能读 current wallet、写、修历史、解绑 Fence 或扩展 receive proof。

## 3. 保持项与验收

APPLIED GET、refund、Legacy 写/replay、TCC、主行/source/集合/marker/owner/global counts 优先级与查询预算保持；existing receive gate 不影响其它路径。

- Unit：null/any row/DAO/owner/primary/collection priority，合法有/无 Legacy owner；
- Native：REJECTED GET × owner/no owner × receive variants，均 same RR/no-write/facts unchanged；健康 GET/source/100 collection 继续通过。
- 不启动 RM/TC/App，不宣称 receive metadata/Fence/全账户因果；资源 finally 清理。

## 4. 交付边界

实现仅允许在 REJECTED GET owner proof 后增加现有 receive point read、必要 JavaDoc/测试；不得修改 APPLIED/写路径或其它 state scope。
