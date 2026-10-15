# CM18 16T207：Pay TCC execution 正金额证据规格

## 1. 评审结论与范围

本批 **GO**。`PayTccExecutionEvidence.classify` 当前只拒绝 execution amount null，execution/receipt/User 金额一致且为0或负数时仍可在合法状态/Fence 下返回 COMMITTED、ROLLEDBACK 或 TRYING。恢复读路径绕过了创建/Bridge 的正金额门。本批只在 Evidence 入口增加正金额域门，不扩展金额 scale、precision、上界或 source 因果。

## 2. 最小入口门

将入口第一门扩展为：

```java
require(execution != null && execution.amount != null && execution.amount.signum() > 0);
```

该门必须早于 User、receipt、Fence 及其它查询；非正金额沿既有 `PAY_TCC_EXECUTION_CONFLICT/409`，不读取 receipt/User/Fence，不进入 observe/TC。合法正金额保持 BigDecimal 数值等价（尾零、指数文本等原语义），不 normalize、不增加 scale/precision/MAX 校验。

execution tuple、receipt/User matching、state/decision、Fence、Coordinator、Store、SQL、事务、API 均不变。

## 3. 验收矩阵

### 3.1 Unit/受控 Coordinator

- 覆盖 amount 0、-0.01、-10，execution/receipt/User 其它字段合法，以及 receipt/User 缺失；均在入口 409、无 receipt/Fence 读取。
- 真实 `Coordinator.recover` 使用 mock Store/Bridge/TC 依赖，覆盖 COMMIT/ROLLBACK × amount 0/-0.01 共4组（receipt 默认 CONFIRMED）；另由 Evidence 独立域矩阵覆盖四种 receipt state 与缺端的24组，验证非正金额不执行 `execution.observe`、不发 TC、不调用 receiptTry。Evidence 之前 Bridge 读取/镜像不声称被撤回。
- 正金额 `.01/10/MAX DECIMAL(18,2)`、尾零/指数文本保留原映射；正常缺端仍 UNKNOWN。

### 3.2 Owned native

- 使用 owned execution/receipt 表实际只读 RR Mapper，User 镜像、Fence 与 Coordinator 受控；不启动业务 App、TC/RM、官方 FenceHandler 或新监听。
- 旧 red：execution/receipt 金额一致为0且 receipt confirmed/Fence2/Userconfirmed 曾 COMMITTED；-0.01 且 receipt cancelled/Fence3/Usercancelled 曾 ROLLEDBACK。修复后非正金额 × receipt state `CONFIRMED/CANCELLED/TRYING/CLAIMED` 共12组均409，execution 仍使用既有 RUNNING/NONE fixture，五表 facts 不变。
- 非正金额缺 receipt 的控制也必须在入口 409；正金额 receipt missing 仍 UNKNOWN。正金额 `.01/10/MAX` × 四状态保留原映射。
- Native 只证明 Evidence 本地入口的正金额域门；User 是已传入的镜像对象，不在本方法重新查询远端；Fence 使用实际 owned Mapper 而非官方 handler，执行/receipt owned 金额变更不代表 Pay 原 source 或 User/TC 真实资金链路已被证明。Coordinator/TC 仅用 mock 验证 recover 后续不调用，不能扩展 execute 的旧异常决议。

## 4. 明确边界

本批不改变金额文本词法、scale/precision/上界、normalize、receipt/User/Fence 查询或状态机，不修历史非正金额，不改变 Coordinator execute 的旧异常决议流程；入口门早于本方法的 User tuple 比较与 receipt/Fence 查询，不撤回 Evidence 之前 Bridge 读取/镜像。新增场景使用真实 Coordinator 与 mock Store/Bridge/TC 依赖，不调用官方 FenceHandler；旧 201–204 仅保留整体专项回归证据。
