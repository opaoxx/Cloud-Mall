# CM-20261007-18：16I Legacy 失败与退款响应语义评审

## 1. 评审结论与范围

结论：**有限 GO；只统一 Legacy `mock-fail` 与 callback failure 对已持久支付状态的只读响应分类，消除 REFUND_PENDING/REFUNDED/FAILED 重放误调用。**

本轮不新增表、字段、flag、补偿任务、退款流程或 RPC；不修改 TCC 路由、16E cancel、16F/16G rollback projection。Legacy `PENDING`/`ORDER_SYNC_UNKNOWN` 的原订单取消保护继续保留。

## 2. 已确认缺口

- `mock-fail` 目前只特判 SUCCESS，REFUND_PENDING 会继续走 Legacy intent/Order.cancel，返回普通未知；REFUNDED 也可能进入取消路径并返回 409；FAILED 重放会再次调用 Order.cancel，随后 `markFailed=0` 返回 409。
- callback failure 已对 REFUNDED 返回 409，但 REFUND_PENDING 仍走普通未知路径；FAILED 可完成 callback receipt，但入口需先确认意图一致性，不能重复远程取消。

## 3. Legacy failure 分类契约

入口先读取当前 `pay_record` 和现有 `pay_debit_intent`，校验原 payNo/userId/orderNo/amount/debit_mode。未知或不一致 mode 继续 `PAY_DEBIT_MODE_UNAVAILABLE`/503；intent tuple、mode 与支付记录矛盾时同样 503，禁止猜测资金结果。

按支付状态分类：

| Pay 状态 | intent 条件 | 结果与副作用 |
| --- | --- | --- |
| `SUCCESS` | 任意 | 既有支付已处理 409；不 RPC、不写入 |
| `REFUNDED` | 任意 | 既有支付已处理 409；不 Order.cancel、不写入 |
| `REFUND_PENDING` | intent 缺失或完整 tuple 匹配 | `PAY_REFUND_PENDING`/202；不 RPC、不写入、不重复退款 |
| `REFUND_PENDING` | intent 存在但 mode/tuple 矛盾 | 503；不 RPC、不写入、不重复退款 |
| `FAILED` | intent 缺失，或存在且完整匹配、`status=FAILED` | 幂等 HTTP 200/FAILED；不 Order.cancel、不 `markFailed` |
| `FAILED` | intent 存在但非 FAILED、未知状态或载荷矛盾 | 202/503（按未知或模式/载荷冲突映射）；不 RPC、不覆盖 FAILED |
| `PENDING` | intent 缺失或完整匹配且 `status=FAILED` | 保持既有 Legacy 失败路径：按现有 `@Transactional` wrapper 调用 Order.cancel 并 CAS FAILED；明确完成返回原 200 |
| `PENDING` | intent 非 FAILED、未知或载荷矛盾 | 202/503；不猜测扣款结果，不覆盖状态 |
| `ORDER_SYNC_UNKNOWN` | intent 缺失或非 FAILED | 保守 `PAY_ORDER_SYNC_UNKNOWN`/202；本轮不扩展未知状态的取消路径 |
| `ORDER_SYNC_UNKNOWN` | intent 完整匹配且 `status=FAILED` | 沿原 Order.cancel→markFailed 路径；SQL 原有条件支持 PENDING/UNKNOWN，写 FAILED 后完成返回 200，响应未知保留既有异常传播 |

`FAILED` 且 intent 缺失允许历史终态幂等返回，因为已持久 Pay 状态本身没有待恢复扣款意图；但不能由该规则推导 PENDING 的余额安全。intent 存在时必须先核对完整 tuple 和 Legacy mode，不能只看 status 字符串。

## 4. callback failure 规则

继续 16D 的 callback receipt 预绑定：先独立事务绑定 `RECEIVED_FAILURE`，再按同一 Legacy 分类读取真实 Pay/intent 状态。

- SUCCESS/REFUNDED：返回既有 409，预绑定记录保持未处理，不调用 complete。
- REFUND_PENDING：返回 `PAY_REFUND_PENDING`/202，保持 `RECEIVED_FAILURE`/`processed_at=NULL`，不 RPC、不写状态、不 complete。
- FAILED 且 intent 缺失或完整匹配 FAILED：不远程取消；调用 complete 将回调日志推进 `FAILED`，返回 HTTP 200。
- PENDING 且 intent 缺失或完整匹配 FAILED 时沿原 Legacy Order.cancel 路径，保留现有 `@Transactional` wrapper；只有 Pay 失败 CAS 明确完成后才 complete。ORDER_SYNC_UNKNOWN 仅在 intent 完整匹配 FAILED 时复用同一路径；写 FAILED 后才 complete，intent 缺失/非 FAILED 保持 RECEIVED_FAILURE/NULL 与 202。
- `notifyFailure` 的既有异常契约保持不变：原 BizException 继续原样传播，其他 RPC/运行时异常继续映射现有 500；本轮不把异常统一改成 202，也不新增取消未知台账。
- intent mode/tuple 矛盾或状态未知：503/202 按既有冲突/未知语义，禁止 complete 或猜测 FAILED。

callbackId 冲突、金额/用户错误和鉴权继续沿既有 409/403 规则；本轮不改变 callback log 模型。

## 5. 禁止扩大范围

- 不为 REFUND_PENDING 新建退款重试器，不重复调用 User refund，不生成新 operationKey；PENDING 基本 mock-fail 的既有 Order.cancel 路径保持不变。
- 不把 REFUNDED/FAILED 重放送入 Order.cancel；不把已 FAILED 的 `markFailed=0` 当作并发错误。
- 不将缺失 intent 的 ORDER_SYNC_UNKNOWN 猜成无扣款并扩展取消，或禁掉 PENDING 缺失 intent 的既有 Order.cancel 基本路径。
- 不修改 TCC、16E cancel、16F/16G rollback、Order/Stock/User 数据模型。

## 6. 最小验收矩阵

1. Legacy mock-fail：SUCCESS/REFUNDED=409，REFUND_PENDING=`PAY_REFUND_PENDING`/202，FAILED=200 且零 RPC/零写入；PENDING+intent 缺失/FAILED 沿原 Order.cancel+markFailed 路径，完成返回 200。
2. Legacy callback failure：同上；REFUND_PENDING 不 complete，FAILED 仅在 intent 缺失或匹配 FAILED 时 complete；PENDING/ORDER_SYNC_UNKNOWN+允许的 intent 只有原取消 CAS 完成才 complete。
3. PENDING+intent 缺失/FAILED 沿原 Order.cancel 路径；ORDER_SYNC_UNKNOWN 仅完整 FAILED intent 沿原路径，缺失/非 FAILED 保守 202；notifyFailure 原有 500/BizException 传播保持不变。
4. mode/tuple 不一致或未知 intent 状态：不调用远程取消或 complete，按 503/202 保守返回；intent 缺失仅在 PENDING 基本失败路径允许沿既有 Order.cancel，其他状态按各自分类处理。
5. 重复 mock-fail/callback 不生成新退款键、不重复 Order.cancel（已 FAILED/REFUNDED/REFUND_PENDING 分支）。
6. TCC 支付和 TCC failure/rollback/cancel 回归保持不变。

## 7. 明确 NO-GO

- 通过 status 字符串直接跳过 intent tuple 校验并返回 FAILED 200。
- 对 REFUND_PENDING/REFUNDED/FAILED 重放调用 Order.cancel 或 User refund。
- 将 PENDING 缺失 intent 猜成无扣款并直接 FAILED。
- 为本轮新增 Legacy 退款补偿表、scheduler、flag 或跨服务契约。
