# CM-20260924-20：库存动作恢复时间 DATETIME(3) 边界评审

## 1. 评审结论与范围

结论：**GO，限定规范化 `OrderStockActionMapper` 恢复路径的时间参数。**

BJ 已规范首次 action INSERT 的三列时间，但 `findDue`、`claim`、`finish`、`retry` 仍直接绑定亚毫秒 `LocalDateTime`。这可能使 MySQL DATETIME(3) 舍入 lease 到期边界、跨秒/月或导致完成写的更新时间与 lease 判定精度不一致。

本轮只处理 `OrderStockActionMapper` 的恢复时间参数；insert 复用 BJ 的等价规范化语义。不得修改 Service 的 `now()`、其他服务时间字段、schema、API、状态、Stock/Feign、历史数据或线程拓扑。

## 2. 共同时间规范化

增加私有 `normalizeActionTime(LocalDateTime)`（名称可等价实现），复用 `OrderCreationTime.normalize(time.atOffset(ZoneOffset.ofHours(8))).toLocalDateTime()`：

- 按项目固定上海本地字段语义解释，不把 DATETIME 当作 UTC instant；
- 向下截断到毫秒，不四舍五入；
- 仅允许年份 1000..9999；null、转换失败、年份越界或派生时间越界抛 `IllegalArgumentException`；
- 所有非法时间必须在 SQL 前拒绝，零 JDBC 交互。

## 3. 各方法契约

### 3.1 findDue

先规范化本次 `now` 一次，并将同一 normalized 值绑定 `next_retry_at<=?` 与 `(lease_until IS NULL OR lease_until<?)` 两个参数。保持 status、attempts>=0、排序、LIMIT 和原严格 `<` lease 到期条件。

### 3.2 claim

先规范化 fresh `now` 一次：

- `updated_at`、next_retry 条件和 lease 过期条件使用同一 normalized now；
- `lease_until` 使用 `normalizeActionTime(normalizedNow.plusSeconds(30))`；
- 派生 lease 超出 9999 年时在 SQL 前抛 `IllegalArgumentException`；
- 保留原 `<` claim 到期条件、30 秒 lease、UUID owner、attempts 饱和、负值门和返回值。

### 3.3 finish

owner null/blank 仍在时间规范化前直接返回 0。有效 owner 后规范化 fresh `now` 一次，并将同一值绑定 `updated_at` 与严格 `lease_until>?` 条件。保留 AZ 的 BINARY owner、lease 非 null、状态、attempts>=0、finish CAS 和 0 行语义。

### 3.4 retry

owner null/blank 仍在时间规范化前直接返回 0。分别规范化：

- 调用方 `retryAt` 只用于 `next_retry_at`；
- 调用方 fresh `now` 只用于 `updated_at` 和严格 `lease_until>?` 条件；

不得使用 retryAt 替代 fresh now。保留 UNKNOWN、lease 清理、AZ fencing、BB attempts 门、error shorten 和 0 行语义。

## 4. 保持与排除边界

- SQL 结构、`<` claim expiry、`>` finish/retry expiry、30 秒 lease、UUID/token、attempts 饱和/负值隔离、owner BINARY、状态、退避、API 和方法签名保持。
- 不增加 schema、字段、Clock 配置、重试线程或历史数据修复。
- 不改变 Service 采样时间，只在 Mapper 内按每次调用使用新鲜参数并规范化。
- 不扩展到订单、intent、outbox、Stock、Pay、消息或其他 action 更新时间字段。

## 5. 验收矩阵

1. 真实 MySQL strict/non-strict 覆盖 `.999499/.999500/.999999`、秒末/月末、9999 年末、1000 年初；claim 三个写入时间均为 floor 后毫秒，30 秒 lease 不跨越错误月份。
2. finish/retry 在 `lease_until == normalizedNow` 时严格返回 0；在此前有效时间可完成；全行保持原 AZ/BB/身份/key 语义。
3. retry 跨秒/月末时 nextRetry 与 fresh now 分别 floor；不以 retryAt 授权过期 lease。
4. null、year 999/0/10000、LocalDateTime MIN/MAX 和派生 lease 溢出均 SQL 前抛 `IllegalArgumentException`，原行/零行不变；owner blank 仍原 0。
5. findDue 的末毫秒/等值边界、排序、LIMIT 和严格 `<` 回归；insert、BI、AZ、BA、BB、AV/AW/AX/AY、reader、Recovery、Creation 测试及 checksum/fixture 清理回归。

## 6. 明确不承诺

本轮只规范库存动作恢复 Mapper 的 DATETIME(3) 参数，不提供全项目时间统一、全局时钟一致性、网络撤回、历史数据修复或其他服务时间字段闭环。
