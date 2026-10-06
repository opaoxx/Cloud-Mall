# CM-20261006-18：Pay/User TCC 派发桥评审

## 1. 评审结论

结论：**GO，实施 16A Pay 持久派发去重 + User 内部 Try/只读状态桥；Pay 对外支付入口仍为 LEGACY，Pay receipt 和主编排后置。**

本轮的实际价值是验证跨进程 Nacos/Feign/TC 链路，并解决“POST 响应丢失后重复注册第二个 TCC branch”的风险。Pay 在首次 POST 前持久化不可变派发记录和 `POST_ATTEMPTED` 标记；只要 POST 已经发出，任何超时、连接断开或响应解析失败都只能使用同一 `payNo/XID/载荷` 调用 User 只读 GET，不能再次 POST。User 通过内部 Try 桥绑定已持久化 XID，调用本地 LocalTCC RM 资源；Confirm/Cancel 仍只由 TC 回调，外部没有 HTTP 接口。

本轮不把该桥接入 `/api/payments/{payNo}/mock-success`、callback 或余额 Legacy 主路由；不创建 Pay receipt，不让用户余额支付切换 TCC。只有显式启用的内部测试/迁移入口可以创建 TCC dispatch，默认关闭。

User 不反查 Pay GET，避免形成 Pay/User 循环依赖。Pay 是派发事实的唯一来源；User 只通过内部服务认证、请求载荷、XID 上下文和自己的接收台账校验请求。Pay 通过只读 User GET 查询 User 事实，该 GET 不对前端开放。

## 2. 边界

### 纳入

- Pay `pay_tcc_dispatch` 持久化派发意图、不可变 XID/载荷和 POST 尝试标记。
- Pay → User OpenFeign Try POST 和只读状态 GET 契约。
- User 内部 Try 桥：校验服务身份、XID、派发载荷后调用 User LocalTCC 资源代理。
- User 内部只读状态桥：只查询 attempt/TCC/ledger/Fence 事实，不读取 TC Unknown 作为资金结论。
- Nacos 注册发现、真实 Feign 跨进程、TC 分支回调和响应丢失验收。
- Gateway 明确拒绝内部路径，调用只走 Nacos/Feign。

### 排除

- Pay 对外支付接口和 Legacy 余额扣款。
- Pay receipt、Pay 主恢复器、Order/Stock 和退款编排。
- User Confirm/Cancel HTTP/Feign。
- Feign POST 自动重试；Feign GET 只允许现有有限重试策略。

## 3. Pay 派发持久化契约

新增 `pay_tcc_dispatch`：

```sql
CREATE TABLE IF NOT EXISTS pay_tcc_dispatch (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  operation_key VARCHAR(128) NOT NULL,
  ledger_key VARCHAR(128) NOT NULL,
  state VARCHAR(32) NOT NULL,
  post_attempted_at DATETIME(3),
  user_branch_id BIGINT,
  last_error VARCHAR(512),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pay_tcc_dispatch_pay(pay_no),
  UNIQUE KEY uk_pay_tcc_dispatch_xid(xid),
  UNIQUE KEY uk_pay_tcc_dispatch_operation(operation_key),
  UNIQUE KEY uk_pay_tcc_dispatch_ledger(ledger_key)
);
```

允许状态：

- `PREPARED`：XID/载荷已持久化，POST 尚未尝试。
- `POST_ATTEMPTED`：已提交不可变派发标记，POST 已经发出；后续禁止再次 POST。
- `TRY_ACCEPTED`：User 已确认 Try 受理。
- `TRY_UNKNOWN`：POST 响应未知，只能 GET 查询。
- `CANCELLED`、`CONFIRMED`、`REJECTED`：由只读 User 事实或后续编排明确收敛；16A 不推进真实 Pay 主路由。`REJECTED` 只有 User 持久化明确拒绝事实才允许使用；普通 HTTP 4xx、Feign 异常或空响应不能推断拒绝。`CONFIRMED/CANCELLED` 是镜像终态，迟到 UNKNOWN/TRYING 不能回退或互相覆盖；`user_branch_id` 只记录首次观察到的 User branch。

`operation_key=tcc-debit:<payNo>`，`ledger_key=payNo`，必须与 User 14/15 轮规则一致。Pay 记录、dispatch 和请求载荷一旦写入不可修改；同 payNo/XID/金额/订单/用户不一致返回 409。

## 4. 派发状态机和响应未知规则

