# CM-18 16T91：User TCC callback Fence identity

## 1. 结论与范围

GO。`BalanceTccDebitLocalResourceImpl.finish` 当前对已有 Fence 只核 actionName；在 CI 候选读到 foreign XID/branch 后，官方 Seata Fence handler 可能继续处理错误来源。

本批只补 incoming Fence 的精确身份门，不改官方 handler、Mapper SQL、资金规则、API 或 Seata 上下文。

## 2. Callback identity gate

新增纯字段 helper `matchesIncomingFence(fence, context)`，要求：

- Fence 非 null 时 `xid` 精确等 `context.getXid()`；
- `branchId` 精确等 `context.getBranchId()`；
- `actionName` 精确等本地 `ACTION_NAME`。

在 `finish` 的外层事务中、任何官方 `TCCFenceHandler.commitFence/rollbackFence` 或业务 callback/funds 调用之前执行。Fence 非 null 且身份不匹配直接返回 false，设置事务回滚，不调用官方 handler、不调用 callback、不写资金或 evidence。保 Fence 缺失时原 terminal proof/官方空回调语义。

`rejectedBranchHasNoFunds` 的二次 Fence 读取也必须复用同一 exact identity 门，再按原 status=4、incoming count=0、owner 搜索与 status 条件处理；不解绑 RootContext、不改变 caller 上下文。

## 3. 保持项与边界

不新增 SQL/Mapper、版本依赖、官方 handler wrapper、状态/金额/账本规则、API、DDL 或新 Fence status。保原 actionName 错误且已有测试期望的 false、Fence 缺失 terminal proof、乱序/空回滚、callback 及本地资金事务。

本批只证明回调 Fence 来源的 XID/branch/action identity；不证明 owner 全 tuple、跨租约 fencing、全局 TC、远端 RM 或完整资金因果。

## 4. 验收

- Unit：wrong XID、wrong branch、null identity、wrong action 的 existing Fence 在 commit/rollback 均 false 且无 funds/attempt/evidence callback；exact identity 与 Fence 缺失对照保持原协议。
- Owned MySQL/官方 handler：真实 owned Fence CI alias（case/accent/PAD-space 仅测试库）被 exact 门拒绝，不改变五类资金事实；healthy exact commit/rollback 及 terminal replay 通过，删 Fence 后原 terminal proof/空回滚保持。
- 使用官方 Fence handler 与同 owned DataSource/REQUIRED 事务代理，finally 恢复静态/测试上下文；不启动 TC/RM/App，不宣称真实网络全链路。
- 保留 T88–T90、User checksum、source/owned 清理和既有 Seata 回归。

## 5. 交付边界

实现仅允许增加 LocalResource 的 incoming Fence identity helper/两个读取点、必要 JavaDoc/测试；不得修改官方 handler、Mapper SQL、业务 callback 或资金状态机。
