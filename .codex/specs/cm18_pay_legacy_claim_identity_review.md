# CM-18 16T79：Pay Legacy claim identity

## 1. 结论与范围

GO。`PayConsistencyService` 当前使用实例级固定 owner；同一实例在租约超时后再次认领同一 payNo 时，迟到的旧处理与新处理拥有相同 owner，旧 retry/finish/updateOwnedStatus 可能匹配并覆盖新租约。

本批只修同一实例内的 claim identity ABA：每次成功 claim 使用独立 token。它不等价于完整 lease fencing，也不关闭远端读写、PayRecord 跨表或 Lua/SQL 窗口。

## 2. Token 生命周期

- 删除实例级 owner 字段。
- `process` 保留原 mode/route 检查、claim SQL、租约 30 秒、参数、状态字段及返回协议；claim 前生成本次尝试所需 token，只有 claim 返回 1 才进入处理。
- 成功 claim 后生成/持有一次 UUID claimToken，并将同一 token 作为原 owner 参数传入 `processClaimed` 及其所有下游路径：`resolveAfterUncertainOrderWrite`、`finishSuccess`、`finishRefunded`、`refundCancelled`、`failWithoutDebit` 以及所有 `updateOwnedStatus`/`recordRetry`/`finish` 调用。
- claim 失败不得进入 `processClaimed` 或远程资金调用；仍读取并返回当前可见状态。每次新的成功 claim 必须有新的 token，即使同一 Service 实例、同一 payNo。

## 3. 保持项与限制

不改变 claim/lease SQL、30 秒租约、状态字段、PayRecord 更新、User/Order RPC 顺序、Legacy mode 隔离、T77 refund proof、API/Feign/DTO、Redis、消息、TCC/RM 或恢复批量窗口。token 只作为既有 owner 条件的调用值，不新增 schema/字段或 public API。

本批仅防实例 owner 复用造成的 ABA；不声称解决 lease 过期本身、所有 UPDATE 受影响行 fencing、PayRecord 与 intent 的跨表原子性、远端 source 读到写窗口或 Lua/SQL fencing。后续失败路径仍按现有 retry 语义处理。

## 4. 验收

- Unit：同一 Service 两次成功 claim 的 token 独立；每次下游 write 使用对应 token，claim 失败不进入处理。
- Owned MySQL/线程控制：A 成功 claim 后在首次 User GET 等待；租约过期后 B 用同一 Service 成功 claim。A 随后 GET 故障时，其 retry 不得清 B 租约、改变 B intent 或覆盖 B owner；B 自己失败时仍可按自身 token 写 retry。使用 latch/受控时钟，不用 sleep；线程、executor、连接 finally 清理。
- 不执行真实资金 RPC 的竞态验收；Pay mapper 事实、source checksum 与 owned 库资源按正例/失败路径核验。测试不伪称完成跨服务 fencing。

## 5. 交付边界

实现仅允许修改 `PayConsistencyService` 的 owner/token 生命周期、相关私有方法参数与必要 JavaDoc/步骤注释及测试；不得扩展 Mapper SQL、租约算法、API 或其它服务。
