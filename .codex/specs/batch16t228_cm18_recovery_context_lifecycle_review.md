# CM18 16T228：Pay TCC recovery context 生命周期规格

## 1. 评审结论与范围

本批 **GO**。recover 入口只在最外层检查 ambient context，后续 read/Bridge/Evidence/metadata/send-preflight/TC 期间若 RootContext 被清空或替换，旧流程可能在污染 context 下写诊断、调用 TC，并泄漏线程 context，导致后续 recovery entry 被拒。本批只保护 recover/shared observation 的本地 context 生命周期。

## 2. Context 生命周期门

### 2.1 recover

保留 entry `requireNoContext()` 在 try/finally 外，保护 caller ambient context。内部原流程包在 finally，退出时仅清理本入口原为空的 RootContext；不解绑/恢复外部 caller context。recover 的 read、terminal、Bridge、Evidence、metadata、send preflight、TC success/error 和 final verify 均在该生命周期内受门保护。

### 2.2 shared observe/record

Coordinator 私有 `observe(PO)` 仅供 recover 及 execute 已清理本次 context 后的末次观察使用，整个 body 包在 try/finally 并清理 RootContext；入口及 Bridge/Evidence 正常回包后均要求 expected context 为 null。新增 `recordUnboundObservation` 在写前核 NULL、调用 T226 `recordObservation`、写后再核 NULL；Biz conflict 优先原样传播，普通 Biz/Runtime 沿原诊断。NULL 是本批 recover 的合法 expected context，不是 drift 失败样本。

### 2.3 sendDecision

`sendDecision(execution, expectedXid)`：verifyDecisionSnapshot 后、terminal early return 前核 expected context；TC exception diagnosis 前与 TC try/catch 结束后再次核 expected context。execute 传原 begin XID，recover 传 null；允许 null expectedXid 与当前 RootContext null 精确匹配。context mismatch 沿既有 conflict，未发/已发动作不撤回。

T227 的 owned context 检查保持；不持锁调用 RPC，不改变 Store/SQL/DTO/Feign/TC client/recovery API/ThreadLocal 实现。

## 3. 验收矩阵

### 3.1 Owned native

- 使用 actual Coordinator/Store/Mapper/owned execution/Pay/Intent 与 Spring 本地 proxy；Bridge/Evidence/TC/receipt 为受控依赖，不启动 App、TC/RM、新监听。
- 两 red：Bridge 返回 foreign、recover send preflight 前 context drift；旧流程无409/返回 VO/污染 metadata/TC 在 foreign context 调用。修复后拒绝后续并清理本入口 context，已发生 begin/TC/metadata 计数如实保持。
- 覆盖 recover 13阶段 × 两方向 × foreign/owned=52；execute 共用 send/末次观察6阶段 × 两方向 × foreign/clear=24。另有正常 context recover26、execute12=38控制，Bridge 明确 conflict4，入口真实 ambient2。recover 的 clear 组合不作为负例；NULL expected 仍是合法路径。
- context 变化由同线程 test advice/受控 callback 注入；native manifest 为 T227 46 + 本批4=50，逐名核 XML。本批只证明调用边界生命周期，不证明真实 Seata 故障、callee 内部、资金因果或完整 fencing。

### 3.2 Unit

- Recovery 新增两方法覆盖52个 drift与2个真实 ambient、1个 caller local transaction、4个 Biz conflict 优先控制共7项；Execute 可复用 T227 fixtures。SDK bind 空字符串的测试设定不作为业务语义：ambient 控制使用非 blank caller IDs，javap 证据与首轮日志/XML/counts 单独归档。
- 断言 context mismatch 不写诊断、不继续 TC、不进行末次 readback；ordinary same-context 保持原流程，finally 只清理本入口 context。

## 4. 明确边界

本批不修改 Store、SQL、状态机、recover 数据、TC client、外部 API 或其它 ThreadLocal 代理；不撤回已发动作，不保护真实远端/TC 全窗口，不宣称完整资金/source/fencing。专项未完成前不得宣称闭环。

execute 末次 `verifyDecisionSnapshot` 若在进入 observe 前直接抛出的外层 context 清理不由本批扩展；callee 内部、真实 TC、资金和 fencing 仍不在范围。
