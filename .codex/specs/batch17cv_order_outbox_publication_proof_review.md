# CM-20260924-20：订单 Outbox 发布完成证明评审

## 1. 结论与范围

结论：**GO，仅补 `markOutboxPublished` 0 行结果的只读 publication proof。**

ACK 后完成 UPDATE 返回 0 可能表示 peer 已合法标记 PUBLISHED，也可能表示缺行/其他状态；当前直接结束会丢失完成证明。本批只在 0 行时查询当前 eventId/status，不改变 mark SQL、Schema、index、消息、DTO 或历史事实。

## 2. 完成结果契约

- `markOutboxPublished` 返回恰好 1：按原成功路径完成，不增加 proof 查询。
- 返回负数或大于 1：立即抛受控 DataAccessException，不查询、不 failure 写、不重发；候选层按 CS 隔离。
- 返回 0：先执行 CU 中断检查，再调用只读 `findPublicationFacts(eventId)`，投影 `event_id,status`；Service 必须校验结果唯一、event_id 与请求 String 精确相等、status=`PUBLISHED`，才作为 peer 已完成的幂等成功返回。
- 0 行、缺行、非唯一、eventId 不精确或 status 非 PUBLISHED：抛受控 OptimisticLockingFailureException（DataAccessException），候选层告警继续；不写 failure、不重发、不修改原行。
- 新读前后沿 CU guard 检查中断；Error 不捕获。查询异常沿 CS 候选隔离。

该 proof 只证明当前 SQL 可观察的 event/status，不验证触发器改写后的全部状态，也不提供 MQ 与 DB 原子性。

## 3. 兼容与验收

- peer 已 PUBLISHED 的 0 行正常完成，不误报警、不 failure、不重发；缺行/PENDING/OTHER/多行/错 event 进入受控 proof 失败，后候选继续。
- 非 1 非 0 计数不查询；正 1 不查询；中断在 proof 前后停止，不新增写。
- 真实 MySQL 覆盖 ACK 后改 OTHER、合法 peer PUBLISHED、缺行和后候选；unit 覆盖投影精确身份/多行/非 1/中断。
- 保留 CQ/CR/CS 的 attempts、快照、数据库隔离、Rabbit confirm/returned 和错误语义；不承诺读后竞态或网络撤回。

## 4. 数据库/API 应用语义

`order_outbox` 的 status、event_id、索引和 mark SQL 不变；新增只读 publication facts 查询为内部 Mapper/Service seam，不对外暴露 API，不更新状态或 payload。

