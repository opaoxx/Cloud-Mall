# CM18 16T195：Pay TCC dispatch mirror 原锚点规格

## 1. 评审结论与范围

本批 **GO**。Bridge 在事务外读取 dispatch、执行 User GET/POST 后，当前 Persistence 只按 payNo/branch 检查当前行；User RPC 期间原 `xid/user/order/amount/operation/ledger/id` 改变时，旧 proof 仍可镜像到新载荷。本批只把原 dispatch snapshot 作为 mirror 锚点，阻止该窗口覆盖，不扩展远端 fencing 或重新读取伪造锚点。

## 2. 接口与锚点校验

将内部 `PayTccDispatchPersistence.mirror` 的第一参数由 `String payNo` 改为 `PayTccDispatchPO expected`，保留 `expectedState`、`targetState`、`branchId`、`lastError`、`updatedAt` 其余参数及调用方语义；删除旧无锚点 signature，不留绕过 overload。Bridge 的 GET、POST、`mirrorUnknown` 均传同一原始 dispatch snapshot，不在 mirror 前重读生成 expected。

Persistence 在事务开始处拒绝 `expected == null` 或 `expected.payNo == null`，沿既有 dispatch conflict 409；使用 `expected.payNo` `selectForUpdate`，当前行缺失仍返回既有 404。锁内、branch/终态/PREPARED/TRY_UNKNOWN 早返回前，精确比较以下不可变锚点：

```text
id, payNo, xid, userId, orderNo, amount, operationKey, ledgerKey
```

要求字符串/Long 使用 `Objects.equals` 精确比较（包括大小写、空格）；金额非 null 且 `compareTo == 0`，允许 `10.000` 与 `10.00` 等数值等价。任一不等抛既有 `PAY_TCC_DISPATCH_CONFLICT/409`，由 Bridge 原有 identity-conflict rethrow 路径传播，不能 fallback 为 TRY_UNKNOWN 或再次镜像。

不比较 `createdAt`（新建 PO 与 DATETIME(3) 存在合法精度差）、mutable `state`、`userBranchId`、`postAttemptedAt`、`lastError`、`updatedAt`；锚点相等后保留原永久 branch、终态、PREPARED、TRY_ACCEPTED 不降级、当前 state CAS 和 `mirrorState` 参数语义。Mapper SQL、Feign、DTO/API、事务与表结构不变。

## 3. 验收矩阵

### 3.1 Unit

- 覆盖八个锚点字段单边漂移、多个字段漂移、金额数值等价、null expected、缺当前行和原 404；字段漂移必须优先 409 且不调用 `mirrorState`。
- 覆盖 GET/POST 使用原 snapshot、branch/终态/PREPARED/TRY_UNKNOWN 原优先级、progress-only 的 state/诊断/时间变化容忍，以及匹配 anchor 的正常更新。
- 旧 wrapper/调用者使用原调用 snapshot，不自行重读或构造新 anchor；User/RPC 仅作调用顺序替身。

### 3.2 Owned native

- 使用实际 DispatchPersistence、Mapper 与 owned Pay/Dispatch 表；User GET/POST 为受控替身，可在 callback 窗口修改 owned 行并记录变化后的完整 facts；不启动业务服务、TC/RM 或真实 HTTP 并发。
- 旧 red：GET 期间改 `xid`、POST 期间改 `operation_key`，旧实现仍镜像 CANCELLED/TRY_ACCEPTED；修复后对应 409，修改后的三表事实不再被 mirror 覆盖。
- control：八字段合法数据库变更 × GET/POST 与正常/空 response，均按 anchor 规则处理；真实匹配时保留 mirror 写；payNo CI 变体继续受既有精确 guard 约束。progress-only state/diagnostic/time 改动保持原允许语义。
- 旧一次 POST、分支、状态、Unicode 诊断矩阵回归保持；native 只证明本地 anchor 锁内比较与 CAS，不冒称 TC、远端 source 或真实并发 fencing。

## 4. 明确边界

本批不冻结 User RPC 之后的远端事实、不比较 mutable dispatch 元数据、不新增 SQL/列/锁或 API，不修历史 dispatch，不扩展 stage/finish/claim/read 的身份校验。成功锚点比较只保护该次本地 mirror 窗口，不能宣称整个 Bridge 流程或跨服务资金因果闭环。
