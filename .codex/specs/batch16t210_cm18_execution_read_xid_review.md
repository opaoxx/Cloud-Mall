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

不改变 bindXid、locked、SQL、事务、Coordinator、Evidence、Store、API、状态/decision 或历史数据。terminal execution 也必须经过该 read 域门；本批不修坏行，只阻止其继续作为 read 事实。

## 3. 验收矩阵

### 3.1 Unit/真实 Coordinator

- 覆盖空串、ASCII 空格、TAB/LF、长度129/130 等非 NULL XID；read 在 payNo 门后 409，Coordinator 在 Bridge 前阻断，不进入 Evidence/observe/TC。NULL read 保持原 prebegin 分支。
- 真实 `Coordinator.recover` 使用真实 Store/Mapper，Bridge/Evidence/TC/receiptService 为 mock；COMMIT/ROLLBACK × bad XID 验证不发送 decision、不 observe。合法非 blank 长度1/128 XID 保持，Evidence.classify 直接收到坏 XID 的行为不在本批修改。
- payNo alias/foreign/missing 优先级、terminal/active、decision NONE/COMMIT/ROLLBACK、positive XID 回归保持。

### 3.2 Owned native

- 使用真实 Coordinator、ExecutionStore/Mapper 与 owned execution、Pay、Intent 三表；Bridge/Evidence/TC/receiptService 为 mock，不启动业务 App、TC/RM、新监听或官方 FenceHandler。
- 旧 red：COMMIT 坏 XID 经过 Bridge 503 后旧流程继续 TC；空格 XID read 旧流程直接返回。修复后非 NULL 空白/非法 XID × `NONE/COMMIT/ROLLBACK` 的实际三表矩阵均 409/不返回坏 execution，execution/Pay/Intent facts 不变；本批不构造或声称 receipt/Fence 证据。
- native 只覆盖数据库可存的空白变体；长度>128 仅 Unit 验证，不改 DDL。正 XID 长度1/128与 NULL prebegin、payNo alias/404 controls 保持。
- 原库表清单/checksum、owned DROP、池关闭和既有 201–209 回归保持；不冒称真实 TC/RM 或完整 XID 来源证明。

## 4. 明确边界

本批不改变其它 Store 方法、bindXid/locked/SQL、XID生成、Coordinator execute 旧异常流程、Evidence.classify、Bridge/Evidence/observe 写入、状态机、API 或历史修复；本批只在 Store.read 的非 NULL bad XID 入口阻断，正常合法/NULL路径保持原流程。不保证 TC 对 XID 的全局来源或 fencing。
