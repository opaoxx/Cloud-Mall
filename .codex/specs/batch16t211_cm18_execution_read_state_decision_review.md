# CM18 16T211：Pay TCC execution read state/decision 域规格

## 1. 评审结论与范围

本批 **GO**。`PayTccExecutionStore.read` 当前只核 payNo/XID，坏 state 或 decision 可进入 Coordinator observe/TC：例如 state BROKEN + COMMIT 经过 Bridge 503 后被写 COMMIT_REQUESTED，或 terminal COMMITTED 搭配小写 decision 被当作合法 read。本批只补持久 state/decision 域门。

## 2. 最小 read 门

在 payNo/XID 门之后增加两个非 NULL exact Set 门：

```text
state ∈ {
  CLAIMED, BEGIN_UNKNOWN, RUNNING, COMMIT_REQUESTED, ROLLBACK_REQUESTED,
  UNKNOWN, COMMITTED, ROLLEDBACK, CONFLICT
}
decision ∈ {NONE, COMMIT, ROLLBACK}
```

任一字段为 null、空串、大小写/尾空格别名、未知值均沿既有 `PAY_TCC_EXECUTION_CONFLICT/409`，早于 Bridge、terminal、observe、TC；NULL XID 的 prebegin 语义不受影响。门只校验单字段域，不推断 state/decision 组合合法性，不 normalize、不修历史、不改变 locked/observe/decide/claim/bind、SQL、Coordinator、Evidence、事务或 API。

## 3. 验收矩阵

### 3.1 Unit/真实 Coordinator

- 覆盖 bad state/decision 的 case、trailing、empty、unknown、NULL；read 在 payNo/XID 后 409，不进入 Bridge/Evidence/observe/TC。
- 正域 raw state 9值 × decision 3值仅证明域读取，不证明组合合法；active/terminal、XID NULL/valid、payNo alias/404 控制保持。
- 真实 Coordinator 使用真实 Store/Mapper，Bridge/Evidence/TC/receipt 为 mock；COMMIT/ROLLBACK × bad state/decision 验证不 observe、不发送 decision、不调用 receiptTry。Evidence 直接传入 bad state 的行为不在本批修改。

### 3.2 Owned native

- 使用真实 ExecutionStore/Mapper 与 owned execution/Pay/Intent 三表；Coordinator 真实，Bridge/Evidence/TC/receipt 为 mock；不启动业务 App、TC/RM、新监听或官方 FenceHandler。
- 旧 red：state BROKEN+COMMIT 经 Bridge 503 曾 observe/继续 COMMIT；terminal COMMITTED + decision 小写/未知曾直接返回。修复后 state/decision bad × active/terminal 矩阵均 409/不返回坏 execution，三表 facts 不变。
- 正域 9 state × 3 decision 控制保持 raw read；合法 `NONE` active、COMMIT/ROLLBACK 重试、NULL prebegin 与 missing/alias/404 控制保持。native 只证明单字段域，不宣称组合/Fence/TC/source 全因果。

## 4. 明确边界

本批不改变 state/decision 组合规则、Coordinator execute 旧异常决议流程、Evidence.classify、observe/decide/claim/bind、SQL、事务、API 或历史数据；不撤回 Bridge 前读取/镜像，不调用官方 FenceHandler，不把 raw domain controls 当作业务状态机证明。
