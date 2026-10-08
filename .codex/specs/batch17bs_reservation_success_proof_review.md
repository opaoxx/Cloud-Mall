# CM-20260924-20：普通预扣成功响应证明评审

## 1. 评审结论与范围

结论：**GO，限定为普通订单首次 Stock reserve 成功响应增加持久身份/状态证明。**

当前 `OrderServiceImpl.create` 只检查 reserve envelope code=0，就设置 `reservationConfirmed=true` 并调用 `markReserved`。成功响应 data 可能缺失、结构错误、orderNo 错误或 status 非已接受状态，导致本地订单创建流程错误推进。

本轮只保护 code=0 的成功响应，不修改 Stock/Feign/API 字段、构造器、错误码、SQL/schema、事务、BR 拒绝分类、BQ 补偿、异步恢复、时间、中断或历史数据。

## 2. 成功响应证明契约

在现有 reserve code=0 门之后、设置 `reservationConfirmed=true`、`markReserved` 或继续订单事务之前，调用私有 helper（名称可等价）验证 data：

1. 使用已有 Jackson `ObjectMapper.valueToTree(data)` 转为 JSON tree；转换失败/抛 `IllegalArgumentException` 返回证明失败；
2. tree 必须为 object；
3. `orderNo` 和 `status` 节点都必须 `isTextual()`；
4. `orderNo` 必须与原订单号 `Objects.equals(originalOrderNo, node.textValue())` 精确相等；
5. `status` 只能精确为 `RESERVED` 或 `ALREADY_RESERVED`。

不接受 null、缺字段、数字/布尔/数组/字符串根、错单、大小写/重音/尾空格 alias、其他状态或转换异常。只校验 orderNo/status，不校验 items 数量、用户或库存明细。

## 3. 失败与成功路径

### 3.1 证明失败

证明失败时保持 `reservationConfirmed=false`，抛既有 `ORDER_RESERVATION_UNKNOWN`/202，进入原 create catch：

- 只调用一次 `markReserveUnknown`；
- 不调用 `markReserved`、rollback、额外 GET、reserve、购物车操作或订单/幂等/outbox 写入；
- 若 helper 具有原始转换异常，应沿现有异常 suppressed 语义保留，不吞 Error。

### 3.2 证明成功

合法 Map、StockReservationVO 或等价 JSON object 的 RESERVED/ALREADY_RESERVED 证明通过后，继续原 `reservationConfirmed=true`、`markReserved`、订单/明细/幂等/outbox/购物车和 COMMITTED 事务流程；不新增 DTO、转换 API 或 Clock。

## 4. 兼容与排除边界

- 保留 Feign `ApiResponse<?>` 签名、JSON 字段、HTTP/错误码、Stock 成功 code=0 语义和 BR 专用拒绝分类。
- 不修历史库存/intent，不增加 Stock 查询、不改变 Q/BQ 补偿或异步 BN/BP 恢复。
- 不引入新中断策略，不校验 items/user/数量，不修改 schema、SQL、事务和构造器。
- 正常测试中已有 null/旧 Map `{reserved:true}` 等忽略 data 的 stub，应改为真实 orderNo/status 结构；异步路径不依赖该响应的 stub保持原样。

## 5. 验收矩阵

1. 真实隔离 MySQL + REQUIRES_NEW intent + Stock 替身覆盖 data 缺失、null、根形状/字段类型错误、错单/alias、状态错误和非法结构：均 202/UNKNOWN，intent 保留非终态错误/退避，订单/明细/幂等/outbox 无残留，且无 markReserved/rollback/额外 GET。
2. 合法 Map 和 StockReservationVO 的原订单号 + RESERVED/ALREADY_RESERVED 均沿原事务成功；确认正常 order/intent/action 事实不变。
3. unit 验证 helper 转换失败/IllegalArgumentException、null/error data、证明失败 suppressed 与原错误路径；不吞 Error、不新增调用。
4. 回归 BR 拒绝分类、BQ 补偿、普通创建精度、intent strict、Recovery/Reader、原订单 checksum 和 fixture 清理；Stock 为替身时不宣称真实远程故障或资金事故。

## 6. 明确不承诺

本轮只证明普通 reserve 成功响应的原订单身份和 RESERVED 状态，不提供库存 items 全量一致性、跨服务签名、历史数据修复或全链路资金 exactly-once。
