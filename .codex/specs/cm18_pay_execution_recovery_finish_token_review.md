# CM18 16T186：Pay TCC ExecutionRecovery finish token 校验规格

## 1. 评审结论与范围

本批 **GO**。`PayTccExecutionRecoveryStore` 的两个 claim 入口已有非 NULL、非 blank、Java UTF-16 长度不超过128的 token 域门，但 `finishRecovery` 缺少同一门。历史 blank 或 UTF-16 超限 token 若与 owner 相等且 lease 仍有效，可清理 owner/lease、写退避时间和错误诊断。本批只补 finish 输入校验，不改 claim、Mapper SQL 或恢复状态机。

## 2. `finishRecovery` 最小修正

在 `finishRecovery(payNo, token, nextAt, error)` 第一行增加与 claim 一致的条件：

```java
if (token == null || token.isBlank() || token.length() > 128) return false;
```

非法 token 必须在错误截断、时间计算和任何 Mapper 调用之前返回 `false`；不清理 `recovery_owner/recovery_lease_until`，不修改 `next_recovery_at`、`recovery_last_error`、`updated_at` 或 execution 其它字段。保留现有四参数 boolean API、事务注解和调用方语义。

合法 token 按原流程执行：错误仍由 `PayErrorMessages.truncate` 按既有 Unicode 规则处理，使用同一次上海毫秒时间，原 Mapper 的 BINARY `payNo`/owner、有效 lease 条件与 affected-row 返回保持。首尾空格但非 blank、ASCII128、emoji64（UTF-16 长度128）保持原行为；emoji65 即使数据库 `VARCHAR(128)` 按字符可存，也因 Java token 域超限被拒。ASCII129 只在 Unit 验证，不写数据库。

## 3. 不变契约

不修改 `claimRollbackProjection`、`claimRecovery` 的 token 门；不修改 RecoveryMapper SQL、表结构、列容量、锁序、lease 时长、`nextAt`、错误截断、状态/decision、调度方向、RPC/TCC 或业务 API。过期 lease、错误 owner、pay alias、缺失行和 Mapper 返回0仍沿原 boolean false/不写语义。

## 4. 验收矩阵

### 4.1 Unit

- 覆盖 NULL、空串、ASCII 空白、TAB/CRLF、全角空白、ASCII129、emoji65 等非法 token，验证第一行返回 false、Mapper 不调用、错误不截断、时间不计算的可观察边界。
- 合法 worker、首尾空格、ASCII128、emoji64 覆盖原 `nextAt`、Unicode error 截断、毫秒时间和 boolean 返回；Mapper 0 仍返回 false。
- 保留两个 claim 入口的既有 token/lease 控制，避免把 finish 门误扩到 claim SQL 或其它 Store。

### 4.2 Owned MySQL

- 使用原 execution DDL、真实 RecoveryMapper/Store 和程序化本地短事务；原业务库只读指纹不变，不启动业务 App、TC/RM 或 Java listener。
- 旧 red 覆盖普通 RUNNING/NONE 与 ROLLEDBACK/ROLLBACK 相关可存 owner：blank、emoji65 finish 曾清理 owner/lease、改变 next/error；修复后六类可存非法 token均不修改 Pay、Intent、Execution 三表事实。
- 合法四类 token保持 `nextAt`、错误截断、毫秒时间、owner/lease 清理和其它字段；过期、错 owner、支付别名、缺失行及原 0 affected controls 保持。
- emoji65 的拒绝属于 Java token 域，不是数据库字符容量错误；native 只使用列可表示的 owner 值，ASCII129 仅 Unit。

## 5. 明确边界

本批不修历史坏 owner，不增加 SQL token 条件，不改变 finish 的 CAS、lease/fencing、状态决策或远端来源证明；程序化短事务不代表 Spring/TC 集成。原数据清理、owned schema 删除和指纹复核必须在 finally 完成，失败环境不得宣称业务验证闭环。
