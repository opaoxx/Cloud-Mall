# CM-20260924-20：订单 Outbox 超时事件类型评审

## 1. 结论与范围

结论：**GO，仅保护当前固定 timeout exchange 的 `ORDER_TIMEOUT` 事件类型。**

订单 outbox dispatcher 固定发送 timeout exchange，但原扫描和 dispatch 未限制 event_type；大小写/尾空格/其他类型可能被发布并标记 PUBLISHED，而 timeout consumer 只接受精确 `ORDER_TIMEOUT`。本批只修 timeout handler 的候选 eligibility 和当前 PO type guard。

## 2. SQL eligibility

`findPendingOutbox` 保留原 status、due time、`attempts>=0`、排序、limit 和投影，在 LIMIT 前增加：

`BINARY event_type = BINARY 'ORDER_TIMEOUT'`

使用 BINARY 避免列 collation 将大小写、重音或尾空格别名占据窗口。合法 `ORDER_TIMEOUT` 的 0/MAX attempts、due/nextRetry/id 顺序继续处理；其他类型不返回、不更新、不删除。

## 3. 当前 PO guard

`dispatchOne` 在 parse、Rabbit、complete/failure 写前使用 `"ORDER_TIMEOUT".equals(outbox.eventType)` 精确校验：

- 上述不匹配类型无论 payload 是否包含有效 `expireAt` 均 warn+return；本 guard 不新增 payload 字段校验；
- 不 parse、不发送、不 markPublished、不 recordFailure、不修改当前事实；后续候选继续；
- guard 只保护当前已读 PO，不查询数据库新状态，不承诺读后并发保护。

保留 CR/CQ/CS/CU/CV 的 attempts、数据库异常、中断和 publication proof 边界；不改变 DDL、索引、状态、消息结构、固定 exchange、payload identity 或历史坏类型行。未来其他 event_type 需独立 handler，不由 timeout dispatcher 猜测处理。

## 4. 验收与数据库/API 语义

- 真实 SQL 验证最早 50 个别名/错误 type 不占窗口，合法 timeout 事件可扫描并发布；非 timeout/PUBLISHED/未来/负计数整行不变。
- unit 验证 null/其他/大小写/尾空格在 parse 前返回，合法 helper fixture 明确填 `ORDER_TIMEOUT`；扫描 SQL 错误仍传播。
- `order_outbox.event_type VARCHAR(64) NOT NULL` DDL、索引和公开 API 不变；本批只限制 timeout dispatcher 的 eligibility，不修历史记录或其他 dispatcher。
