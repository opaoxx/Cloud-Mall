# CM-20261007-18：16D 公开户支付 TCC 路由与失败边界评审

## 1. 评审结论

结论：**有限 GO；允许显式配置把新公开支付单选择为 TCC，默认仍为 LEGACY；成功路径接入已实现的 coordinator → delivery；本批不开放 TCC 主动失败/取消，不新增表。**

本批的安全边界是公开入口只改变新支付单的协议选择。既有 `pay_record.debit_mode`、`pay_debit_intent.debit_mode` 和已有 TCC execution 的持久模式优先于当前配置。任何 TCC 记录不得进入 Legacy `PayConsistencyService`、Legacy debit、Legacy refund 或旧恢复器；任何 LEGACY 记录不得被 TCC coordinator/delivery 接管。

### 可安全实施的有限范围

1. `cloudmall.pay.debit-mode` 允许 `LEGACY`/`TCC`，默认 `LEGACY`；配置为 `TCC` 还必须校验独立 TCC entry、coordinator、receipt、User bridge、recovery、delivery、Seata 及未代理 DataSource 等既有前置开关。TCC entry Bean 使用可选注入，相关 flag 默认关闭；任一前置未开时启动失败。未知、空串、小写和尾随空格仍启动失败。
2. 新建支付单把选择冻结到 `pay_record.debit_mode`。成功模拟和成功回调按持久模式路由：LEGACY 继续现有一致性服务；TCC 只走既有 coordinator `execute/recover`，执行已确认后只走既有 delivery `deliver`。
3. TCC `fail`/`callback-failure` 本批保守返回结果未知（HTTP 202），只做本地读取/核验，不发任何 RPC、不调用 Order cancel、不写 `pay_record.FAILED`，不创建或补建 execution，不发送 Legacy debit/refund。仅在已经有完整 `ROLLEDBACK` 证据时允许按下文规则投影 FAILED。
4. 成功/失败回调的 `callbackId` 并发幂等必须先在独立 `REQUIRES_NEW` 事务 `INSERT IGNORE` 预绑定 `payNo/userId/amount/success` payload，状态为 `RECEIVED_SUCCESS`/`RECEIVED_FAILURE` 且 `processed_at=NULL`；重放仍执行/核对真实支付结果，不能仅凭日志返回 200。跨支付、成功/失败方向不同、金额或用户不一致均 409。
5. 保持公开 API 的 Gateway Bearer/RedisToken 鉴权、金额字符串、时间和现有 202/409/503 语义；不新增公网接口、回调鉴权方式、数据库表或新的补偿主题。

## 2. 现有实现缺口与禁止的捷径

- 当前 `PayDebitModeConfiguration` 只接受 LEGACY。简单放开 TCC 后，`PayServiceImpl.success/callback(success)` 仍会调用 Legacy `payConsistencyService.initiate`，会造成错误扣款路径；必须先按持久模式分流。
- `PayDebitModes.requireSupported` 必须保留为 Legacy 专用，不能把它扩为 TCC；旧 `PayConsistencyService`/恢复器继续调用它，避免旧路径误扣。公开入口另行按 `pay_record.debit_mode` 选择 TCC entry；缺失、未知、模式不一致仍返回 `PAY_DEBIT_MODE_UNAVAILABLE`/503。
- 不得因为 `pay_tcc_execution` 查询不到就直接远程 `Order.cancel`。首次 begin 与 fail 并发时，查询无 execution 不能证明没有 begin，也不能证明订单可安全取消。
- 不得把主动 TCC failure 先写 FAILED 再异步猜测 cancel；本批 failure 入口只读本地事实，不发 coordinator/recover、TC、User 或 Order RPC，因此采用保守 202 边界。
- 不得用当前配置覆盖旧支付模式，不得通过移除旧 mode guard 迁移历史 Legacy 记录。

## 3. 公开成功路径契约

### 3.1 create

`POST /api/payments` 仍由 Gateway 鉴权并校验订单归属、订单状态和金额。只对新行调用 `PayDebitModeConfiguration.newPaymentMode()`，把结果写入 `pay_record.debit_mode`；按原 `Idempotency-Key` 重放时读取并校验原支付载荷，不能重新读取配置或换协议。

### 3.2 mock-success/callback-success

