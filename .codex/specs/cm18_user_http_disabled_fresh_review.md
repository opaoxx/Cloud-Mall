# CM-18：User HTTP disabled fresh TCC prepare

## 1. 结论

当前 `UserTccHttpService.prepare` 只检查用户存在，不检查 status；disabled 用户的 fresh 请求会先 `UserTccReceiveStore.claim` 提交 CLAIMED receipt，再进入 LocalTCC，随后 `tryDebit` 才以“用户不可用”拒绝。该行为永久消耗一次接收/派发权，属于真实资金入口缺口。

已有 receipt 的 replay 必须保持可读，不能在 HTTP 层用简单 status 门拦截所有请求。

## 2. 最小修复契约

扩展内部签名为 `claim(Long userId, UserTccTryDTO request, Integer userStatus)`，prepare 复用一次 `users.selectById` 的结果传入；不得保留无 status 的绕过 overload。Store 在同一 `REQUIRES_NEW` 接收事务内区分 winner/replay：

- `INSERT IGNORE` 影响 0：已有 receipt replay，沿原 lock/payload proof/query.read 路径继续，不因当前用户 disabled 重新 claim；
- 影响 1 且用户 status 非启用：在原 `INSERT IGNORE`→lock→payload proof 成功后、事务提交前抛 `IDEMPOTENCY_CONFLICT/409`“用户不可用”，使本次接收事务回滚，不能调用 `resource.prepare`、绑定 RootContext 或进入 Try；
- 影响 1 且用户 status 启用：沿原 claim→LocalTCC→state/bind 流程。

重复接收（insert=0）与坏 payload/身份校验优先于 status guard；该门只保护 prepare 已观察到的 status，不宣称 read→claim 之间 status 漂移的 fencing。该门不新增 HTTP endpoint、字段、表或认证方式；不解绑 caller XID，不改变既有 replay/branch/fence 事实。

## 3. 保持项

用户不存在、载荷/认证/XID 校验优先级保持；HTTP 默认开关、callback token、LocalTCC resource、receive DDL、TCC/RM/GET/state API 不改。disabled existing receipt replay 仍只读状态，不注册第二次分支；disabled fresh 不留下 receipt、attempt/freeze/funds 写或派发权。该冲突使用“用户不可用”409，不使用表示配置/开关故障的 `PAY_DEBIT_MODE_UNAVAILABLE/503`。

## 4. 验收

- Unit：disabled fresh 在 claim winner 后抛固定不可用错误、`resource.prepare`/bind/query 不调用、事务回滚；disabled existing replay 仍 query.read；enabled fresh/replay 保持。
- Owned MySQL：真实 HTTP/Service prepare 用 disabled 用户，确认 receive 表无残留、User 六类资金事实不变、无 Fence/attempt/freeze/余额写；同用户已有合法 receipt 时只读 replay。
- 复现竞态边界使用既有事务与资源清理，不宣称覆盖用户 status 在读取与 claim 之间变化的完整 fencing；该窗口另行处理。
- 不启动完整 TC/RM/App，不修改外部 Pay 合同。

## 5. 交付边界

实现仅允许调整 UserTccReceiveStore claim 的内部 status/winner 分支、prepare 调用参数和必要测试/JavaDoc；不得在 prepare 外先写 receipt，也不得拦截已有 replay。
