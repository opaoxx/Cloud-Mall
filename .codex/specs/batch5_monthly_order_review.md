# 第 5 轮跨月订单查询评审

## 范围

本轮只冻结 CM-20260924-10 与 CM-20260924-11：订单列表跨月查询缺页被吞掉、JVM 全量排序导致内存增长，以及长运行后未来月份物理表不存在。评审对象为 `GET /api/orders`、订单月表路由和 `OrderTableInitializer` 的职责边界。

## 已确认故障

1. `OrderServiceImpl.list` 跨月逐月读取时对 `DataAccessException` 空捕获，某月缺表或查询失败后仍可能返回成功，造成 `items` 和 `total` 不完整。
2. 旧路径把命中月份的订单全部读入 JVM，再排序并 `subList`；深分页和大用户订单量会使内存随总订单量增长。
3. 初始化逻辑只预建上月、本月和未来两个月，服务长时间运行跨入新月份后可能缺少目标物理表。
4. 请求时间可以携带非 `+08:00` offset。必须按 same instant 转换到 `Asia/Shanghai` 后计算月份，否则月初请求可能漏查实际属于上海时区前一月的订单。
5. 实测 `mall_order_202609` 当前仅 17 行；用户和时间范围查询命中 `idx_user_created`，`ORDER BY created_at DESC,order_no DESC` 出现 `Using filesort`。当前数据规模不足以证明新增索引收益。

## 冻结方案

- API 保持 `status/page/pageSize/startTime/endTime` 请求参数和 `items/page/pageSize/total` 响应结构。`total` 为命中所有月份的精确计数。
- `startTime/endTime` 先规范到 `Asia/Shanghai`，再计算 `yyyyMM` 路由范围；查询条件使用左闭右开 `[start,end)`。
- 列表在单个只读 `REPEATABLE_READ` 本地事务中完成 COUNT 与数据读取，使 `total` 和 `items` 使用同一 InnoDB 快照。
- 先逐月 COUNT，再按月份倒序使用 COUNT 跳过完整月份，只对承载目标页的月份执行有界 `LIMIT/OFFSET` 批次；批次内固定按 `created_at DESC,order_no DESC` 排序。月表时间区间严格不重叠时，该方案与 k 路归并等价，内存为目标页和当前批次规模。
- `page` 偏移计算使用 `long` 并检查乘法溢出。错误月份数据作为数据异常告警并失败，不启动全量扫描兜底。
- 任一命中月份缺表、COUNT 失败、批量读取失败或结果不完整，整体返回 `ORDER_QUERY_SHARD_UNAVAILABLE`（HTTP 503），丢弃已读结果，不返回部分页。
- 读路径不自动建历史表。初始化器和定时任务只预建当前月份及配置窗口内的未来月份；建表失败应记录可告警状态。
- 保留 `idx_user_created(user_id,created_at)`。虽然当前执行计划有 filesort，但表很小；`(user_id,created_at,order_no)` 的存储、写入和历史迁移成本，以及带 status 条件的收益尚未被证明。本轮不新增索引，后续以生产规模 `EXPLAIN ANALYZE`、深分页耗时和写入成本另行评审。

## 必要迁移与运维动作

- 无新增数据库字段、无索引迁移、无 API 字段变更。
- 需要为未来月份预建任务补充长运行调度和失败告警；历史缺表由显式迁移/运维流程补齐后再重试查询。
- 实现发布前核对所有订单、明细和状态日志月表的创建窗口，避免只创建主表而缺少同月从表。

## 故障验收

| 场景 | 验收结果 |
| --- | --- |
| 命中月份缺物理表 | 返回 HTTP 503 与 `ORDER_QUERY_SHARD_UNAVAILABLE`；不返回已有月份的部分 items/total，不在读路径建表 |
| 跨月边界 | `startTime/endTime` 覆盖月末、月初；订单按 `created_at DESC,order_no DESC` 返回，`[start,end)` 边界准确 |
| 非 `+08:00` offset | 例如 `2026-09-01T00:30:00+09:00` 按 same instant 转成上海时间后路由到 202608 与 202609 的正确范围，不漏查 8 月订单 |
| 同毫秒排序 | 相同 `created_at` 的订单按 `order_no DESC` 稳定排列，重复请求顺序一致 |
| 深分页 | 超大 `page` 不发生 int 溢出；有效深页只读取有界批次，内存不随总订单量线性增长 |
| 长运行跨月 | 定时任务预建新月份；历史缺表仍明确失败，不由读请求隐式创建 |
| 并发写入 | COUNT 和 items 在同一只读 REPEATABLE_READ 快照内，响应内部不出现跨月计数与数据页不一致 |
| 执行计划 | 小数据允许 filesort；待接近生产规模时用 `EXPLAIN ANALYZE` 和深分页指标决定是否评审复合索引 |
