# CM18 16T183：Pay TCC 交付租约 token 输入校验规格

## 1. 评审结论与范围

本批 **GO**。问题限定为 `PayTccDeliveryStore` 三个写入口没有统一校验租约 token：`claim` 可写入 `NULL`/空白 owner，`stage` 可在历史空白 owner 与空白入参相等时推进 Pay 和 delivery，`finish` 可用空白 owner 清理租约并改退避。本批只收紧 Store 输入 token 域，不修复历史坏 owner，也不宣称完成完整 fencing。

## 2. 共享 token 语义

增加 Store 私有共享谓词 `validToken(token)`，定义为：

```java
token != null && !token.isBlank() && token.length() <= 128
```

长度按 Java `String.length()` 的 UTF-16 code unit 计算，与既有 `PayTccExecutionRecoveryStore` 规则一致。不得 trim、normalize、大小写折叠或替换字符；因此首尾存在空格但整体非 blank 的 token 仍属合法，ASCII128 与 UTF-16 长度128的 emoji token 合法，emoji65（UTF-16 长度130）非法。数据库列容量与 schema 不变。

## 3. 三个入口的最小行为

### 3.1 `claim`

`claim(payNo, token)` 在任何 Mapper 读取、支付锁或 delivery 锁之前校验 `validToken`。非法 token 抛既有 `PAY_TCC_DELIVERY_CONFLICT/409`，不写 lease、attempt、更新时间，也不改变返回 API。合法 token 的 60 秒 lease、状态/到期/tuple/资金门和 readback 完全保持。

### 3.2 `stage`

保留现有 payment 与 delivery 锁定顺序。锁定之后、读取 execution 或执行任何 Pay/Delivery 写入之前，用 `validToken` 替换现有仅 `token == null` 的门；非法 token 返回 `false`，不读取 execution，不调用 Pay 状态更新，不更新 delivery。合法 token 的 lease 比较、状态转换、事务回滚和既有异常保持。

### 3.3 `finish`

入口最先校验 `validToken`。非法 token 直接返回，不调用 Mapper、不清理 lease、不改变退避、错误或诊断；保持既有 `void` API。合法 token 的退避、lease 条件、错误截断和 SQL 保持不变。

Mapper SQL、锁序、状态集合、lease 时长、错误码、DTO/API、RPC/TCC 均不改；直接调用 Mapper 的行为和可写域不在本批扩展范围。

## 4. 验收矩阵

### 4.1 Unit

- `claim` 覆盖 null、空串、ASCII 空白、TAB/CRLF、全角空白、UTF-16 长度129/130；断言既有 conflict、无 Mapper 调用。
- `stage` 覆盖同一非法集合，允许既有 payment/delivery 锁调用，但不得读取 execution、不得调用 `mark*` 或 delivery update；返回 `false`。
- `finish` 覆盖同一非法集合，断言无 Mapper 调用且 void 返回；既有合法 token 参数和 readback/返回保持。
- 合法边界覆盖非 blank 首尾空格、ASCII128、emoji64（UTF-16 128），以及旧 token 过期/不匹配控制。

### 4.2 Owned MySQL

- 使用已有 owned Delivery、Payment、Execution、Dispatch、Receipt/Intent Mapper 与程序化本地事务；不启动业务 App、TC/RM 或写原业务库。
- 在实际列可表示的 owner 值中复现旧行为：null/blank claim 会写 lease、blank stage 可推进 Pay/Delivery、blank finish 可清 lease；这些 red 证据与修复后零写/全事实不变分别归档。
- 修复后验证非法 token 的 claim、stage、finish 不改变 delivery、payment、execution、dispatch、receipt/intent 事实；合法 token 仍能 claim、stage、finish，60 秒 lease、attempts=31 上限和旧状态规则不变。
- ASCII129 与 emoji65 这类 UTF-16 超限值不写入 128 长度数据库列，使用 unit 验证拒绝；native 只验证列可表示的合法/非法 owner 边界。

## 5. 明确边界

本批不迁移或修复历史 null/blank owner，不增加 SQL 条件、不改变表结构，不扩展其它 Store、TCC/RM、远端 RPC 或 read-after-write fencing；已领取后外部 tuple/阶段变化仍由既有后续门处理。测试使用程序化本地事务而非 Spring 代理，不能据此宣称跨服务事务或完整资金证明。
