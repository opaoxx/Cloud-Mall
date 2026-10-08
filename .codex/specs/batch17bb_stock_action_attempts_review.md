# CM-20260924-20：库存动作 attempts 边界评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderStockActionMapper` 的 INT attempts 溢出与负值污染。**

当前 claim 直接执行 `attempts+1`，达到 `INT_MAX` 可能触发 MySQL 越界；负 attempts 记录也可能进入候选、被认领或继续执行。17BB 只处理 attempts 计数边界，继承 17AZ lease fencing 和 17BA 单候选数据库异常隔离。

本规格 supersede 17AZ/17BA 中与 attempts 边界相关的排除项，仅作用于本 Mapper 的候选、claim、finish/retry SQL；不重复修复数据库异常隔离，不改变 schema、public 签名、状态、owner/lease、Stock、Feign 或调度拓扑。

## 2. Claim 饱和契约

claim 保留原认领时点、owner、lease、状态、nextRetry、attempts 递增位置和所有其他条件，仅将递增表达式改为：

```sql
CASE WHEN attempts < 2147483647 THEN attempts + 1 ELSE attempts END
```

合法 attempts `0..2147483647` 均可认领；`INT_MAX` 认领后保持 `INT_MAX`，不溢出、不阻断后续候选。并发 claim 仍由原 lease 条件仲裁，只有一个 owner 成功。

## 3. 负值 fail closed

### 3.1 findDue

在现有状态、next_retry 和 lease 条件中增加 `attempts >= 0`，并保持排序、LIMIT、参数和 `<` 到期语义。负 attempts 记录在 LIMIT 前排除，不能占用正常候选窗口。

### 3.2 claim/finish/retry

claim、finish、retry 的 WHERE 均增加 `attempts >= 0`。负值记录：

- 不被认领、不查询 Stock、不执行 confirm/rollback；
- finish/retry 直接返回 0，保留原 id、订单、类型、operationKey、状态、lease、错误和时间事实；
- 不自动重置为 0、不清理 lease、不猜测修复、不产生额外 retry。

Recovery 不使用负 attempts 计算退避来发送动作，因为负值候选已经被 findDue 排除；直接 Mapper 调用同样被 SQL 门拒绝。

## 4. 保持边界

- 保留 17AZ 的 UUID token、BINARY owner、`lease_until > now`、状态和完成 CAS；保留 17BA 的候选数据库异常隔离。
- 不改变 30 秒 lease、claim 原 `<` 到期条件、attempts 在 claim 时递增、nextRetry 算法、50 条上限、排序、时间和 error shorten。
- 不增加 schema/check、状态、API、字段、迁移或历史负值自动修复。
- 合法 attempts 的 CONFIRM/ROLLBACK 目标、UNKNOWN retry、finish/retry 和 Stock 调用保持原语义。

## 5. 验收矩阵

1. 真实 MySQL strict/non-strict：attempts=0、1、INT_MAX-1、INT_MAX 认领；MAX-1 饱和为 MAX，MAX 重复认领保持 MAX，无 SQL 越界。
2. 负值 MIN/-1 低 id 在 LIMIT 前排除，limit=1 仍能扫描后续正常行；负值 direct claim/finish/retry 返回 0 且整行不变。
3. MAX attempts 的两方向目标终态可正常 finish；Stock 失败仍进入 UNKNOWN 和原不超过 60 秒退避，下一健康候选继续；后续新 token claim 仍保持 MAX。
4. 并发 MAX claim 只有一个 owner 成功，计数保持 MAX；lease fencing、owner alias/过期和数据库异常边界回归。
5. 回归 BA/AZ/AY/AV/AW/AX、Action Error/Identity/Creation IT 与 unit、原订单 checksum 和随机 fixture 清理；不把本批计数修复描述为其他可靠性问题的闭环。

## 6. 明确不承诺

本轮只处理库存动作 attempts 的饱和和负值 fail closed，不提供历史数据迁移、数据库全局一致性、租约续期、网络撤回或其他恢复器的计数修复。