1. 按 `payNo` 锁定/读取支付记录，核对持久模式、用户、订单和金额。首次没有 execution 时先通过 Order payment-state 核验 `PENDING_PAYMENT`，再允许 TCC begin；已有 execution 时必须直接 recover，不因订单后来变化而阻断原执行。
2. `LEGACY` 保持现有 `PayConsistencyService.initiate`，旧的 202 未知、409 失败/已处理、退款中语义不变。
3. `TCC` 调用既有 coordinator：首次只允许唯一 execution claim、原 XID、原两个 Try；重入只能 `recover`，不能新 begin、新 XID 或新 branch。执行结果为 `COMMITTED` 后调用既有 delivery；delivery 未完成时沿用其 202/503 pending 语义，完成后才把 Pay 记录投影为 SUCCESS。
4. coordinator 结果为 `ROLLEDBACK` 时按第 5 节投影 FAILED 并返回现有支付失败 409；`UNKNOWN`、`RUNNING`、`REQUESTED`、TC Unknown 或证据不足均返回 202，不猜测资金结果。
5. 成功回调重复投递只能在 callbackId 绑定事实完全一致时复用原结果；不能因为日志存在就跳过 TCC coordinator/delivery 的必要事实核验。

## 4. 主动失败与首次 begin 并发

### 4.1 本批选择：保守拒绝 TCC 主动失败

对 `debit_mode=TCC` 的 `mock-fail` 和 `callback(success=false)`：

- 支付仍为 PENDING/ORDER_SYNC_UNKNOWN 时返回 `PAY_ORDER_SYNC_UNKNOWN`/202；不调用任何远程 RPC，不写 FAILED，不改 execution，不创建新 execution。保留现有 `@Transactional` wrapper 不改变此只读边界。
- 已有 execution 且为 `RUNNING/UNKNOWN/COMMIT_REQUESTED/ROLLBACK_REQUESTED` 时同样返回 202；入口不主动触发 coordinator/recover/TC/User/Order RPC，由既有后台事实链自行恢复；不能发相反决议。
- 已 `SUCCESS`/`REFUNDED` 返回既有 409；已 `FAILED` 可按原幂等语义返回。
- TCC callback-failure 先预绑定 `RECEIVED_FAILURE`，不把它当作处理终态；返回 202 时保持 `processed_at=NULL`，真实结果处理成功后才更新为 `FAILED` 并写 `processed_at`。

此边界明确表示本批不提供“用户主动失败立即关单”的 TCC 业务能力。订单超时/既有订单恢复仍按其自身持久状态工作；本批不新增取消恢复链路。

### 4.2 并发安全要求

首次 TCC begin 的唯一资格仍是 `PayTccExecutionStore.claim` 的持久唯一插入及原支付行校验。公开成功请求之间的并发只能有一个 begin；其他请求只能 recover。TCC failure 不抢占 PENDING 行，因而不能与首次 begin 形成“先写 FAILED、后 begin”的半成品竞态；它统一返回 202。

若未来要支持主动 TCC failure，必须另行评审：先在本地持久化不可逆抢占（阻止未来 begin），事务提交后再调用 Order.cancel，并为 cancel 响应未知建立有界恢复台账；不能用本批的 `pay_tcc_delivery` 或仅查询 execution 代替该设计。

## 5. ROLLEDBACK 到 Pay FAILED 的安全投影

只有同时满足以下条件，TCC 成功入口/恢复入口才可将 Pay 记录从 `PENDING` 或 `ORDER_SYNC_UNKNOWN` CAS 为 `FAILED`：

- execution 为原 `payNo` 的 `state=ROLLEDBACK`、XID 非空；`decision` 可为 `NONE`、`COMMIT` 或 `ROLLBACK`，不得因投影改写既有 decision。TC 超时后双端确定取消时不能永久保留 202。
- execution 与 `pay_record` 的 userId、orderNo、amount、operationKey、ledgerKey 完整一致，且持久 `debit_mode=TCC`；
- Pay receipt/Fence 与 User TCC 当前永久事实均已核对为取消/回滚，且没有 `balance_ledger(DEBIT,APPLIED)` 或其他已确认扣款事实；
- 当前 Pay 状态仍允许失败投影。CAS 失败必须重新读取并按现有 409/202/成功状态处理，不能覆盖 SUCCESS、REFUNDED 或冲突状态。

该投影须在事务外完成 User live GET 与 Pay 本地快照核验，再以短本地事务 CAS FAILED；不调用 Legacy debit/refund，也不凭 ROLLEDBACK 自动调用 Order.cancel。证据缺失、TC Unknown、User/Pay 状态冲突或 ledger 未知均保留 202/UNKNOWN。

## 6. callbackId 隔离契约

当前 `pay_callback_log.callback_id` 全局唯一，首次处理必须在独立 `REQUIRES_NEW` 事务中 `INSERT IGNORE` 预绑定原 payload；冻结 `RECEIVED_SUCCESS`/`RECEIVED_FAILURE` 为仅收到未处理状态，`processed_at=NULL`。真实处理完成后才更新为 `SUCCESS`/`FAILED` 并写 `processed_at`。读取命中后必须先核对日志 `pay_no` 与当前请求：

