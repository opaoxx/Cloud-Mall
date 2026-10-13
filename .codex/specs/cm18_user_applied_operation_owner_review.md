# CM-18 16T112：User APPLIED operation owner proof

## 1. 结论与范围

GO。operation GET 已有主行/source/双集合 proof，但 APPLIED DEBIT source 未核当前 Legacy/TCC owner 与 freeze；ghost owner/freeze 可被 Pay recovery 当成功账本使用。

本批只为 operation GET 的 original.state=APPLIED 增加 T108 owner proof；REJECTED、REFUND marker、fresh/replay 写路径不扩展。

## 2. GET owner gate

在原主行/source、linked/payment 集合、累计 cap、REJECTED 限制、selector/current membership 全部通过后、VO 返回前调用纯 `requireAppliedDebitOwnership(original)`：

- attempt null：仅同 pay freeze point 为 null 才通过；
- LEGACY：attempt APPLIED、user/pay/order/operation/ledger tuple 精确、xid/branch null、amount 复制 original，且 freeze point null；
- TCC：attempt CONFIRMED 完整 tuple/amount，freeze CONFIRMED 精确复制 original；
- unknown/bad owner/freeze/DAO：映射现有 `COMMON_CONFLICT/409`“余额账本事实待核对”，DAO 异常原样。

为避免伪造 request DTO，将 T108 helper 改为以已证 original 的 operationKey/payNo 读取；active fresh/disabled wrapper 保持原 request 语义与顺序。GET APPLIED 最多增加 attempt 与 freeze 各一次读取，同一 RR。

## 3. 保持项与边界

REJECTED 不新增 owner query；REFUND original APPLIED 先保 source proof 后复用 original owner gate；marker/REFUND null、404、集合/selector 语义保持。API/VO、SQL 写、current wallet、receive/Fence、TCC callback、全账户/source fencing 不改。

## 4. 验收

- Unit：nil/healthy Legacy/TCC、坏 owner/freeze、DAO/source priority、query budget；REJECTED 无 owner read。
- Native：DEBIT/REFUND GET × Legacy/TCC ghost owner/freeze 变体返回 409，四表 facts 不变；healthy 100 collection、REJECTED、User checksum/资源清理保持。
- 同 RR/no-write 断言，fresh/replay/disabled/T70–T111 回归，不启动 RM/TC/App。

## 5. 交付边界

实现仅允许提取 T108 helper 为 original-only owner proof 并接入 GET、必要 JavaDoc/测试；不得改变 fresh/replay/disabled 或集合/写路径。
