# CM-18 16T31：Stock 秒杀 reservation 原订单身份只读校验

## 1. 结论与范围

本切片 GO，基线为 `cf13760`，只修正 Stock GET 及其 Order legacy fallback 对 CI 候选误认原订单的问题。原查询按 `order_no` 返回候选，但秒杀 settlement、主 legacy `ACCEPTED` 和 Stock fallback legacy 分支没有逐行核对返回行的原始 `order_no`，大小写、重音、尾随空格或跨订单候选可能被包装为有效终态，进而影响旧 Order 的 `SUCCEEDED` 判断。

本批只增加三个 read guard：秒杀 settlement 行、该方法无 settlement 时的 legacy `ACCEPTED` 行、以及 `StockServiceImpl.reservationResult` 的 fallback legacy 行；不改变写入、迁移、recovery、CAS、Redis、RabbitMQ、业务 API、DDL、全局序列化或其它维度校验。

## 2. `SeckillQuotaSettlementService.reservation` guards

当 `SeckillQuotaSettlementService.reservation(requestedOrderNo)` 读到非 null settlement 时，必须在既有 `validateSettlement` 前执行：

```text
Objects.equals(originalOrderNo, requestedOrderNo)
```

`originalOrderNo` 必须来自本次既有 settlement SELECT 返回的原始列，不使用请求 orderNo 回填或 echo 代替。非 null settlement 的 null、非文本、大小写/重音/尾随空格不精确相等候选，统一返回以请求 orderNo 为 echo 的 `StockReservationVO(requestedOrderNo, "UNKNOWN", items=[])`；不得泄露候选原值，也不得把非 null 错误候选转成 `return null` 让 Stock outer 误走普通路径，更不得包装成请求 orderNo 的有效 `CONFIRMED`/`ROLLED_BACK` 或其它终态。精确合法候选的数量、状态和响应保持。

该 guard 位于 `validateSettlement` 之前，但仍只读一次既有 SELECT；不新增 BINARY 过滤、SQL、事务或锁，不改变 settlement 状态映射或写入方法。

当同一方法没有 settlement 时，读取旧 legacy Map；只有在 `status=ACCEPTED` 分支中，先比较 Map 的原始 `order_no` 与请求 orderNo，再调用既有 `requiredPositive` 和包装 `RESERVED`。非 null `ACCEPTED` 身份不匹配返回以请求 orderNo 为 echo 的 `StockReservationVO(requestedOrderNo, "UNKNOWN", items=[])`，不得 return null 伪装 missing 或进入 Stock outer 普通路径；非 `ACCEPTED` 继续原 `return null` 语义。

## 3. Stock fallback legacy guard

### `StockServiceImpl.reservationResult` fallback legacy

Stock fallback 读取旧 legacy reservation 时，先校验实际返回行的原始 `order_no` 与请求 orderNo 精确相等，再处理任何原 status。非 null 行的 null、非文本、错 order、case/accent/trailing-space alias 均返回以请求 orderNo 为 echo 的 `UNKNOWN/items=[]`，不得把错误行伪装成 missing 或让 Order fallback 映射 `SUCCEEDED`；精确行保持原状态语义：`REJECTED` 仍为 `REJECTED`，`PENDING` 仍为 `UNKNOWN`，其它既有状态沿原映射。该 guard 不改变普通 flows、request 分支或其它既有 identity/数量规则。

Order `legacyStockActionStatus` 消费实际 Stock GET 时，三类身份失败均不得映射为 `SUCCEEDED`，不发 Stock 写调用；精确合法的三种状态和原 fallback 行为保持。测试可使用实际 Order/Stock 读取代码与 StockClient 桥接，但不宣称四服务 HTTP/TC/真实资金全链。

## 4. 不变边界

- 不改 settlement/legacy 的写方法、迁移 `ensureTable`、recovery、CAS、Redis、MQ、业务 API、DDL、索引或全局序列化。
- 不新增 SQL 或参数，不把异常候选在 SQL 层 BINARY 过滤掉；候选仍由既有 SELECT 返回，Java guard 保守阻塞。
- 不处理本批之外的 required-positive、完整 tuple 因果、库存余额、请求快照完整性、秒杀身份、Order SKU、TC/MQ 或其它 M1 缺口。
- 不改变秒杀无 settlement/非 `ACCEPTED` 的原 null 语义、精确合法 settlement/legacy 的状态映射、Stock fallback 的 `REJECTED`/`PENDING` 语义，也不改变普通 flows/request 分支；非 null identity failure 必须保持 `UNKNOWN/items=[]`，不能降级为 null。

## 5. 验收矩阵

- Unit：settlement 行、无 settlement 的主 legacy `ACCEPTED`、Stock fallback legacy 各覆盖精确合法、null、非文本、错 order、case/accent/trailing-space alias；主 legacy 的非 `ACCEPTED` 保留原 null 语义，fallback 的 `REJECTED`/`PENDING` 保留原状态语义。
- Owned MyBatis/MySQL：实际 settlement 与 legacy SELECT 返回 CI 候选，验证 identity guard 在 `validateSettlement`/状态映射之前生效；source checksum、四表/结算行、状态、时间和数量前后不变。
- 正常三状态、unknown internal、accepted、missing 对照保持；Order fallback 只消费实际 Stock GET 结果，错误身份不能变成 `SUCCEEDED`，无 POST/写 RPC。
- 预算保持既有读取：settlement 一个 SELECT；主 legacy 沿原两次读取；Stock fallback 沿既有读取次数，不新增 SQL、连接或事务。owned 库和测试资源 finally 清理。

本切片只冻结原订单 identity guard，不宣称修复所有秒杀/普通库存证明或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
