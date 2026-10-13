# CM-18 16T136：Pay Legacy rejected debit recovery

## 1. 结论与范围

GO。operation=REJECTED 当前在 Order read 后（除 PAID/COMPLETED）直接 FAILED，可能抹掉 intent/PayRecord 已进入 DEBITED/ORDER_SYNC_UNKNOWN/REFUND_PENDING，或忽略 refundOperationKey 标记。

本批只收紧 rejected operation 的失败条件，不改已观察 APPLIED、RPC、Order write 或 TCC。

## 2. Rejected gate

在既有 `validateDebitOperation`、REJECTED Order read 与 PAID/COMPLETED 保护后，仅以下组合允许原 `failWithoutDebit`：

- intent.status 精确 INIT；
- observed PayRecord status 精确 PENDING；
- operation.refundOperationKey 精确 null。

其它组合（已推进 intent/PayRecord、marker 非 null、未知状态）抛 Runtime “扣款拒绝事实与资金恢复状态不一致”，不发 User/Order/退款 RPC，不写 terminal。PAID/COMPLETED 的原错误优先保持。

catch/retry 沿 T135 projection：推进状态保 ORDER_SYNC_UNKNOWN/REFUND_PENDING，INIT+坏 marker 保 INIT；不改 claim/lease/SQL/API/Feign。

## 3. 保持项与验收

合法 INIT+PENDING+无 marker+非 PAID/COMPLETED 仍 FAILED；补正确 APPLIED 后同 pay 下一次 process 可 SUCCESS/REFUNDED。已发 RPC 不回滚，历史不修。

- Unit/native：11 种 intent/record 推进组合 × pending/cancelled Order；INIT/PENDING × 四 non-null marker × 两 Order；合法 rejected 控制、补证恢复控制。
- Owned Pay 两表 + controlled User/Order Feign，核 checksum/资源清理；不启动 App/RM/TC，不宣称全资金因果。

## 4. 交付边界

实现仅允许在 rejected operation 分支增加 intent/payment/marker gate、固定 Runtime/JavaDoc/测试；不得修改合法 FAILED、APPLIED、RPC 或状态机写路径。
