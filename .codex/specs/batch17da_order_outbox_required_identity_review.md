# CM-20260924-20：订单 Outbox 必填身份快照评审

## 1. 结论与范围

结论：**GO，仅增加 timeout dispatcher 当前 PO 的 eventId/orderNo 必填身份 guard。**

数据库 NOT NULL 约束不能阻止空字符串或空白字符串；当前合法 `expireAt` 的坏身份行可能被发送并标记 PUBLISHED，而 timeout consumer 会拒绝空白 eventId 或直接跳过空白 orderNo。本批只保护 dispatcher 当前快照。

## 2. Guard 契约

在既有 attempts、event_type、status 精确门之后、parse payload 前检查：

- `outbox.eventId == null || outbox.eventId.isBlank()` 或 `outbox.orderNo == null || outbox.orderNo.isBlank()`：warn+return；
- 不 parse payload、不调用 Rabbit、不 markPublished、不 recordFailure、不修改 PO；后续候选继续；
- 合法非空白字符串保持原样，不 trim、不重建、不校验 width/月格式/inner payload identity；空白字符串仍拒绝；
- guard 只保护当前已读 PO，不新增 scan/LIMIT 过滤，不改变 Mapper UPDATE、CV proof、消费者或历史行语义。

## 3. 兼容与验收

- 真实隔离 MySQL + Rabbit 替身覆盖空串、ASCII 空白、tab、换行、Unicode blank eventId/orderNo；坏行原字段/status/published/错误/时间保持，后续合法事件可发布。null 仅通过当前 PO unit seam 验证，因为既有列为 NOT NULL。
- unit 覆盖 null 和 guard 前不 parse，健康后项完整完成；合法 nonblank eventId/orderNo 路径保持。
- 不新增 schema/index/API/message 字段，不承诺真实 broker 消费演练或解决扫描窗口饥饿；CQ–CZ、CU 中断和 publication proof 回归保持。

## 4. 数据库/API 应用语义

既有 `event_id VARCHAR(128) NOT NULL`、`order_no VARCHAR(64) NOT NULL` DDL 保持；本批只增加 dispatcher 当前快照的非空白身份门，不自动修复空白历史行或改变扫描/索引。
