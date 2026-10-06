# CM-20261007-18：16P 已 claim READY 的 COMMITTED TCC 取消评审

## 1. 评审结论

结论：**有限 GO；允许扩展 16O 到已 claim 但仍停留在 READY、且 worker lease 已释放或明确过期的窗口。**

安全前提已由现有代码核验：所有正常 TCC paid 路径都必须先通过 `PayTccDeliveryStore.stage(..., ORDER_SYNC_UNKNOWN)`，该事务成功后才调用 `OrderClient.paid`。READY 本身没有取得 paid 写许可；旧 worker 若在 verifyMoney/读订单阶段暂停，取消 CAS 先获 Pay→delivery 锁后，旧 worker 的 READY→ORDER_SYNC_UNKNOWN CAS 必须失败，因此不会再调用 paid。Legacy PayConsistencyService 不使用 delivery READY 台账，不属于本窗口。

本轮仍复用 `cloudmall.pay.tcc-cancel.enabled` 和 `CANCEL_REQUESTED`，不新增表、字段、flag、XID、branch、Try、退款键或公网 endpoint；不撤销有效 lease，不改 attempts/旧 owner/旧 lease 时间。接受改向时只允许把 `next_retry_at` 提前为 `LEAST(原值, now)`，避免旧退避延迟主动取消恢复。

## 2. 新增允许窗口

在继承 16O 原 execution `COMMITTED/COMMIT`、Pay `TCC + PENDING/ORDER_SYNC_UNKNOWN`、完整 tuple/receipt/Fence、无 16E cancel 和 Legacy intent gate 的基础上，delivery 允许以下状态改向：

1. delivery 不存在；
2. delivery 为 READY、`attempts` 非空且 `attempts>=0`，且 `lease_owner IS NULL AND lease_until IS NULL`；
3. delivery 为 READY 且 owner 与 lease_until 同时存在、owner 去空白后非空、`lease_until <= now`，表示 lease 已明确过期。

以下情况必须拒绝改向并保留原 READY/交付事实：

- READY 的 owner/lease 只有一侧非空；
- READY 的 owner 与未过期 `lease_until > now` 同时存在；
- READY 的 lease_until 为空但 owner 非空，或 owner 为空但 lease_until 非空；
- delivery 已为 ORDER_SYNC_UNKNOWN、REFUND_PENDING、SUCCESS、REFUNDED 或其他状态；
- Pay/execution/receipt/Fence/16E cancel/Legacy intent tuple 不一致。

取消 CAS 不修改 `attempts`、`lease_owner`、`lease_until` 或 immutable tuple；只将 `next_retry_at` 更新为 `LEAST(next_retry_at, now)`。过期 owner 的 token 不被撤销；其迟到 worker 只能在后续 stage CAS 中失败。有效 lease 不得被取消请求接管。已有 CANCEL_REQUESTED 的幂等重放完全不修改任何 metadata。

## 3. 锁、CAS 与旧 worker 竞争

request、claim、ensure、stage 统一使用 `pay_record FOR UPDATE → pay_tcc_delivery FOR UPDATE`。新增 READY 取消方法在持有这两行锁后，以同一 `now` 执行状态 CAS：

```text
state = READY
AND (
  (lease_owner IS NULL AND lease_until IS NULL)
  OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL AND lease_until <= now)
)
```

`attempts` 必须非空且不小于 0；`attempts=0` 的无 lease READY 可由 16O/16P 一起接受，已 claim 的 READY 必须保留 attempts 事实。部分 lease、空白 owner 记录不满足 CAS，不能靠清空字段修复。Java 条件检查、CAS 和 `LEAST` 更新必须使用同一次按 DATETIME(3) 精度取样的 `now`。

旧 worker 在取消前已经成功完成 READY→ORDER_SYNC_UNKNOWN stage，则取消请求看到新状态并拒绝；取消先成功则旧 worker 的 `stage(READY, ORDER_SYNC_UNKNOWN)` 因当前 state/lease 条件失败，不能更新 PayRecord、不能 paid。两者至多一方先提交，不调用相反远程写操作。

## 4. worker 状态护栏

- `CANCEL_REQUESTED` claim 后必须重读 state；只进入 cancel 分支，不进入 paid。
- `CANCEL_REQUESTED` 遇 `PENDING_PAYMENT`，先以同态 stage 检查当前 lease，再调用 Order.cancel；旧 READY worker 不得复用该 token 执行 paid。
- Order/资金未知或 `CANCELLED` 但 stock 未 `SUCCEEDED` 时保持 CANCEL_REQUESTED 并退避。
- `CANCELLED + stock SUCCEEDED` 才进入原 `REFUND_PENDING` 和固定 `refund:<payNo>` 退款；退款未知保留 REFUND_PENDING，最终 APPLIED 后进入 REFUNDED。
- 旧 worker 已发出的只读 RPC 无法撤回；其后续本地 stage/finish 只能按 lease/state CAS 失败，不得改变 Pay 或 delivery 方向。

## 5. API 边界

继续复用 mock-fail/callback failure，无新增 endpoint。取消接受返回 `PAY_ORDER_SYNC_UNKNOWN`/202，不写 Pay FAILED，不调用 TC/Order/User。callback failure 保持 RECEIVED_FAILURE。cancel flag 关闭只禁止新请求，已持久化 CANCEL_REQUESTED 仍由 delivery recovery 清算。

## 6. 验收矩阵

1. READY claim 后暂停 verifyMoney，正常 finish 释放 lease；取消请求 CAS 成功并将 next_retry_at 最多提前到同一 now，旧 worker 放行后 READY stage 失败且不 paid，新 worker 进入 cancel/退款。
2. READY claim 后 owner/lease 明确过期；取消请求接受且保留 attempts/旧 owner/旧 expiry，仅按 LEAST 提前 next_retry_at，旧 worker迟到 stage 不改变 Pay。
3. READY 无 lease、attempts=0/大于0 均可按窗口接受；部分 lease、空白 owner、有效 lease、null/负 attempts 均拒绝。
4. 取消先 CAS 时旧 worker 后选 ORDER_SYNC_UNKNOWN 必须失败；旧 worker 先 stage 时取消必须拒绝，正常 paid 路径仍可继续。
5. 10 次以上 request/claim 同步竞争无死锁；至多一方赢得方向，另一方不改 attempts/lease/Pay。
6. CANCEL_REQUESTED 新 worker 只调用 cancel，不调用 paid；库存完成后唯一 refund，重复恢复不重复扣款/退款。
7. 16O 无 delivery、16L 过期 lease、16C 正常 delivery、16E 无 execution cancel、16F/G rollback、16K abort 和 Legacy 回归保持通过。

## 7. 实施门与范围

实现前冻结上述时间条件、锁顺序和竞争测试；提交前必须通过真实 MySQL 并发、旧 worker 迟到、有效/过期/部分 lease、Order/库存未知和唯一退款验收。16P 仍不支持已进入 ORDER_SYNC_UNKNOWN、REFUND_PENDING、SUCCESS、REFUNDED 或有效 lease 的 COMMITTED 主动取消。
