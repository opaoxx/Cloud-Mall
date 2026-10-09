# CM-20261009-17DX：订单预扣意图 LocalDateTime 直接读取评审

## 1. 结论与范围

结论：**GO，仅替换 `OrderReservationIntentMapper.ROW_MAPPER` 三个 DATETIME 列的 JDBC 读取方式。**

`findByUserAndKey` 与 `findRecoverable` 共享 RowMapper，当前对 `next_retry_at`、`created_at`、`updated_at` 使用 `getTimestamp(...).toLocalDateTime()`；Connector/J/Java GregorianCalendar cutover 会使合法历史 1582 日期在读取阶段抛错，恢复扫描在候选隔离前阻断后续健康意图。本批只保护该共享读取 seam。

## 2. RowMapper 契约

保留两个查询的 SQL、全行投影、WHERE、参数数量/顺序、排序、LIMIT 归一和异常传播。ROW_MAPPER 仅改为：

- `next_retry_at`：`resultSet.getObject("next_retry_at", LocalDateTime.class)`，SQL NULL 继续映射 Java null；
- `created_at`：typed `LocalDateTime` 读取；
- `updated_at`：typed `LocalDateTime` 读取。

移除不再使用的 `java.sql.Timestamp` import，并更新 RowMapper 注释。其他 PO 字段、写入、claimRecovery、status/recovery、Stock、时区、配置、DDL、索引和历史事实不变；不设置 SQL mode/driver zero-date 参数，不增加 filter 或历史 repair，仅保证本批合法日期读取，其余非法历史值行为待核验；不增加全局 catch、Calendar/string fallback。

## 3. 验收矩阵

- 真实 MySQL 隔离库 strict/empty 两 mode 覆盖三列 × 7 个合法日期（1000、1582-10-04/05/10/14/15、现代毫秒值），两个入口 `findByUserAndKey` 与 `findRecoverable` 均保留日期组件、毫秒和全行事实；`next_retry_at=NULL` 保持 NULL。
- 独立 UTC Surefire fork 仅运行新增两方法，验证不依赖 JVM 默认时区；恢复场景古老 retry 日期与健康后项能完成真实 SQL `claimRecovery`/`markReleased`，两个意图按原 `order_no`/`idempotency_key`/`items_snapshot` 事实收敛为 `RELEASED`，并清除 `next_retry_at`/`last_error`；Stock 与 Order 查询为替身，不发 Stock reserve/rollback RPC。
- 保留原 strict insert、claim time、rollback proof、Recovery 与状态回归；本表没有 operationKey、attempts 或 lease 字段，不引入 finish/lease 断言，不宣称真实 Stock/Order HTTP 或全服务时间路径已验证。

## 4. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
