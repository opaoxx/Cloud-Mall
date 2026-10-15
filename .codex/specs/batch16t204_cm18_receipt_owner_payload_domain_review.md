# CM18 16T204：Pay TCC receipt owner payload 域规格

## 1. 评审结论与范围

本批 **GO**。T201 已校验 owner state/branch，T203 已校验四个唯一键至少一项 Java 精确匹配，但 owner 的 `xid` 空白、userId 非正、orderNo 空白、amount 非正仍可能满足查询并提前 ACK。ReceiptBusinessCallback.payload 已将这些域定义为合法入站 context 的必要条件。本批只增加 owner payload 有效域门，不扩展完整 owner/source/资金因果证明。

## 2. 最小 payload 域门

增加私有纯谓词 `hasValidOwnerPayload(owner)`，要求：

```text
owner.xid != null && !owner.xid.isBlank()
owner.userId != null && owner.userId > 0
owner.orderNo != null && !owner.orderNo.isBlank()
owner.amount != null && owner.amount.signum() > 0
```

循环顺序固定为：T201 `hasValidStateAndBranch` → 排除 CLAIMED → 本批 payload 域门 → T203 `hasExactOwnerKey` → 原 different xid/branch 判断。只校验正数/非 blank 域，不新增 XID 词法、金额 scale/precision、payNo/operationKey/ledgerKey 全等条件；不要求 owner 与 incoming user/order/amount/xid 全部相等。T203 原 OR 语义和不同 payload 的既有行为保持。

不修改 SQL、显式 Mapper 调用、锁、事务、Store、callback、API、Fence 或状态机。坏候选仍沿原流程进入 verify 的既有锁读，不能声称所有输入整条 callback SQL 次数与旧 ACK 相同。

## 3. 验收矩阵

### 3.1 Owned public rollback/query

- 复用 T201–T203 official Fence static scope、真实 Receipt/Fence Mapper、Store、Callback、LocalResource 与程序化本地事务；static datasource/transactionTemplate finally 恢复，记录空队列，不启动项目服务、TC/RM、新监听。
- 旧 red：owner xid 空字符串或 amount=0、context branch=7/Fence4/owner TRYING/owner key 精确时旧实现 ACK；修复后 false，Pay/Intent/Dispatch/Receipt/Fence facts 不变。
- native 覆盖 3 advanced state × 10 owned payload 变体：xid 三类（空、ASCII 空格、控制字符集合 TAB/LF）、userId 0/-1、orderNo 同样三类、amount 0/-0.01；均不得提前 ACK。TAB 与 LF 属同一控制字符变体集合，不计作两个独立变体。NOT NULL 字段的 null 只通过 actual Mapper `delegatesTo` 返回 PO 的 seam 验证，不改 DDL、不冒称物理 DB 可存 null。
- 正控制覆盖合法域但与 incoming 不同的 user/order/amount/xid、Long.MAX_VALUE userId、DECIMAL 最大正金额；按 T203 OR 语义保持 ACK，T201–T203 矩阵回归。

### 3.2 Unit/query 边界

- 通过公开 rollback precondition/query 矩阵验证域门顺序、Fence/count/findOwners 短路和 callback 边界；不反射 private helper、不用静态 Mock 镜像实现。
- 仅本地 payload 有效域拒绝；不把不同 payload 误改为 full equality，也不把本批门描述为 source/funds/fencing 完整证明。

## 4. 明确边界

本批不修历史 receipt、不新增 SQL/锁/查询、不改变 owner OR、T201 state/branch、T202 Fence identity、callback、Store、prepare/commit、API、TC/RM 或全局唯一键；amount 不新增 scale/精度词法，xid 不新增格式协议。native 只验证本地 owner 域门与既有 rollback 资源边界。
