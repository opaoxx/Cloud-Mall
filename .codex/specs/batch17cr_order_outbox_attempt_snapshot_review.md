# CM-20260924-20：订单 Outbox attempts 快照保护评审

## 1. 结论与范围

结论：**GO，仅在 dispatcher 单候选处理入口增加 attempts 快照 guard。**

CQ 已使失败写入拒绝负 attempts，但 dispatcher 仍可能解析并发送负计数行，成功后将其标记 PUBLISHED。本批在 `dispatchOne` 进入 parse、Rabbit、完成写或失败写前检查当前候选的 `attempts`。

## 2. Guard 契约

`dispatchOne` 首行要求 `outbox.attempts != null && outbox.attempts >= 0`：

- null/负值记录 warn（eventId 与 attempts），立即返回当前候选；
- 不解析 payload、不调用 Rabbit、不调用 `markOutboxPublished`/`recordOutboxFailure`、不修改坏行、不归零、不清租约；
- 后续候选继续处理；`findPendingOutbox` 不新增 attempts 过滤，本批不改变扫描 SQL；
- 合法 0、MAX-1、MAX 保持 CQ 饱和失败计数与既有 confirm/TTL/完成路径。

该 guard 只保护扫描返回的当前 PO 快照；读取后发生的并发变化、其他字段非法、DB 一般错误和发送前 lease 竞态不在本批扩大。

## 3. 兼容与验收

- 数据库 `INT NOT NULL` 的真实负值通过隔离 fixture/SQL 验证；nullable attempts 仅通过 unit raw PO seam 验证，不修改 DDL。
- 真实 MySQL 验证 -1/INT_MIN 行不发送、不完成、不失败写，合法后行继续；合法 MAX 行和 CQ 失败退避保持。
- unit 验证 null/负快照早返回、无 JSON/Rabbit/Mapper 写；原合法 PO 测试明确填 attempts=0。
- 不新增 API、DTO、SQL、状态、消息、Rabbit 政策或全局坏数据修复；不宣称保护读后并发或其他字段。

## 4. 数据库/API 应用语义

`order_outbox.attempts INT NOT NULL DEFAULT 0` 与 CQ 的 SQL 饱和更新保持不变；本批只定义 dispatcher 对已读 PO 快照的非负门，不改变数据库结构和扫描索引。

