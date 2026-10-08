# CM-20260924-20：普通订单创建补偿回滚结果证明评审

## 1. 评审结论与范围

结论：**GO，限定补充 `OrderServiceImpl.compensateFailedCreate` 在同步 rollback 后的库存终态证明。**

当前创建失败补偿收到 rollback code=0 后直接 `markReleased`，没有确认库存端已持久化为原订单的回滚终态。响应可能只代表请求受理，导致本地 intent 被错误释放。

本轮只保护同步创建补偿路径，不修改 rollback 方法签名、Feign/API、Mapper SQL/schema、事务、状态、时间、异步 BN/BP 恢复或历史数据。

## 2. 补偿证明契约

在现有 rollback envelope code=0 门之后，调用一次原订单号 `stock.reservation(originalOrderNo)`：

1. response 必须成功、data 非 null；
2. `Objects.equals(originalOrderNo, response.data.orderNo)` 必须精确相等，区分大小写、重音和尾随空格；
3. status 仅允许精确 `ROLLED_BACK` 或 `REJECTED`；
4. 只有上述条件全部满足时调用原 `reservationIntentService.markReleased(originalOrderNo)`。

身份缺失/错单、envelope 错误、查询异常、状态为 RESERVED/CONFIRMED/INIT/NOT_FOUND/UNKNOWN 或其他值，均抛原补偿失败语义（可使用既有 `BizException(INTERNAL,500)`），进入现有 compensate catch：

- 只调用一次 `recordFailure`；
- 将补偿异常添加到原 creationFailure 的 suppressed；
- 不再次 rollback、不 reserve、不重复 GET、不 markReleased。

## 3. 不变异常边界

- rollback null、非 0、Feign/熔断异常：保持现有失败路径，不新增即时 GET。
- `markReleased` 失败和 `recordFailure` 失败的既有 suppressed 关系保持；不能覆盖原创建异常。
- 原订单、幂等、outbox 写失败导致补偿调用的事务和返回错误码不变。
- 不引入新的中断策略；中断/数据库/恢复任务边界沿既有 AS/AR/BN/BP 保持。

## 4. 数据与兼容边界

- 使用原一参 rollback 和原订单号；不改变 Stock/Feign JSON 字段或 API。
- 不修复历史 intent、库存流水或 action 行，不新增 schema/状态/API/异步任务。
- 合法终态证明只影响是否 markReleased；原创建失败仍抛回 creationFailure。
- Stock/RPC 为替身时只验证本地补偿状态门，不宣称真实远程故障或资金事故。

## 5. 验收矩阵

1. 真实隔离 MySQL + Service/事务/REQUIRES_NEW intent：rollback code=0 后覆盖正确 `ROLLED_BACK/REJECTED`、坏 status、null/错单/alias orderNo、null data/envelope/error；错误路径不释放 intent，原订单/幂等/outbox 保持零行，RESERVED intent 仅记录 error/nextRetry/updated。
2. 合法终态路径只调用一次 rollback、一次 GET、一次 markReleased；不重复 reserve/rollback/GET，intent 独立事实为 RELEASED。
3. unit 覆盖 rollback 非 0/异常不即时 GET、post-proof 失败一次 recordFailure、原 creationFailure 与 compensation/recordFailure suppressed 关系及异常不遮蔽。
4. 回归普通订单创建精度、17AQ/AR/AS/BN/BP、Stock 身份与 action 门、原订单 checksum 和随机 fixture 清理；不声称真实网络或历史修复闭环。

## 6. 明确不承诺

本轮只证明同步创建补偿 rollback 后的持久终态，不提供远端 RPC 撤回、跨服务 exactly-once、历史库存修复或其他补偿路径结果证明。
