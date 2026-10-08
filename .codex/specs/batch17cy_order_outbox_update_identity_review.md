# CM-20260924-20：订单 Outbox 更新身份精确评审

## 1. 结论与范围

结论：**GO，仅修复订单 outbox 发布完成与失败记录两个 UPDATE 的 event_id 精确身份条件。**

当前两个 UPDATE 以排序规则比较 `event_id`，大小写、重音或尾空格别名可能命中其他行并改写错误事实。本批保留原 `event_id=?` 索引候选，再增加无长度截断的二进制精确比较：

`CAST(event_id AS BINARY)=CAST(? AS BINARY)`

参数使用同一原 eventId 绑定两次；不改变方法签名、返回值、SQL 其他条件或字段。

## 2. 方法边界

- `markOutboxPublished` 保留 status 精确 PENDING、CAS 影响行数和 CV 的 0 行 publication proof；alias/重音/尾空格输入不得改写其他 event 行。
- `recordOutboxFailure` 保留 status、attempts>=0、CQ 饱和计数、错误裁剪和 next retry 语义；身份不精确返回 0，不更新任何字段。
- `findPublicationFacts` 仍按原候选查询返回 event_id/status，由 Service 做精确 String 核验；本批不改该读查询、扫描 SQL、PO 投影或历史归一化。
- 不保证完整读后并发保护，不承诺 MQ 与 SQL 原子性；Rabbit 仅用于替身测试。

## 3. 验收与数据库/API 语义

- 真实隔离 MySQL 覆盖 direct alias/重音/尾空格两种更新、callback/confirm 后数据库 ID 变化、后续健康候选；坏行全字段保持，合法行可完成。
- unit 仅回归现有 18 项，覆盖精确 eventId、更新 0 行、CV proof、CQ/CR/CS/CU 边界；两个 eventId 参数绑定和真实 0 行行为由 MySQL IT 覆盖；确认不重发、不追加 failure。
- `order_outbox.event_id VARCHAR(128) UNIQUE NOT NULL` DDL、索引、状态、API、DTO、消息和历史数据保持；CAST 只增加应用 SQL 身份精度，不新增 schema/index。
