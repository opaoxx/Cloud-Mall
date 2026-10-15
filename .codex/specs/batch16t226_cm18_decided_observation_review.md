# CM18 16T226：Pay TCC decided observation 方向规格

## 1. 评审结论与范围

本批 **GO**。T225 final gate 只保护 Bridge/TC 前的 snapshot；Bridge/Evidence/RPC 期间 peer 可在同身份/原 Pay 下把已决 COMMIT/ROLLBACK 改成反向或 NONE，旧 `observeSnapshot` 仍可能把旧诊断/终态写入新方向。本批只收紧已决观察写的本地方向，不全局收紧旧 observe API。

## 2. Decided observation 门

新增 REQUIRES_NEW `observeDecidedSnapshot(expected,state,error)`：

1. 复用 `lockedSnapshot(expected)` Pay→execution、两域、八 anchor、source 门；
2. 要求 `expected.decision != NONE` 且 locked current.decision 与 expected.decision 精确相等；
3. 拒绝先于 terminal early return、incoming state 判断和 SQL；通过后复用原 `mergeObservation`。

旧 `observeSnapshot`、`observe(payNo,...)`、SQL、DTO/API 保持，允许 T218/T225 的 mutable/legacy 行为。Coordinator 增加私有 `recordObservation(execution,state,error)`：NONE 路由旧 `observeSnapshot`，COMMIT/ROLLBACK 路由新 decided 方法。execute 两 catch、sendDecision RPC failure、observe(PO) prebegin/正常/Biz/Runtime 的 snapshot 写回统一使用该 helper；T225 verify/final observe 入口不改。NONE→COMMIT 的合法并发进度继续旧门，已决方向必须精确一致。

## 3. 验收矩阵

### 3.1 Owned native

- 使用 actual Coordinator/Store/Mapper/owned execution/Pay/Intent 与本地 proxy；Bridge/Evidence/TC/receipt mock，不启动 App、TC/RM、新监听。
- 两 red：execute final callback 将 COMMIT 改 ROLLBACK、recover 初次 callback 将 ROLLBACK 改 COMMIT；旧实现可能返回 VO/污染 peer/下游 observation-readback，修复后 409，三表不变，已发 TC 计数如实保持。
- 覆盖 execute/recover 双入口 × 两原方向 × 反向/NONE × 7结果（COMMITTED、ROLLEDBACK、UNKNOWN、BridgeRuntime、BizNULL、Biz普通、RPCerror）=56，其中 Bridge/Evidence 48、TC failure 8；拒绝先于 terminal 与 merge。相同方向控制为两方向×5观察=10，另6 terminal 控制核 UNKNOWN 幂等/反向终态冲突，以及 NONE→COMMIT 进度1。native manifest 沿 T225 38项加本批4项=42，逐名核 XML，不把无关矩阵计入。
- 本地 peer 交错不证明真实 TC/远端返回窗口、资金 source 或完整 fencing；已发 TC/Bridge 不撤回。

### 3.2 Unit

- Owner 单元覆盖两方向×11拒绝=22（方向/NONE/expectedNONE/null/currentmissing/id/source/域/入参/SQL0）、两方向×5进度=10及 NONE→COMMIT 进度1；旧 Recovery mocks 精确适配 NONE 旧门与已决新门，保留原样本。预检 fixture/stub 问题必须单独归档，不计业务结果。
- 直接新 Store 方法的 Unit 只在实现存在后增加；不在 red 前引用不存在 API。其它 T218–T225 矩阵保留。

## 4. 明确边界

本批只保护已决 observation 写的本地锁内方向，不改变旧 observe/SQL/API、决议算法、TC client、历史数据或 NONE legacy 语义，不撤回已发动作，不宣称真实 TC/远端并发/完整资金或 fencing。专项未完成前不得宣称闭环。

T224 旧 native helper 的 recover checkpoint 必须同步实际新路径 `observeDecidedSnapshot`；保持内层事务提交后 peer 注入位置、原样本和断言语义，不是放宽断言或删除覆盖。首次 focused 29 类/414 方法的3个失败（0 error/skip）属于旧 helper 路径与新 decided observation 路径不一致，原日志/XML/counts 与失败 owned schema 清理证据保留；最终验收须分别核对 red、focused-initial、focused 三个 owned schema 的精确 DROP、指纹与池关闭。
