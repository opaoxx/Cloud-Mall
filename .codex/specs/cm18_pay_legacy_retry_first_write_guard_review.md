# CM-18 16T148：Pay Legacy retry first-write guard

## 1. 结论与范围

GO。T145 retry transaction 保留 first PayRecord update=0 时仍执行 intent retry 的兼容语义；当 PayRecord 已 REFUND_PENDING、intent INIT 时，first cleanup 0 却会写 intent ORDER_SYNC_UNKNOWN，分裂两表并清理 lease/error。

本批只收紧 ORDER_SYNC_UNKNOWN retry 的 first-write affectedRows gate。

## 2. Guard

在 `recordOwnedLegacyRetry` 中，ORDER_SYNC_UNKNOWN 的 PayRecord cleanup 返回值必须为 1 才执行 intent `recordRetryOwnedLegacy`；返回 0 则 `setRollbackOnly` 并立即 return，不写 intent、不抛新业务异常，caller 仍返回原 retryStatus/log。

first write Runtime 继续沿 TransactionTemplate rollback 传播；source lock、RootContext、second write 0/异常、非 ORDER_SYNC_UNKNOWN 单 intent retry、SQL/退避/API/stage/terminal 保持。

不从当前 Pay 猜新状态、不主动释放 lease；补证或 lease 自然过期由下一轮原 route 处理。本规则取代 T145 “first0+second1 可提交”边界，仅针对 ORDER_SYNC_UNKNOWN first cleanup。

## 3. 验收

- Unit：first0 no intent write、first1/second0 rollback、fault/source-null/Rootguard/REFUND_PENDING 单写；全 facts/lease/error 不误清理。
- Native：Pay REFUND_PENDING、intent INIT、first cleanup 0 保持两表事实；Order 期间 Pay 变 REFUND_PENDING 后 read fault 不写 intent、不发 stage/RPC；lease 到期下一轮按退款方向恢复。healthy first1 回归。
- 不启动 App/RM/TC，不宣称跨服务 fencing；资源 checksum/cleanup 保持。

## 4. 交付边界

实现仅允许修改 retry callback first-write guard、必要 JavaDoc/测试，并同步 T145 first0 compatibility 断言；不得修改非 ORDER_SYNC_UNKNOWN、stage、terminal 或状态计算。
