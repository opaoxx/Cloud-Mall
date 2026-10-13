# CM-18 16T152：Pay Legacy INIT retry no-regression

## 1. 结论与范围

GO。T151 阻断当前非初始 phase 的远端写，但 catch 仍可能使用较早 INIT 快照将已 DEBITED/ORDER_SYNC_UNKNOWN intent 重置 INIT，清 lease/error/nextRetry。

本批只保护 `recordRetryOwnedLegacy` 的 canonical INIT retry，不改变 retry 计算或其它目标状态。

## 2. INIT guard

在现有 owned/lease/LEGACY/active/EXISTS 与 T149 REFUND_PENDING no-regression 条件之外追加：

`AND (BINARY ? <> 'INIT' OR status = 'INIT')`

末尾绑定本次 retry target status；仅 canonical target `INIT` 要求当前 status 仍为 INIT（保留 status 列既有 CI 等价语义）。当前 DEBITED、ORDER_SYNC_UNKNOWN、REFUND_PENDING 时 INIT retry 返回 0，不清 lease/error/nextRetry；其它 target（ORDER_SYNC_UNKNOWN/REFUND_PENDING）保持原条件。

第二写 0 沿 T145/148 retry transaction rollback/caller 等待；不猜当前状态、不主动释放 lease，下一轮 lease 到期重新 route/proof。此门仅 canonical INIT，不扩展 target 词法迁移。

## 3. 保持项与验收

T151 permission、stage/terminal、retry error 512/退避、SQL/API/owner/lease/status 计算保持；不宣称完整 process fencing。

- Native：旧 INIT snapshot→当前 DEBITED/ORDER_SYNC_UNKNOWN（含 CI/尾空格）时 INIT retry 不改字段；INIT→INIT 允许；advanced→合法 ORDER_SYNC/RF target 保持；T151 permission 阻断 debit，catch INIT SQL 被拒，lease 到期同 pay 原 APPLIED/PAID proof 可 SUCCESS，无新 debit/paid。
- Unit/owned：目标 canonical、owner/lease/phase、second0/异常、资源 checksum/清理。

## 4. 交付边界

实现仅允许修改 `recordRetryOwnedLegacy` INIT WHERE/末尾参数与必要 JavaDoc/测试；不得修改 Service retry 计算、RPC permission、stage/terminal 或旧通用 retry。
