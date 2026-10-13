# CM-18 16T127：User UNKNOWN state ledger scope

## 1. 结论与范围

GO。UserTccStateQuery attempt=null 当前只核固定 debit/fixed ledger 为空后直接返回 UNKNOWN；同 pay 其它 DEBIT/REFUND/未知账本可并存，导致 fixed-key 已冲突而 other-key 仍被报告 UNKNOWN。

本批有意扩展 UNKNOWN 分支的 no-funds observation scope；旧“UNKNOWN 不提供无资金证明”仅表示历史未覆盖该范围，本切片后不再维持其零查询预算。

## 2. UNKNOWN scope gate

在 attempt=null 且既有 debit==null、fixed ledger==null 门通过后，依次复用既有：

1. `requireEmptyDebitScope(payNo)`：全 state DEBIT LIMIT2，null/nonempty → `USER_TCC_STATE_CONFLICT/409`，DAO 原样；
2. `requireEmptyPaymentLedger(payNo)`：全 type/state LIMIT101，null/nonempty → 同 conflict；

只有两个列表均可靠 empty 才返回 UNKNOWN。此门不读 Fence/current wallet、不写、不修历史、不核 metadata/归因。

## 3. 保持项与验收

已存在 attempt、TRYING/CANCELLED/CONFIRMED、raw points、权限/receive/字段/RR/写路径不改；新增只针对 UNKNOWN attempt=null。固定 key 仍由旧门优先。

- Unit/native：null/empty/DAO/priority；same-pay DEBIT APPLIED/PENDING/REJECTED、REFUND、unknown type/state、foreign、CI other-key 全部 conflict；healthy empty 仍 UNKNOWN。
- 查询预算明确由原 UNKNOWN 0 增为两次 bounded reads；旧测试的零预算断言需同步为本切片后的两次读取，不得通过移除 no-write/RR 断言来适配。
- 六表/source facts/同连接 readonly 保持，不启动 RM/TC/App；不宣称完整历史/账户/fencing。

## 4. 交付边界

实现仅允许在 attempt-null branch 增加既有两个 empty-scope helpers、必要 JavaDoc/测试；不得修改 known states 或资金状态机。
