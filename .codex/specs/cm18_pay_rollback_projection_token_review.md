# CM18 16T187：Pay TCC 回滚投影 token 上限校验规格

## 1. 评审结论与范围

本批 **GO**。两个带恢复租约的回滚投影入口只拒绝 NULL/blank，漏掉 Java UTF-16 长度上限：`PayTccPublicStateStore.projectFailedClaimed` 可在超长历史 owner 下把 Pay 从 PENDING 投影为 FAILED，`PayTccRollbackProjectionService.projectClaimed` 还会在超长 token 下执行本地 proof 与 User GET。本批只补两个已有 guard，不改变无租约公开投影路径。

## 2. 最小行为修正

### 2.1 Store `projectFailedClaimed`

将现有 token 门扩展为 `token == null || token.isBlank() || token.length() > 128`。非法 token 沿既有 `PAY_ORDER_SYNC_UNKNOWN/202` 的 `pending()` 返回，在支付锁、execution 锁、取消/intent 查询、`validate`、`markFailed` 之前退出；不执行本地 proof、Pay 写或其它 Mapper 操作。

### 2.2 Service `projectClaimed`

将入口的 `recoveryToken` 门扩展为 `recoveryToken == null || recoveryToken.isBlank() || recoveryToken.length() > 128`。非法 token 使用既有 `PAY_ORDER_SYNC_UNKNOWN/202` 消息返回，在 `verifiedProof`、User GET、Store 调用之前退出；不发远端请求、不写 Pay/Execution/Cancel/Intent。

两个入口均按 Java `String.length()` 的 UTF-16 code unit 计算，非 blank 首尾空格、ASCII128、emoji64（UTF-16 128）合法；emoji65（UTF-16 130）即使 `VARCHAR(128)` 按数据库字符数可存，也因 Java token 域超限拒绝。ASCII129 仅 Unit 验证，不写数据库。

`projectFailed(payment,xid)`、claim 两入口、projectLocked 的状态/decision/lease/owner 比较、SQL、表结构、错误截断、API、调度、RPC/TCC 全部保持。

## 3. 验收矩阵

### 3.1 Unit

- Store：七类非法 token 均在任何 Mapper 前返回既有 pending；四类合法 token 的精确 token、公开 project 控制、返回值和写入条件保持。
- Service：七类非法 recovery token 均不执行 proof/User GET/Store；四类合法 token 保留 proof 顺序、User 证据和 Store 返回；公开无租约 `projectFailed` 路径不受影响。
- 保留错误 owner、过期 lease、pay alias、缺失 execution、资金/取消/intent 事实门和三种 decision 控制。

### 3.2 Owned MySQL

- 使用原 execution DDL 与真实 Recovery/PublicState Mapper/Store；取消表缺失时只在 owned schema 调用实际 `ensureTable`，不复制生产 SQL。原 Pay/Intent/Execution/Cancel 指纹只读保持，不启动业务 App、TC/RM 或真实远端 User。
- 旧 red：emoji65 owner 在 `ROLLEDBACK` 状态的 `NONE`、`COMMIT`、`ROLLBACK` decision 下实际可令 Store 投影 FAILED；`RUNNING` 状态保留原有 pending 拒绝控制。修复后六类数据库可存非法 token均不改变 Pay、Intent、Execution、Cancel 四表事实。
- native 只直接使用 PublicStateStore 与 Pay/Execution/Cancel/Intent Mapper；RecoveryMapper、Dispatch/Evidence 替身不参与本批 native。Service 超长 token 在 proof/User GET 前的提前拒绝由 Unit 验证，不将其写成 native 已证明的远端行为。
- 合法四类 token 保留两种可写状态及 FAILED 重入；错 owner、过期 lease、资金门、别名支付和缺失事实保持原结果。
- emoji65 的拒绝是 Java token 域约束，不是数据库字符容量错误；native 只使用列可表示的 token，ASCII129 仅 Unit。

## 4. 明确边界

本批不改变公开无租约投影、不修历史 owner、不增加 Mapper SQL/表字段、不改变 lease/状态/decision 或远端证据语义，不宣称真实 TC、User 远端或全资金因果证明。native 使用程序化本地事务，不能冒称 Spring/跨服务事务集成。
