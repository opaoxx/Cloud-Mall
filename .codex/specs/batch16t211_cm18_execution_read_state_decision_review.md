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

- Unit 覆盖 state 坏值5种（空、space、RUNNING尾space、lower running、BROKEN）+NULL × decision3 × XID NULL/valid=36，以及 decision 坏值5种（空、space、commit、COMMIT尾space、ABORT）+NULL × state9 × XID NULL/valid=108；Unit 使用 mock Mapper，均在 read payNo/XID 后 409、不进入 Bridge/Evidence/observe/TC。另有 stateunknown/statealias/decisionlower/decisionNULL × XID2 的8个 no-follow-up 控制。
- 正域 raw state9 × decision3 × XID2=54 只证明字段域读取，不证明组合合法；positive read 同对象快照保持，DAO 异常原样，active/terminal、NULL/valid XID、payNo alias/404 控制保持。
- Unit使用真实Coordinator和Store，Mapper及Bridge/Evidence/TC/receipt为mock；上述8组拒绝在Bridge和缓存终态返回之前，不调用observe/TC/receiptTry。Evidence直接传入bad字段的行为不在本批修改。

### 3.2 Owned native

- 使用真实 ExecutionStore/Mapper 与 owned execution/Pay/Intent 三表；Coordinator 真实，Bridge/Evidence/TC/receipt 为 mock；不启动业务 App、TC/RM、新监听或官方 FenceHandler。
- 旧red：BROKEN+COMMIT经Bridge503曾观察改写并继续TC commit，5个断言失败；缓存COMMITTED+小写commit曾直接返回VO，2个断言失败。修复后state坏5×decision3×XID2=30，decision坏5×四state（RUNNING/COMMITTED/ROLLEDBACK/CONFLICT）×XID2=40，共70 read/recover均409、三表不变、无Bridge/Evidence/TC。NULL字段仅Unit，不改NOT NULL DDL。
- 正域 9 state × 3 decision × XID2=54 保持 raw read；另真实 Coordinator+Store/Mapper 的 Bridge503流程保留：NONE→UNKNOWN/Bridge1/TC0，COMMIT/ROLLBACK→原 REQUESTED/Bridge2/对应 TC 各1，Pay/Intent保持，execution 仅 state/error/updatedAt 可变。native 只证明单字段域，不宣称组合/Fence/TC/source 全因果。
- 新增native未套Spring事务代理，read原无事务注解；同owned数据源实际Mapper，不证明完整事务隔离。原T210 NULL prebegin、404、支付号别名及201–210整体回归。原库全表指纹、owned精确DROP与有界池关闭保持。

## 4. 明确边界

本批不改变state/decision组合规则、Coordinator execute旧异常决议流程、Evidence.classify、其它Store方法、SQL、事务、API或历史数据。坏初始字段在read处早于Bridge阻断，合法值仍原流程；不扩其它Mapper/locked入口或读后变化窗口，不调用官方FenceHandler，不把raw域控制当作完整状态机或资金因果证明。
