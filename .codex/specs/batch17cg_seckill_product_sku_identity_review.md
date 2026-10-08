# CM-20260924-20：秒杀消费 Product SKU 身份绑定评审

## 1. 结论与范围

结论：**GO，限定 `consumeSeckill` 首次落单阶段的 Product 响应 SKU 身份与状态校验。**

当前消息 SKU 用于秒杀订单、库存事实和幂等记录，但消费端直接使用 Product 返回的 SKU 事实；响应带错 SKU 或错误 code/data 时可能把价格和明细写到错误商品。本批只保护首次消费落单，不改变既有消息身份、时间门、claim、恢复和已提交重放路径，也不接入普通订单或 Cart 清理链路。

## 2. 校验位置与失败语义

保留现有顺序：消息解析、事件身份/指纹、claim 和 occurredAt 时间门先执行；已有 `COMMITTED` 重复消息仍在 Product 查询前按原幂等路径收敛，不查询 Product。首次需要创建订单时，Product 查询只执行一次，并在任何订单对象、金额、明细、outbox、`markCommitted` 或 `markResolved` 写入之前验证：

- response envelope 非 null、code 精确为 `0`、data 非 null；
- `data.skuId` 非 null，且与消息 `skuId` 使用 `Objects.equals` 精确一致；
- 不用消息 SKU 覆盖或猜测 Product 响应 SKU，不 trim、归一化或接受别名。

null envelope、null data、非 0 code、null/错 SKU 均抛受控 `IllegalStateException`（或当前方法既有等价错误），沿原 outer catch 保留 cause，交由原 Rabbit retry/DLQ 流程处理。首次消费事务必须回滚本次 claim 之后的本地状态和所有订单事实；不调用后续 `markCommitted`/`markResolved`，不新增 RPC 或补偿动作。只有本批前已存在的已提交 claim=0、`COMMITTED` 等原分支事实按既有语义保留，不能声称同步撤回远端事实。

合法匹配继续使用 Product 返回的原价格、消息 quantity=1、原 occurredAt 和现有秒杀订单/明细/outbox/seckill dedup/recovery 写入。Product 价格 null、负值、scale、metadata 等其他校验保持原规则，不在本批扩展。

## 3. 兼容边界

- 不修改普通 `Order.create`、Stock、Cart、Seata、Product Feign DTO/API、数据库表、SQL、消息字段或状态枚举。
- 不改变 claim=0、事件身份错误、时间错误、Feign/Runtime/Error 异常分类和已有恢复器语义；异常 cause 必须保留，不吞 Error。
- 仅重命名本方法相关变量并补充 JavaDoc/步骤注释；不做其他方法重构。
- 首次错响应允许存在 Product 查询，但错误路径不得存在秒杀订单、明细、outbox、COMMITTED/Resolved 或后续库存/订单事实；Stock/Cart 均不得被调用。尚未提交的本次 claim 与本地事务一起回滚；只有既有已提交 claim=0、COMMITTED 等原分支事实按既有流程保留，不把新 claim 泛化为已提交事实。

## 4. 验收矩阵

- 真实 MySQL 事务 + Product/Stock/Cart 替身：错 SKU、null SKU、非 0 code、null envelope、null data 均回滚首次落单并保持完整 fixture facts；纠正响应后使用原事件可重试成功。
- 合法匹配验证订单 SKU、明细 SKU、原价格、quantity=1、occurredAt 和幂等记录；已 COMMITTED 重放验证 Product 零查询。
- 单元测试覆盖原异常 cause、Feign/Runtime/Error 不吞、claim/时间门优先级；普通创建与 Cart 相关回归保持独立执行。
- 不将无事务直接调用测试冒称为 claim 回滚；真实事务测试需核对秒杀订单、明细、outbox、dedup 和 recovery 记录。

## 5. 明确不承诺

本批不修秒杀价格语法/范围、库存远端网络、历史错误订单、Product 其他字段或普通订单链路，不提供真实 Rabbit/Feign 网络故障演练。
