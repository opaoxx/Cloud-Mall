# CM18 16T201：Pay TCC rejected receipt owner/branch 证据规格

## 1. 评审结论与范围

本批 **GO**。`PayTccReceiptLocalResourceImpl.rejectedBranchHasNoReceipt` 在 Fence 为 SUSPENDED、当前分支无 receipt 后，直接按 branch 非 null 和状态集合判断 owner；branch 0/负数及 CLAIMED/坏 state 可能让 rejected 分支提前 ACK。T200 已形成 receipt state/branch 纯校验门，本批只复用它收紧 rejected owner 证据。

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

### 3.1 Owned native

- 在 owned schema 克隆 tcc_fence_log 与既有 Pay/Intent/Dispatch/Receipt 表，使用真实 Receipt/Fence Mapper、Store、BusinessCallback、LocalResource 和真实 DataSourceTransactionManager；绑定官方 TCCFenceHandler 的 static datasource/transactionTemplate 时，必须保存并 finally 恢复原 static 值，不启动 App/TC/RM/后台服务或监听器。
- 旧 red：SUSPENDED Fence、当前 branch=7 无 receipt，owner branch=0/-1（同 xid）或 other xid 曾返回 true/ACK；修复后返回 false，Pay/Intent/Dispatch/Receipt/Fence 五表 facts 不变。
- 合法 owner 为 TRYING/CONFIRMED/CANCELLED 且 branch 正数（含 Long.MAX_VALUE）时仍可按原逻辑 ACK；owner CLAIMED null/positive branch、advanced null branch、UNKNOWN/空/别名状态、非法 branch 均不 ACK。
- native 只验证本地资源与 Mapper/Fence 绑定，scope 为程序化同步调用；不冒称真实 TC/RM、远端 User 或完整资金因果。NOT NULL state 的 null 只由共享 Unit helper 兜底，不在 native 伪造生产 DDL。

### 3.2 Unit

- 覆盖 owner 四状态、null/positive/0/负 branch、同/异 xid/branch、Fence4、count=0 与其它前置门；坏 owner 不提前 ACK，合法 advanced 正 branch 保持。
- 断言原查询顺序、`countByBranch` 与 `findOwners` 调用和 callback/事务边界；不把 CLAIMED 未注册状态解释为另一分支已归属。

## 4. 明确边界

本批不修改 Store helper、receipt callback、prepare/commit、Fence SQL/DDL、其它 owner 查询、资源状态机或 API；不修历史 owner、不补写 Fence/receipt、不宣称完整 TCC fencing 或资金来源闭环。测试 static 绑定仅用于 owned 证据，必须在 finally 恢复并清理 schema。
