# CloudMall 性能基线报告

## 测试结论

本报告记录 2026-09-05 本地环境的首次可重复基线。测试不是生产容量结论：业务服务运行在本机 Java 进程，Docker 中间件运行在本地，JMeter 位于 `E:\apache-jmeter-5.6.3\bin`。普通订单/购物车写链路在回归阶段已被契约解码错误阻断，因此没有把写接口伪装成成功基线。

## 环境与夹具

- Gateway：`localhost:8080`；user/product/cart/order/stock/pay：`8081`–`8086`
- Docker 中间件全部 healthy；Prometheus `9090` 的 7 个服务 target 均为 `up`
- 测试商品：`productId=5643018474422449472`，`skuId=900001`
- 普通库存：100；秒杀夹具：5；使用隔离测试用户和唯一幂等键
- order/pay 在 Java 21 下以 `--add-opens java.base/java.lang=ALL-UNNAMED` 启动，原因见瓶颈报告

## 低风险普通接口基线

JMeter 计划：每个接口 5 并发线程，5 秒 ramp-up，持续 30 秒；三个 Thread Group 并行运行。总请求 49,626，平均吞吐约 1,269 req/s，总错误 10（0.02%）。各接口窗口按本采样器首尾时间计算，因 Thread Group 并行，数值用于本地相对比较，不作为容量上限。

| 接口 | 请求数 | 近似 QPS | 平均 RT | P95 | P99 | 最大 RT | 错误 |
|---|---:|---:|---:|---:|---:|---:|---:|
| GET `/api/products` | 2,663 | 296.81 | 69.45 ms | 22 ms | 28 ms | 30,018 ms | 5 |
| GET `/api/products/{id}` | 3,301 | 367.88 | 55.99 ms | 17 ms | 23 ms | 30,043 ms | 5 |
| GET `/api/cart` | 43,662 | 1,459.49 | 3.15 ms | 6 ms | 9 ms | 40 ms | 0 |

注意：商品两个接口的平均值被 5 个约 30 秒的失败请求拉高；成功请求的 P95/P99 仍较低。Prometheus 将这些失败标记为 product `CannotGetJdbcConnectionException`，需要在后续复测中单独验证连接池/数据库瞬时耗尽，而不能只看平均值。

## 秒杀入口突发

JMeter 计划：20 线程、1 秒 ramp-up、每线程 1 次请求、唯一 `Idempotency-Key`；活动库存 5，单用户限购在该专项夹具中设为 100，以便观察库存竞争。

| 指标 | 结果 |
|---|---:|
| 总请求 | 20 |
| 200 成功 | 5 |
| 500 | 15 |
| 平均 RT | 28 ms |
| P95/P99 | 35 ms / 45 ms |
| Redis 秒杀库存 | 5 → 0 |
| RabbitMQ `cloudmall.seckill.order.dlx` | 1 → 6（含前序单次验证） |

5 次入口成功说明 Redis Lua 预扣的竞争结果符合库存上限，没有观察到负库存；15 次 500 并非售罄业务响应，而是异步订单消费者的 Feign DTO 解码失败，详见 `bottleneck_analysis.md`。

## 原始证据

- JMeter 计划：`.codex/runtime/perf-test-baseline.jmx`、`.codex/runtime/perf-test-seckill.jmx`
- JMeter 结果：`.codex/runtime/jmeter-baseline/results.jtl`、`.codex/runtime/jmeter-seckill-2/results.jtl`
- 服务日志：`.codex/runtime/perf-test-logs/`
- Prometheus：`http://localhost:9090`；Zipkin：`http://localhost:9411`；Kibana：`http://localhost:15675`

## 后续基线规则

修复 SKU DTO 契约和当前月分表自动创建后，按相同 JMX 重新跑一轮，增加 1/2/5/10/20 并发阶梯；每次记录成功/业务失败/系统错误三类，不将 409 售罄与 500 混合统计。
