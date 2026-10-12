# CM-18 16T84：Pay Legacy recovery original tuple

## 1. 结论与范围

GO。Pay recovery 当前只检查 intent 与 payment 的两种 mode，未重新证明两表的原始 pay/order/user/amount tuple；坏 intent 因而可能领取 lease 并向 User/Order 发送基于错误事实的请求。

本批只增加 Legacy recovery 的原 payment tuple proof，不扩大 PO、API 或远端证据范围。

## 2. 统一 route proof

将现有 route helper 收窄为 `requireLegacyRoute(requestedPayNo, intent)`，在每个既有检查点调用，并保持 mode 优先：

1. 先按现有 `PayDebitModes.requireSupported(intent.debitMode)` 处理 null/未知 mode；
2. 用现有 `payRecordMapper.selectByPayNo(requestedPayNo)` 读取原支付记录，必须恰好一行；缺失/多行沿既有 unavailable/503 语义；
3. 原记录 debitMode 再按既有支持集合校验；
4. 对 tuple 做精确证明：requestedPayNo 非 null/blank；intent.payNo 与 record.payNo 均非 blank 且精确等 requested；intent.orderNo 与 record.orderNo 均非 blank 且相等；intent.userId 与 record.userId 均正且相等；intent.amount 与 record.amount 均非 null、正、无损落在 DECIMAL(18,2) 且数值相等。

tuple 不符返回固定 `COMMON_CONFLICT`/409（“支付意图与原支付记录不一致”），不进入 claim 后 RPC/中间 stage/terminal。mode 检查仍优先于 tuple conflict，保旧 503 兼容。

## 3. 三个检查点

- process 领取前核 pending 与 requested payNo；
- claim 返回 0 后读取 existing 时用同一 requested key 核验；
- claim 成功后重新读取 intent 时再次核验。

若成功 claim 后第三点失败，保持本次 claim 已发生这一事实；本批只保证不继续 RPC、stage、retry 或 terminal，不伪称已回滚 claim 或无任何数据库变化。`recoverDue`、`initiate` 继续通过 process 获得同一门。

## 4. 保持项与边界

不改原 SQL/claim 30 秒 lease、状态、token、T80–T83 本地事务与上下文门、API/DTO/RPC 顺序、TCC、历史修复或完整 source provenance。观察之后的 tuple drift、claim 后写窗口与完整 fencing 仍未解决。

## 5. 验收

- Unit：intent/payment 各 pay/order/user/amount 单字段及双表坏域、金额尾零健康、mode null/unknown；覆盖领取前、claim0 重读、claim 后重读三个检查点。
- Owned MySQL：preclaim 修改事实时 zero claim/RPC；after-claim 仅测试 owned 变更时不得发 RPC/stage/retry/terminal，但保留 claim 事实；验证全 facts/checksum 与资源清理。
- 保留正常 Legacy 三终态、initiate/scan、T79–T83 与 TCC/mode 回归；不启动完整 App/RM/TC，不宣称观察后的 source fencing。

## 6. 交付边界

实现仅允许调整 route helper 参数/tuple 纯校验及三个既有调用点、必要 JavaDoc/测试；不得新增 PO、SQL、接口、模块或远端协议。
