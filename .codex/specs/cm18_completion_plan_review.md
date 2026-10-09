# CM-18 完成计划评审：资金协议隔离与可证明收敛

## 1. 评审结论

CM-18 不能因为已经完成 16A–S 的多个微边界修复就宣称闭环。现有规格和代码已经覆盖 Pay/User LocalTCC、Fence、派发、receipt、delivery、取消/退款窗口、恢复 lease 以及 LEGACY/TCC 持久模式隔离的许多局部护栏；这些成果需要保留并作为后续验收基线。

仍缺少能够支撑主项结论的统一证据链：四服务真实资金/订单/库存链路与 MQ 故障下，原协议、原 XID/branch、User 资金账本、Order 状态、Stock 动作、Pay 决议和恢复结果必须能逐笔对应。`BEGIN_UNKNOWN`、XID 缺失、TC 或远端证据缺失时，系统不能猜测提交/回滚，也不能新建 begin、Try、branch 或改变资金决议。

本计划先安排一个独立的 16T 取证与分类切片；只有 CM18 的 M1–M4 全部达成并通过主项验收后，才进入 CM-20、CM-21。16T 允许只读 reconciliation 视图或诊断工具，但不新增资金写入；它的输出用于受控阻塞、人工补证和后续真实故障验收。

## 2. 当前状态分层

### 2.1 已有实现和规格资产

- 16A–S 已分别冻结 Pay/User TCC 桥、User 本地状态机和 Fence、Pay receipt/coordinator/delivery/recovery、Legacy/TCC 行级模式隔离、取消与退款竞争窗口、Lease CAS 和恢复扫描护栏。
- 持久模式字段、Pay/User 归属行、TCC execution/receipt/fence、delivery/cancel/recovery 记录及原 XID/branch 绑定已经存在相应 Mapper、Service 和测试资产。
- 默认支付配置仍是 `LEGACY`；显式 `TCC` 需要独立开关和一组 Pay/TCC 依赖配置。历史行按持久化 `debit_mode` 继续走原协议，不能由当前配置追溯改写。
- 已有单元、真实 MySQL、Seata/TCC 和故障过程测试分别证明了若干局部不变量，但这些证据不能自动替代四服务端到端验收。

### 2.2 必须继续视为未完成

- 没有逐笔完整证据的 `BEGIN_UNKNOWN`、缺 XID/branch、receipt 缺失、TC 状态未知、User/Order/Stock 查询未知，不能自动收敛为成功或回滚。
- 不能用预期的 `UNKNOWN`、HTTP 超时、进程退出、TC 返回 Unknown 或缺少一张表的记录推断资金已经扣除、已确认或应退款。
- 不能通过新建全局事务、重新 Try、重新注册 branch、生成新 operation key、重复扣款/退款或直接写资金终态来“修复”不可证明的旧交易。
- 未完成真实隔离四服务与 MQ 故障矩阵、发布前默认 LEGACY/TCC 切换门、回退演练和清理/保留未决证据的运行手册。

## 3. 16T：资金证据取证与分类

### 3.1 目标

为每个指定 `payNo/orderNo/userId` 生成不可变、可审计、带采集时间的证据包，回答以下问题：

1. Pay 记录、Pay debit intent、TCC dispatch/receipt/execution/fence/delivery/cancel 是否属于同一 payNo、XID、branch、载荷、协议模式。
2. User 的 balance、frozen_balance、attempt、TCC 行、ledger 是否能与同一 `payNo`、`ledger_key`、operation key 和载荷完整对应。
3. Order 状态、订单库存动作及 Stock reservation 是否显示同向终态，MQ/outbox 是否只有原 event/idempotency key 的证据。
4. TC 的全局事务/分支状态与服务数据库事实是否一致；若 TC 或任一服务证据缺失，分类必须为“受控阻塞”，不能分类为成功或回滚。

### 3.2 只读工具边界

- 只允许只读 SQL、只读 TC 查询和已存在的只读服务 GET；禁止 INSERT/UPDATE/DELETE、claim、Try、Confirm、Cancel、refund、Order 状态写入、Stock 写入和 Rabbit 重发。
- 默认内部运维入口，不经 Gateway 暴露；必须有强鉴权、审计日志、请求范围和分页上限。不得接受客户端提供的 XID/branch 作为信任事实。
- 输出使用 `payNo/orderNo` 的受控业务标识；XID、branch、凭证、连接串和 token 不原样返回，采用不可逆摘要或受权限保护的脱敏值。原始证据仅写入受保护的审计存储，不进入普通日志。
- 查询按固定 payNo/orderNo 和时间范围分页，禁止无界全表扫描；视图或诊断 SQL 不改变生产表、索引、协议状态和重试时间。

### 3.3 分类结果

仅允许下列分类及明确的下一步：

| 分类 | 证据条件 | 自动动作 |
| --- | --- | --- |
| `LEGACY_CONFIRMED` | Pay/intent 持久模式均为 LEGACY，Legacy User 账本与订单/库存事实一致 | 沿既有 Legacy 恢复；不接触 TCC 资源 |
| `TCC_TRYING` | 原 XID/branch、TCC User 行、冻结额和 Pay execution/receipt 完整绑定，尚未确认 | 只能使用原 XID/branch 由 TC/原 RM 重试；不得新建 Try |
| `TCC_COMMITTED` | Fence/receipt、User ledger、Pay execution 和 Order/Stock 同向提交事实完整 | 只做幂等核验或原交付收尾，不重复扣款 |
| `TCC_CANCELLED` | 原 Fence/业务 tombstone、User 释放事实及 Order/Stock 取消事实完整 | 只做幂等核验或原回滚收尾，不重复释放 |
| `PROTOCOL_CONFLICT` | LEGACY/TCC 混合、载荷/金额/用户/订单/XID/branch 不一致 | 受控阻塞并人工补证；不得自动改模式或写资金 |
| `BEGIN_UNKNOWN` | BEGIN 结果、XID 或 TC 事实缺失，无法证明全局事务是否创建 | 保留原记录与原重试证据；不 begin、不 Try、不猜决议 |
| `EVIDENCE_INCOMPLETE` | 任一 User/Pay/Order/Stock/MQ 关键事实缺失或只剩 HTTP 结果 | 受控阻塞，等待原系统补证或人工对账 |

