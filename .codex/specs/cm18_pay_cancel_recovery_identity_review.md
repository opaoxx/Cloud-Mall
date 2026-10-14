# CM18 16T180：Pay TCC 取消恢复支付号身份校验规格

## 1. 评审结论与范围

本批 **GO**。问题限定为取消恢复入口对请求 `payNo` 的身份使用不一致：`claimRecovery` 的外层 `cancel.pay_no=#{payNo}` 受数据库排序规则影响，可能用大小写别名领取另一条取消任务；取消事实读取也直接返回 CI 查询命中的异源行。本批只修正这两个观察点的支付号身份，不扩展取消状态机、资金互斥证明或完整 fencing。

## 2. `claimRecovery` 的最小 SQL 修正

仅将 `PayTccCancelMapper.claimRecovery` 外层资格条件改为：

```sql
BINARY cancel.pay_no = BINARY #{payNo}
```

保留现有 JOIN 及其条件：支付行必须与取消行的支付号、用户、订单、金额精确匹配，支付模式为 `TCC`、支付状态为 `PENDING`，execution/dispatch/receipt/debit intent 均不存在，取消状态、到期时间和 lease 条件均满足，尝试次数上限与现有 `LEAST(..., 31)` 保持。SQL 参数顺序、更新列、`updated_at`、返回 affected rows 和其它 Mapper 方法均不变。

因此，大小写别名请求只能得到 `0` 行并由 Store 返回 `null`，不会写入 lease、attempt 或更新时间。Store 现有 payment `selectForUpdate`、claim 后 readback 和事务边界保持原样；不新增 readback 身份判断，不改变成功行的返回对象。

## 3. `PayTccCancelStore.read` 的返回身份门

`read(payNo)` 保持一次 `selectByPayNo(payNo)`。结果为 `null` 时仍返回 `null`。结果非 `null` 时必须同时满足：

```java
row.payNo != null && Objects.equals(row.payNo, payNo)
```

否则抛现有 `PAY_TCC_CANCEL_CONFLICT`、HTTP 409 的冲突异常；不返回异源取消对象，也不继续调用方后续逻辑。该门只作用于 `read`，不改变 `claimOrRead`、`stage`、`finish`、`claimRecovery` readback 或其它取消入口。

`payNo` 的比较使用 Java `Objects.equals`，不 trim、不大小写折叠、不把空白或 `null` 变成有效键。生产表的 `pay_no` 仍按既有 NOT NULL 契约处理；null 行仅在单元测试对象中验证防御语义。

## 4. 验收矩阵

### 4.1 真实 owned MySQL

- 使用已有取消、支付、execution、dispatch、receipt、intent 表结构的 owned schema 和实际 Mapper；不启动业务服务、TC/RM 或修改原库。
- 以实际 CI 排序规则构造请求与持久行的双向大小写别名。`claimRecovery` 必须 affected rows 为 0，lease、attempts、`updated_at` 及六张来源表事实全部不变；`read` 必须抛 `PAY_TCC_CANCEL_CONFLICT/409`。
- canonical `payNo` 的三个可恢复状态保持原行为：合法任务可领取一次、lease 约 60 秒、attempts 上限 31；第二次被 lease/状态门阻断。缺行、未到期、已有 lease、资金事实互斥、非 TCC、非 PENDING 等既有零行语义保持。
- 通过真实 `PayTccCancelStore` 与受控 Service 调用验证：异源终态不能作为 fallback 返回，不能触发 Order RPC 或其它资金写；匹配键的 `read`、claim、终态及缺失行为保持。
- native 仅对大小写别名作实际 CI 查询验证。foreign、空格尾缀、null 返回对象以及 NOT NULL 列的 null 语义只在 unit 中模拟，不声称数据库实际可返回这些形态。

### 4.2 Unit

覆盖 `read` 返回 foreign、case alias、space、null-payNo、null row，以及 `claimRecovery` 的 null/非法 token、missing、canonical 和 alias 请求；断言冲突 code/message、一次 Mapper 读取、无 stage/finish/资金调用。已有取消状态、`CANCEL_UNKNOWN`、canonical terminal、互斥事实和支付锁优先级回归保持。

## 5. 明确边界

本批不修改 SQL JOIN 的排序规则、不引入全局唯一索引、不修复历史重复数据，不扩展 `selectForUpdate`、readback、stage、finish、claimOrRead 或 Service 的来源身份检查，也不宣称取消流程已经具备完整 tuple fencing、跨表原子性或远端 Order/TC 证明。现有租约期间外部事实漂移仍由后续规格处理。

## 6. Native fixture 与连接环境边界

本批 native 验收只使用 owned schema。测试通过同一 MySQL 实例的临时 loopback 通道连接；该通道仅解决当前 host 转发端口立即 EOF 的测试传输问题，不改变 MySQL、操作系统、容器或业务服务配置，也不代表 `host:13307` 转发已修复。通道、监听 socket、JDBC/CLI 子进程均须在 `finally` 中关闭回收；连接失败、表创建失败或断言未执行时，必须记录为环境/fixture 失败，不宣称业务红测或全链路验证通过。

owned 取消表 fixture 的规则如下：

- `pay_tcc_dispatch`、`pay_tcc_execution`、`pay_tcc_receipt` 按既有 DDL 克隆到 owned schema。
- 若 owned schema 中已有 `pay_tcc_cancel`，按既有 DDL 克隆；若不存在，只调用实际 `PayTccCancelMapper.ensureTable()` 在 owned schema 创建，禁止在测试中抄写或扩展生产 SQL/表结构。
- 原 cloudmall_pay 的源表清单、逐表指纹和数据保持只读且不变；fixture 失败产生的 owned schema 必须按精确名称归档后删除并复查不存在。
