# CM18 16T220：Pay TCC execution conflict 传播规格

## 1. 评审结论与范围

本批 **GO**。Coordinator.execute 两层 catch 当前把 BizException conflict 当作普通 Runtime/Exception：先 observe UNKNOWN、默认 ROLLBACK、decide 并可能发 TC，违反已有载荷/XID/branch/payNo/金额 conflict 语义。本批只在两个 catch 开头传播既有 conflict，不改变其它未知失败路径。

## 2. 最小 conflict rethrow

增加私有 `rethrowConflict(Exception failure)`：仅当 `failure instanceof BizException`、code 非 null 且 `code.contains("CONFLICT")` 时原对象抛出；不按 HTTP 409 泛化，不识别其它 Business code 或普通 Runtime。

- inner `tryFailure` catch 第一行调用该 helper；conflict 不 observe UNKNOWN、不继续 decide/TC，非 conflict 沿原 UNKNOWN/默认 ROLLBACK 流程。
- outer `beginOrPersistenceFailure` catch 第一行调用该 helper；conflict 不 observe、不新增 decision/TC，非 conflict 保原 observe UNKNOWN。
- finally 仍清理本次 begin 的 RootContext 绑定；claim、bind、已发 Try 不撤回，冲突发现前已发生的本地事实保持。

不改 private observe、sendDecision、Store/Mapper、SQL、状态机、事务/API、其它 unknown 路径或历史数据。

## 3. 验收矩阵

### 3.1 Owned native

- 使用 actual Coordinator、Store/Mapper、owned Pay/Intent/Execution 表与程序化本地 proxy；receipt/Bridge/Evidence/TC 为 mock，不启动 App、TC/RM、新监听。
- 注入 receipt/Bridge/Evidence 载荷冲突与 bind/persistence conflict：冲突原对象传播，冲突发现时三表 facts 保持，不 observe、不新增 decide、不发 TC、不执行末次 readback；RootContext finally 清理。
- NULL/其它 Business code/普通 Runtime 仍沿原 UNKNOWN/ROLLBACK/TC 语义；正常 commit/rollback、context reject、重入健康回归保持。TC 可能后续 timeout，不称其已回滚。
- native 只证明 catch 传播与本地写入保护，不证明真实 TC/远端 source/完整资金因果。

### 3.2 Unit

- 用真实 Coordinator/Store orchestration 与 mock 依赖覆盖 inner/outer 两 catch、Biz conflict/non-conflict/Runtime、claim/bind 前置、已发 Try、finally unbind；不把 mock 视为真实 TC。
- 断言 conflict 不调用 observe/decide/TC，非 conflict 保持原流程；其它 ErrorCodes、HTTP语义、private observe 冲突分支不扩展。

## 4. 明确边界

本批只处理 `BizException.code` 含 `CONFLICT` 的传播，不重分类其它错误，不改变 execute 的既有决议方向算法，不撤销已提交 claim/bind/Try，不改历史或新增 fencing/source 查询。全仓/专项未完成前不得宣称闭环。
