# CM18 16T216：Pay TCC execution 未绑定 XID 与状态关联规格

## 1. 评审结论与范围

本批 **GO**。T210–T215 的单字段域门仍允许 `xid == null` 与 COMMITTED/COMMIT 等不可能的持久组合；Coordinator 可把缓存 terminal 当作终态，observe 也可用 NULL XID 写出终态。本批只补 NULL XID 与 state/decision 的既有 B2 关联，不重建完整状态机。

## 2. 两处最小关联门

### 2.1 persisted read/locked

在原 state/decision gate 之后、payload 检查之前增加：

```java
if (execution.xid == null
    && (!"NONE".equals(execution.decision)
        || !("CLAIMED".equals(execution.state) || "BEGIN_UNKNOWN".equals(execution.state))))
  throw conflict();
```

因此 NULL XID 仅允许 `CLAIMED/BEGIN_UNKNOWN + NONE`；UNKNOWN、TRYING、请求/终态或任何非 NONE decision 均 409。非 NULL XID 的既有所有 state/decision 组合不受本批改变。

### 2.2 observe

保持 locked 和 incoming `state == null` 门优先；在原 merge 前增加：

```java
if (current.xid == null && terminal(state)) throw conflict();
```

NULL XID 对非 terminal incoming state 仍沿原逻辑映射 `BEGIN_UNKNOWN`，不 begin TC、不推断资金结果；合法 `CLAIMED/BEGIN_UNKNOWN + NONE` 可 read/replay/bind。非 NULL XID、相反 TC 观测与其它组合保持。

不改 SQL、事务、公开 API、claim/bind/decide、Coordinator、Evidence、其它 Store、历史数据或完整 source/fencing。

## 3. 验收矩阵

### 3.1 Owned native

- 使用真实 ExecutionStore/Mapper/Coordinator 与 owned execution/Pay/Intent 三表，程序化 TransactionTemplate 不套 Spring 代理；不启动 App、TC/RM、新监听或 Docker。
- red：NULL XID+COMMITTED/COMMIT cached recover 旧返回 VO；CLAIMED/NONE/NULL observe(COMMITTED) 旧写终态。修复后 NULL 关联非法组合 read/observe/bind/decide 均 409/无三表写。
- 覆盖 25 个非法 NULL 组合：read/bind/decide/observe/claim 入口及三种 incoming terminal observe，另保健康 NULL read/replay、两种非 terminal observe、bind；非 NULL 原矩阵全部回归。
- 复核 T210–T215 raw positive NULL 样本：原单字段域通过但关联非法的改为 409/无写，不跳过测试；合法 `CLAIMED/BEGIN_UNKNOWN + NONE` 保持。

### 3.2 Unit

- 覆盖 NULL XID × state/decision 关联门、incoming terminal/非 terminal observe、claim/bind/decide/locked 优先级；Mapper mock 验证无 write/TC。
- 非 NULL XID 的单字段、组合、历史回归保持；不把本批关联门扩到其它直接 Evidence 输入或组合状态机。

## 4. 明确边界

本批不证明 state/decision 完整状态机，不从 decision 猜资金结果，不修历史坏行、不新增来源查询、不改变合法 NULL prebegin 的 UNKNOWN/BEGIN_UNKNOWN 语义；不撤回 Bridge/Evidence 前动作，不宣称 TC/RM 或完整 fencing。
