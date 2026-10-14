# CM18 16T198：Pay TCC receipt fresh dispatch 权限规格

## 1. 评审结论与范围

本批 **GO**。Receipt fresh claim 当前只核 payment PENDING 与 anchor PREPARED，未核 dispatch/anchor 的 POST marker、已观察 User branch 或输入 dispatch 阶段；PREPARED 已消耗 POST 权限或已绑定 branch 仍可插入 CLAIMED 并触发 LocalTCC。existing receipt replay 必须继续只校验/返回既有事实，不能被新的 fresh 门误拒。

## 2. Fresh gate 的最小扩展

在现有 source tuple、payNo、cancel gate 和 existing receipt 锁/validate 之后，保留 existing receipt 分支优先：已有 receipt 仍按四合法状态返回 `false`/stored fact，不能因当前 Pay、anchor、marker、branch 或输入阶段已推进而重新注册或误拒。

仅当 existing receipt 为 null、即将 fresh insert 时，追加以下全部条件：

```text
payment.status == PENDING
dispatch.state == PREPARED
dispatch.userBranchId == null
anchor.state == PREPARED
anchor.postAttemptedAt == null
anchor.userBranchId == null
```

任一不满足沿既有 `PAY_TCC_RECEIPT_CONFLICT/409`，不 insert、不调用 LocalTCC/resource。原 source tuple/payNo/cancel 优先级、anchor payload proof、claim SQL、锁序、事务、DTO/API、Feign、回调和状态机不变。输入 dispatch 的 state/branch 只参与 fresh gate，不改变已有 receipt replay。

## 3. 验收矩阵

### 3.1 Unit

- Fresh 单变异覆盖 payment status、输入 dispatch state/branch、anchor state/postAttemptedAt/userBranchId；每项 conflict、无 insert、无 resource。
- Existing receipt 四状态覆盖 Pay SUCCESS、anchor 已推进、marker 已消耗、branch 已观察及输入 state/branch advanced；仍返回 stored fact/false，不重新注册。
- 保留 payload/cancel/source identity 优先级、missing/invalid anchor、CLAIMED null branch 的既有 validate；healthy fixture 的 dispatch state 明确设为 PREPARED。

### 3.2 Owned native

- 使用真实 ReceiptStore/Facade、Payment/Dispatch/Receipt Mapper 与 owned 原 DDL；Bridge/resource 为受控替身，claim 与 LocalTCC 由程序化短事务边界验证，不启动业务 App、TC/RM 或真实 HTTP。
- 旧 red：当前 POST marker 非 null、当前 User branch 非 null 时 fresh 仍 insert CLAIMED 并触发 resource.prepare；修复后各返回 409，四表 facts 不变、resource 不调用。
- control：fresh 各 predicate 单变异、输入 advanced、anchor advanced 均零 resource/零写；唯一 valid fresh winner 只一次 resource 尝试，重放不新增。
- 原 receipt source identity、healthy four receipt states、missing/cancel gate、历史 bad owner 与 T195/T196 回归保持。generic seed helper 不改；healthy seed 可在本批专用 helper 中清理 `postAttemptedAt`/branch 为 null。

## 4. 明确边界

本批只关闭 fresh claim 的 POST 权限/branch 阶段门，不修历史 marker/branch，不改变 existing replay，不新增 SQL/锁/字段/API/Feign/事务/回调，不证明完整 TC、远端 User 或其它 dispatch 来源的 fencing。旧 healthy fixture 的准备状态调整只属于测试数据，不是生产契约放宽。
