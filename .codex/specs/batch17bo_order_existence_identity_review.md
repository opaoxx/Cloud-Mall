# CM-20260924-20：普通订单存在性查询身份评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderSqlMapper.orderExists` 按 collation 误判别名订单已提交的问题。**

当前 `orderExists` 使用 `COUNT(*)`，CI/AI/PAD SPACE 命中大小写、重音或尾随空格别名后会返回 true，`OrderReservationRecovery` 可能错误 `forceCommitted`，跳过原有 Stock 查询/补偿流程。简单改成 BINARY 过滤会把不可信候选伪装成缺行，反而进入 Stock rollback/补偿路径。

本轮只处理该 Mapper 的存在性身份三态，不修改 schema/collation、其他 id/find SQL、Service/API、状态、lease、Stock/Feign 或历史数据。

## 2. 三态 orderExists 契约

保持 `orderExists(String tableName, String orderNo)` 方法签名和单次索引候选 SQL：WHERE 仍使用原 `order_no=?`，投影原始 `order_no`，不使用 BINARY 过滤丢失别名证据。

结果定义：

1. **无候选行**：返回 `false`，恢复任务继续 17AQ/17BN 的 Stock 状态确认和补偿流程；
2. **恰好一个候选且存储 order_no 为 String、与请求 orderNo `Objects.equals` 精确相等**：返回 `true`，恢复任务可沿原 `forceCommitted`；
3. **候选存在但 orderNo 为 null、非 String、大小写/重音/尾空格不精确，或候选行数不为 1**：抛 `IllegalStateException`，不得返回 false。

精确比较区分大小写、重音和尾随空格，不 trim、不归一化、不从请求重建存储值。多行候选是不可可信数据，必须拒绝，不能任选首行。

## 3. Recovery 集成边界

- exact true：继续原 `forceCommitted`，不 Stock 查询、不释放库存。
- genuine false：继续原 `reservationStatus`、reserve、rollback/post-proof、`markReleased` 流程。
- alias/null/非 String/多行异常：沿现有 `recoverOne` 业务异常路径 `recordFailure` 与一分钟退避，不调用 Stock、不 `forceCommitted`，不猜测订单是否已提交。
- SQL/连接/表错误继续 DataAccessException 原传播，并由 17AR 候选隔离；不伪装为 false。
- 保留 17AQ 身份门、17BN rollback proof、17AS 中断、AZ/BA/BB 等其他边界。

## 4. 兼容与排除边界

- 不新增 SQL 往返、参数、表字段、schema、collation 迁移、状态或 API。
- 不改变其他 `findOrder`/id 查询、列表查询、订单读取身份（17BF 另行负责）。
- 不修改 Stock/Pay/Feign、lease、attempts、恢复排序或历史订单/action 行。
- 不把 alias/multi-row 视为真实缺行，不宣称历史数据修复或真实资金事故验证。

## 5. 验收矩阵

1. 真实随机 MySQL fixture clone 订单模板和 intent：精确 orderNo 返回 true；真正缺行返回 false；存储大小写、重音、尾空格 alias 候选返回异常；source COUNT 仍可证明候选命中但不被错误当作提交。
2. 多行/坏 raw orderNo 由 unit seam 覆盖并抛 `IllegalStateException`；Mapper SQL/连接/表缺失异常继续传播。
3. 真实 Recovery 中 alias 不 `forceCommitted`、不调用 Stock，首行 intent 保持原事实，后续健康候选可继续；exact 行仍 forceCommitted；missing 行继续 AQ/BN 流程。
4. 回归 17AQ/AR/AS/BN、17BF/BE/BG、Reader/Creation、原订单 checksum 和 fixture 清理；不宣称全局订单身份闭环。