- 同一 `payNo`、同一方向、同一 userId/amount：无论日志为 RECEIVED 还是终态，都必须按当前真实支付结果继续执行/核对，不能因有日志直接 200；
- 同一 callbackId 对应其他 payNo，或同一 payNo 但 success/failure 方向不同：409 `PAY_CALLBACK_CONFLICT`；
- 金额或 userId 不同：沿用 409 金额/权限错误，不得返回 200；
- 现有历史日志无法证明完整事实时，按冲突或结果未知处理，不能静默兼容。

不扩表时，沿用原 `payload JSON` 保存不可变 `payNo/userId/amount/success`；旧 `payload={}` 仅在 same payNo、same 方向且当前不可变 Pay 字段已校验时兼容。不得先查到 callbackId 就直接 200。

## 7. 保持的既有错误语义

| 场景 | 结果 |
| --- | --- |
| 未知/不一致 debit mode | `PAY_DEBIT_MODE_UNAVAILABLE`，503；不调用任何扣款/退款 |
| TCC coordinator、TC、User、Order 或 delivery 事实未知 | 202/503 pending，保留原 payNo，禁止猜失败 |
| 已 FAILED 或已处理支付再次 success/fail | 既有 409 |
| 金额不一致 | 既有 `PAY_MISMATCH`，409 |
| callback 用户不匹配 | 既有鉴权错误，禁止先查全局 callbackId 返回 200 |
| 订单取消且 TCC 资金已确认 | 仅走既有 delivery 的唯一 `refund:<payNo>`，退款响应未知保持 REFUND_PENDING/202 |

## 8. 数据库、API 与架构影响

- 本批不新增或扩大数据库表；沿用已评审的 `pay_tcc_execution`、`pay_tcc_delivery`、Pay receipt/dispatch 和现有 callback log，仅冻结 `RECEIVED_SUCCESS`/`RECEIVED_FAILURE` 两个已有 VARCHAR 状态值及 payload 语义。
- `pay_record.debit_mode` 的允许取值从“当前实现只允许 LEGACY”调整为“新建配置可选 LEGACY/TCC，历史行按持久值路由”。`pay_debit_intent` 仍只服务 LEGACY，TCC 不创建或读取为扣款事实。
- 不新增公网 endpoint；现有 `/api/payments`、`/{payNo}/mock-success`、`/{payNo}/mock-fail`、`/callback` 的鉴权、字段和路径保持不变，仅按持久协议分流其内部实现。
- 规格实施前必须由 `pm_arch` 冻结 mode 分流、TCC failure 202 边界和 callbackId 冲突规则；之后才委派 backend。backend 不得修改 frontend、Gateway 路由或未评审公共字段。

## 9. 必须验收的最小矩阵

1. 默认配置创建 LEGACY，显式 TCC 配置创建 TCC；配置未知值启动失败；历史支付不随配置变化。
2. TCC success 首次 begin 只有一个 execution/XID/Pay branch/User branch；重复请求走 recover，绝不进入 Legacy debit。
3. TCC COMMITTED 只经 delivery 完成后 SUCCESS；Order/Stock 未确认、User ledger 未确认、TC Unknown 均保持 202/UNKNOWN。
4. TCC ROLLEDBACK 在完整双端证据下投影 FAILED；缺任一证据不得 FAILED，且不调用 Legacy debit/refund。
5. TCC fail/callback-failure 在无 execution、首次 begin 并发、已有 UNKNOWN、已有 commit request 四种场景均不 cancel、不写 FAILED，返回 202。
6. LEGACY success/fail/callback 既有回归保持通过，尤其 202 未知、409 已失败/已成功、金额和鉴权错误。
7. callbackId 跨 payNo、同 payNo 改 success 方向、金额/用户不一致均 409；同一完整事实重放才可复用结果。
8. Gateway Bearer/RedisToken 鉴权、内部 callback token、既有 refund 唯一键和恢复扫描回归通过。

## 10. 明确 NO-GO

- 仅解除 `PayDebitModes.requireSupported` 或只改配置而不做 success/callback TCC 分流。
- TCC failure 查询不到 execution 后直接远程 cancel，或先远程 cancel 再补写失败状态。
- 由公开失败/重放请求补建 execution、补 begin、新 XID、新 branch，或将旧 Legacy 记录迁入 TCC；首次成功请求在无 execution 且订单预检为 `PENDING_PAYMENT` 时，允许由唯一 coordinator claim 创建原 execution 并 begin 一个 XID。
- 用 `ROLLBACK` RPC 返回、TC Unknown 或缺失 ledger 事实直接写 FAILED。
- callbackId 仅全局命中即 200，允许跨支付单复用。
- 本批新增失败抢占表、取消 outbox、退款表或改变 Order/Stock 数据模型；这些需要下一轮单独评审。
