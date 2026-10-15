# CM18 16T210：Pay TCC execution read XID 域规格

## 1. 评审结论与范围

本批 **GO**。`PayTccExecutionStore.bindXid` 已拒绝 NULL/blank/长度>128，但 `read(payNo)` 仅做 payNo identity 门；历史空白或超长 recovery XID 可进入 Coordinator observe，Bridge 异常后被写 UNKNOWN，已有 COMMIT/ROLLBACK 甚至可能继续以坏 XID 向 TC 重试。本批只补 read 的持久 XID 域门。

## 2. 最小 read 门

在现有 payNo exact identity 门之后增加：

```java
if (execution.xid != null
    && (execution.xid.isBlank() || execution.xid.length() > 128)) throw conflict();
```

非 NULL 且 blank/超长沿既有 `PAY_TCC_EXECUTION_CONFLICT/409`，不返回 execution、不进入 Bridge/Evidence/observe/TC；该门在 Coordinator 进入 Bridge 前阻断。NULL 保持原合法 prebegin 语义，Coordinator 仍可按原 `xid == null` 分支返回/观察；不 parse XID、不 trim/normalize、不要求 Seata 格式或新增长度定义。

不改变 bindXid、locked 等其它 Store 方法、SQL、事务、Coordinator、Evidence、API、状态/decision 或历史数据。terminal execution 也必须经过该 read 域门；本批不修坏行，只阻止其继续作为 read 事实。

## 3. 验收矩阵

### 3.1 Unit/真实 Coordinator

- 空串、ASCII 空格、TAB+LF、129字符四变体 × 六 execution state × 三 decision，共72组；只进行一次查询且不写入，409和字段快照保持。长度129只在PO mock覆盖，不改数据库DDL。
- 真实 Coordinator/Store，Mapper、Bridge/Evidence/TC/receiptService 为 mock；四坏XID × COMMIT/ROLLBACK × active/terminal 共16组，不调用 Bridge/Evidence/observe/TC。
- NULL、长度1、首尾空格但非blank、长度128、Unicode五种原XID × 五state 共25组仅证明原对象和字段读回不变，不证明每种state/XID配对合法；另核NULL CLAIMED恢复仍按原流程写BEGIN_UNKNOWN，不调用Bridge或TC。
- payNo alias/foreign/missing 优先级、terminal/active、decision NONE/COMMIT/ROLLBACK、positive XID 回归保持。

### 3.2 Owned native

- 使用真实 Coordinator、ExecutionStore/Mapper 与 owned execution、Pay、Intent 三表；Bridge/Evidence/TC/receiptService 为 mock，不启动业务 App、TC/RM、新监听或官方 FenceHandler。
- 旧 red：COMMIT_REQUESTED 坏 XID 经过 Bridge 503 后旧流程继续 TC/观察写入；空格 XID read 旧流程直接返回。修复后 native 三类空白变体 × `NONE/COMMIT/ROLLBACK` × active/terminal 共18组均 409/不返回坏 execution，execution/Pay/Intent facts 不变；本批不构造或声称 receipt/Fence 证据。
- native 只覆盖数据库可存的三种空白变体；长度>128 仅 Unit 验证。NULL及四种非blank文本 × 五state 共25组仅证明raw read字段不变，不证明这些state/XID配对合法。另核真实Coordinator下NULL CLAIMED→BEGIN_UNKNOWN只改原execution观察、Pay/Intent保持，三种健康终态无Bridge/TC且三表不变，缺行404保持；payNo alias等原矩阵整体回归。
- 新增native不通过Spring事务代理；read本身原无事务注解，正NULL恢复和旧red观察写使用同owned数据源实际Mapper，不作为完整事务隔离或TC/RM证明。
- 原库表清单/checksum、owned DROP、池关闭和既有 201–209 回归保持；不冒称真实 TC/RM 或完整 XID 来源证明。

## 4. 明确边界

本批不改变 bindXid/locked/SQL、XID生成、Coordinator execute 旧异常流程、Bridge/Evidence/observe实现、状态机、API 或历史修复。坏非NULL XID由read在Bridge之前拦截；合法与NULL路径仍原流程。Evidence.classify直接收到坏XID、其它Mapper/locked调用入口未扩，不保证TC对XID的全局来源或完整fencing。
