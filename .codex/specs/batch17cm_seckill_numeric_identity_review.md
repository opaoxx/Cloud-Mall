# CM-20260924-20：秒杀数值身份无损解析评审

## 1. 结论与范围

结论：**GO，仅修复 `consumeSeckill` 对 userId/activityId/skuId 的数值解析精度。**

当前 `Number.longValue()` 会把 7.5 截为 7，把超出 long 的 BigInteger 截断为别的合法 ID；消息随后可能通过指纹、claim 和 Product 流程创建错误身份订单。本批只保护秒杀消费者解析，不改变指纹算法、消息字段、恢复器、Stock、普通订单或数据库结构。

## 2. 无损解析规则

三个字段统一使用私有无损转换：

- `Number` 使用 `new BigDecimal(number.toString()).longValueExact()`；`BigDecimal` 直接 `longValueExact()`，`BigInteger` 直接 `longValueExact()`，避免先转 double 或 `longValue()` 截断；
- `String` 保持原 `Long.parseLong` 语义，允许 `+` 号和前导零，不 trim；
- 非 Number、非 String 不猜测、不调用 `String.valueOf`；小数、非有限值、溢出和非法文本均拒绝。

解析必须早于 hash、claim、Product、订单和 `markResolved`。非法 userId 继续沿现有冲突证据路径使用 userId=0；非法 activityId/skuId 继续沿原合法 user 归属记录 INVALID_PAYLOAD。保留原正数/0/负数业务门，不把数值范围规则迁移或扩大到 parser。

## 3. 幂等与失败边界

- 已有 claim=0、hash/订单号核验和 `COMMITTED` 重放顺序保持；合法重复消息仍在 Product 前收敛。
- 非法解析不得认领新记录、查询 Product、创建订单或 Resolve；允许既有不可变冲突证据写入，不宣称所有 facts 零变化。
- 已持久化 held/COMMITTED 记录不因新解析器修改；纠正后的原事件仍按原指纹和身份流程处理。
- 不改变 `SeckillRequestFingerprint`、消息 JSON、状态、Recovery、Stock/Cart/Feign/DTO/API/DDL。

## 4. 数据库语义与验收

数据库 BIGINT 身份列继续由既有 DDL 表示；应用必须在写入和 hash 前保持 long 精度，不依赖数据库截断、回绕或非严格模式转换。真实 SQL + JSON Map 测试覆盖 7.5、2^64+7、alias 数值与合法 Long、异常用户归属、held/COMMITTED 重放和纠正重试；单测覆盖 BigDecimal/BigInteger/非有限值/非字符串、正负零和 cause/冲突路径。

## 5. 明确不承诺

本批不修改恢复器或 Stock 解析、不改变正数/负数业务约束、不修历史错误订单、不更改指纹算法、消息字段、表结构或真实消息网络行为。

本批 helper 只校验已经进入 consumer 的 Number/String 表示；Rabbit/JSON 转换器在接收前若已将原始数字文字转换为浮点并丢失精度（例如 `8.00000000000000001` 先变为 `8.0`），helper 无法还原。本批不修改消息转换器或解码配置；合法的数值整数表示（如 `8.0` 或 `BigDecimal(8.000)`）仍按数学整数兼容处理。“无损解析”仅指 helper 不再截断或回绕当前输入，不代表全链路原始数字文字可恢复。

冲突证据的 `payload` 仍沿现有 MySQL JSON 存储语义；超出数据库 JSON 精确数值表示范围的大整数可能转换为浮点并丢失数值精度，例如 `18446744073709551624` 可能保存为 `1.8446744073709552E19`（对应数值已变化），原数值与原数字文字保真均未解决。本批只保证进入 consumer 后的 helper 身份转换不截断、不回绕，保留身份拒绝及其余事实不变；JSON payload 表示保真、DDL 或证据台账迁移属于独立问题，不在本批扩大。
