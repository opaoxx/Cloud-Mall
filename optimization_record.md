# CloudMall 优化记录

本轮已完成最小业务修复，并用同一套本地夹具做了运行时回归；性能 QPS 对比仍需后续重新执行完整阶梯压测。

## 记录 1：统一 SKU 快照 DTO

- 问题现象：购物车新增、普通下单返回 500；秒杀入口成功后异步订单消息重试并进入 DLX。
- 根因：product 返回对象型 `skuSnapshot`，cart/order Feign DTO 使用 `String`，Jackson 解码失败。
- 修改方案：以冻结 API 约定为准，将 cart/order 的快照字段改为结构化对象（或统一定义 common DTO）；为 product/cart/order 增加契约测试，覆盖对象型快照、空快照和字段兼容；消费者失败时保留可重放事件。
- 优化前：普通写接口 0 次成功；秒杀专项 20 次中 5 次入口成功、15 条异步消息进 DLX。
- 优化后：购物车新增、普通订单、支付成功均通过；秒杀 accepted 消息成功消费并落库，主队列为 0，修复后验证未新增 DLX。
- 对比压测：同一商品、同一库存、相同 JMX，比较成功率、DLQ 数、订单落库数和 P95/P99。

## 记录 2：订单分表自动化

- 问题现象：进入新月份时订单表不存在，创建订单失败。
- 根因：schema 只初始化固定月份，应用按当前时间动态路由。
- 修改方案：部署迁移预建当前月+未来 2–3 个月的 order/item/status 表；应用启动时校验当前表并报警；将建表迁移纳入版本化脚本，禁止测试时手工补表代替正式方案。
- 优化前：2026-09 普通下单无法落库。
- 优化后：启动初始化器自动创建当前月前一月至未来两个月的 order/item/status 表；日志确认覆盖 2026-08 至 2026-11，普通订单成功落到 202609 表。

## 记录 3：RabbitMQ JSON 事件一致性

- 问题现象：product 日志报告 Java serialized object 无法由 JSON converter 转换，2 条进入 product index DLQ。
- 根因：生产者和消费者消息转换器/content type 不统一。
- 修改方案：全局注册 Jackson JSON converter；事件 envelope 固定 `eventId/eventType/occurredAt/traceId/payload`；队列消费契约测试验证 content type、重试和 DLX；增加 DLQ 数量和消费延迟指标。
- 优化前：索引 DLQ=2（本轮夹具事件）。
- 优化后目标：新商品变更事件使用 JSON converter；历史 DLQ 未清理，需后续执行重放/清理策略验证。

## 记录 4：商品读尾延迟定位与优化

- 问题现象：商品列表/详情各 5 次 500，最大 RT 约 30 秒；成功样本 P95 22/17 ms。
- 当前根因判断：Prometheus 指向 `CannotGetJdbcConnectionException`，优先怀疑 Hikari 获取连接/数据库连接资源，而非直接认定慢 SQL。
- 修改方案：先加 Hikari pending/timeout、MySQL running threads、慢查询采集；确认后再调整连接池上限、查询索引/SQL 或 Gateway 超时。避免盲目扩大连接池造成 MySQL OOM。
- 优化前：列表/详情错误率均约 0.15%–0.19%，最大 RT 约 30 s。
- 优化后目标：相同 5 并发基线错误率 0，P99 不出现 30 s 尾延迟；再以 1/2/5/10/20 并发阶梯确认拐点。

## 本轮未完成的后续项

- 尚未重新执行完整 1/2/5/10/20 并发性能阶梯，因此没有新的 QPS/RT 对比数据。
- 库存服务对非 SKU 1 的 Redis 可售库存自动初始化仍是独立缺口；本轮仅用测试夹具初始化，建议下一批补充并记录。
