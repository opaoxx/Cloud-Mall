# CM-18 16T146：Pay Legacy retry tuple lock

## 1. 结论与范围

GO。T145 retry short transaction 仍只核 pay/mode/token/status/lease；远端故障期间 payment/intent user/order/amount 改变时，catch cleanup 可能推进错误 tuple。

本批只在 retry 两写前锁定本地原 tuple，不改 retry direction/退避、stage/terminal 或远端 fencing。

## 2. Retry source lock

新增 `PayRecordMapper.lockOwnedLegacyRetrySource(expectedIntent, token, now)`：单 JOIN/FOR UPDATE 投影，精确双表 pay/user/BINARY order/amount、双 LEGACY、payment/intent active status、token、未过期 lease。

`recordOwnedLegacyRetry` 在 retry transaction 内、原 cleanup SQL 前调用：

- null（载荷/资格变化）直接结束 helper，无 retry cleanup write、不释放 lease，caller 仍返回原 retryStatus/log；
- 非 null 锁保持至原 ORDER_SYNC_UNKNOWN 两写或单 intent retry commit/rollback。

RootContext guard 优先且不解绑；T145 first=0/second=1、second=0 rollback、异常传播、SQL/退避/API 保持。

## 3. 保持项与验收

不改 claim/stage/terminal/RPC/lease/tuple API，明确 process 其它已发生写不被宣称为零写；不解决锁后远端/跨表完整 fencing。

- Native：三 retry direction × payment/intent/both user/order/amount/CI order 36 变体，source 缺证 zero cleanup write；两 writer 锁阻塞/commit/rollback release，healthy 与 T145 保持。
- Unit：null/owner/mode/status/lease priority、RootContext、第一0/第二1、错误回滚；资源 bounded/cleanup/source checksum。

## 4. 交付边界

实现仅允许新增 retry source-lock Mapper、在 T145 helper 内调用及必要 JavaDoc/测试；不得修改 stage/terminal/SQL写/远端 RPC/状态方向。
