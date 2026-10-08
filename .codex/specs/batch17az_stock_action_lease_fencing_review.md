# CM-20260924-20：库存动作恢复租约 fencing 评审

## 1. 评审结论与范围

结论：**GO，限定为库存动作恢复完成/重试写入的有界租约 fencing。**

当前 `OrderStockActionRecovery` 使用实例级固定 owner；同一实例下一轮重新认领同一行时仍可能使用相同 token。`OrderStockActionMapper.finish/retry` 只按 `id + lease_owner + status` 判断，旧 worker 在 lease 过期或被接管后可能覆盖新 worker 的结果。

本轮仅增加每次候选的新 token 和 `finish/retry` 的有效租约 CAS，不修改 `findDue/claim` 的过期条件、数据库 schema、公开方法签名、状态、Stock 调用撤销、其他恢复器或其他合法性校验。

本规格 supersede 17AY 中关于“本轮不新增 lease_until 到期门”的排除项；该 supersede 仅适用于本批 `OrderStockActionMapper.finish/retry`，不扩展其他 Mapper 或恢复链路。

## 2. Recovery token 传播

移除实例级固定 owner 对候选完成写的依赖，`recoverDue` 每处理一条候选时生成新的随机 UUID token：

1. 在该候选调用 `claim` 前生成 `claimOwner`；
2. `claim` 使用该 token；
3. claim 返回 1 后调用 `recoverOne(action, claimOwner)`；
4. 该候选内所有 `finish`、`retry` 必须使用同一个 `claimOwner`；
5. 下一轮或同实例下一次认领必须生成不同 token。

claim 返回非 1 时丢弃该 token，不进入 Stock 流程。不得用旧 token、实例常量或 action.operationKey 替代本次 token。保留原 claim 的 attempts、30 秒 lease、next_retry 和 `findDue` 顺序。

## 3. Mapper finish/retry fencing 契约

### 3.1 参数和非法 token

`finish`、`retry` 保持现有 public 方法签名及返回值。进入 SQL 前，owner 为 null 或 blank 时直接返回 0，不执行写入；其他参数语义不变。

### 3.2 SQL 条件

两方法保留原 `id` 定位、源状态 `PENDING/UNKNOWN` 和写入内容，并增加同一份有效租约条件：

- `lease_owner IS NOT NULL`；
- `BINARY lease_owner = BINARY ?`，大小写、重音、尾随空格均精确区分；
- `lease_until IS NOT NULL`；
- `lease_until > ?`，使用调用方传入的 fresh `now`；
- 使用同一 `now` 绑定本次 SQL 的 updated_at 和有效租约比较。

`finish` 仍写入传入目标状态、错误、updated_at 并清理 lease；`retry` 仍写入 UNKNOWN、错误、nextRetry、updated_at 并清理 lease。0 行更新表示 token/租约/状态已失效，Recovery 不追加 retry、不猜测成功、不发反向动作。

### 3.3 claim/findDue 保持

本轮不改 `findDue` 和 `claim` 的既有条件 `(lease_until IS NULL OR lease_until < now)`、30 秒 lease、attempts+1、LIMIT/排序或参数签名。等于边界仍遵循既有 claim 语义；完成写使用严格 `> now`，因此到期/等于到期均不能完成。

## 4. Recovery 与旧 worker 边界

- 有效 token、有效 lease 和合法状态时，finish/retry 保持原动作结果。
- lease 过期、被新 token 接管、owner 大小写/空格不同、owner null/blank、状态已终态或 id 不存在时，finish/retry 返回 0，整行不被旧 worker 覆盖。
- 旧 worker 的 Stock 查询或动作可能已经发生；本轮不能撤回在途 RPC，只阻止旧结果写回新 lease 的持久化行。
- Recovery 捕获业务/RPC异常后调用 retry 时仍传当前候选 token；若 lease 已过期/被接管，retry 0 行后不得用其他 token再次写入。
- 不新增公共 API、状态、错误码、schema、Clock 配置或跨服务 fencing header。

## 5. 验收矩阵

1. 真实隔离 MySQL 直接验证 finish/retry：有效 owner+未到期 lease 成功；过期、等于边界、NULL lease、null/blank owner、大小写/重音/尾空格 alias、其他 owner、已接管 token、终态、缺失 id 均返回 0 且整行保持。
2. 验证两个方法 owner 精确 BINARY 比较，且 `lease_until > now` 使用调用方同一 fresh now；不改变 AY 的 Unicode error `shorten` 契约。
3. 多 worker 并发 claim 只有一个有效 token；旧 token 被接管后 finish/retry 均拒绝，新 token 可完成；同一 Recovery 实例连续两轮认领产生不同 token并正确传播。
4. 真实 Recovery 隔离库中模拟旧 worker 的 Stock 回调跨过 lease/被接管：旧结果不能改变新行；合法新 token可按原流程完成，失败重试和后续健康候选保持原语义。
5. 回归 17AV identity、17AW type、17AX key、17AY error、ActionError/Identity/Creation MySQL IT 与 unit；不把 Stock/RPC 替身当作真实网络撤回验证。

## 6. 明确不承诺

本轮只防止过期/被接管的旧 worker 写回库存动作行，不保证 Stock RPC、Rabbit、数据库事务或网络调用可撤回，不修 `findDue/claim` 等于到期边界、不扩展其他恢复器租约 fencing。
