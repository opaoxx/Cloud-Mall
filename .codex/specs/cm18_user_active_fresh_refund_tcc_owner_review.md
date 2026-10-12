# CM-18 16T98：User active fresh refund TCC owner

## 1. 结论与范围

GO。active fresh refund 当前只证明 DEBIT source；若实际 attempt 为 TCC，freeze 缺失、TRYING/CANCELLED、错 branch 或坏 snapshot 仍可能 credit/insert。disabled 路径已有 T74 confirmed TCC proof。

本批只补 active 用户 fresh refund 对已观察 TCC owner 的本地 proof，不扩 Legacy 全 tuple、全历史或 source fencing。

## 2. 调用位置与兼容

在 T70/T97 原 DEBIT proof 与 bounded same-pay DEBIT scope 通过后、T75 refund aggregate 和 wallet/credit 前，仅 active（`user.status == 1`）读取一次 `balanceDebitAttemptMapper.selectByPayNo(payNo)`：

- attempt null 或 mode `LEGACY`：保历史 active Legacy 兼容，不作完整 TCC 因果证明；继续 T75/wallet 原链；
- mode null/未知或非 LEGACY 且非 TCC：沿原 `BALANCE_REFUND_INVALID`/409；
- mode TCC：必须复用 T74 `confirmedTccRefund` 全部 attempt tuple 与本地 freeze proof，否则同一 409、无 credit/insert/cache。

可将 `confirmedTccRefund` 拆为预读 attempt 的纯 overload：disabled 既有调用仍只读 attempt 一次并保 404；active fresh 复用预读 attempt，TCC 再读取 freeze 一次。不得对 active existing replay 增加查询。

## 3. Proof 与保持项

TCC proof 继续精确核 CONFIRMED、xid/branch、user/pay/order、operation/ledger key、attempt amount 与 original DEBIT，及 freeze CONFIRMED、快照/delta/金额复制；DAO 异常原样传播。Legacy 与无 attempt 不被误判为坏 TCC。

不改 T75 aggregate、T72 wallet/ledger atomicity、T73 replay、T74 disabled 404、SQL/API/DTO/锁/状态/TCC callback/Redis；fresh active TCC 仅增加 attempt（及 TCC freeze）读，不写历史或自动修复。

## 4. 验收

- Unit：active fresh TCC attempt 缺失/未知 state/错误 branch/xid/amount/freeze 缺失/TRYING/CANCELLED/坏 snapshot；source proof、查询计数、无 credit/insert 断言。
- Native：active TCC fresh 坏 attempt/freeze HTTP/service 均 409 且四表 facts 不变；healthy active Legacy、无 attempt 历史、active TCC、disabled fresh/replay 保持；TCC freeze 仅 owned 实际读。
- existing replay 不新增 attempt/scope query；active Legacy 不被新 TCC 门拦截；资源 finally 清理，不启动 RM/TC/App。
- 保留 T70–T97 回归，不宣称完整 TCC/全历史 source fencing。

## 5. 交付边界

实现仅允许调整 active fresh refund 的 attempt 分支、抽取 confirmed TCC 纯 helper 与必要 JavaDoc/测试；不得修改 existing replay、disabled 404、T75/T97 或资金写路径。
