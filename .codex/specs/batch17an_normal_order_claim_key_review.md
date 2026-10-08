# CM-20260924-20：普通下单幂等键长度入口校验评审

## 1. 评审结论与范围

结论：**GO，限定修复 Order 普通下单入口缺少幂等 key 长度校验；不改变数据库 schema、API 字段、订单状态机、金额/时间、Seata 或库存流程。**

当前 `OrderServiceImpl.create` 只拒绝 null/blank key，129 code point 的 Idempotency-Key 仍会进入幂等查询、地址/Product RPC 和 reservation intent 持久化；`VARCHAR(128)`/SQL mode 可能导致数据库错误、静默截断或留下不完整预扣意图。

## 2. 入口门契约

在现有 blank/null 校验处新增 Unicode code point 长度检查：

- null/blank 继续沿既有 `INVALID`/400；
- code point 数超过 128 返回既有参数错误/400；
- 校验必须发生在 `findIdempotentOrderNos`、地址查询、Product RPC、Stock RPC、reservation intent 和任何订单/幂等写入之前；
- 合法 128 ASCII/Unicode/非 BMP emoji key 原样传递，不 trim、不大小写转换、不重建 key。

## 3. 不变边界

- 不改变 `Idempotency-Key` 请求字段名、Feign/API 签名、数据库列定义、金额/时间语义、Seata 事务、库存预扣和补偿状态机；
- 不把本轮扩展为 Mapper 通用列宽改造；若已有底层物理守卫继续保留，入口校验只负责公开请求早期拒绝；
- 不改变 Stock/Pay/Cart 等其他入口的 key 契约；不自动清理历史超长/截断记录。

## 4. 验收矩阵

1. 普通 create 的 null/blank key 仍返回 400，且不调用 Mapper/RPC。
2. 129 code point ASCII/Unicode/emoji key 在任何数据库、地址、商品、库存调用前返回 400，零 intent/订单/幂等事实。
3. 128 code point ASCII 和非 BMP emoji key 原样进入幂等查询、intent/订单流程；合法重复继续原幂等语义。
4. 金额、时间、地址、库存、Seata 和默认业务链路回归不变。
5. 现有订单库 checksum 和历史数据保持；不把输入校验失败当作数据库故障或库存未知。

## 5. 实施门与范围声明

实现限定在 `OrderServiceImpl.create` 入口参数校验及对应单测；不修改 Mapper、schema、API、业务状态机或其他服务。该修复只阻止新普通订单请求把超长 key 带入持久化/远程链路。
