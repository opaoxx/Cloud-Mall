# CM-18 16T158：Pay Legacy recovery payment phase

## 1. 结论与范围

GO。findDue/findDueAfter/claim 只核两表 LEGACY mode，PAY 已 SUCCESS/FAILED/REFUNDED/未知时仍可占候选窗口或领取 intent，随后才被 service 门阻断。

本批只收紧候选/claim 的 PayRecord payment phase eligibility。

## 2. Three SQL guards

在现有三方法 payment LEGACY mode 条件旁追加 `payment.status IN ('PENDING','ORDER_SYNC_UNKNOWN','REFUND_PENDING')`：

- `findDue` / `findDueAfter`：先由 WHERE 过滤 payment status，再执行原 ORDER/LIMIT/cursor，时间、参数、排序保持；
- `claim`：EXISTS 子查询同时核 payment mode 与同一 active status，保持原原子领取条件；
- status 列沿既有 CI 语义，不新增 trim/casefold/终态解释。

不改 intent status、lease、nextRetry、SET、cursor、hasLease、已领取后 drift 或完整 tuple/fencing；不修数据、不发远端读写、不自动解释终态 Pay。

## 3. 保持项与验收

PAY active phase（PENDING/ORDER_SYNC_UNKNOWN/REFUND_PENDING）与 Legacy intent 健康领取；PAY inactive/unknown/blank 及 inactive 的 CI/尾空格等价值不候选、不 claim；active PAY 的既有 CI/尾空格等价值仍按原列语义可候选/领取，原 intent/payment facts 不变。

- Native direct claim：inactive status/alias/unknown 0，active phases 1；service 两表合法但 payment inactive 不发 User/Order RPC；
- Scan：100 inactive 头 + healthy 尾，findDue/findDueAfter filter 后 LIMIT/cursor/time ordering 保持，候选只读不改 facts；
- 保留 T79–T157、资源 checksum/cleanup，不启动 App/RM/TC。

## 4. 交付边界

实现仅允许修改 `findDue`、`findDueAfter`、`claim` 三处 payment status WHERE/EXISTS 条件与 JavaDoc/测试；不得扩展其它 Mapper、Service/API 或状态机。
