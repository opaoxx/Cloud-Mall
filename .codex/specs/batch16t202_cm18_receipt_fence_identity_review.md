# CM18 16T202：Pay TCC receipt Fence 身份规格

## 1. 评审结论与范围

本批 **GO**。`PayTccReceiptLocalResourceImpl.finish` 目前只核 Fence actionName，CI 查询返回的异源 xid（真实 native 覆盖 xid 大小写别名）可被当作当前 context 的 Fence；rejected 分支 helper 的第二次 Fence 读取也有同样问题，可能让 alias Fence 与另一 owner 提前 ACK。本批只收紧 Fence 身份，不改 receipt owner、Store、callback、SQL 或状态机。branch/null/action 异常对象由第二读 Mapper wrapper 覆盖，不表述为数据库 CI branch/null/action 别名。

## 2. 共享 Fence 身份门

增加私有纯谓词 `hasSameFenceIdentity(fence, context)`，精确比较：

```text
fence.xid == context.xid
fence.branchId == context.branchId
fence.actionName == ACTION_NAME
```

null 任一身份字段均不匹配；不 trim、不大小写折叠、不改变 actionName。`finish` 首次非 null Fence 读取：身份不匹配直接返回 false，保留原错误 actionName false 语义；NULL Fence 继续原路径。rejected helper 第二次非 null Fence 读取：身份不匹配抛 `IllegalStateException`，由现有 finish 外层 catch 返回 false 并回滚本地事务，避免 helper 返回 false 后继续合法 callback/ACK；NULL Fence 保持原路径。

不改变 Fence 查询次数、锁、receipt callback、owner T201、Store、prepare/commit、事务、API 或 SQL。该门只证明返回对象与 context/action 的身份一致，不解决数据库 CI 查询、TOCTOU 或完整 fencing。

## 3. 验收矩阵

### 3.1 Owned public rollback/commit

- 使用真实 Fence/Receipt Mapper、Store、BusinessCallback、LocalResource、DataSourceTransactionManager 与 owned schema；官方 TCCFenceHandler static datasource/transactionTemplate 保存并 finally 恢复。
- 复用 16T201 的 scoped static 绑定和 cleanup；不启动项目业务服务、TC/RM、项目后台服务或新监听器。官方 JVM 初始化 daemon 如实记录，不冒称无任何线程，`LOG_QUEUE` 为空、static 前后相同。
- red：首读 CI alias Fence（xid/branch/action匹配 context 的 action 但身份不同）不再让 rollback ACK；commit 已 CONFIRMED receipt 与 alias Fence 也不再成功 ACK，Pay/Intent/Dispatch/Receipt/Fence facts 不变。
- 健康精确 Fence4、missing Fence、原 CLAIMED/null empty-cancel、终态同方向和其它 direction 控制保持；owner T201 的 advanced 正 branch 规则不放宽。
- 第二读用 Mapper `delegatesTo` wrapper 的受控返回对象覆盖 xid/branch/action 单字段变化及 NULL；声明这是异常 Mapper 返回边界，不是物理 DB 并发/TOCTOU fencing。五表 facts 与事务回滚断言保持。

### 3.2 Unit/query 边界

- 通过公开 `commit/rollback` 前置与 rejected query 矩阵验证精确 Fence 身份、action mismatch、null identity、missing、owner/receipt gate 和 callback 短路；不反射 private helper、不用 static Mock 镜像实现。
- 断言首次 mismatch 返回 false；rejected 第二次 mismatch 传播 Runtime 后由外层返回 false/回滚；NULL Fence 继续原分支。State/branch/owner 证据仍按 T201 规则处理。

## 4. 明确边界

本批不改 Fence SQL/DDL、查询锁、receipt callback、Store、owner、prepare/commit、API 或状态机，不修历史 alias，不保证读取后远端/数据库事实稳定，不宣称真实 TC/RM 或完整跨服务资金因果。所有 static 绑定和 owned schema 必须 finally 恢复/删除并复查。
