# CM-20260924-20：Order reservation intent 严格保存评审

## 1. 评审结论与范围

结论：**GO，限定修复普通订单 reservation intent INSERT IGNORE 的静默截断/默认值风险；不改变 schema、API、状态机、时间、Seata、库存恢复或历史数据。**

当前 `OrderReservationIntentMapper.insertIfAbsent` 使用 INSERT IGNORE，Order 入口 key 门无法保护内部 PO 的 orderNo/items/status 等元数据和直接 Mapper 调用；严格/非严格 SQL mode 可能产生变形意图或误报首次成功。

## 2. 严格输入契约

`insertIfAbsent` 保留 PO/method public 签名，使用普通参数绑定 INSERT；只捕获 DuplicateKeyException 返回 0，其他 DataAccessException/JSON/连接/约束错误直接传播。

执行 SQL 前必须拒绝：

- id、userId、createdAt、updatedAt、orderNo、idempotencyKey、itemsSnapshot、status 的 required null；
- orderNo 非 blank 且 Unicode code point ≤64；
- idempotencyKey 非 blank 且 code point ≤128；
- status 非 blank 且 code point ≤32；
- lastError 若非 null，code point ≤512；
- nextRetryAt、lastError 的 nullable 语义保持；itemsSnapshot 的 JSON 合法性继续由 MySQL JSON 列约束和既有调用方负责，不新增业务 JSON 规则。

code point 使用 Unicode code point 计数，非 BMP 字符不按 UTF-16 length 双计。输入非法在 SQL 前失败，不留下意图行、不改变 recovery 状态、不调用库存或 Redis。

## 3. 事务与幂等边界

`OrderReservationIntentService.createIntent` 的既有事务/调用顺序不变；同一用户/幂等键、orderNo 等 UNIQUE 冲突只返回 0，由上层按现有 find/replay 语义处理。不同用户、不同唯一键按数据库现有索引独立处理。普通订单创建的 Product/Stock/intent/订单事务和恢复流程不改。

不迁移 schema/collation，不自动修历史截断/坏行，不改变 PO 字段、状态枚举、时间归一化或错误码契约。

## 4. 验收矩阵

1. 真实 MySQL strict/non-strict 模式下 required null、orderNo/key/status/lastError 超长均拒绝且零行；不依赖 SQL mode 静默处理。
2. 合法 ASCII/BMP/非 BMP Unicode 边界、nullable nextRetryAt/lastError 和合法 JSON snapshot 正常保存。
3. 精确重复及各 UNIQUE 冲突只返回 0；不同用户独立；并发同键一成功一重复，首事实不覆盖。
4. 坏 JSON、连接/约束/数据库错误不被吞；Mapper/Service 事务边界沿原异常路径回滚。
5. 普通订单真实创建、intent 恢复、Stock 未知/补偿和原库 checksum 回归保持。

## 5. 明确排除

本轮不修改 Order 入口之外的 API、schema、索引/collation、历史数据、状态机、库存/RPC、Seata 或一般数据库错误恢复；只防止新 intent 写入静默变形。
