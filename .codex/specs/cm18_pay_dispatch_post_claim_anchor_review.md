# CM18 16T196：Pay TCC POST claim 原 dispatch 锚点规格

## 1. 评审结论与范围

本批 **GO**。T195 只保护 mirror，但 `markPostAttempted` 仍只核支付/取消 gate 与 payNo/state CAS：Bridge 读取 PREPARED 后若 User RPC 前 dispatch 的不可变载荷漂移，旧实现仍会消耗 POST 权限并发 User POST，随后才在 mirror 阶段发现冲突；已有 `postAttemptedAt` 的 PREPARED 也可能重复 POST。本批只关闭“读取 PREPARED→CAS POST claim”的窗口。

## 2. `markPostAttempted` 最小行为

保留 `PayTccDispatchPersistence.markPostAttempted` 公开签名、REQUIRES_NEW、Pay→cancel 锁序和既有 payment/cancel gate。入口 `dispatch == null` 或 `dispatch.payNo == null` 沿既有 dispatch conflict 409；支付记录不唯一、模式/状态/tuple 不符或 cancel 存在仍优先返回 `false`。

在原 Pay 锁和 cancel 锁之后增加一次 `dispatchMapper.selectForUpdate(dispatch.payNo)`：

- 当前行缺失返回 `false`，保持原 CAS 0 行语义；
- 当前行存在但与传入 `dispatch` 的八字段 anchor 不一致，抛既有 `PAY_TCC_DISPATCH_CONFLICT/409`；复用 T195 的 `id/payNo/xid/userId/orderNo/amount/operationKey/ledgerKey` 精确比较，金额非 null 且 `compareTo==0`，字符串 `Objects.equals`；
- anchor 相等后，仅当当前 `state == PREPARED` 且 `postAttemptedAt == null` 执行原 `markPostAttempted` CAS；其它 state 或已有 marker 返回 `false`，不发 User POST。

不比较 mutable `state`（权限门单独判断）、`userBranchId`、created/updated 时间、lastError 或其它诊断字段；不回收已消耗 marker。Mapper SQL、CAS 参数、事务、DTO/API、Feign、TCC 不变；本批明确增加一次锁定读取，不声称零新增查询。

## 3. 验收矩阵

### 3.1 Unit

- 覆盖 preclaim 前八字段单边/多字段漂移、金额数值等价、null expected、缺当前行；漂移优先 409，缺行沿原 false/CAS0，不调用 `markPostAttempted`。
- 覆盖 current state `PREPARED/POST_ATTEMPTED/TRY_ACCEPTED/CANCELLED/CONFIRMED` 与 marker null/non-null：仅 PREPARED+null 可 CAS 成功；其它均 false、无 User POST/本地业务写。
- 保留 payment/cancel gate 优先、合法 anchor 参数、旧 zero-CAS 测试必须提供真实 current dispatch fixture 并验证 CAS 调用，避免缺行门伪通过。

### 3.2 Owned native

- 使用实际 DispatchMapper/Persistence/Bridge 与 owned Pay/Dispatch 表；通过程序化本地事务和测试 hook 在 claim 前注入 owned drift，不冒称真实 HTTP 并发、TC/RM 或 Spring 代理。
- 旧 red：claim 前改 xid 时仍 POST/消耗 marker，随后 T195 mirror 才失败；PREPARED+已有 post marker 时旧实现仍 POST。修复后八字段 drift 返回 409、无 User RPC、三表事实保持；已有 marker/非 PREPARED 返回 PREPARED 或 false，零写。
- control：八字段 beforeclaim 合法 DB 漂移均返回 409、无 User RPC 且 facts 不变；五种 current state（PREPARED、POST_ATTEMPTED、TRY_ACCEPTED、CANCELLED、CONFIRMED）× marker null/non-null，仅 PREPARED+marker null 能 CAS 并发一次 POST，随后 dispatch 只读；其它组合 Persistence 返回 false、无 RPC。progress/terminal/branch/Unicode 状态矩阵、T195 mirror anchor 回归保持。
- 原库表指纹、owned 清理、池关闭和 T195 历史证据保持；已有 marker/非 PREPARED 时 Persistence 返回 false，Bridge 沿原 mark 失败 readback 返回当前 state；脏 PREPARED+已有 marker 返回 PREPARED。native 只证明本地 POST 权限门，不宣称 claim commit→远端 POST、TC 或完整 fencing 已闭环。

## 4. 明确边界

本批不改变 Bridge 外部流程、POST 重试/回收策略、markPostAttempted SQL、dispatch 状态机、mirror/stage/finish、历史 marker 或远端调用契约；不修 claim 提交后到 User POST 的后续窗口，不新增字段、表、锁类型或 API。
