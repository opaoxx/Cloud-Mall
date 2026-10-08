# CM-20260924-20：普通订单恢复回滚结果证明评审

## 1. 评审结论与范围

结论：**GO，限定补充普通订单恢复 rollback 成功后的库存终态证明。**

当前 `OrderReservationRecovery` 在 RESERVED/ALREADY_RESERVED 状态下调用原订单号 rollback，收到 code=0 后直接 `markReleased`，没有再次读取持久库存事实。响应可能只代表请求受理，不能证明回滚已落库完成；未知或错单结果可能错误释放本地 intent。

本轮只补 rollback 后的只读 GET 证明，不修改 rollback 方法签名、SQL/schema、状态机、lease/claim、时间、中断、数据库隔离或 Stock/Feign 契约。

## 2. 回滚后证明契约

在 rollback envelope code=0 后：

1. 检查当前线程中断；若已中断，立即返回，不 GET、不 `markReleased`，保留原 lease/intent事实；
2. 使用原 `reservationStatus(intent.orderNo)` 读取持久库存状态，沿 17AQ 的精确 response `orderNo` 身份门；
3. 只有 status 精确为 `ROLLED_BACK` 或 `REJECTED` 时，才执行原 `markReleased`；
4. 其他 status（RESERVED、CONFIRMED、INIT、NOT_FOUND、UNKNOWN、null/非法）、错单/缺身份、envelope 错误或 GET 异常均沿原业务失败路径 `recordFailure` 和一分钟退避；
5. 失败后不追加 rollback、不 reserve、不重复 GET、不猜测库存事实。

`REJECTED` 作为不可逆的释放结果继续视为可完成；本轮不新增状态枚举或改变初次状态分支。

## 3. 不变分支

- 初次状态已为 `REJECTED` 或 `ROLLED_BACK`：保持原流程，不增加额外 RPC/GET。
- 本地订单已提交：继续原 `forceCommitted` 分支，不查询库存。
- rollback 响应 code 非 0、null 或 Feign/熔断异常：保持原异常路径，不新增即时 GET。
- 初次 status 为 RESERVED/ALREADY_RESERVED 时仍只调用一次原一参 rollback，使用原订单号；post-GET 只读证明，不改变动作 key/API。

## 4. 与 AQ/AR/AS 边界

- post-GET 必须复用 17AQ 身份校验，不 trim、不大小写折叠、不从请求补全订单号。
- `markReleased` 数据库异常沿 17AR 候选隔离；post-GET/rollback 后的中断沿 17AS 停止，不清 lease、不猜结果。
- 保留 17AR 的普通业务失败一次 `recordFailure`、17AS 的 cause/flag 规则及现有恢复排序/claim/时间。
- 不清洗历史 intent、不修改 Stock 服务、不宣称真实远程故障或历史数据修复。

## 5. 验收矩阵

1. 真实 MySQL Mapper+Recovery 验收覆盖 RESERVED/ALREADY_RESERVED 后 rollback code=0 的 post-GET 状态、身份和 envelope 矩阵；只有 ROLLED_BACK/REJECTED 可 `markReleased`，其他均只记录失败，不二次 rollback/reserve。
2. 真实随机隔离 MySQL 验证原 intent 的 id、orderNo、key、payload、created/status 保持；允许失败路径只更新既有 error/nextRetry/updated 元数据，正确 post-GET 后可 RELEASED。
3. unit 聚焦 post-GET 中断、包装中断、rollback 非 0/异常不即时 GET、初次 REJECTED/ROLLED_BACK、已提交订单调用次数；中断在 rollback 返回后阻止 GET/释放。
4. 回归 17AQ/AR/AS、Stock 身份/动作、checksum、fixture 清理和既有恢复矩阵；Stock/RPC 为替身，不宣称真实网络故障或资金事故验证。

## 6. 明确不承诺

本轮只证明 rollback 后的持久终态再释放本地 intent，不保证远端 RPC 可撤回、跨服务 exactly-once、历史数据修复或其他库存动作的结果证明。
