# CM-20261007-20：秒杀死信 ACK/NACK 异常边界评审

## 1. 评审结论与范围

结论：**GO，限定拆分 `consumeDeadLetter` 的持久化、延迟重投和 ACK/NACK 异常边界；不改变 API、schema、状态、Rabbit 拓扑或 JSON 幂等。**

当前 try/catch 将 persist、basicAck、republish 和第二次 basicAck 混在同一异常路径：持久化成功但 ACK IOException 会被误当作持久失败，导致重复 republish/ACK；重投 confirm 成功后的 ACK 异常又可能继续进入 nack，产生同一 deliveryTag 多次确认操作。

## 2. 三段式结果契约

### 2.1 持久化失败

- `persistDeadLetter` 失败时才进入一次 `republishForDelayedRetry`；
- 延迟重投 confirm 成功后只尝试一次 `basicAck(deliveryTag,false)` 并返回；
- 延迟重投失败时只尝试一次 `basicNack(deliveryTag,false,true)` 并返回；nack 失败抛出包含原重投异常和 nack 异常 suppressed 的 IllegalStateException；
- 持久化失败路径不得在 republish 前 ACK，不重复 republish，不在 ACK 异常后再 NACK。

### 2.2 持久化成功

- 持久化成功后只尝试一次 `basicAck`；
- ACK IOException/Channel 异常直接抛出带原 cause 的 IllegalStateException；
- 不再次 ACK、不 NACK、不 republish。结果未知交给 Rabbit 容器/连接恢复与已有 eventId/JSON 幂等事实处理。

### 2.3 重投成功后的 ACK

- publisher confirm 成功表示延迟副本已获得发送确认，随后只尝试一次原 deliveryTag ACK；
- ACK 失败只抛出保留 cause 的异常，不再 NACK/republish；
- 不宣称 exactly-once，也不保证已送出的 ACK 可以撤回；重复 delivery 依赖原恢复 eventId/hash/payload 幂等。

## 3. 调用顺序与异常保留

调用顺序必须可由 Mockito 精确验证：

```text
persist fail → delayed publish → confirm → ACK
persist fail → delayed publish fail → NACK(requeue=true)
persist success → ACK
```

每条分支对同一 deliveryTag 最多一次 ACK 或 NACK。Channel ACK IOException 不得被外层当作 persist failure；publisher confirm/returned 失败不得先 ACK。nack IOException 必须保留 retryFailure 为 cause，并将 nackFailure 作为 suppressed；其他原始异常不得静默吞掉。

## 4. 不变边界

- `saveDlq` 的严格 INSERT、JSON 幂等、eventId/hash 身份和 17N 时钟保持不变；
- DLQ 原始字节、重投 header、eventId、状态和 recovery lease 不变；
- 不新增 API、状态、表字段、Rabbit exchange/queue 或消息格式；
- 不能将 ACK 异常归类为数据持久化失败，也不能把结果未知声称为成功/失败；
- 真实 broker 网络断连、连接恢复和分布式 exactly-once 不在本轮宣称范围。

## 5. 验收矩阵

1. persist 成功 + ACK IOException：只调用一次 ACK，零 NACK/republish，异常保留 cause。
2. persist 失败 + delayed publish confirm 成功 + ACK IOException：只调用一次 republish 和 ACK，零 NACK，异常保留 cause。
3. persist 失败 + delayed publish 失败：只调用一次 republish 和一次 requeue NACK；nack 失败时异常含 suppressed。
4. persist 成功、重复 eventId/JSON 幂等、非法 JSON/严格保存失败：ACK/NACK 调用次数和顺序符合上述契约。
5. 真实 MySQL handler IT 验证持久化/重投基线；Channel/RabbitTemplate 替身验证异常分支，不将其表述为真实 broker 网络验收。
6. 17I/P/Q/N 相关 JSON、严格保存、时钟、身份和原始字节回归保持通过。

## 6. 实施门与范围声明

实现限定在 `consumeDeadLetter` 异常边界和 JavaDoc/测试；不改变数据模型、消息拓扑、状态机、租约或历史记录。本轮只消除重复 ACK/NACK/republish 的错误分支，不解决 exactly-once 或已发消息撤回问题。
