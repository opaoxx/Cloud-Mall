# CM-20261007-18：16Q 有效 lease READY 的 COMMITTED TCC 取消评审

## 1. 评审结论

结论：**有限 GO；允许扩展 16P 到 READY 且仍持有有效 delivery lease 的窗口，但不接管、不清除、不缩短原 worker lease。**

安全前提已核验：所有正常 TCC paid 路径均先在 Pay→delivery 锁内完成 READY→ORDER_SYNC_UNKNOWN stage，再调用 Order.paid；READY 没有取得 paid 写许可。有效 lease 只代表原 worker 当前拥有调度尝试，不代表 paid 已授权。Legacy PayConsistencyService 不使用 delivery READY 台账。

本轮复用 `cloudmall.pay.tcc-cancel.enabled` 和 `CANCEL_REQUESTED`，不新增表、字段、flag、XID、branch、Try、退款键或公网 endpoint。

## 2. 允许窗口和 lease 形状

在 16O/16P 的 COMMITTED/COMMIT、Pay TCC `PENDING/ORDER_SYNC_UNKNOWN`、完整 receipt/Fence/tuple、无 16E cancel 与 Legacy intent gate 基础上，READY delivery 可在以下形状改向：

- `attempts` 非空且 `attempts>=0`；
- `lease_owner IS NULL AND lease_until IS NULL`；或
- `lease_owner` 去空白后非空且 `lease_until IS NOT NULL`，无论 lease 有效还是已过期。

取消 CAS 只改变 `state READY→CANCEL_REQUESTED`，并把 `next_retry_at` 更新为 `LEAST(next_retry_at, now)`；必须保留 attempts、owner、lease_until、immutable tuple 和固定 refund key。不得重领、清空、延长、缩短或提前释放别人的有效 lease。重复 CANCEL_REQUESTED 请求完全不改变 metadata。

部分 lease（只有一侧存在）、空白 owner、null/负 attempts、ORDER_SYNC_UNKNOWN、REFUND_PENDING、SUCCESS、REFUNDED 或其他状态均拒绝改向。

## 3. 竞争和 worker 行为

request、claim、ensure、stage 共用 `pay_record FOR UPDATE → pay_tcc_delivery FOR UPDATE`。两者竞争规则：

1. 旧 worker 先完成 READY→ORDER_SYNC_UNKNOWN stage：取消请求看到新状态并拒绝；原 paid 流程继续按既有 delivery 语义。
2. 取消请求先 CAS READY→CANCEL_REQUESTED：旧 worker 后续 READY→ORDER_SYNC_UNKNOWN stage 因当前状态不符失败，不更新 PayRecord，也不调用 Order.paid。
3. 取消请求不改变有效 lease；旧 worker 可继续执行只读 RPC，并可用自己的 token 调 `finish` 释放 lease/写原退避。新 worker 必须等 lease 释放或过期后才能 claim CANCEL_REQUESTED。
4. 旧 worker 若已经读取到 `CANCELLED + stock SUCCEEDED`，其同向 `REFUND_PENDING` stage 可继续；若读取到 `PENDING_PAYMENT`，只能在有效 lease stage 同态后调用 cancel，不能 paid。

旧 worker 已发出的 RPC 不承诺撤回；迟到的本地 stage 仍受当前 state/lease CAS 约束。有效 lease 过期前不允许新 worker 重叠执行取消写操作。

## 4. 相反订单状态护栏

正常路径不允许 READY 在 Order.paid 之后存活：paid 前必须先提交 ORDER_SYNC_UNKNOWN，因此有效 lease READY 与 Order PAID/COMPLETED 属于外部或历史矛盾。即使出现该矛盾，取消 CAS 也不得将状态写成 SUCCESS 或退款；CANCEL_REQUESTED worker 读取 PAID/COMPLETED 或 stock CONFLICT 时保留取消记录并退避/告警，不调用 paid、不调用 refund、不写 FAILED，等待人工或事实核验。

`CANCELLED + stock SUCCEEDED` 是取消方向唯一允许进入 REFUND_PENDING 的条件；库存 pending/unknown/conflict 或订单/资金未知保持 CANCEL_REQUESTED。退款仍使用固定 `refund:<payNo>`，最终沿原 REFUNDED 链。

## 5. API 边界

继续复用 mock-fail/callback failure，无新增 endpoint。接受请求返回 `PAY_ORDER_SYNC_UNKNOWN`/202，不调用 TC、Order、User，不写 Pay FAILED。cancel flag 关闭只禁止新请求，已持久化 CANCEL_REQUESTED 仍由 delivery recovery 清算。callback failure 保持 RECEIVED_FAILURE。

## 6. 验收矩阵

1. 暂停活跃旧 worker 在 READY→ORDER_SYNC_UNKNOWN 前，取消接受并保留有效 owner/until/attempts；并行 claim 不能抢 lease。
2. 放行旧 worker 后，其 READY stage 失败且不调用 paid；旧 worker 可 finish，释放自己的 lease；新 worker 随后唯一执行 cancel/退款。
3. 旧 worker 先 stage：取消请求拒绝，状态为 ORDER_SYNC_UNKNOWN，原 paid 链可继续。
4. 无 lease、过期 lease、有效 lease、部分 lease、空白 owner、null/负 attempts 均按冻结条件验收；LEAST 提前 next_retry_at，重复 CANCEL 不改 metadata。
5. 旧 worker 读取 CANCELLED+SUCCEEDED 后可同向 REFUND_PENDING；读取 PAID/COMPLETED 或 stock CONFLICT 不退款、不 paid、不 FAILED。
6. request/claim/stage/finish 多实例竞争无死锁；有效 lease 不被新 worker 接管，释放/过期后只产生一次退款。
7. 16O/16P、16L lease、16C delivery、16E 无 execution cancel、16F/G rollback、16K abort 和 Legacy 回归保持通过。

## 7. 实施门与范围

实现前冻结上述 lease 形状、锁序和 READY paid 前置不变量；提交前必须通过真实 MySQL 竞争、旧 worker 迟到、有效 lease 保留、finish 后接管、相反订单事实和唯一退款验收。16Q 不处理 ORDER_SYNC_UNKNOWN、REFUND_PENDING、SUCCESS、REFUNDED 或其他已取得交付写许可的 COMMITTED 取消场景。
