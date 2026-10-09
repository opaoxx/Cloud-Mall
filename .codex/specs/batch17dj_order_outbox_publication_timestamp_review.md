# CM-20261009-17DJ：订单 Outbox 发布完成时间毫秒边界评审

## 1. 结论与范围

结论：**GO，仅在 `OrderOutboxMapper.markOutboxPublished` 入口保护 `publishedAt` 的 DATETIME(3) 毫秒边界。**

17DI 已保护 failure writer，但完成写仍把调用方原纳秒 `publishedAt` 同时绑定到 `published_at` 与 `updated_at`。精度不足时 MySQL 默认舍入可能跨秒、月或年份；`9999` 端点还可能越界或在 non-strict 模式产生零日期。本批只复用同一时间归一算法保护完成写，不改变 dispatcher raw 采样、中断、完成计数或 17DD proof。

## 2. Mapper 完成时间契约

`markOutboxPublished` 方法签名、SQL 文本、WHERE 资格、eventId 双重精确绑定、返回行数和参数位置保持不变。进入 `JdbcTemplate.update` 前：

- `publishedAt` 必须非 null，年份必须在 `1000..9999`；非法值即抛 `InvalidDataAccessApiUsageException`（`DataAccessException`），首次 JDBC 前拒绝，即使缺行、非 PENDING、type 不符或 attempts 负数；
- 合法值使用 DI 已冻结的共同时间 helper 向下 `truncatedTo(ChronoUnit.MILLIS)`，得到同一个 `normalizedPublishedAt`；
- 将该同一归一结果按原位置绑定到 `published_at` 和 `updated_at`，不采新时钟、不转换 offset、不替换调用方时间、不修改原对象。

helper 可由 `normalizeFailureTime` 改名为 `normalizeOutboxWriteTime` 后复用；DI 的 failure writer 行为、异常边界和毫秒 floor 必须保持不变。

## 3. 兼容与不变量

- 不修改 SQL 文本、WHERE 身份/状态/type/attempts 条件、索引、返回 `0/1` 语义或历史数据；不扩展 insert、failure writer、scan 或其他时间路径。
- Dispatcher 仍以 raw `publishedAt` 采样并执行原 CU/CS；Mapper 只在 JDBC 边界归一。完成返回 `1`、返回 `0` 的 17DD proof、DF failure result、DE route、DG 配置和 API/message 保持。
- 不依赖 SQL mode 或全局 `TIME_TRUNCATE_FRACTIONAL`，不引入 DDL/API/message 变更，不做历史修复，不声明全系统 DATETIME(3) 规则。

## 4. 验收矩阵

- 真实 MySQL strict/empty 两种 mode 覆盖 `.999499`、`.999500`、`.999999`、跨月/年、年份 1000/9999 端点和已是毫秒的输入；返回 1，status=PUBLISHED、error=NULL、attempts/nextRetry、身份、payload、createdAt 原样，`published_at` 与 `updated_at` 均等于同一精确 floor 值，无 warning。读取时间使用 `CAST(... AS CHAR)` 保留 non-strict 越界对照证据。
- unit 覆盖 null、年份 999、10000、`LocalDateTime.MIN/MAX` 无 JDBC 调用；合法输入验证两个时间参数相同、毫秒精度、eventId 双绑定和调用方对象不变。
- 保留现有 dispatcher/配置/FailureTime/SQL 与 DE Rabbit 回归；不新增 proof、routing、SQL 或 Schema 断言。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
