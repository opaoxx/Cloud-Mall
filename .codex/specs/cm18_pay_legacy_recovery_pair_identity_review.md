# CM-18 16T159：Pay Legacy recovery pair identity

## 1. 结论与范围

GO。findDue/findDueAfter/claim 当前 JOIN/EXISTS 只核 pay_no CI 与 mode/status；两表 user/order/amount 漂移仍可占候选或领取 lease，之后才由 route 409。

本批只在三条 recovery SQL 增加当前两表基本 pair identity/amount 条件。

## 2. Three SQL boundaries

`findDue` 与 `findDueAfter` JOIN 条件改为：

- `BINARY payment.pay_no = BINARY intent.pay_no`；
- `payment.user_id = intent.user_id`、`BINARY payment.order_no = BINARY intent.order_no`、`payment.amount = intent.amount`；
- 保留现有双 LEGACY、payment active status、intent status、due/lease、排序/limit/cursor。

`claim`：

- outer requested key 改为 `BINARY pay_debit_intent.pay_no = BINARY ?`；
- EXISTS 同样核 BINARY pay_no、payment/intention userId、BINARY orderNo、amount、双 LEGACY 与 payment active status；
- 保留 intent status、nextRetry、lease、SET、参数 phase CI 语义。

不新增两表“全字段”或 pending snapshot proof；SQL NULL 比较结果为 UNKNOWN 或两表不等时不匹配；双方相同的非法域值不属于本门验证。coherent 双方同时变更不在本门保证。

## 3. 保持项与验收

不改 Service、旧写、TCC/API/字段/claim lease、cursor/排序/LIMIT；不宣称 tuple 永恒不变或完整 claim fencing。

- Native direct claim：payment/intent user/order/CI order/amount/pay key mismatch → 0/full facts；requested payNo alias → 0。
- Service pre-route 后任一边变化导致 pair 不一致 → claim0、不改 lease/attempt、不 RPC，原 route readback 409；100 bad pair head 先过滤再 LIMIT，healthy coherent rows 可领取。
- 健康 phase CI、资源/只读/清理保持；不启动 App/RM/TC。

## 4. 交付边界

实现仅允许修改 `findDue`、`findDueAfter`、`claim` 三条 SQL 的 JOIN/WHERE/EXISTS pair 条件及 JavaDoc/测试；不得新增 Mapper/Service/API 或修改状态机。