`BEGIN_UNKNOWN` 和 `EVIDENCE_INCOMPLETE` 不是“待重放成功”或“预期 UNKNOWN”。它们只有在补齐原 XID/branch、原载荷和两端持久事实后，才允许转入对应的已知分类。

## 4. 后续真实里程碑

### M1：16T 只读取证与受控阻塞

- 冻结字段清单、证据哈希、分类枚举、脱敏规则、访问审计和保留期限。
- 对 LEGACY、TCC 已确认、TCC 已取消、TRYING、BEGIN_UNKNOWN、无 XID、载荷冲突、User/Order/Stock/MQ 缺证各准备真实隔离样本。
- 证明工具不会写任何资金表、业务状态、TCC Fence、delivery/intent、Rabbit 或 TC；同一输入重复查询结果和原始事实 checksum 一致。
- 输出一份逐笔分类报告；无法证明的样本必须停在受控阻塞，列出需要补取的原始事实和责任系统。

### M2：默认协议和历史隔离闸门

- 默认 `LEGACY` 在独立环境启动并完成旧支付、扣款、退款和恢复回归；现有历史行不因配置切换进入 TCC。
- 新 TCC 环境只允许显式模式、完整依赖和完整证据链；Pay、User、Order、Stock、MQ 的协议字段/事件/操作键必须逐行一致。
- 混合模式、空值、大小写/空白异常、旧数据缺列或不完整迁移均在副作用前拒绝，并进入 16T 分类；不得静默降级为 Legacy。

### M3：四服务真实故障矩阵

使用独立 MySQL 数据库、Seata TC、RabbitMQ vhost/队列、Pay/User/Order/Stock 进程和隔离测试身份，至少覆盖：

- Pay→User Try 在本地提交前、提交后 ACK 前、XID/branch 已持久化后进程退出。
- TC 暂失、RM 重启、Confirm/Cancel 响应丢失、原 XID 重试、重复 callback、旧 worker 迟到。
- Order 写入前后、Stock 预扣/确认/回滚响应未知、Rabbit publish/ack/return/consumer 重启及重复消息。
- Order、Stock、User、Pay 查询分别返回未知、冲突、缺失、已知终态；所有场景保留原 payNo/orderNo/XID/branch/operation key。
- 每个场景核对 Pay、User balance/frozen/ledger、Order 状态、Stock action/reservation、MQ event 和 TC 终态；未知场景只接受受控阻塞，不得通过猜测结束。

### M4：发布、观察与回退

- 发布前门槛：M1 报告无未分类资金样本；M2 LEGACY/TCC 隔离通过；M3 四服务故障矩阵全部有资金级断言；所有未决样本都有责任人、补证路径和保留期限。
- 首次发布保持默认 LEGACY；TCC 仅对明确选择的新请求开启，按环境和小范围身份分批放量。旧交易始终按持久 `debit_mode` 处理。
- 观察指标必须区分协议模式、`BEGIN_UNKNOWN`、证据缺失、TC 重试、重复 callback、资金事实冲突和最终已知终态；禁止把 UNKNOWN 计为成功率。
- 回退只关闭新 TCC 入口并保留原 TCC 未决记录、XID/branch 和恢复能力；不得把 TCC 行改成 Legacy、清除冻结、重放新 Try 或删除证据。回退期间由 16T 继续只读分类和人工补证。

## 5. CM18 主项完成判据

只有同时满足以下条件，才能报告 CM18 完成：

1. 默认 LEGACY 和显式 TCC 的新建、恢复、确认、取消、退款协议已隔离，历史行不随配置切换；旧交易协议回归通过。
2. Pay/User/Order/Stock/MQ 的真实故障矩阵通过，至少覆盖进程、TC、HTTP、Rabbit 和旧 worker 竞争窗口；每个已知终态都有跨表资金级证据。
3. 原 XID/branch、operation key、ledger key、payNo/orderNo 和消息幂等键在重试中保持不变；不存在第二 Try、第二 branch、第二扣款或第二退款。
4. 所有 BEGIN_UNKNOWN、无 XID、TC Unknown、查询缺失、载荷冲突和证据不足样本均进入受控阻塞并可审计；没有任何自动猜决议、补始事务或资金写入。
5. 发布与回退在隔离环境实际演练，回退后 Legacy 继续可用，TCC 未决事实不被破坏，未决样本可由原协议和原 XID 继续补证。
6. 证据工具通过权限、脱敏、分页、无写入和清理验收；生产数据、凭证、XID 原文和 MQ 内容不会泄露到普通日志或公开 API。

## 6. 当前阻塞与责任边界

在 M1 之前，不把 16A–S 的局部单测通过、TC 返回 Unknown、HTTP 超时或数据库中某一行状态当作 CM18 主项完成证据。无法由现有事实自动收敛的样本必须保留原现场，由人工补取原 XID/branch、TC 记录、服务数据库快照和 MQ 证据；若证据永久缺失，只能维持受控阻塞并记录人工决议，禁止用新资金操作掩盖缺口。

本文件只规划 CM18 后续闭环，不修改 API、数据库结构、业务实现或测试；16T 的字段和只读权限在实现前仍需由对应 backend/qa 角色按本计划另行冻结。