1. Pay 事务创建全局 XID 后，先锁定并核对原 `pay_record`：payNo、userId、orderNo、amount 完全一致，`debit_mode` 精确为 `TCC` 且状态为 `PENDING`；再以独立本地事务插入 `PREPARED`，保存 XID、payNo、用户、订单、金额、operationKey、ledgerKey。
2. 以条件更新将 `PREPARED → POST_ATTEMPTED`，提交后才发出一次 User Try POST。只有持有该派发记录且状态为 PREPARED 的实例可以执行这次转换。
3. POST 返回明确成功时更新为 `TRY_ACCEPTED`；只有响应中的 User 持久化拒绝事实明确存在时才更新为 `REJECTED`；余额不足等本地事务回滚且没有拒绝行时保持 `TRY_UNKNOWN`。连接断开、超时、空响应、响应解析失败同样更新为 `TRY_UNKNOWN`。
4. `POST_ATTEMPTED/TRY_ACCEPTED/TRY_UNKNOWN` 永久禁止再次 POST。恢复只能 GET `/tcc-debits/{operationKey}`，使用同一 payNo/XID 读取 User 持久事实。
5. GET 返回 `TRYING/CONFIRMED/CANCELLED` 等业务事实时更新本地镜像；若 User receive 为 `CLAIMED` 且没有 branch 事实，GET 返回 UNKNOWN 且不修改 receive。返回 404、空结果或服务不可达只能保持 `TRY_UNKNOWN`，不能解释为未执行并重发 POST。已确认的 `CONFIRMED/CANCELLED` 不得被迟到 TRYING/UNKNOWN 覆盖。
6. Pay 不依据 TC `getStatus=Unknown` 推测资金状态；没有 User 持久事实时保持未知并告警，等待后续恢复批处理。

该状态机宁可保留一次 POST 未知，也不能通过自动重发制造第二个 branch。只有明确证明 POST 未发出且仍为 `PREPARED` 时，才允许唯一派发者执行一次 POST。

### 4.1 User 接收台账

User 新增 `user_tcc_receive`，用于防止多个入站 POST 在 User LocalTCC 注册第二 branch：

```sql
CREATE TABLE IF NOT EXISTS user_tcc_receive (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  branch_id BIGINT,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  operation_key VARCHAR(128) NOT NULL,
  ledger_key VARCHAR(128) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_user_tcc_receive_pay(pay_no),
  UNIQUE KEY uk_user_tcc_receive_xid(xid),
  UNIQUE KEY uk_user_tcc_receive_operation(operation_key),
  UNIQUE KEY uk_user_tcc_receive_ledger(ledger_key)
);
```

接收状态至少包含 `CLAIMED`、`TRY_UNKNOWN`、`TRYING`、`CONFIRMED`、`CANCELLED`。`CLAIMED` 必须在进入 LocalTCC 代理前独立提交；如果 branch 尚未注册即进程退出，保持未知并阻断重发。接收台账与 attempt/TCC 事实按完整 user/pay/order/amount/XID/branch/operationKey/ledgerKey 校验，任何不一致只返回 409，不能覆盖原事实。

## 5. Pay → User API/Feign 契约

### 5.1 Try POST

`POST /api/internal/users/{userId}/balance/tcc-debits/try` 仅供 Pay 内部桥调用：

- Java 契约：`PayTccBridgeService.dispatch(PayTccDispatchDTO)` 从 `RootContext` 取 XID；`recover(payNo)` 只执行 GET。Feign `UserTccClient.prepare(userId,callerId,service,token,xid,UserTccTryDTO)`。
- `PayTccDispatchDTO` 只包含 `payNo、userId、orderNo、amount`。User `UserTccTryDTO` 包含 `payNo、orderNo、amount、xid、operationKey、ledgerKey`；branchId 只能由 User Seata 上下文绑定。
- Header：`X-Internal-Service: cloud-mall-pay`、`X-User-Id`、`X-Internal-Callback-Token`（复用 `cloudmall.internal.callback-token`）、`TX_XID`；User 校验 Header、路径 userId、持久 dispatch 和当前 RootContext 一致。
- User 先在独立本地事务写入 `user_tcc_receive(state=CLAIMED)`，以 payNo、XID、用户、订单、金额、operationKey、ledgerKey 唯一认领；只有唯一 winner 可以进入 LocalTCC 代理。并发/重放 POST 只读取接收台账和 User 资金事实，绝不再次注册 branch。
- 接收台账写入后进程在 branch 注册前退出，记录保持 `CLAIMED/UNKNOWN`；后续 POST 仍禁止重发，Pay 只能 GET 原 operationKey，直到 TC/业务事实明确收敛或人工核验。
- User 在同一个请求内调用 LocalTCC 代理的 Try；不直接调用 raw implementation，不手工生成 branchId。
- Gateway/Auth 必须最先阻断包含 `tcc-debits` 路径段的新内部命名空间及其编码、路径参数变体，不经 Nacos discovery 转发；即使通用 `cloud-mall-user` discovery route 为 enabled，也不能绕过。该规则不修改其它内部路由。User Controller 只允许来自服务内 Feign 的认证上下文。
- POST 禁止 Feign 自动重试；任何响应未知由 Pay 标记 TRY_UNKNOWN。

