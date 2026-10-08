# CM-20260924-20：普通订单购物车清理 After-Commit 评审

## 1. 评审结论与范围

结论：**GO，限定修复普通订单创建事务内提前删除购物车导致订单回滚后购物车事实丢失的问题。**

当前 `OrderServiceImpl.create` 在本地订单、明细、幂等、outbox 和 COMMITTED intent 完成前调用 Cart 删除；后续本地失败/回滚会留下订单未提交但购物车已删除的事实，Cart 失败还可能触发已预扣的反向补偿。

本轮只调整普通 create 的 Cart cleanup 时序，不修改 Cart API/Feign、SQL/schema、订单/intent 状态、Seata、Stock、BR/BS/BQ、消息或其他创建路径。

## 2. After-Commit 契约

### 2.1 注册时机与快照

移除 create 本地事务中的直接 `cart.deleteItem` 循环。创建流程在原 `markCommitted(orderNo, ...)` 成功后，使用 Spring `TransactionSynchronizationManager` 注册现有事务的 `afterCommit` 回调：

- 只在存在 active actual transaction 且 synchronization active 时注册；
- 若没有可注册的实际事务/同步上下文，不提前删除购物车，只记录告警并跳过；不新增 API 错误；
- 回调闭包捕获不可变 `userId` 和本次请求的不可变去重 SKU ID 列表；不从可变 req 或事务对象重新读取；
- 每个 SKU 最多调用一次既有 `CartClient.deleteItem(skuId,userId)`。

注册失败/无同步上下文不能让已提交订单进入 Stock 补偿；只记录告警并保留购物车事实。

### 2.2 回调异常

afterCommit 回调逐 SKU 执行：

- response null、非 0 code 或 RuntimeException：记录 warn，继续后续 SKU，不抛回已提交 create，不调用 Stock rollback，不重复当前 SKU；
- `Error` 不吞，沿现有 JVM/回调异常语义传播；不伪造清理成功；
- Cart 删除只发生在 outer 本地事务成功 commit 之后；事务 rollback 时零 Cart 删除。

购物车清理失败后本批不新增持久重试、补偿表、消息或条件删除语义；全量 Cart 持久化闭环另行评审。

## 3. 创建成功/失败边界

- `markCommitted` 之前任何本地失败、订单 CAS/写入失败、find 失败或补偿失败：不执行 Cart delete；沿原订单/intent/Stock 失败流程。
- `markCommitted` 成功后创建响应结构、HTTP code、订单和 intent 提交语义保持；Cart 在 afterCommit 清理，Cart 依赖故障不把已提交订单改成 500，也不触发 Stock rollback。
- 不改变 Product、User、Stock、订单、明细、幂等、timeout outbox 和 COMMITTED intent 的事务顺序与数据事实。

## 4. 兼容与排除边界

- 不新增 Cart API/Feign 字段、数据库表、SQL、消息、状态或 Seata 方案；不改 Cart 服务。
- 不实现购物车清理持久重试、并发重新加入 SKU 条件删除、CM15 持久性闭环或历史购物车修复。
- 不扩展秒杀、补偿 rollback、库存恢复、支付和其他入口。
- 正常成功响应保持原结构和 code=0；Cart 清理从同步成功门改为提交后告警语义，属于本批明确契约调整。

## 5. 验收矩阵

1. 真实 Spring 代理/实际事务 + MySQL + Cart/Stock 替身：事务尚未提交时 Cart delete 为 0；afterCommit 独立观察连接可看到订单、intent COMMITTED 后才逐 SKU 删除。
2. 外层事务 rollback：Cart delete 为 0，订单/intent 按原回滚；不触发 Stock rollback。
3. 本地 findOrder/后序失败触发原补偿：Cart 为 0，订单/明细/幂等/outbox 无残留，原 intent/Stock 补偿语义保持。
4. afterCommit 首个 SKU null/non0/Runtime 失败：订单已提交、后续 SKU 仍各调用一次；不抛回 create、不调用 Stock rollback；Error 保持传播。
5. 无 actual transaction/synchronization 的 unit：不删除 Cart；验证 userId/SKU 快照不可变、重复 SKU 只调用一次；回归正常 create、BR/BS/BQ、checksum 和 fixture 清理。

## 6. 明确不承诺

本轮只保证 Cart 删除不早于订单事务提交，不保证 afterCommit 后的 Cart 依赖最终一致、持久重试、跨服务 exactly-once 或整个购物车系统的持久性闭环。
