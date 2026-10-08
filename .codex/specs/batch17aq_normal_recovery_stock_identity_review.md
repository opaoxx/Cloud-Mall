# CM-20260924-20：普通订单恢复库存响应订单号身份校验评审

## 1. 评审结论与范围

结论：**GO，限定修复普通订单预扣恢复对库存响应缺少原订单号身份校验的问题。**

当前 `OrderReservationRecovery.reservationStatus` 只检查响应 code、data 和 status，没有核对 `StockReservationStatusVO.orderNo`。因此，错单响应可能让恢复任务对原意图执行错误的 `reserve`、`rollback` 或 `markReleased`；缺失订单号也可能被当作合法状态继续推进。

本轮只增加响应身份门，不修改 Stock API/Feign 字段、恢复排序、租约/claim、状态机、SQL、schema、错误码或其他服务。

## 2. 响应身份契约

在既有响应成功且 `data != null` 的检查之后、读取 `data.status` 之前，使用：

```java
Objects.equals(originalOrderNo, response.data.orderNo)
```

进行精确比较。比较必须区分大小写，保留尾随空格和其他字符差异，不 trim、不大小写折叠、不从请求路径或其他字段补全订单号。

以下情况全部视为身份校验失败：

- `orderNo == null` 或 blank；
- 大小写、重音、尾随空格不同；
- 返回其他订单号；
- 响应 data 类型/字段无法得到合法的原订单号。

失败抛出稳定的 `IllegalStateException`，沿现有 `recoverOne` 异常路径记录 `recordFailure` 并按既有一分钟退避重试；不得执行后续状态分支、reserve、rollback 或 `markReleased`。不新增 RPC、状态或错误码。

## 3. 调用顺序与状态边界

### 3.1 首次查询

订单未在本地订单月表提交时，第一次 `reservationStatus(originalOrderNo)` 必须经过订单号身份门。即使返回 status 为 `NOT_FOUND`、`INIT`、`REJECTED`、`ROLLED_BACK` 或其他值，身份不匹配都必须先失败，不能据此 reserve 原单、释放原意图或提前结束恢复。

### 3.2 合法原身份后的第二次查询

当首次原身份查询允许 `NOT_FOUND`/`INIT` 并按既有流程对**同一原订单号**执行 reserve 后，第二次状态查询仍必须重新执行同一身份门。第二次返回错单、缺失或空白订单号时：

- 不执行 rollback；
- 不执行 `markReleased`；
- 原 intent 保持非终态事实，记录错误和既有退避；
- 不生成新订单号、不重建快照、不猜测库存结果。

第二次返回原身份且 status 合法时，继续既有 `RESERVED/ALREADY_RESERVED` 回滚、`REJECTED/ROLLED_BACK` 释放及其他未知状态人工核对语义。

### 3.3 已提交订单路径

恢复任务在本地订单已存在时，继续使用现有 `forceCommitted` 路径，不增加库存查询；本轮只保护库存状态查询响应，不改变已提交订单的收敛规则。

## 4. Stock 端与兼容边界

- Stock 端既有所有 `reservation` 查询分支（包含 `NOT_FOUND`、正常流水、拒绝和回滚结果）继续返回请求 `orderNo`；本轮不修改 Stock 端实现或 Feign 方法签名。
- `StockReservationStatusVO.orderNo` 作为既有响应字段使用，不新增字段、不调整 JSON 命名和状态枚举。
- 保留原订单号、items snapshot、库存恢复租约、claim 时间和异常记录语义。
- 不校验 user、items、库存流水归属或 lease；这些属于其他批次的事实一致性问题。
- 不清洗历史错误台账，不保证网络响应在途期间可撤回，也不扩展到秒杀查询或其他库存接口。

## 5. 验收矩阵

1. 单测覆盖响应订单号为正确值、其他订单号、大小写差异、重音差异、尾随空格、null/blank 六类输入；所有错误输入均不调用 reserve、rollback 或 markReleased，并进入原 recordFailure/退避路径。
2. 首次查询身份正确但 status 为 `NOT_FOUND`/`INIT` 时，保留原 reserve 重试流程；首次身份错误时不 reserve。
3. 首次合法 reserve 后，第二次查询身份错误时不 rollback、不 markReleased，intent 的 id、orderNo、key、payload、created/status 等原事实保持，仅允许原错误与 nextRetry/updated 元数据变化。
4. 正确身份下 `RESERVED`/`ALREADY_RESERVED`、`REJECTED`、`ROLLED_BACK` 和既有未知状态继续原分支行为；匹配原订单号不改变恢复结果。
5. 真实隔离 MySQL 验证恢复意图原行、Stock 查询替身和错误记录，确认原业务库 checksum 不变、隔离库清理；回归已有订单 `forceCommitted`、恢复排序和 Stock API/Feign 契约。

## 6. 明确不承诺

本轮只阻止恢复任务根据错单或缺失订单号推进库存动作，不提供跨服务响应签名、库存流水全量归属校验或分布式 exactly-once 保证。
