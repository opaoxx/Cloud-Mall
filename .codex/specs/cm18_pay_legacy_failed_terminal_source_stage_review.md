# CM-18 16T153：Pay Legacy FAILED terminal source stage

## 1. 结论与范围

GO。FAILED path 仍可覆盖较早 INIT/absence 观察后已变为 DEBITED/ORDER_SYNC_UNKNOWN 的 intent；T150 只保护退款方向，T152 只保护 INIT retry。

本批只保护 FAILED terminal 的当前 payment/intent stage pair：owned PayRecord FAILED 写与 intent finish 均必须对应 INIT/PENDING 起点。

## 2. FAILED finish guard

在 `finishOwnedLegacy` 现有 REFUND_PENDING 条件后追加：

`AND (BINARY ? <> 'FAILED' OR status = 'INIT')`

末尾绑定 target status；canonical FAILED 只允许当前 status INIT（保留 status 列现有 CI INIT 等价语义）。DEBITED/ORDER_SYNC_UNKNOWN/REFUND_PENDING 返回 0；T141 terminal transaction 回滚 PayRecord/paid_at，原 catch/wait 保持。不猜失败、不新资金、不迁移 target 词法。

其它 target、REFUND_PENDING→REFUNDED、100年 nextRetry、SET/owner/deadline/mode/API/旧 finish 保持。

同时仅收紧 Legacy owned PayRecord 的 FAILED 方法：其 intent 条件必须为 `status='INIT'`，payment 条件必须为 `status='PENDING'`（沿现有列 CI 语义）；原 mode/owner/deadline/BINARY payNo/SET 保持。这样只有 INIT/PENDING 的合法 pair 能进入 FAILED，PayRecord ORDER_SYNC_UNKNOWN/intent INIT 或 intent advanced/PayRecord PENDING 任一观察后 advanced pair 均在第一写返回 0。旧通用 `markFailed` 与 TCC caller 不改。

## 3. 验收

- Native：stale INIT→current DEBITED/ORDER_SYNC_UNKNOWN（CI/尾空格）或 PayRecord ORDER_SYNC_UNKNOWN/alias + intent INIT 的 FAILED 均被第一写拒绝，full facts/lease/error 保持；current INIT/PENDING 合法 FAILED 与其它终态保持。
- 实际无账本取消末次 User GET404 时 DB 变 advanced，FAILED 终态 0 回滚；T152 INIT retry 亦拒绝；lease 过期补 APPLIED/取消证据后可 REFUNDED，不新增 debit/paid。
- Unit/owned 验证 target/owner/lease/source priority、terminal rollback、资源 checksum/清理；不启动 App/RM/TC。

## 4. 交付边界

实现仅允许修改 `markOwnedLegacyFailed` 的 INIT/PENDING pair guard、`finishOwnedLegacy` 的 canonical FAILED guard/末尾绑定参数及必要 JavaDoc/测试；不得修改 Service 状态、FAILED source read、T152 retry、SUCCESS/REFUNDED、其它 owned 写或 terminal transaction。
