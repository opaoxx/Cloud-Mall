# CM18 16T206：Pay TCC CLAIMED receipt 账本证据规格

## 1. 评审结论与范围

本批 **GO**。`PayTccExecutionEvidence.classify` 对 CLAIMED receipt 直接返回 UNKNOWN，导致 branch 0/-1 等非法 CLAIMED 载荷绕过正 branch 门，可能让 Coordinator 继续 observe/TC RPC。T200 已定义 receipt state/branch 域门，本批只将其前置到 CLAIMED early return 之前，不扩展 CLAIMED 的 Fence/资金因果含义。

## 2. 最小判断顺序

在 execution/User tuple 与金额证明、receipt 缺失 UNKNOWN 之后，对非 null receipt 复用 `PayTccReceiptStore.hasValidStateAndBranch(pay)`，然后再执行：

```java
if ("CLAIMED".equals(pay.state)) return "UNKNOWN";
```

因此 state/branch 域语义统一：

- CLAIMED 允许 branch null 或正数；CLAIMED branch 0、负数、其它非正值抛既有 `PAY_TCC_EXECUTION_CONFLICT/409`；
- TRYING/CONFIRMED/CANCELLED 必须 branch 非 null 且正数，状态不在四态或 null 也沿既有 409；
- 合法 CLAIMED null/正 branch 仍 UNKNOWN，且不读取 Fence；
- 缺 receipt 仍 UNKNOWN；User tuple/金额/source proof 失败优先于 receipt state 门。

Fence 读取、User state/branch、terminal 映射和 COMMIT/ROLLBACK decision 行为保持。Coordinator、Store、SQL、事务、API、TC/RM 不改。

## 3. 验收矩阵

### 3.1 Unit/受控 Coordinator

- 覆盖 CLAIMED branch 0/-1/Long.MIN_VALUE 的 409、无 Fence 读取；覆盖 CLAIMED null/1/MAX × User CONFIRMED/TRY_ACCEPTED/CANCELLED/null 的 UNKNOWN，且不读 Fence。
- 覆盖 advanced state 的 null/0/负 branch、合法正 branch、未知状态；保持原 tuple/User payload 失败优先。
- 通过受控 Coordinator/Store/Bridge/TC mock 验证坏 CLAIMED branch 不 observe、不发 TC；合法 CLAIMED 只返回 UNKNOWN，receiptTry 不调用；缺 receipt 保持 UNKNOWN。

### 3.2 Owned native

- 使用实际 Evidence/Receipt/Execution/Fence Mapper 与只读 RR 程序化 wrapper；不启动业务 App、TC/RM、新监听或官方 Fence handler。本批不冒称真实 TC/远端 User。
- red：CLAIMED branch 0/-1/MIN_VALUE 在旧代码为 UNKNOWN 并可能继续 observe；修复后 3×4 User state 组合共12组 409、五表 facts 不变。
- healthy：CLAIMED null/1/MAX ×四 User states 共12组 UNKNOWN；missing receipt 保持 UNKNOWN；其它 advanced 正 branch/Fence 矩阵与 T200–T205 回归保持。
- native 只证明 CLAIMED state/branch 本地域门，不证明 CLAIMED branch 正数已有 Fence/owner/资金注册或完整 source 因果。

## 4. 明确边界

本批不改变 CLAIMED 的 UNKNOWN 语义，不读取 Fence、不新增分支注册、不修历史 receipt、不改变其它 receipt state/branch helper、Coordinator 状态机、SQL、事务、API 或 TC/RM。合法 CLAIMED 仍是未注册/未完成状态，不能被本批升级为资金事实。
