# CM-18 16T143：Pay Legacy stage local transaction

## 1. 结论与范围

GO。正向中间 stage `markOwnedLegacyOrderUnknown → updateOwnedLegacyStatus(DEBITED)` 与退款 stage `markOwnedLegacyRefundPending → updateOwnedLegacyStatus(REFUND_PENDING)` 当前不在本地事务中；第二写 0/异常会留下第一写，退款方向与 intent 状态可能分裂。

本批只把两组中间写包入独立本地短事务，不扩大 terminal/T141、claim、retry、RPC 或 tuple proof。

## 2. Stage transaction

新增独立 `stageTransactions` `TransactionTemplate`，使用同一 Pay DataSource、本地 `REQUIRES_NEW`；RootContext 非 null 时在进入事务前抛“Legacy阶段不得继承全局事务”，不解绑 caller XID。

私有 `commitOwnedLegacyStage(intent, token, stage)` 仅执行原顺序两 SQL：

1. `markOwnedLegacyOrderUnknown` 或 `markOwnedLegacyRefundPending`；
2. 对应 `updateOwnedLegacyStatus(DEBITED/REFUND_PENDING)`。

两者必须各返回 1；第二写 0/异常整体 rollback，异常沿原 catch/retry，不留下第一写。RPC 必须在事务外；`finishRefunded` 的单 stage、claim、terminal、catch retry 不纳入此事务。

## 3. 保持项与验收

健康两写在同一连接/本地事务提交；远端 User/Order 不持有 stage transaction。T82 terminal transaction 独立保持，unit 应区分 stage+terminal 两个本地事务，不宣称二者一起回滚。

- Unit/owned：第二写 0/异常时 cleanup 前确认第一写已 rollback；健康两写成功；T142 Order 坏证仍无 terminal。
- Native：真实 Mapper/事务 proxy 验证 stage 失败、retry、下一次恢复及 remote 不在事务内；source checksum/资源清理，不启动 App/RM/TC。

## 4. 交付边界

实现仅允许新增 stage TransactionTemplate/helper、替换两个 caller 及必要 JavaDoc/测试；不得修改 terminalTransactions、SQL、API、claim/retry/RPC。
