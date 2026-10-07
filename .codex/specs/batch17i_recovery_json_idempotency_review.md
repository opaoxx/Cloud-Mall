# CM-20261007-20：秒杀死信 JSON 幂等比较评审

## 1. 评审结论与范围

结论：**GO，限定修复 `SeckillMessageRecoveryMapper.saveDlq` 的重复 eventId 判断；不改变状态机、表结构、lease、重放策略或 API。**

当前 `payload` 列为 MySQL JSON。首次保存时 MySQL 会规范化 JSON 的空白和对象键顺序；重复保存却把入参原始 JSON 字符串与读取后的 JSON 字符串直接 `Objects.equals`，导致同一 eventId、同一 request_hash、语义等价 JSON 被误判为冲突。

## 2. 重复保存契约

保留现有 `INSERT IGNORE` 并发认领和“首载荷不可覆盖”语义：

- 首次 eventId 插入成功，保存原始 payload、request_hash、状态和时间；
- 重复 eventId 必须使用 MySQL 原生 JSON 值比较（例如 `payload = CAST(? AS JSON)` 或当前 MySQL 8 驱动实际支持的等价 Mapper SQL），不能用 Java 字符串比较或 Jackson 树猜测；
- request_hash 仍按精确字符串相等比较；
- 同 eventId、同 hash、JSON 值等价时返回 0，保持数据库已有 payload、status、attempts、lease_owner、lease_until、next_retry_at、last_error 和时间字段完全不变；
- hash 不同，或 JSON 值确实不同，继续保留首载荷并按原路径标记 CONFLICT。

JSON 比较必须保持以下区别：数字与字符串不同、数组顺序不同、JSON null 与字段缺失不同；对象键顺序和空白不造成冲突。数字的内部格式等价性以 MySQL 8 JSON 原生比较语义为准，并在真实数据库中固定测试，不转由应用层猜测。

## 3. Mapper SQL 与竞态

所有 JSON 值比较归 `SeckillMessageRecoveryMapper`，不新增通用 SQL 或业务层持久化逻辑。参数必须以安全绑定方式传入 JSON CAST；非法 JSON 应沿现有保存失败/重投语义，不把 SQL 异常误判为同事件幂等成功。

并发相同 eventId 的 `INSERT IGNORE` 仍由唯一键裁决；后到事务读取已提交首记录，再执行 hash 精确比较和 JSON 原生比较。重复成功不得更新首记录任何状态/lease/退避字段；真实冲突仍只保留首载荷并进入 CONFLICT，不能覆盖原始证据。

## 4. 验收矩阵

1. 相同 eventId/hash、对象键顺序不同、空白不同：返回 0，数据库首载荷和所有状态/lease/退避字段不变。
2. 相同 eventId/hash、数字与字符串类型不同、数组顺序不同、JSON null 与字段缺失不同：标记原冲突路径，首载荷不变。
3. 相同 eventId、hash 不同但 JSON 等价：冲突；hash 相同但 JSON 不同：冲突。
4. 相同 eventId/hash/JSON 的连续重复与并发重复：只插入一条，所有调用不误报 CONFLICT，首载荷不变。
5. 非法 JSON、数据库 JSON CAST/查询异常：不返回幂等成功，不覆盖首记录，沿现有异常/重试语义处理。
6. 实际 MySQL 8 验证原生 JSON 比较，不以 H2、字符串排序或 Jackson 替代；真实 `consumeDeadLetter` 连续投递确认两次均成功 ACK，数据库只保存一次且不误 CONFLICT。

## 5. 明确排除

本轮不改 recovery lease/stale worker、payload 历史迁移、JSON schema、eventId/request_hash 生成、DLQ 状态机、Rabbit 拓扑或其他死信恢复问题。首载荷保留和 CONFLICT 安全边界不变。
