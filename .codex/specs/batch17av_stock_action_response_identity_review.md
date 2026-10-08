# CM-20260924-20：订单库存动作响应身份校验评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderStockActionRecovery` 对库存状态响应缺少原订单号身份校验的问题。**

当前 `stockStatus` 只检查响应 code、data 和 status，没有核对 `StockReservationStatusVO.orderNo`。错单响应可能让 CONFIRM/ROLLBACK 动作把目标状态误判为 `SUCCEEDED`，把相反状态误判为 `CONFLICT`，或在返回 `RESERVED` 时对原订单执行错误动作；写动作后的第二次 GET 也存在相同风险。

本轮只增加响应身份门，不修改 Stock/Feign API、动作 key、lease/owner/attempts、状态枚举、扫描排序、SQL、schema、消息或中断策略。

## 2. 状态查询身份契约

在既有响应成功且 `data != null` 的检查之后、读取 `data.status` 之前，使用：

```java
Objects.equals(originalOrderNo, response.data.orderNo)
```

进行精确比较。比较区分大小写，保留重音、尾随空格及其他字符差异，不 trim、不大小写折叠、不从 status/items 或请求路径补全订单号。

以下情况全部抛出 `IllegalStateException`：

- response data 的 orderNo 为 null 或 blank；
- 大小写、重音、尾随空格不同；
- 返回其他订单号；
- 响应 data/orderNo 无法得到合法原始字符串。

异常沿现有 `recoverOne` RuntimeException 路径进入 `actionMapper.retry` 的 UNKNOWN/原退避语义；不调用 `finish`，不把身份失败写为 SUCCEEDED 或 CONFLICT。

## 3. 两次查询与动作边界

### 3.1 首次查询

每个动作在执行目标/相反状态判断前，第一次 `stockStatus(action.orderNo)` 必须经过身份门。身份不匹配时：

- 不调用 `confirm` 或 `rollback`；
- 不写库存动作；
- 不 `finish` 为 SUCCEEDED/CONFLICT；
- 仅按既有 retry UNKNOWN 和 attempts/lease/nextRetry 更新规则处理。

身份匹配后，CONFIRM 继续以 `CONFIRMED` 为目标、`ROLLED_BACK` 为相反状态；ROLLBACK 继续以 `ROLLED_BACK` 为目标、`CONFIRMED` 为相反状态。所有既有 RESERVED、目标终态、相反终态、REJECTED 和未知状态分支保持不变。

### 3.2 授权写入后的第二次查询

当首次查询为原身份 `RESERVED` 并已使用原 `operationKey` 执行一次 confirm/rollback 后，第二次 `stockStatus` 仍必须执行同一身份门：

- 第二次身份不匹配、null、blank 或异常时，不执行第二次库存写入、不 finish、不按错单状态完成或冲突；
- 保留已经授权的原动作一次及其 operationKey，进入原 UNKNOWN retry；
- 不执行相反动作，不生成新 key，不补偿猜测库存结果。

第二次查询返回原身份时，继续既有目标终态成功、相反终态冲突和未知状态 retry 语义。

## 4. Stock 契约与兼容边界

- Stock 端既有 reservation 查询的所有分支（正常流水、NOT_FOUND、REJECTED、回滚、已确认以及兼容台账路径）继续在 `StockReservationVO.orderNo` 返回请求订单号；本轮不修改 Stock 端或 Feign 方法签名。
- `StockReservationStatusVO.orderNo` 是既有响应字段，不新增 JSON 字段或动作枚举。
- 保留原 action 的 claim attempts、owner/lease、retry/UNKNOWN、operationKey 和 finish CAS 语义。
- 不校验 user、items、库存流水归属或远端响应签名；不修复历史已写入的终态，不扩展普通 intent/AQ、秒杀恢复或中断策略。
- 数据库、RPC 或响应解析异常继续沿原 Runtime/retry 处理，不新增重试线程或额外 RPC。

## 5. 验收矩阵

1. 单测覆盖 CONFIRM/ROLLBACK 两方向，以及 RESERVED、目标终态、相反终态、REJECTED、NOT_FOUND/未知等状态；错单号、大小写、重音、尾随空格、null/blank 均不触发库存写或 finish，只进入 UNKNOWN retry。
2. 首次查询身份匹配且为 RESERVED 时，确认原 operationKey 只写一次；写后第二次错单响应不产生 SUCCEEDED/CONFLICT、不重复写入或执行相反动作。
3. 正确身份的原 4 类 happy path 保持：目标终态直接 SUCCEEDED、相反终态/REJECTED 按原冲突规则、RESERVED 执行对应动作后按第二次状态收敛、未知状态按原 retry。
4. 真实隔离 MySQL 验证两方向 first/second mismatch：action 的 orderNo、operationKey、createdAt 等不可变事实保持，原 claim attempts+1、lease 释放/nextRetry 更新规则保持；修正为原身份后可按原流程收敛且不重复写。
5. 回归 StockReservationStatusVO/Feign 契约、原订单 checksum、隔离库清理和既有动作恢复测试；Stock/RPC 使用替身时不宣称真实错单网络故障验证。

## 6. 明确不承诺

本轮只防止库存状态响应错单导致错误动作收敛，不提供跨服务响应签名、库存全量归属校验、在途 RPC 撤回或分布式 exactly-once 保证。
