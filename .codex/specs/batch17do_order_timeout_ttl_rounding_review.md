# CM-20261009-17DO：订单 Outbox Future TTL 余量向上取整评审

## 1. 结论与范围

结论：**GO，仅把 future timeout 的正 Duration 转毫秒从 floor 改为 ceil，避免数学取整本身缩短正 TTL。**

当前 `remainingTtlMillis` 对正 Duration 调用 `Duration.toMillis()` 并以 `max(1, ...)` 处理；`1ms+1ns` 变成 1ms、`10ms+.999ms` 变成 10ms，发送期限短于原 `expireAt`。本批只改变 future 正余量的数学取整；zero/negative 仍返回 0 并沿 17DE 既有 DLX、`expiration=null` 路由。

## 2. 纯函数与 Dispatcher 契约

订单模块新增 `util.OrderTimeoutTtl` 纯工具：private constructor，`public static long remainingMillis(Duration duration)`。该方法不读取 clock、offset、SQL、Rabbit 或配置：

- 参数 null：沿 Java 原生 `NullPointerException` 契约；
- negative/zero：提前返回 `0`，不调用 `toMillis`；
- positive：取得 `wholeMillis = duration.toMillis()`；若纳秒余量不为整毫秒，使用 `Math.addExact(wholeMillis, 1)`，否则返回 `wholeMillis`；转换或加一溢出原样抛出 `ArithmeticException`。

Dispatcher 保留原 `OffsetDateTime.now(+08:00)` 单次采样、offset/Shanghai LocalDateTime 解析、expireAt 原文本、envelope、future exchange、正 expiration、confirm/returned、published 计数、CU/CS、SQL/17DD proof 和 17DE zero/negative route。现有 `remainingTtlMillis` 的等价 negative/zero/max1 逻辑可先提取接入 helper，再只替换 helper 的 future 正余量取整；不得注入 clock、反射私有方法或改变 route/consumer。

正余量转换或 `Math.addExact` 溢出继续原 send/parse failure 路径：不猜测到期、不发送、不写 PUBLISHED；不承诺真实 broker、clock、transport、queue、consumer 或 future FIFO 队头问题被闭环。

## 3. 兼容与不变量

- 相对 `Duration.toMillis()` 的 floor，有纳秒余量时加 1；相对旧 `max(1, floor)` 逻辑，`0<duration<1ms` 仍为 1，大于 1ms 且有余量时提高 1，整毫秒保持不变。zero/negative 结果与 17DE 保持 0。
- 不改变 DI/DJ 时间写入、DF failure rows、DD proof、DG 配置、SQL/Schema/API/message/topology/Service consumer。
- 不把 Duration ceil 扩展到数据库时间、订单事实时间或其他服务的 TTL/退避逻辑。

## 4. 验收矩阵

- unit 直接验证 public helper：fractional 1ns、999999ns、整 1ms、1ms+1ns、1.999999ms、999.999999ms、整秒、30 分钟带余量、`Long.MAX_VALUE` 整毫秒和 `MAX-1ms+.999ms`；zero 与巨大 negative 先返回 0；`toMillis` 溢出及 `Long.MAX_VALUE ms + 1ns` 的 ceil 溢出抛 ArithmeticException。测试不依赖 clock、反射或时序。
- 保留 40 SQL IT、29 dispatcher、4 config、2 failure、2 publication 与 17DE 两条真实 Rabbit case；正式 red 先固定 Dispatcher 等价 floor 接入，再只修改 helper ceil。旧 route、confirm、proof、中断和 SQL 回归保持。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
