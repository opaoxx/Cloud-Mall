# CM18 16T201：Pay TCC rejected receipt owner/branch 证据规格

## 1. 评审结论与范围

本批 **GO**。`PayTccReceiptLocalResourceImpl.rejectedBranchHasNoReceipt` 在 Fence 为 SUSPENDED、当前分支无 receipt 后，直接按 branch 非 null 和状态集合判断 owner；branch 0/负数可能让 rejected 分支提前 ACK。CLAIMED/未知状态原有集合门已拒绝，null state 原有行为是 NPE 后由外层捕获为 false。T200 已形成 receipt state/branch 纯校验门，本批只复用它收紧 rejected owner 证据。

## 2. 最小业务修正

保留 `rejectedBranchHasNoReceipt` 原查询顺序和条件：先 `findByBranch` 核 Fence status=4，再 `countByBranch` 必须为0，再 `findOwners`。对每个 owner 只在以下条件全部成立时返回 true：

```text
PayTccReceiptStore.hasValidStateAndBranch(owner)
owner.state != CLAIMED
owner.state ∈ {TRYING, CONFIRMED, CANCELLED}
owner.xid/branch 与当前 context 不同（原条件）
```

因此仅三个 advanced 状态且 branch 为正数的 owner 可证明另一分支归属；CLAIMED（branch null 或正数）、null/UNKNOWN/空/别名状态、null/0/负 branch 均不能提前 ACK。T200 helper 本身保持不变；不增加完整 payload、精确 SQL、资金因果或其它 owner 域校验。

Fence status、count/findOwners 次数与顺序、其它 owner 语义、callback、prepare/commit、Store、API、SQL、事务和状态机均不改。

## 3. 验收矩阵

### 3.1 Owned public rollback/query

- 在 owned schema 克隆 tcc_fence_log 与既有 Pay/Intent/Dispatch/Receipt 表，使用真实 Receipt/Fence Mapper、Store、BusinessCallback、LocalResource 和真实 DataSourceTransactionManager；绑定官方 TCCFenceHandler 的 static datasource/transactionTemplate 时，必须保存并 finally 恢复原 static 值，不启动项目业务服务、TC/RM、项目后台服务或新监听器。官方框架 JVM 初始化线程不计作项目服务/TC/RM/项目后台任务，需记录 `LOG_QUEUE` 为空。
- 旧 red：SUSPENDED Fence、当前 branch=7 无 receipt，owner branch=0/-1（同 xid）或 other xid 曾返回 true/ACK；修复后返回 false，Pay/Intent/Dispatch/Receipt/Fence 五表 facts 不变。既有 `Set.of` 已排除 CLAIMED/未知状态；null state 原本是 NPE 后由外层 false，均作为保留拒绝控制，不写成旧 ACK 现象。
- 合法 owner 为 TRYING/CONFIRMED/CANCELLED 且 branch 正数（含 Long.MAX_VALUE）时仍可按原逻辑 ACK；owner CLAIMED null/positive branch、advanced null branch、UNKNOWN/空/别名状态、非法 branch 均不 ACK。
- native 只验证公开 rollback precondition/query 与 Mapper/Fence 绑定，scope 为程序化同步调用；不冒称真实 TC/RM、远端 User 或完整资金因果。NOT NULL state 的 null 只由 T200 共享 Unit 覆盖，不在 native 伪造生产 DDL。官方 Seata 1.5.2 类的 JVM 初始化线程不计为项目服务、TC/RM、新监听或本项目后台任务；需记录 `LOG_QUEUE` 为空、静态 datasource/transactionTemplate 前后相同，并在 finally 恢复 static 绑定。

### 3.2 公开入口 query 矩阵

- 通过公开 rollback precondition/query 矩阵覆盖 owner 四状态、positive/0/负 branch、同/异 xid/branch、Fence4、count=0 与其它前置门；坏 owner 不提前 ACK，合法 advanced 正 branch 保持。真实 Mapper 使用 Mockito 接口 mock `delegatesTo` 计数 wrapper，不反射 private helper、不用静态 Mock 镜像实现。
- 断言 Fence4→count0→findOwners 的顺序与短路、callback/事务边界；CLAIMED/未知状态和 null state 的既有拒绝控制保持。官方 Fence static 绑定仅作为本地事务夹具，不能解释为没有任何 JVM 内部线程。

## 4. 明确边界

本批不修改 Store helper、receipt callback、prepare/commit、Fence SQL/DDL、其它 owner 查询、资源状态机或 API；不修历史 owner、不补写 Fence/receipt、不宣称完整 TCC fencing 或资金来源闭环。测试 static 绑定仅用于 owned 证据，必须在 finally 恢复并清理 schema。
