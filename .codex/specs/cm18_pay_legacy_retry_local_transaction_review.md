# CM-18 16T145：Pay Legacy retry local transaction

## 1. 结论与范围

GO。catch retry 当前 ORDER_SYNC_UNKNOWN 先写 PayRecord，再写 intent retry；第二写 0/异常会留下第一写，重试证据与租约清理分裂。非 ORDER_SYNC_UNKNOWN 也只有单 intent 写，缺少统一本地短事务边界。

## 2. Retry transaction

新增独立 `retryTransactions` 本地 `REQUIRES_NEW` TransactionTemplate 与 `recordOwnedLegacyRetry(intent, token, retryStatus, error)`：

- RootContext XID 非 null 时 helper 直接返回，不写、不解绑 caller XID；
- ORDER_SYNC_UNKNOWN：先执行原 `markOwnedLegacyOrderUnknown`，再 `recordRetryOwnedLegacy`；第一写返回 0 仍保原 best-effort 语义，不阻止第二写；
- 第二写必须返回 1；若为 0，设置 rollbackOnly，回滚第一写，helper 正常返回、由 caller 返回原 retryStatus，不 fallback/抛新业务异常；
- 第二写 Runtime 原样传播并整体 rollback；
- 非 ORDER_SYNC_UNKNOWN：仅原 `recordRetryOwnedLegacy` 也在同一短事务内执行。

保留错误 512 截断、退避、ORDER_SYNC_UNKNOWN/REFUND_PENDING/INIT 三方向与 SQL 参数；不新增 tuple proof、API、stage/terminal/RPC。

## 3. 保持项与验收

stage/terminal 使用各自独立 TransactionTemplate，不合并；第一写0+第二写1仍可提交 retry，第一写1+第二写0/异常整体回滚。

- Unit：区分 stage/terminal/retry 独立事务；fake manager 仅验证 definition/rollbackOnly；RootContext guard 无写；
- Native：Order/User 故障未 stage 时真实两写 rollback，healthy 两写同连接/RPC 外；single intent INIT/REFUND_PENDING、DAO second0/fault、同 pay 补证下一轮恢复。
- 真实 Pay owned DB/Mapper/资源清理，不启动 App/RM/TC；不宣称跨服务 fencing。

## 4. 交付边界

实现仅允许新增 retry TransactionTemplate/helper、替换 catch 两写及必要 JavaDoc/测试；不得修改 stage/terminal、SQL/API/lease 或 retry 状态计算。
