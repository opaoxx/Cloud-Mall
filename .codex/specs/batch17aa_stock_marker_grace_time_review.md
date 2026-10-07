# CM-20261007-20：Stock 秒杀 marker grace 时间一致性评审

## 1. 评审结论与范围

结论：**GO，限定修复 `SeckillOrderOutboxDispatcher.isPastMarkerGrace` 对 MySQL DATETIME、Timestamp 和 offset 时间的识别及时区语义；不改变扫描排序、UNKNOWN 状态策略、outbox lease、重试或 API。**

当前方法只处理 `Timestamp.toInstant/OffsetDateTime`，真实 MySQL DATETIME 常映射为 `LocalDateTime`，会被保守忽略，旧 PENDING marker 丢失后不能按 grace 转 UNKNOWN；Timestamp 直接依赖 JVM 默认时区也可能偏离项目 Asia/Shanghai DATETIME 语义。

## 2. 时间类型与 Clock 契约

Dispatcher 增加私有 `Clock markerClock = Clock.systemUTC()` 或等价测试接缝，不注册 Bean、不外部配置、不改变 public 构造器/API。每次判断只采样一次 `markerClock.instant()`，并按以下规则将 updated_at 转为 instant：

- `LocalDateTime`：按项目固定 `+08:00`/Asia/Shanghai DATETIME 语义解释后转 instant，不引入历史 DST 转换；
- `java.sql.Timestamp`：先 `toLocalDateTime()`，再按项目固定 `+08:00`/Asia/Shanghai 解释，不把 JDBC DATETIME 当带时区 Instant；
- `OffsetDateTime`：直接使用其 same-instant `toInstant()`；
- null、未知类型或转换异常：保守返回 false，不猜当前时间、不修改状态。

只在 `markerClock.instant() - updatedInstant > 30 seconds` 时返回 true；恰好 30 秒不算过期。负时差、未来时间和精度转换异常均返回 false。计算不得调用 JVM 默认时区或多次读取 clock 造成边界漂移。

## 3. 业务边界

在现有 Redis marker 缺失、PENDING 及旧 marker 恢复逻辑中，仅替换 grace 时间判断；超过 grace 后仍沿既有路径标记 UNKNOWN，不创建 accepted marker、不创建 outbox、不猜测订单成功。未超过 grace 继续保持 PENDING/原事实。

不改变扫描排序、候选窗口、UNKNOWN 后续恢复、outbox lease、重试次数、Redis marker 语义或 Stock API。UNKNOWN 长期占据扫描窗口/饥饿问题另行评审，本轮不宣称整体解决。

## 4. 验收矩阵

1. 真实 MySQL DATETIME 映射 LocalDateTime，Redis marker 缺失且 updated_at 超过 30 秒：转 UNKNOWN，且无 accepted marker/outbox 新增。
2. 同一 fixture updated_at 距 markerClock 恰好 30 秒：不转 UNKNOWN；超过 30 秒才转。
3. LocalDateTime、Timestamp、OffsetDateTime 分别在 UTC/GMT+14 JVM 默认时区下验证仍按 Shanghai/same-instant 规则；不调用系统默认时区。
4. null、未知类型、未来时间、负时差和转换异常保守 false，不更新业务状态。
5. 默认扫描排序、UNKNOWN 恢复、outbox/lease/重试和原库 checksum 回归不变。
6. Clock fixed 单测与真实 MySQL marker 场景通过；不将替身 Redis/Rabbit 视为真实网络故障验收。

## 5. 实施门与范围声明

实现限定在 marker 时间转换 helper、私有 Clock 和 grace 判断；不新增 schema/API/状态，不修改 Stock marker/outbox 模型或整体扫描公平性。本轮只修时间类型/时区导致的 marker grace 误判。
