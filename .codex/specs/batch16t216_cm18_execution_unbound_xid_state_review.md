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

- 使用真实 ExecutionStore/Mapper、owned execution/Pay/Intent 三表和程序化 TransactionTemplate，不套 Spring 代理；cached recover red/green 场景使用真实 Coordinator，其 Bridge/receipt/Evidence/transactions 依赖为 mock；其它 native 场景不引入 Coordinator。不启动 App、TC/RM、新监听或 Docker。
- red：NULL XID+COMMITTED/COMMIT cached recover 旧返回 VO；CLAIMED/NONE/NULL observe(COMMITTED) 旧写终态。修复后 NULL 关联非法组合 read/observe/bind/decide 均 409/无三表写。
- 覆盖 25 个非法 NULL 组合：read/bind/decide/observe/claim 入口及三种 incoming terminal observe；另有合法 prebegin 两组 × 三 terminal=6 组拒绝控制、合法 prebegin 两组 × 五动作=10 组保持，以及 bound COMMIT 意图实际 ROLLEDBACK 观察对照1组。非 NULL 原矩阵全部回归。native claim 矩阵中87组保持 false、75组转为关联门拒绝。
- 复核 T210–T215 raw positive NULL 样本：原单字段域通过但关联非法的改为 409/无写，不跳过测试；合法 `CLAIMED/BEGIN_UNKNOWN + NONE` 保持。此前损坏域/入参矩阵可能被更早门拒绝，不把它们全部归为本批独立证据。

### 3.2 Unit

- 覆盖 NULL XID × state/decision 关联门、incoming terminal/非 terminal observe、claim/bind/decide/locked 优先级：125 拒绝、6 terminal 拒绝、10 prebegin 保持、27 bound pure-read 保持与1相反观察对照；Mapper mock 验证无 write/TC。Unit claim 矩阵中261组保留 false、225组转为关联门拒绝（含3种请求金额）；旧 T210/211/212/215 原样本继续回归。
- 非 NULL XID 的单字段、组合、历史回归保持；不把本批关联门扩到其它直接 Evidence 输入或组合状态机。

## 4. 明确边界

本批不证明 state/decision 完整状态机，不从 decision 猜资金结果，不修历史坏行、不新增来源查询、不改变合法 NULL prebegin 的 UNKNOWN/BEGIN_UNKNOWN 语义；不撤回 Bridge/Evidence 前动作，不宣称 TC/RM 或完整 fencing。

本批最终专项证据应区分：native 的 actual Store/Mapper/owned 三表/程序化 TransactionTemplate 与 mock 外部依赖；不把专项总套件中的其它轮次、历史坏域早门拒绝或旧矩阵回归写成 T216 独立证明。测试未结束前不得宣称闭环。
