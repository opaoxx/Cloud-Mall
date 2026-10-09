# CM-20261009-17DI：订单 Outbox 失败写时间毫秒边界评审

## 1. 结论与范围

结论：**GO，仅在 `OrderOutboxMapper.recordOutboxFailure` 入口把两个 DATETIME(3) 参数按项目毫秒协议向下归一。**

当前 failure UPDATE 直接绑定调用方的 `nextRetryAt` 和 Mapper 自身采样的 `updatedAt`，纳秒部分交给 MySQL `DATETIME(3)`。MySQL 在目标列精度不足时默认会舍入，可能跨秒、月或年份边界；官方文档说明，较低 FSP 的时间写入会舍入且默认无 warning，只有 `TIME_TRUNCATE_FRACTIONAL` 才改变为截断（[MySQL fractional seconds 文档](https://dev.mysql.com/doc/refman/8.0/en/fractional-seconds.html)）。本项目已冻结应用侧向下截断毫秒，因此不能依赖全局 SQL mode。

本批只保护 failure writer 的两个时间绑定；不扩展 insert、`markOutboxPublished`、scan、其他服务时间路径或全系统时间协议。

## 2. Mapper 时间参数契约

`recordOutboxFailure` 方法签名、SQL 文本、参数绑定位置、返回行数、WHERE 资格、错误裁剪和 attempts 饱和保持不变。进入 `JdbcTemplate.update` 前按固定顺序执行：

1. 校验并归一调用方传入的 `nextRetryAt`：非 null，年份必须在 `1000..9999`，返回 `nextRetryAt.truncatedTo(ChronoUnit.MILLIS)`；
2. 只采样一次 `LocalDateTime.now(Asia/Shanghai)` 作为 `updatedAt`，校验年份 `1000..9999`，同样 `truncatedTo(ChronoUnit.MILLIS)`；
3. 将两个归一后的上海 `LocalDateTime` 绑定到原 SQL 的 `next_retry_at`、`updated_at` 参数位置。

归一只能向下截断纳秒，不能四舍五入、timezone 转换、取代调用方时间、重新采样 retry 时间或修改调用方对象。`nextRetryAt == null`、`LocalDateTime.MIN/MAX`、年份小于 1000 或大于 9999 统一在第一次 JDBC 调用前抛 `InvalidDataAccessApiUsageException`（`DataAccessException`）；即使 eventId 不存在、状态非 PENDING、类型不符或 attempts 负数，也必须先拒绝非法时间参数，不能依赖 SQL WHERE 短路。

## 3. 兼容与边界

- `clipOutboxError`、`attempts` 饱和、eventId 双绑定、状态/type/counter 精确资格和返回 `0/1` 语义不变。
- Dispatcher DH 仍负责用 raw nanosecond `now + delay` 计算并记录日志；Mapper DI 只在 JDBC 边界向下归一，不能让 Service 改用替代时间或宣称日志时间等于存储时间。
- DG 正数配置、默认值和无任意 cap 保持；DF row count、DD publication proof、DE TTL/DLX 路由、CU/CS 控制流，以及 SQL/Schema/API/message 不变。
- 不修改 DDL、SQL mode、系统时钟或全局 `TIME_TRUNCATE_FRACTIONAL`，不扩展为全系统时间归一规则，不声明 DATETIME(3) 以外的舍入边界。

## 4. 验收矩阵

- 真实 MySQL 隔离库在 `STRICT_TRANS_TABLES` 和空 SQL mode 下覆盖 `.999499`、`.999500`、`.999999`、跨秒/月/年以及合法 1000、9999 端点；数据库保存值均为应用向下毫秒结果，无 warning，其他字段保持。
- unit 使用 JdbcTemplate seam 捕获两个时间参数：均为毫秒精度，`nextRetryAt` 的输入对象未被修改；`null`、年份 999、10000、`LocalDateTime.MIN/MAX` 均在 JDBC 前拒绝。
- 既有 34 条 SQL IT、17DE 两条真实 Rabbit case 与 dispatcher 回归保持；本批不验证 insert/markPublished/scan 或全系统时间路径。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
