# CM-20261009-17DG：订单 Outbox Dispatcher 正数配置启动校验评审

## 1. 结论与范围

结论：**GO，仅在 `OrderOutboxDispatcher` 构造阶段拒绝三个非正调度参数，避免服务以不可解释的扫描、confirm 或退避节奏启动。**

当前构造器读取 `batch-size=50`、`confirm-timeout-ms=5000`、`retry-delay-seconds=10`，但未要求正数：Mapper 会把非正 limit 隐式归一为 1，非正 confirm timeout 可能立即失败，非正 retry delay 会把失败退避放到当前或过去。校验只属于 constructor fail-fast，不改变运行中的调度流程、SQL、Rabbit 路由或消息契约。

## 2. 配置读取与 fail-fast 契约

保留 `Environment.getProperty` 的现有 property 名、类型转换、默认值和读取顺序：

| property | 当前类型/默认值 | 正数含义 |
| --- | --- | --- |
| `cloudmall.order.outbox.batch-size` | `Integer` / `50` | 每轮扫描上限 |
| `cloudmall.order.outbox.confirm-timeout-ms` | `Long` / `5000L` | publisher confirm 等待毫秒数 |
| `cloudmall.order.outbox.retry-delay-seconds` | `Long` / `10L` | failure 退避秒数 |

读取三个值后、调用 `rabbitTemplate.setMandatory(true)` 前，统一要求值 `> 0`。任一值为 `0`、负数或对应类型最小值时，立即抛 `IllegalArgumentException`；异常文本必须包含对应 property 名和原始数值，便于启动日志定位。非法配置路径不得调用 `setMandatory`，不得扫描 Mapper、发送 Rabbit、执行 SQL 或修改 RabbitTemplate 状态。

合法正值原样保存并继续既有行为：默认配置仍扫描 50、等待 5000ms、退避 10 秒；自定义正值分别继续作为 scan limit、`Future.get(timeout, TimeUnit.MILLISECONDS)` 和 retry 时间使用。`findPendingOutbox(limit)` 的非正归一为 1 规则不改，因为构造器校验只保护 dispatcher 配置，不改变 Mapper 公共方法语义。

校验不引入 Bean Validation 依赖，不增加上限、不统一处理 `dispatch-interval-ms`，不把超大正值的 duration 溢出或配置导致的性能问题扩展为本批范围。

## 3. 兼容与不变量

- 构造器仍接收原有四个参数，字段、property 名、默认值和 Rabbit mandatory 语义不变；仅新增正数边界。
- scan、parse、future/confirm、failure writer、17DD publication proof、17DE TTL/DLX 路由、中断/数据库异常隔离、SQL、Schema、API、消息和 topology 均不变。
- 本批只保证非正配置在 dispatcher 实例化时早失败，不宣称生产服务已启动、所有配置组合性能可接受或任意正值都不会在后续时间计算中溢出。

## 4. 验收矩阵

- unit：三个 property 分别覆盖 `0`、`-1` 和对应类型 `MIN_VALUE`，每种非法配置都抛包含 property 名/原值的 `IllegalArgumentException`，且无 Mapper/Rabbit 调用、无 `setMandatory` 副作用。
- unit：默认值、自定义正值和最小正值通过实际行为 seam 验证 scan limit、confirm `Future.get` timeout + `TimeUnit.MILLISECONDS`、retry 时间；不通过反射断言 private 字段。
- 回归既有 33 条 SQL IT、28 条 dispatcher unit 和 17DE 两条真实 Rabbit 3.11 case；不新增 Schema/API/message/routing/proof 断言。

## 5. 交付边界

本评审只冻结规格，backend 按本文件和 API/DB 相邻条款实现；本轮不修改业务代码、测试或 Maven 配置。
