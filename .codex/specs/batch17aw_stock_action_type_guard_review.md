# CM-20260924-20：库存动作类型精确门评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderStockActionRecovery` 将任意非 `CONFIRM` 类型默认为 ROLLBACK 的风险。**

当前方向计算使用 `"CONFIRM".equals(actionType) ? ... : ...`。因此 null、blank、大小写差异、尾随空格及未知字符串都会被当作 ROLLBACK，可能对错误方向调用库存、把错单事实标记为 SUCCEEDED 或触发相反终态 CONFLICT。

本轮只增加精确 actionType 门，不修改数据库筛选、状态枚举、operationKey、claim/attempts、owner/lease、扫描、中断或 17AV 响应身份门。

## 2. actionType 输入契约

`recoverOne` 进入 try 后、计算 target/opposite、任何 Stock 状态 GET 或其他库存动作之前，必须精确允许以下两个字符串：

- `CONFIRM`
- `ROLLBACK`

比较区分大小写，不 trim、不大小写转换、不把 blank/null 映射到任一方向。其他所有值均抛出 `IllegalStateException`，包括 null、空串、空白、大小写变体、尾随空格和未知标识。

该异常沿现有 RuntimeException catch 进入 `actionMapper.retry` 的 UNKNOWN/原退避语义；不得查询 Stock、调用 confirm/rollback、finish 为 SUCCEEDED/CONFLICT 或改写 actionType/operationKey。

## 3. 合法类型与恢复边界

合法 `CONFIRM` 继续以 `CONFIRMED` 为目标、`ROLLED_BACK` 为相反状态；合法 `ROLLBACK` 继续以 `ROLLED_BACK` 为目标、`CONFIRMED` 为相反状态。

其余既有流程保持不变：

- 首次状态已是目标终态时按原规则 SUCCEEDED；
- 相反终态或 REJECTED 按原规则 CONFLICT；
- RESERVED 只使用原 `operationKey` 执行对应方向的一次动作；
- 写入后再次查询沿 17AV 订单号身份门和原目标/相反状态规则收敛；
- 未知或动作结果不确定时按原 UNKNOWN retry 处理。

未知 actionType 不进入任何上述分支，不能因为 Stock 当前状态恰好匹配某方向而完成本地动作。

## 4. 数据库与兼容边界

- 不新增 SQL 筛选、不改变 `order_stock_action` schema、索引、状态枚举、动作字段或历史行。
- 保留 claim attempts+1、owner/lease、retry/nextRetry、finish CAS、operationKey 和已有中断策略。
- 不校验 operationKey 内容与 actionType 的组合；该身份契约另行处理，本轮不扩展。
- 不自动修复历史 UNKNOWN/非法 actionType 行；它们只在下一次恢复时被安全退避，需人工或后续批次处理。
- 不修改 Stock/Feign API、消息、Seata 或其他服务。

## 5. 验收矩阵

1. unit 覆盖 null、空串、空白、大小写变体、尾随空格和 UNKNOWN 等非法类型，在 RESERVED、目标终态、相反终态、REJECTED 等状态下均不调用 Stock、不 finish，只执行一次原 retry。
2. 真实隔离 MySQL 写入合法物理 UNKNOWN actionType，确认原 id、orderNo、actionType、operationKey、createdAt 保持，claim attempts、retry、owner/lease 按原规则变化；后续健康 CONFIRM/ROLLBACK 行仍可正常 SUCCEEDED。
3. 合法 CONFIRM/ROLLBACK 回归原目标终态、相反终态、RESERVED 动作和写后状态查询；17AV 的 response orderNo 身份门继续生效。
4. 验证非法类型路径不产生 confirm/rollback、不产生 finish、不改变历史事实；原订单 checksum、fixture 清理和既有 6 项 action unit/恢复 IT 保持。

## 6. 明确不承诺

本轮只阻止非法 actionType 被默认为 ROLLBACK，不扩展 operationKey 一致性、历史数据修复、动作方向迁移或跨服务库存事实校验。
