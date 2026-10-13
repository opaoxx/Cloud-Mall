# CM-18 16T134：Pay Legacy missing debit recovery

## 1. 结论与范围

GO。intent 已进入 DEBITED/ORDER_SYNC_UNKNOWN/REFUND_PENDING 后，User operation 404 仍可能被当作 fresh pending，向 PENDING_PAYMENT 再发 debit，或对 CANCELLED 直接 FAILED；缺失账本不能证明此前未扣款/未退款。

本批只增加推进状态与 operation 缺失的 fail-closed 门，不新增 SQL/RPC/字段/flag/lease。

## 2. Missing-source gate

在 processClaimed 已完成 Order envelope/identity/status proof 后、operation-null 的 CANCELLED early return 与 fresh debit 门之前：

- intent.status 精确为 `INIT`：保原行为；PENDING_PAYMENT 可发新 debit，CANCELLED 可 FAILED，PAID/COMPLETED 按 T133 等待/重试；
- intent.status 非 INIT 且 operation 为 null：抛 Runtime “已有资金恢复状态但原扣款账本缺失”；不发 debit/paid/refund，不 terminal/stage；
- 沿既有 catch 保持 `ORDER_SYNC_UNKNOWN` 或 `REFUND_PENDING` retry 状态与 owned token/lease 规则。

已观察 APPLIED/REJECTED 继续原逻辑；补证后的下一次 process 可恢复 SUCCESS 或取消退款。读取后 pay/user source drift、INIT 与 PayRecord 历史错配不在本批。

## 3. 保持项与验收

不改 Order/User API、SQL、RPC headers、claim/lease、T133 status/ACK gate、T79–T132、TCC/退款/Redis。missing gate 只看已在内存中的 intent.status，不新增 Mapper 查询。

- Unit/native：DEBITED/ORDER_SYNC_UNKNOWN/REFUND_PENDING × Order 四态 × Stock 四形态，operation 404 不发新 debit/paid/refund/terminal；INIT controls 保持。
- Owned Pay 两表 + controlled User/Order Feign：补入原账本 APPLIED 后下一次恢复成功/退款 pending；source checksum/资源清理，不启动完整服务/RM/TC。
- 既有 T133 paidOrderWithoutDebit 的 INIT 与三个推进意图样本，非 INIT 现按本批 missing-source Runtime 优先，旧 error message 断言需同步；其它状态/租约/预算断言保持。T133 ACK-bad-key 专项只保留 INIT 的八种 ACK 变体可进入 debit gate；DEBITED/ORDER_SYNC_UNKNOWN/REFUND_PENDING 的 ACK 行为由本批新增 48 状态矩阵覆盖，不削弱既有合同。
- 不宣称全资金因果、历史修复或 source fencing。

## 4. 交付边界

实现仅允许在 `processClaimed` operation-null 分支增加 intent status gate、必要 JavaDoc/测试；不得修改 fresh INIT、已观察 operation 或其它恢复阶段。
