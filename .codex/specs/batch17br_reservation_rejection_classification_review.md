# CM-20260924-20：普通预扣拒绝结果分类评审

## 1. 评审结论与范围

结论：**GO，限定修复普通订单首次 Stock reserve 的持久拒绝分类。**

当前 Feign 409 且 remoteCode 属于旧 STOCK/CONFLICT/DUPLICATE/key-reuse 集合时会直接释放 intent；所有非 0 envelope 也被当作确定拒绝。Stock 端已定义 `ORDER_STOCK_RESERVATION_REJECTED`（409）表示库存请求已持久化为 REJECTED，只有该证据足以安全结束本地 intent；版本冲突、幂等冲突或未知错误仍可能保留 INIT 并由恢复任务继续确认。

本轮只调整首次 reserve 的拒绝/未知分类，不修改 Stock/Feign/API 字段、错误码、SQL/schema、事务、异步恢复、成功 code=0 语义或其他业务逻辑。

## 2. 确定拒绝契约

仅当以下条件成立时沿原确定拒绝路径：

- Feign 异常 HTTP status 为 409，且 `remoteCode(exception)` 精确为 `ORDER_STOCK_RESERVATION_REJECTED`；或
- reserve 返回已成功解码的响应 envelope，且 `reserved.code` 精确为 `ORDER_STOCK_RESERVATION_REJECTED`。

确定拒绝路径保持原行为：

- `definiteRejection=true`；
- 以原订单号调用一次 `reservationIntentService.markReleased(orderNo)`；
- 对外抛既有 `ErrorCodes.STOCK`/409。

remoteCode 只能读取现有 Feign body 的 JSON code；解析失败、空 code、大小写/空白/别名 code 均不视为确定拒绝。

## 3. 未知/可恢复分类

以下全部沿原未知结果路径，不释放 intent：

- 非 409 Feign 异常；
- 409 但 code 为旧 STOCK、CONFLICT、DUPLICATE、`ORDER_RESERVATION_KEY_REUSED` 或其他非专用码；
- 非 0 envelope 的其他 code，包括 500、null、bad code 和别名/空白 code；
- reserve 返回 null；
- 远端异常、熔断、连接/解析失败。

未知路径保持：

- 对外抛 `ORDER_RESERVATION_UNKNOWN`/202 或原异常映射；
- 调用一次 `markReserveUnknown(orderNo, ...)`，保留 INIT/RESERVE_UNKNOWN 供恢复任务确认；
- 远端异常作为 suppressed 附加到未知异常，不能遮蔽原 cause；
- 不额外 GET、不 rollback、不 reserve、不 markReleased、不调用 markReserved。

版本 CAS conflict 和幂等冲突可以保留 INIT，由恢复任务沿原订单号复核，不得直接丢弃 intent。

## 4. 保持与排除边界

- `reserve` 成功 code=0 的后续 markReserved/订单创建流程不变。
- 保留原一参 reserve、次数、事务、购物车/订单/outbox 顺序和补偿流程；不引入额外 SQL、RPC 或重试。
- 不新增错误码、状态、字段、schema、Feign 方法或 API；不修改历史 intent。
- 不扩展到秒杀、confirm/rollback、其他服务错误分类或支付流程。

## 5. 验收矩阵

1. 真实隔离 MySQL + Service/REQUIRES_NEW intent + Stock 替身覆盖专用 409/专用 envelope code：只此分类返回 409、释放 intent；订单、明细、幂等、outbox 保持零行。
2. 覆盖旧 STOCK、CONFLICT、DUPLICATE、key reuse、500、null/bad JSON、空/大小写/别名 code、非 409 持久拒绝码：均 UNKNOWN/202，intent 保留非终态错误与重试时间，不 markReleased。
3. unit 验证远端异常 cause suppressed、只调用一次原 reserve、无后续 GET/rollback/第二次 reserve/cart/markReserved；`markReserveUnknown`/`markReleased` 调用边界准确。
4. 回归普通创建精度、17AQ/AR/BN/BQ、Stock API/Feign 契约、原订单 checksum 和随机 fixture 清理；不宣称真实 Stock 网络故障或资金事故验证。

## 6. 明确不承诺

本轮只区分 Stock 持久拒绝与未知 reserve 结果，不修历史 intent、不改变恢复器最终仲裁、不提供跨服务 exactly-once 或真实资金事故闭环。
