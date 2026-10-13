# CM-18 16T111：User fresh refund record capacity

## 1. 结论与范围

GO。T110 允许 fresh refund 在已有 100 条退款记录时再写第 101 条，而 GET/replay 的 bounded history 上限为 100，造成新建能力与读取证明边界矛盾。

本批只收紧 fresh 新记录容量；existing replay/GET 仍可观察合法既有 100 条，不自动修历史。

## 2. Fresh collection helper

将 fresh caller 的 `provenRefundedAmount(original)` 替换为 fresh-only `requireFreshRefundCollection(original, requestAmount)`，内部保持 T110 顺序与 kernel：

1. linked LIMIT101 读取并逐行证明；
2. payment LIMIT102 读取并执行 membership；
3. 全部 source/DAO/坏行/membership 先按原错误边界处理；
4. `linkedTotal + requestAmount > original.amount` 沿原 `BALANCE_REFUND_EXCEEDED/409`；
5. 超额检查通过后，若已证明 linked refund 数量 `>= MAX_REFUND_CANDIDATES (100)`，返回 `BALANCE_REFUND_INVALID/409` 固定“退款记录数量已达上限”；
6. 只有 `<100` 才返回 fresh caller 继续原 wallet/credit/insert。

不新增 SQL/读/锁/API/表，不放宽 kernel；历史超过100仍沿原累计拒绝，不修复、不截断。

## 3. 保持项

99→第100条可成功；已有100条可 existing replay/GET 证明，但不得新建第101条。金额超额优先于 capacity message，source/owner/DAO/坏集合优先级不变。T70–T110、disabled/active owner、wallet/ledger atomicity、API/TCC 保持。

## 4. 验收

- Unit/native：99/100/101 边界、三模式 POST/service、99→100 并发仅一次成功、100 条 facts/余额只加一次、成功第100条 replay/GET 200；count/state fault 与金额优先。
- capacity 门位于 wallet 校验之前：count=100 且 wallet 同时坏时先返回容量错误；count<100 时保留原 wallet 错误语义。DAO、source/owner、坏集合与金额超额仍优先于 capacity；查询预算保持 T110 双集合一次各读。
- 更新旧 T75 “已有100可新增”预期为新规则，T110 100 replay 继续通过；资源 finally 清理，不启动 RM/TC/App。
- 不宣称完整历史/账户因果或自动修复。

## 5. 交付边界

实现仅允许新增 fresh-only collection helper/调用替换和必要 JavaDoc/测试；不得改变 existing replay/GET 的 bounded 观察能力或其它资金路径。
