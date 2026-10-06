# CM-20261007-18：16J Legacy 首次 intent 与 failure 认领竞态评审

## 1. 结论与范围

结论：**有限 GO；统一 Legacy 首次扣款意图与 failure 入口的 Pay→intent 锁序和当前读，消除旧 RR snapshot 授权取消竞态。**

当前 `PayDebitIntentService.ensureIntent` 非锁定读取 Pay、允许在 PENDING 下与并发 failure 交错；`PayServiceImpl.fail/callback failure` 又可能依据外层事务旧快照看不到刚插入的 INIT intent，从而先 Order.cancel、再写 Pay FAILED，之后扣款恢复无法把已扣款记录推进 REFUND_PENDING。16J 只修认领与失败授权，不新增表、字段、flag、资金恢复或 TCC 逻辑。

## 2. 统一互斥与锁序

所有 Legacy 首次意图/失败授权使用同一锁序：

1. 独立本地事务中 `SELECT pay_record FOR UPDATE`；
2. 核对 Pay 不可变 tuple、`debit_mode=LEGACY` 和当前状态；
3. 对 `pay_debit_intent` 使用 `findByPayNoForUpdate` 当前锁定读；
4. 只有在锁内完成事实判断后，才允许插入 INIT、执行原 Order.cancel/markFailed 或返回未知。

success 与 failure 竞争只能一个方向先持有 Pay 行锁。success 先提交 INIT 时，failure 看到现有 intent，不能取消订单；failure 先完成 PENDING 无 intent 的失败 CAS 时，success 随后不能新建扣款意图。数据库锁等待由 MySQL/事务现有超时机制控制，Order RPC 仅受既有 Feign 连接 3 秒/读取 5 秒约束；本轮不新增锁等待配置或远程补偿模型。

## 3. ensureIntent 契约

`PayDebitIntentService.ensureIntent` 改为 `REQUIRES_NEW` 下：

- 先 Pay `FOR UPDATE`，校验 payNo、userId、orderNo、amount、Legacy mode；模式未知/TCC 返回既有 503，载荷冲突 409。
- 再 intent `FOR UPDATE` 当前读；已有 intent 必须完整核对 mode/tuple 后原样返回，允许原 PayConsistencyService 继续按其状态重放，不覆盖 status/lease/attempts。
- 仅当锁内当前 Pay 为 `PENDING` 且不存在 intent 时插入唯一 INIT；Pay 为 `ORDER_SYNC_UNKNOWN`、SUCCESS、FAILED、REFUND_PENDING、REFUNDED 或其他非 PENDING 时不新建 intent，按现有调用方状态语义返回/冲突。
- 唯一键竞争后重新 `FOR UPDATE` 读取并完整校验，不以 INSERT IGNORE 的 0 行结果推断无 intent。

该服务仍在任何 User 扣款 RPC 前独立提交 INIT；不修改现有资金恢复状态机、租约和操作键。

## 4. Legacy failure 服务契约

新增独立 `PayLegacyFailureService.fail(PayVO)`（或等价现有服务边界），入口使用 `REQUIRES_NEW`，由 mock-fail 与 callback failure 共同调用；它不能使用 Controller 外层旧 PayVO/RR snapshot 授权取消。服务在锁内重新读取 Pay 与 intent，复用 16I 分类，并返回锁内确认的最新 PayVO：

- SUCCESS/REFUNDED：409；REFUND_PENDING：`PAY_REFUND_PENDING`/202；FAILED 且 intent 缺失或完整匹配 FAILED：幂等 200，零 RPC；
- PENDING 且 intent 缺失或完整匹配 FAILED：沿原 `notifyFailure` Order.cancel 与 Pay `markFailed` 路径；保留现有 Order RPC 异常传播和本轮外层事务边界，只有 markFailed 完成后才返回成功；
- ORDER_SYNC_UNKNOWN 仅完整匹配 FAILED intent 才沿原 cancel+markFailed 路径，intent 缺失/非 FAILED 保守 202；
- intent mode/tuple/状态矛盾：按既有 503/202，禁止 Order.cancel、markFailed 或 callback complete。

失败服务不得创建新 intent、调用 User debit/refund、调用 TCC、改支付协议或新增补偿台账。PayServiceImpl.fail 移除外层 `@Transactional`：该 wrapper 只做鉴权、持久模式读取和委托，不持有旧 RR snapshot 或额外 Pay 连接；Legacy cancel+markFailed 完整包含在 `PayLegacyFailureService.REQUIRES_NEW` 中。TCC failure 继续由原 `NOT_SUPPORTED` 入口处理。Order RPC 仍位于 Legacy failure 服务的既有事务边界内，连接/锁等待沿 MySQL/事务现有机制，RPC 沿用 Feign 3/5 秒超时与原 500/BizException 映射。

## 5. callback 一致性

callback failure 先沿 16D 独立 receipt 预绑定，再委托同一 `PayLegacyFailureService.fail`；服务成功写 Pay FAILED 后才调用 receipt complete，并使用 service 返回的最新 PayVO。PENDING/ORDER_SYNC_UNKNOWN 未明确完成、REFUND_PENDING、冲突或异常均保留 RECEIVED_FAILURE/NULL，不 complete。callback 没有外层 `@Transactional`，仅 receipt claim/complete 各自独立事务；mock-fail 移除原外层事务，TCC 分支仍由原 `NOT_SUPPORTED` 入口处理。

## 6. 最小验收矩阵

1. success ensureIntent 与 fail 同 payNo 并发：只有 Pay→intent 锁胜者决定；success 已插入 INIT 时 failure 不 cancel/FAILED，failure 先完成时 success 不新建 INIT。
2. ensureIntent 已有 INIT/DEBITED/ORDER_SYNC_UNKNOWN/REFUND_PENDING：锁内完整 tuple 重放，不覆盖状态或生成第二意图。
3. Pay 非 PENDING 且无 intent：不创建新 intent；按现有状态分类返回，不能远程扣款。
4. Legacy mock-fail/callback 复用同一当前读分类；FAILED 重放零 Order RPC，REFUND_PENDING 零 RPC，PENDING 允许的 intent 沿原 cancel+markFailed。
5. ORDER_SYNC_UNKNOWN 缺失/非 FAILED intent 保守 202；完整 FAILED intent 的原取消路径成功后 callback 才 complete。
6. intent mode/tuple 冲突、Pay tuple 变化、INSERT IGNORE 竞争：503/409/202 按既有契约，不写错误终态。
7. TCC success/failure/cancel/rollback/后台投影回归保持不变。

## 7. 明确 NO-GO

- 使用 PayServiceImpl 外层旧 snapshot 判断“无 intent”并授权取消。
- 只用非锁定 `findByPayNo` 判断首次 intent，或让 success 锁 Pay→intent、failure 采用相反锁序。
- PENDING 并发下先远程取消再确认 intent；ORDER_SYNC_UNKNOWN 缺失 intent 猜测无扣款。
- 为本轮新增取消/扣款恢复表、补偿消息、flag 或修改 TCC/Legacy 资金操作键。
