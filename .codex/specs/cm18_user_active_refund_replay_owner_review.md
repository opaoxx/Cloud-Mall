# CM-18 16T108：User active refund replay owner

## 1. 结论与范围

GO。T105 active existing replay 只核原 DEBIT/source 与单笔 refund cap，仍可能忽略当前 TCC/LEGACY owner 的坏 tuple、null owner 或 ghost freeze；fresh 已有 T98–T101 完整本地门。

本批提取 fresh owner proof 为私有 helper，并复用于 active existing replay；不改变 disabled、fresh 集合或资金写入。

## 2. Replay owner gate

在 active existing 的 T105 source/amount 门通过后调用 `requireActiveRefundOwnership(original, request)`，复用 fresh 的相同规则：

- attempt null：仅当同 pay freeze point read 为 null 才保留历史兼容；任意 freeze row → `BALANCE_REFUND_INVALID`/409；
- non-null LEGACY：必须 APPLIED、user/pay/order/operation/ledger key 精确、xid/branch null、amount 合法且复制 original；通过后同样要求 freeze point null；
- TCC：必须 CONFIRMED attempt tuple 与 freeze 完整匹配；
- unknown mode/坏 owner/freeze/DAO：沿 active `BALANCE_REFUND_INVALID`/409 或 DAO 原样。

抽取 helper 保持 fresh 原调用顺序与查询数；active existing 增加一次 attempt read，并按模式最多一次 freeze read。

## 3. 保持项与非目标

T73 row proof、T105 source/cap、disabled T74/T106 404、fresh T98–T104、wallet/ledger/集合、SQL/API/锁/Redis 保持。existing replay 不 credit/insert，不核集合/current wallet 之外的新资金事实。

不处理 owner 全域、receive/Fence、全历史、完整退款因果或 source fencing。

## 4. 验收

- Unit：source/T73 先级、null/Legacy/TCC/unknown、坏 tuple/freeze/DAO、查询计数、历史 null+无 freeze、active replay no-write；disabled/replay/fresh controls。
- Native：真实 Legacy/TCC refund 后错 owner/freeze、删 owner 留 freeze，active HTTP/service 409，四表 facts 不变；healthy Legacy/TCC、wallet NULL、multi-refund、disabled/replay 保持。
- 资源 finally 清理，不启动 RM/TC/App；不镜像坏夹具放宽 helper。

## 5. 交付边界

实现仅允许提取并复用 active fresh owner helper、增加 active existing 调用及必要 JavaDoc/测试；不得修改 disabled/fresh/T75/T105 写链。
