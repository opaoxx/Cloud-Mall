# CloudMall 瓶颈与故障现象分析

## P0：SKU 跨服务契约不一致，阻断购物车、普通下单和秒杀异步落单

商品服务 `/api/products/skus/{skuId}` 返回 `skuSnapshot` JSON 对象；cart 与 order 的 Feign `SkuView` 将该字段声明为 `String`。实测 `POST /api/cart/items` 和 `POST /api/orders` 均返回 500，库存保持 100、订单数为 0。order 日志给出明确证据：`MismatchedInputException: Cannot deserialize value of type java.lang.String from Object value`。

秒杀入口可以成功把请求写入 Redis/MQ，但 order 消费者在读取商品 SKU 时使用同一错误 DTO，20 次突发中 15 条消息重试后进入 `cloudmall.seckill.order.dlx`，导致“入口 accepted，但最终没有订单”的一致性风险。

根因归属：backend/product 与 backend/cart、backend/order 的 API DTO 契约没有共享或独立校验；不是 Redis Lua 原子性问题。

## P0：当前月订单分表未自动准备

本地日期为 2026-09-05，schema 只预建 `mall_order_202608`、对应 item/status 表。已在测试夹具中手动从 202608 建立 202609 表，否则普通下单会在生成订单号后访问不存在的表。长期方案不能依赖手工 SQL，应在部署迁移中预建未来窗口或实现受控的月度建表任务，并为分片路由添加启动检查。

## P1：商品读接口出现 30 秒级尾延迟和 500

基线中商品列表/详情各有 5 次 500，最大 RT 约 30 秒；Prometheus 将其标记为 product `CannotGetJdbcConnectionException`。成功请求 P95 只有 22/17 ms，说明问题更像数据库连接获取/连接池瞬时耗尽或请求排队，而非稳定的 SQL 执行慢。当前没有启用 MySQL slow query log，也没有足够长的时间序列，暂不能断言是慢 SQL。

建议下一轮同时采集 Hikari `connections.active/pending/timeout`、MySQL `Threads_running`、慢查询日志和服务线程池队列，区分数据库连接耗尽、JVM 停顿和 Gateway 超时。

## P1：RabbitMQ 消息没有丢失，而是消费失败进入 DLX

秒杀突发后主队列 `cloudmall.seckill.order.queue` 为 0，DLX 有消息；order 日志显示重试耗尽和 `RejectAndDontRequeueRecoverer`。这次现象是“消息已投递但业务消费失败”，不是 broker 丢消息。还需增加 DLQ 告警、事件状态表/补偿任务和消费成功确认指标，避免只看入口 200。

## P2：商品索引消息存在序列化/消费告警

product 日志出现 `content-type [application/x-java-serialized-object]` 无法由 JSON converter 转换，并有 2 条进入 `cloudmall.product.index.dlq`。商品查询本身仍可用，但索引异步链路没有闭环；应统一 RabbitTemplate 的 JSON message converter、事件 envelope 和消费者 content type。

## 尚未观察到的现象

- 超卖/负库存：本轮秒杀 Redis 库存从 5 到 0，没有负数；持久化库存因异步订单 DTO 失败未进入最终确认，不能宣称完整库存闭环通过。
- Sentinel 限流：本轮未观察到 429；项目当前运行证据不足以证明规则已配置生效。
- Seata 卡顿：Seata 客户端成功注册，未执行成功的普通跨服务订单事务，因此没有有效卡顿样本。
- ES 查询慢：商品查询实现走 MySQL 列表路径；本轮没有有效 ES 检索接口样本。
- Zipkin 慢链路：Zipkin 容器健康，但本轮未建立可用于归因的稳定 trace 样本。

## 定位结论

当前最先应修复的是跨服务 DTO 契约和分表生命周期。否则继续压测下单/秒杀只会重复制造 500 与 DLQ，无法测量订单、库存、支付的真实性能。修复后再进行组件级瓶颈判断。
