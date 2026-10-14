# CM18 16T184：Pay TCC 取消租约 token 输入校验规格

## 1. 评审结论与范围

本批 **GO**。`PayTccCancelStore` 的三个租约入口对 token 校验不一致：`claimRecovery` 和 `stage` 只拒绝 NULL/blank，漏掉 Java UTF-16 长度上限；`finish` 完全没有 token 有效域门，历史 blank owner 可被用于清租约和修改退避。本批只统一 Store 输入域，不修复历史坏 owner，不扩展取消流程 fencing。

## 2. 共享 token 语义

增加私有共享 `validToken(token)`：

```java
token != null && !token.isBlank() && token.length() <= 128
```

长度按 Java `String.length()` 的 UTF-16 code unit 计算，与既有 ExecutionRecovery token 规则一致；不 trim、normalize 或大小写折叠。非 blank 首尾空格、ASCII128 和 emoji64（UTF-16 长度128）合法；emoji65（UTF-16 长度130）即使 MySQL `VARCHAR(128)` 按字符可存，也因 Java token 域超限而拒绝。数据库 schema 与列容量不变。

## 3. 三入口行为

### 3.1 `claimRecovery`

保留原 `payNo == null` 参数门及既有 conflict code/message。其后在任何 Mapper 读取之前校验 `validToken`；非法 token 沿原 `PAY_TCC_CANCEL_CONFLICT/409` 参数错误语义返回，不锁支付/取消行、不写 lease、attempt 或更新时间。合法 token 的 60 秒 lease、状态/到期/互斥/tuple 门、readback 和事务边界不变。

### 3.2 `stage`

保留支付行后取消行的既有锁顺序及前置 token/lease/终态门。将 token 条件统一为 `!validToken(token)`；非法 token 在原两行锁之后立即返回 `false`，不读取其它 execution/dispatch/receipt/intent，不调用 Pay 状态更新，不更新取消状态。合法 token 的状态转换、回滚和异常保持。

### 3.3 `finish`

入口最先校验 `validToken`。非法 token 直接返回，不调用 Mapper、不清理 lease、不更新 next retry、error 或 updated_at；保持既有 void API。合法 token 的退避、lease、错误截断和 SQL 不变。

不修改 Mapper SQL、表结构、状态集合、锁序、lease 时长、退避、API/DTO、RPC/TCC 或历史数据。

## 4. 验收范围

### 4.1 Native owned MySQL

- 使用 owned 取消/支付及既有来源表的实际 Mapper 与原 DDL；原库只读指纹保持，不启动业务 App、TC/RM/RPC。
- 旧 red：emoji65 token claim 写 lease/attempt；相同历史 emoji65 owner 可 stage；blank finish 可清 lease/改退避。
- 修复后覆盖 NULL、空串、ASCII 空白、TAB/CRLF、全角空白和 emoji65，三入口分别拒绝且取消/支付/来源事实不变。ASCII129 仅 Unit 验证，不写数据库。
- 合法 worker、非 blank 首尾空格、ASCII128、emoji64 真实 claim/stage/finish 保持；60 秒 lease、attempts=31、旧 owner/过期 lease 控制保持。
- 若 owned schema 缺少取消表，仅调用实际 `PayTccCancelMapper.ensureTable()` 建 owned 表，禁止抄写生产 DDL；临时资源 finally 清理。

### 4.2 Unit

覆盖 claim/stage/finish 的非法 token、合法边界、历史空白 owner、null token、Mapper/锁调用顺序；断言 claim 的既有 conflict、stage false、finish 无 Mapper，并保持合法参数与原 readback/返回语义。

## 5. 明确边界

本批不修历史 null/blank owner，不增加 SQL token 条件，不改变 readback、stage/finish 状态机、Pay 资金事实、取消表 DDL 或其它 Store；不宣称完整 lease fencing、跨表原子性或远端资金证明。Native 使用程序化本地事务，不冒称 Spring/TC 集成。
