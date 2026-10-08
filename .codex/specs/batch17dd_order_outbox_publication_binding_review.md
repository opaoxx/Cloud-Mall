# CM-20261009-17DD：订单 Outbox 发布完成事实绑定评审

## 1. 结论与范围

结论：**GO，仅收紧 `markOutboxPublished` 返回 0 时的只读 publication proof。**

17DC 的 proof 只读取 `event_id,status`。在同一 `eventId` 的 peer 已完成写入后，若另一进程把 `event_type`、`order_no` 或 `attempts` 改写，旧 proof 仍会把这条不再对应原 timeout 候选的事实当作完成。本批扩展只读投影和 dispatcher 的 proof 校验，绑定原候选的四项身份/资格事实；不改变完成/失败 SQL、表结构、索引、扫描资格、消息、API 或历史行。

## 2. 只读 publication facts 投影

`findPublicationFacts(String eventId)` 方法签名不变，仍只按原 `eventId` 读取当前事实、不更新状态、不重新投递。查询投影从 `event_id,status` 扩为：

- `event_id`
- `status`
- `event_type`
- `order_no`
- `attempts`

映射为 `OrderOutboxPO` 的 partial PO；不得读取 payload、时间列、错误列或整行。`attempts` 使用 `ResultSet.getObject("attempts", Integer.class)` 或等价的 nullable 精确读取，不能用 `getInt` 丢失 SQL NULL 语义；数据库列仍为 `INT NOT NULL`，NULL 仅作为 unit seam 的坏 proof 输入。投影只用于 proof，不能触发重投、修复事实或复用 ID。

## 3. 0 行完成 proof 契约

publisher confirm 成功后，`markOutboxPublished` 的既有结果处理保持不变：

- 返回 `1`：直接完成，不查询 proof；
- 返回非 `0/1`：立即抛既有受控 `OptimisticLockingFailureException`（`DataAccessException`），不查询、不写 failure、不重发；
- 返回 `0`：遵循 CU 的读前/读后中断检查，读取一次 `findPublicationFacts(originalEventId)`。

只有投影唯一一行同时满足以下条件，才接受 peer 已完成：

1. `publicationFacts.size() == 1` 且行对象非空；
2. `fact.eventId` 与发送时原 `outbox.eventId` 使用 `String.equals` 精确相等；
3. `fact.status` 精确为 `PUBLISHED`；
4. `fact.orderNo` 与发送时原 `outbox.orderNo` 使用 `String.equals` 精确相等；
5. `fact.eventType` 精确为 `ORDER_TIMEOUT`，且与发送时原 timeout 类型一致；
6. `fact.attempts` 非 null 且 `>= 0`。

`attempts` 不要求等于发送快照原值：peer 合法完成期间允许计数增长 1，也允许饱和为 `Integer.MAX_VALUE`；本 proof 不验证计数增量、published 时间、错误文本或其他原值。eventId/orderNo 不做大小写、重音、空格、trim 或字符串化归一化。

缺行、非唯一、空对象、eventId/orderNo/type 不精确、status 非 `PUBLISHED`、attempts 为 null 或负数，均抛 `OptimisticLockingFailureException`（属于 `DataAccessException`）并沿 CS 单候选数据库异常隔离：告警、继续后续候选，不写 failure，不重发，不修复或再次读取事实。读前/读后数据库异常和中断语义保持 CS/CU；不得用 proof 结果推断 MQ 与数据库原子性。

## 4. 兼容与验收

- 真实 MySQL 隔离库：使用 Rabbit 确认替身完成发送确认，由 peer 通过真实 SQL 将同一 `eventId` 标记 `PUBLISHED`，随后改写 `event_type`、`order_no` 或 `attempts`，dispatcher 必须 proof 失败、保留整行并继续；不演练实际 broker 或双 publisher 网络，也不修复坏行或重放历史，另以独立健康 peer 场景验证合法 proof。
- 健康 peer 的 `attempts=1` 与 `attempts=INT_MAX` 均不得误告警；不要求计数等于原发送快照。
- 错 facts 告警不追加 failure、不重复发布、不修改 payload、时间、错误或状态；原行保留供重试/人工核验。SQL `NOT NULL` 下的 NULL attempts 只在 unit proof seam 验证。
- unit 覆盖四项绑定、唯一性、null/负 attempts、计数增长与饱和、返回 `1` 和非 `0/1`、CU 中断及查询异常；`findPublicationFacts` 仍禁止重投。
- 不新增 API、消息字段、Mapper 方法签名、Schema/index、scan/update SQL 或 MQ/SQL 原子证明；不读取 full row，不引入 payload/time/ID 复用。

## 5. 交付边界

本评审只冻结规格，生产实现由 backend 按本文件和 `api_spec.md`、`db_schema.md` 的相邻条款执行；本轮不修改业务实现、测试或 Maven 配置。
