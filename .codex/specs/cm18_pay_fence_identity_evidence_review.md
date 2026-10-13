# CM-18 16T174：Pay Fence identity evidence

## 1. 结论与范围

GO。PayTccExecutionEvidence 对 present Fence 只校 actionName/status，未核 Fence xid/branchId 是否与已证明 receipt/pay 相同；CI alias 行可能被当作正确 Fence，输出终态。

本批只补已有 Fence 行的来源身份门，不改 SQL、Fence 表、历史数据或其它资金域。

## 2. Exact Fence gate

当 `fences.findByBranch(pay.xid,pay.branchId)` 返回 non-null 时，除原 actionName/status 条件外必须满足：

- fence.xid 非 null 且 `Objects.equals(fence.xid, pay.xid)`；
- `Objects.equals(fence.branchId, pay.branchId)`。

不 normalize、不改 SQL 为 BINARY、不 repair 历史。Fence null 的原 cleanup/TRYING/terminal 语义保持；其它 receipt/execution/user tuple、amount、branch、state/RR/DTO/API 不改。

## 3. 验收与边界

- Unit：wrong xid case/space/null/foreign、wrong/null/zero branch，Coordinator recover 不 observe/no TC；matching canonical/cleanup 保持。
- Owned native：实际 Fence Mapper CI alias 返回 alien row，旧可 COMMITTED 的场景修后 conflict/full facts 保持；healthy TRYING/CONFIRMED/CANCELLED、Fence null/missing branch controls、原 Pay checksum/UUID cleanup/pool close。
- 测试使用 owned clone/实际 Mapper，不启动 Boot/TC/MQ；不宣称全 source/资金因果或全局 fencing。

## 4. 交付边界

实现仅允许在 Evidence present Fence predicate 增加 xid/branch exact proof、必要 JavaDoc/测试；不得修改 Fence SQL、handler、状态机或其它服务。
