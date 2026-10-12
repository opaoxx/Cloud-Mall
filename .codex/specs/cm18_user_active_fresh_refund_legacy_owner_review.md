# CM-18 16T99：User active fresh refund Legacy owner

## 1. 结论与范围

GO。T98 active fresh refund 已观察 attempt 时只对 non-Legacy 模式强制 TCC proof；非 null LEGACY attempt 仍绕过 owner tuple/state，错误 user/pay/key/amount/xid 可能继续 credit/insert。

本批只补 active fresh 的 non-null LEGACY attempt proof；不扩大 null-owner 历史兼容，不改 TCC/disabled/existing replay。

## 2. Legacy attempt proof

在 T98 active fresh attempt 读取后：

- attempt null：保持历史 active Legacy 兼容，不伪造完整归属证明；
- attempt mode `LEGACY`：必须满足纯字段 predicate：state `APPLIED`；userId/payNo/orderNo 与 original/request 精确相等；operationKey 与 ledgerKey 均精确等 request.originalOperationKey；xid、branchId 均 null；amount 非 null、正且合法 DECIMAL(18,2)，数值等于已证明 original.amount；
- mode 为 TCC：复用 T98 `confirmedTccRefund(original, request, attempt)`；
- mode null/其它未知：沿 active `BALANCE_REFUND_INVALID`/409。

LEGACY predicate 失败沿同一 `BALANCE_REFUND_INVALID`/409 与原 message；不构造伪 DTO，不复用 Legacy debit 异常码，不新增查询/SQL/锁。

## 3. 保持项与边界

attempt 读取仍位于 T97 source proof 后、T75 aggregate 前；existing replay 不读取新门。disabled 仍由 T74 先做原 source/TCC 404；active null attempt、健康 Legacy、TCC、wallet/ledger/T75 写链保持。

不改 DTO/API、SQL、状态、TCC callback、Redis、历史 owner 修复、全域 source fencing 或幽灵 TCC 归属；不宣称 CM18 整体闭环。

## 4. 验收

- Unit：Legacy attempt wrong state/user/pay/order/key/ledger/xid/branch/amount/null/bad money；active fresh 全部 409、无 credit/insert；null attempt、健康 Legacy、TCC、disabled/replay 查询计数保持。
- Native：真实 Legacy debit 后修改 attempt owner tuple，active fresh HTTP/service 返回 409，User 四表 facts/checksum 不变；healthy Legacy、无 attempt 历史、active TCC、disabled/replay 保持。
- 覆盖 zero extra query、source/T97 优先、资源 finally 清理；不启动 RM/TC/App，不扩历史全域。

## 5. 交付边界

实现仅允许增加 active fresh 的 LEGACY pure predicate/helper、必要 JavaDoc/测试；不得修改 null 兼容、TCC helper、disabled 404、existing replay 或资金写入。
