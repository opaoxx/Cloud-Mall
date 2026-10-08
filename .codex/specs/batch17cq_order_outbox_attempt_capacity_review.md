# CM-20260924-20：订单 Outbox 尝试次数容量评审

## 1. 结论与范围

结论：**GO，仅修复 `OrderOutboxMapper.recordOutboxFailure` 的 INT attempts 饱和更新。**

当前 `attempts=attempts+1` 在严格 MySQL 的 INT_MAX 行上溢出并中断当前 dispatcher 候选处理。本批只改该 SQL 表达式，不改变 dispatcher、Rabbit、DTO、消息、DDL、索引、历史数据或一般数据库故障隔离。

## 2. SQL 契约

失败写入保留现有方法签名、返回行数、error clipping、nextRetryAt 和 updatedAt 绑定，SQL 改为：

`attempts=CASE WHEN attempts < 2147483647 THEN attempts+1 ELSE attempts END`

并保留原身份/状态条件，同时增加 `attempts>=0`：

`WHERE event_id=? AND status='PENDING' AND attempts>=0`

语义：

- 0 到 MAX-1 正常递增；MAX 保持饱和并继续写 last_error/next_retry/updated_at，允许 dispatcher 原有 error/retry 流程继续；
- 负 attempts 影响 0 行，整行保持，不自动修复、不清租约、不改变状态；
- 按既有 `event_id` 查询未命中或状态非 PENDING 时返回 0 行；保留现有排序/比较规则，不新增 owner 或 BINARY 精确身份核验；SQL/连接错误仍原样传播；
- 不改变下一次失败时间算法、10 秒退避、Unicode 512 code point 截断和 published/status/payload/created_at。

## 3. 兼容与边界

- MAX 行发送失败后仍可继续处理后续候选；本批不把 Dispatcher 的 per-row DB 隔离、负 attempts 扫描门、发送前 guard 或 Rabbit 政策扩展为新规则。
- 不修改 DDL；INT_MAX 饱和是应用 SQL 对既有列容量的保护，不是新增业务次数上限。
- 测试覆盖 strict/non-strict MySQL、0/1/MAX-1/MAX/负数、重复更新、并发边界、non-PENDING/missing，以及真实 dispatcher MAX 行失败后后行继续；Rabbit 使用替身。

## 4. 数据库/API 语义

`order_outbox.attempts INT NOT NULL DEFAULT 0` 保持不变；本批仅规定失败更新对非负计数做饱和加一并拒绝负值行，不新增列、索引、API、消息字段或历史回填。
