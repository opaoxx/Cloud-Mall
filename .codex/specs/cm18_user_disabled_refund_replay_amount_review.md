# CM-18 16T106：User disabled refund replay amount

## 1. 结论与范围

GO。disabled existing refund 当前可通过 T74 original DEBIT/TCC proof 与 T73 REFUND row proof，即使该单笔 refund amount 大于原扣款仍返回成功。T105 只覆盖 active existing。

本批只补 disabled existing replay 的 amount upper bound；不扩完整退款集合或 TCC 因果。

## 2. 门位置

保留 disabled existing 的原顺序：

1. 读取 User 行并读取 `original`；
2. T74 `requireDisabledTccRefund(original, request, userId)`，失败继续 404；
3. T73 `ensureRefundMatches(existing, userId, request)`，失败继续 `IDEMPOTENCY_CONFLICT/409`；
4. 在返回 VO 前追加 `existing.amount <= original.amount` 数值比较。

超额沿 active/T105 同一 `BALANCE_REFUND_INVALID`/409 与原 message；等额、尾零和合法边界保持 200。original 使用已读对象，不新增查询；DAO/source/TCC 错误优先级不变。

## 3. 保持项与边界

active T105 source 门、disabled 坏 source 404、T73 row proof、fresh/T75/wallet/ledger、SQL/锁/事务/API/Redis 保持。existing replay 不 credit/insert/cache，不读取集合、owner/freeze 或 current wallet。

不处理全退款累计、账户因果、历史修复或 source fencing。

## 4. 验收

- Unit：disabled source/TCC 失败优先 404、坏 row 409、amount null/坏/超原额 409、equal/tail-zero 成功、无新 query/no credit。
- Native：真实 TCC refund 与原 source/attempt/freeze facts，amount=1 等额成功、amount>original 409，四表 facts/checksum 不变；active/replay/fresh regression 保持。
- 资源 finally 清理，不启动 RM/TC/App；不扩查询计数。

## 5. 交付边界

实现仅允许保存并复用 disabled existing 已读 original、增加 amount 比较及必要 JavaDoc/测试；不得改 source/TCC helper、active T105、fresh 或写路径。