### 5.2 Read-only GET

`GET /api/internal/users/{userId}/balance/tcc-debits/{operationKey}`：

- 只读 `balance_debit_attempt`、`balance_tcc_debit`、`balance_ledger` 和必要 Fence 事实。
- 返回完整载荷摘要、状态、精确 XID、branchId、ledgerKey 和是否存在 APPLIED 账本；精确 XID 只供 Pay 内部机器校验，不返回前端。
- 状态来自同一读取快照的一致核对：`CLAIMED/UNKNOWN` 只表示请求已认领，`TRYING` 表示冻结存在但未最终确认，`CONFIRMED` 必须同时存在 APPLIED ledger，`CANCELLED` 必须有取消归属且无冻结；`TRYING` 不能作为已扣款或支付成功。
- 404 只表示未观察到 User 持久事实；不能直接作为“未执行”结论。
- GET 可按现有 Feign 有限重试策略调用；重试只读，不注册分支、不改变状态。
- Feign `UserTccClient.state(userId,operationKey,callerId,service,token,xidParam)` 使用 `?xid=` 原值核对派发绑定，但 GET 不绑定 RootContext。
- Pay/User 各自使用 `UserTccStateVO`，字段为 `userId、payNo、orderNo、amount`（金额字符串）、`operationKey、ledgerKey、xid、branchId、state、ledgerApplied`。

Confirm/Cancel 不提供 HTTP/Feign 接口，只能由 TC 回调 User LocalTCC RM。

## 6. 内部认证和 XID 绑定

- User 必须校验服务身份、内部凭证、路径 userId、RootContext XID、请求 XID 和 dispatch 持久记录完全一致。
- User 不调用 Pay 反查；请求必须先通过内部认证和 `user_tcc_receive` 唯一认领，再进入 LocalTCC。
- body 中的 XID、branchId、operationKey、ledgerKey 不能覆盖框架上下文或持久事实；不一致返回 409/503 并无资金副作用。
- 所有内部路径只通过 Nacos/Feign 直连；Gateway 路由表不得暴露，匿名或用户 Bearer Token 访问统一拒绝。
- 资源默认关闭；开启 TCC bridge 必须同时满足 User Seata RM、Fence 同库、关闭 AT 自动代理和 DataSourceTransactionManager 校验。

## 7. 16A 跨进程验收矩阵

必须使用真实 Nacos、真实 User RM、真实 Pay bridge 进程、真实 TC 和独立 MySQL：

1. `PREPARED → POST_ATTEMPTED → TRY_ACCEPTED`：User 真实注册同一 XID/branch，TC Confirm/Cancel 后资金事实正确。
2. POST 响应丢失/Feign 超时：Pay 只进入 TRY_UNKNOWN；GET 查询原 payNo/XID 收敛，网络恢复后没有第二个 POST/branch。
3. User Try 余额不足：若 User 没有持久拒绝行，Pay 保持 TRY_UNKNOWN；TC Cancel 后 GET 才能观察明确 CANCELLED，余额、冻结额、账本无副作用。
4. 两个并发入站 POST：User 接收台账只允许一个 winner 注册 branch，另一个只读事实且无第二 branch。
5. User RM 迟到或重启：Pay POST 只发一次，GET 只读；TC 回调仍由 User RM 收敛。
6. Nacos 服务重选：Feign 只在第一次 POST 发送一次；实例变化不能触发重复 POST。
7. 内部路径安全：Gateway、匿名请求、错误内部凭证、错误 userId/XID 均被拒绝，User/Pay 无资金变化。
8. 现有 Pay Legacy 支付、退款、恢复和全量 User 资金测试保持通过；对外支付入口不进入 TCC dispatch。

所有场景检查 Pay dispatch、User attempt/TCC、余额、冻结额、账本、Fence 和 TC 分支终态。未决或 Unknown 保留数据库/XID/TC 记录，不删除和不猜测。

## 8. 后续边界

- **16A GO**：派发一次性 POST、只读恢复、内部认证、跨进程 Feign/Nacos/TC 验收。
- **16B 后续**：Pay receipt、TCC 主编排、支付入口切换、Order/Stock 联动、退款和完整支付故障恢复。
- 本轮完成后只能宣称 Pay/User TCC bridge 通过，CM-18 真实支付闭环仍未完成。
