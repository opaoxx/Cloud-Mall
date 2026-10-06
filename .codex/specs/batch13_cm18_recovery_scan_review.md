# CM-20261006-18：Pay 恢复扫描模式隔离评审

## 1. 评审结论

结论：**GO，允许本轮只修复恢复扫描饥饿；12B1 User TCC 资源继续暂停。**

当前 `PayDebitIntentMapper.findDue` 只按 `status`、`next_retry_at` 和租约扫描 `pay_debit_intent`，没有筛选支付记录和意图的可执行模式。12A 对 TCC/未知/不一致模式要求保持 `lease_owner`、`lease_until`、`attempts`、`next_retry_at`、`status` 不变；因此最早 50 条不支持记录会在每一轮占满扫描窗口，后续合法 LEGACY 支付无法被恢复。这是已实现 12A 的真实调度缺陷，优先级高于 User TCC 资源扩展。

本轮只改变 `findDue` 的读取候选集：扫描结果必须是 Pay 记录和扣款意图两表模式一致且可执行的 LEGACY 记录。被排除的 TCC、未知、空关联、孤立或模式冲突记录保持所有恢复字段不变，继续由单笔 `process` 入口返回 `PAY_DEBIT_MODE_UNAVAILABLE`（503）或进入后续有界诊断。不得通过修改 `next_retry_at`、清租约、增加 attempts、改状态或自动降级到 LEGACY 来“挪走”不支持记录。

## 2. 最小实现范围

### 2.1 Mapper 查询契约

将 `findDue(now, limit)` 改为只读取以下 SQL 语义：

```sql
SELECT i.*
FROM pay_debit_intent i
JOIN pay_record p ON p.pay_no = i.pay_no
WHERE BINARY i.debit_mode = 'LEGACY'
  AND BINARY p.debit_mode = 'LEGACY'
  AND i.status IN ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING')
  AND i.next_retry_at <= :now
  AND (i.lease_until IS NULL OR i.lease_until <= :now)
ORDER BY i.next_retry_at, i.id
LIMIT :limit
```

实现必须继续使用参数绑定；`limit` 仍以 `Math.max(1, limit)` 保护。`pay_no` 两表均有唯一索引，JOIN 不得引入重复意图。数据库两列为 `NOT NULL DEFAULT 'LEGACY'`，SQL 必须使用 `BINARY` 精确匹配，不能把空串、小写值、尾随空格或未知值当作 LEGACY；Java `process` 对旧对象中的 null 仍按 12A 兼容语义处理，但持久化异常值不得进入 User/Order 调用。

### 2.2 双表一致性

扫描的“可执行”定义是：

- 意图行存在且状态属于现有恢复集合；
- 对应 `pay_record` 存在；
- 两行 `debit_mode` 均为 BINARY 精确值 `LEGACY`；
- 到达 `next_retry_at` 且租约已释放或过期。

如果一行是 TCC、未知值或两表模式冲突，JOIN/WHERE 必须排除；如果支付记录不存在，孤立 intent 也排除。排除动作不能写任何表。`process(payNo)` 仍保留现有 claim 前和 claim 后 503 保护，因此手工查询不支持记录时不会回落为普通扣款。

### 2.3 不在本轮处理的对象

- 不新增 User TCC PO、表、`frozen_balance` 或 TCC endpoint。
- 不改变 `PayConsistencyService.process`、`PayServiceImpl`、退款逻辑或 LEGACY 资金语义。
- 不修改不支持记录的状态、租约、attempts、错误文本和重试时间。
- 不增加无界全表诊断；若后续需要统计隔离记录，另立只读、有界分页诊断任务，不与恢复候选混用。

## 3. 51 条窗口复现与验证

必须使用真实 Pay MySQL 数据库建立受控夹具：

1. 使用会话临时表映射生产表结构，准备至少 60 条混合隔离记录（TCC、未知、大小写变体、空串、尾随空格、模式冲突、孤立 intent），不改动持久化业务行；确保这些记录的租约和重试字段可观测且不会被修改。
2. 在临时表中插入 51 条及以上已到期、两表模式 BINARY 精确一致为 LEGACY 的可恢复 intent，使合法记录跨越两个 `limit=50` 批次。
3. 修复前运行 `findDue(now,50)`，已真实复现首条结果为 `blocked-0` 而非 `ready-0`，证明前 50 条不支持记录占满窗口；修复后首批必须返回 LEGACY 候选。
4. 修复后运行同一查询，证明第 51 条 LEGACY 记录在首轮候选中出现；不支持记录的 `attempts/lease/status/next_retry_at` 前后完全一致。
5. 通过单笔 `process` 验证 TCC/unknown/mismatch 仍返回 `PAY_DEBIT_MODE_UNAVAILABLE`，User/Order 没有调用，支付钱包和余额账本无变化。
6. 重复执行查询和恢复器，证明同一 LEGACY 记录仍由数据库租约认领，重复扫描不会产生第二次扣款；清理夹具后再执行 Legacy 支付和退款回归。

夹具必须使用独立 payNo 和测试用户，验证完毕后删除并核对原有 Pay 数据、用户余额、`balance_ledger`、支付 intent 和 Docker/MySQL 容器状态。不得使用真实未决支付或生产性账号。

真实验证使用 `SHOW CREATE TABLE` 复制生产表结构到同一连接的会话临时表；连接关闭后临时表自动清理，不写生产表。查询、claim 前后对原始四类持久事实做 checksum/行计数核对，确认本轮只改变读取候选集。无需重启真实业务或扩大资金故障注入；执行 MySQL 集成场景、现有余额/退款/未知模式单测、Legacy 回归和全量构建即可。

## 4. 查询性能与索引

现有 `pay_debit_intent.pay_no` 与 `pay_record.pay_no` 唯一索引支持 JOIN；`pay_debit_intent` 已有 `(status,next_retry_at,lease_until)` 索引。实现后记录 `EXPLAIN`、唯一 pay_no 关联和 SQL 层 LIMIT 行为。小规模会话临时表上优化器选择 `ALL` 不能单独认定为缺陷，也不能无证据强加索引；若更大代表性数据仍显示候选扫描代价异常，再另立索引评审，不在本轮引入通用调度重构。

LIMIT 必须在 SQL 层生效，不能先查询 50 条再在 Java 内过滤；否则仍会重现窗口饥饿。

## 5. 测试契约

需要新增或补充以下测试：

- Mapper 查询排除 TCC、未知、模式冲突和孤立 intent。
- Mapper 查询只保留两表 BINARY 精确 LEGACY，并按 `next_retry_at,id` 排序限制数量。
- 会话临时表中 60 条混合隔离记录与跨两批 51 条及以上 LEGACY 记录的 MySQL 集成测试。
- 排除记录字段快照不变测试。
- `process` 对 TCC/unknown/mismatch 仍在 claim 前返回 503；不调用 User/Order，lease/attempt/status 不变。
- 现有 Legacy 成功、余额不足、订单同步未知、退款恢复回归。

## 6. 结论和后续边界

- 本轮：**GO，修复恢复扫描候选隔离和 50+1 窗口饥饿。**
- 本轮完成后：12A 路由保护和恢复调度可闭环验收，但 CM-18 仍未整体完成。
- 后续 12B1：重新评审 User TCC Try/Confirm/Cancel、Fence 同库和冻结余额状态机。
- 后续 12B2/12C：再评审 Pay TCC 编排、真实 Seata 回调、进程中断、TC 暂失和资金闭环。
