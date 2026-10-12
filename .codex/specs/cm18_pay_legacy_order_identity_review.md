# CM-18 16T85：Pay Legacy Order identity

## 1. 结论与范围

GO。`readOrderState` 当前只核 userId 与 amount；同用户同金额但 orderNo 缺失、别单、大小写/尾空格别名的响应可能被用于支付、退款、失败或扣款决策。

本批只补远端 Order 读响应的精确 orderNo identity 门，不改变 Order API、请求 header 或业务状态机。

## 2. 最小 proof

在既有 response envelope 成功、非 null body、userId 精确及 amount 数值匹配条件中追加：

`Objects.equals(order.orderNo(), intent.orderNo())`。

null、blank、别单、case/重音/尾空格别名均冲突；不 trim、casefold 或猜测归属。失败沿现有 `ErrorCodes.CONFLICT`/409，固定消息可统一为“订单身份、归属或金额与支付意图不一致”；不把坏订单当查询缺失，不新增 header 或身份协议。

## 3. 所有调用与失败边界

该 helper 在现有所有 `readOrderState` 调用生效：未扣款取消→FAILED、未扣款 pending→debit、REJECTED→FAILED、APPLIED 取消→refund、APPLIED paid→SUCCESS、已有 refund marker→REFUNDED，以及 Order.paid 后的二次 GET。

首次坏 GET 不发 User debit/Order paid/refund，也不 terminal；保持本次 claim、owned retry 与原 catch 语义，不宣称两表零写。二次 GET 坏响应时，已发出的 Order.paid、前置 stage 和 lease 事实不回滚，且不得继续 refund/terminal。

## 4. 保持项与非目标

不改 OrderClient API/Feign DTO、金额 JSON、SQL、claim/lease、T79–T84、TCC、User/Stock/MQ、RPC 请求顺序或 terminal 本地事务。只核当前 Order view 的 orderNo/userId/amount，不证明 SKU、全订单快照、查询后 source drift 或跨服务 fencing。

## 5. 验收

- Unit/native：上述 7 条路径覆盖 other/null/blank/case/alias orderNo，断言身份冲突；首次坏读无远端写 RPC，二次坏读保留已发生 paid/stage 但不继续终态。
- 健康：原 orderNo、同金额尾零、三订单状态与 post-paid 稳定读保持原协议。
- Owned Pay/真实两个 Mapper：核 Pay 两表及原 Pay 全表 checksum、lease/intent 与资源清理；User/Order 仅使用受控 Feign 响应替身，不访问 OrderDB，不宣称真实 Order 端身份修复或完整跨服务链路。测试不启动完整 App/RM/TC。
- 保留 T79–T84 与 Legacy/TCC 回归；失败测试不得宣称闭环。

## 6. 交付边界

实现仅允许修改 `readOrderState` 的 orderNo 条件、必要 JavaDoc/固定冲突消息与对应测试/README；不得扩展其它源码路径。
