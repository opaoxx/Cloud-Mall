# CM-18 16T9：User 账户/冻结/固定退款键只读观察评审

## 1. 结论与位置

结论：**GO，作为 CM18 M1 的 User 账户补充只读切片；不改变既有 UserTccStateQuery、User HTTP/RM、资金写状态机或 Pay/Order/Stock API，不代表统一跨服务包或 CM18 M2–M4 闭环。**

本切片观察 User 账户 `balance/frozen_balance/status`、TCC 冻结聚合和固定退款键事实。它不授权 Confirm/Cancel/refund，不把账户余额归零或冻结汇总相等当作终态证明。

## 2. 入口与安全门

新增 default-off：

```text
cloudmall.user.tcc-reconciliation.enabled=false
```

新增内部 GET：

```text
GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}
```

- callback token、caller=`cm18-reconciliation`、positive userId、ASCII payNo、无 Spring active transaction/无 Seata XID 必须在任何连接前校验；Gateway 禁止该路径，错误与成功都 `Cache-Control: no-store`。
- 不依赖或开启 `cloudmall.user.tcc-http.enabled`，不绑定 RM/RootContext，不接受请求覆盖 userId/payNo/operation key/XID/branch/金额。
- 独立 `@Service` ReadService 通过构造器创建 plain delegate 的原 `UserTccStateQuery` 纯读取委托，不注册新的同类型 Spring bean；外层 `@Transactional(readOnly=true,isolation=REPEATABLE_READ)` 先建立，原委托不依赖自身 Spring 代理/事务注解，所有 SQL 都在该外层 readOnly RR 内执行。

## 3. 读取顺序与 SELECT 边界

1. `UserTccReceiveMapper.findByPayNo` 新增无锁纯 SELECT，按用户键候选读取后精确核对 payNo；缺 receive 返回 404，CI 别名/tuple 错误返回 409。
2. receive 必须为 `operationKey=tcc-debit:<payNo>`、`ledgerKey=payNo`、正且无损 amount、非空原 XID、state=`CLAIMED`，branch null 或正；取得 operation/XID 后调用原 `UserTccStateQuery.read`。
3. 原委托按既有 RR 读取 receive/attempt/debit/ledger/Fence；不调用写方法。再执行新增 `UserMapper.selectBalanceEvidenceById(userId)`（只投影 id/status/balance/frozen_balance，不读取 password/phone/role）、`BalanceTccDebitMapper.selectFrozenEvidence(userId)`、`BalanceLedgerMapper.selectByOperationKey("refund:"+payNo)` 三个无锁 SELECT。完整样本为 9 SELECT/1 connection；无 attempt 时原委托可少读 Fence，仍不得补写；旧 `UserMapper.selectById` 不改。
4. aggregate 固定 userId WHERE，只 `SUM` state=`TRYING` 的原 amount，并 COUNT 非法行；不新增索引/DDL、不在 SQL 过滤坏行。

## 4. 账户、冻结和退款观察契约

- 账户只摘要 userId、status、balance、frozen_balance；不读/不输出 password、phone、role。账户缺行、金额缺失/负数、status 非 0/1 为缺证或冲突；disabled=0 可正常观察已有资金事实。
- frozen aggregate 返回 `frozen_total` BigDecimal 与 invalid row count；amount NULL/非正、state NULL 或非精确 `TRYING/CONFIRMED/CANCELLED` 均计入 invalid。invalid>0 为 `LOCAL_CONFLICT`，不因聚合过滤而隐藏坏行；合法 aggregate `frozen_total` 必须与账户 `frozen_balance` 数值相等，否则 `FROZEN_PROJECTION_CONFLICT`。不要求每行 attempt 关联证明，不要求 frozen_total=0 或等于旧 ledger balanceAfter，也不验证 TC/MQ。合法其他交易冻结非零保持。
- `refund:<payNo>` 只读固定键；absence 不解释为无退款。存在时精确核对 user/pay/order、operation key、original operation key、operation_type=`REFUND`、state=`APPLIED`、正 amount、amount<=原始金额及原 `CONFIRMED + ledgerApplied` 证据；`balanceBefore`/`balanceAfter` 必须存在且非负，且 `balanceAfter-balanceBefore=amount`，缺字段为缺证，负数或增量错误为冲突。部分正退款保留 `REFUND_PARTIAL_OBSERVED` 缺证，不猜自动补退。
- 部分固定键退款保留 `REFUND_PARTIAL_OBSERVED` 缺证分类，不猜自动补退；无原 CONFIRMED/ledger 证明为缺证；已知 User TRYING/CANCELLED 且存在 APPLIED 退款为事实冲突。

## 5. 输出与分类

响应固定 `scope=USER_ACCOUNT_FROZEN_REFUND_OBSERVED_ONLY`、`automaticActionsAllowed=false`、上海 `collectedAt`，并输出账户/aggregate/refund 白名单摘要。XID/branch/operation/ledger/refund key 只 SHA-256 指纹，错误只 presence；不输出 raw key、凭证或 error。`externalSystemsNotQueried=[PAY,ORDER,STOCK,TC,MQ]`，不宣称 Pay 消费、统一包或资金终态。

分类优先级：入口/SQL unavailable→固定 503；身份/tuple/mode/status/refund 冲突→`LOCAL_CONFLICT`；缺 receive/account/aggregate/refund 字段→`BLOCKED_LOCAL_EVIDENCE`；合法观察但仍缺其他系统→`BLOCKED_EXTERNAL_EVIDENCE`。UserTccStateQuery 原 409 沿固定冲突，不自动转换为成功/回滚。

## 6. 验收矩阵

- Unit：default-off/auth/context/参数 gate 0 DB、no-store、CI alias、完整 receive/CLAIMED/branch/null-or-positive、错误 operation/XID/amount、账户 disabled、aggregate invalid rows、partial/full refund、其他冻结和未知状态。
- 真实 owned User MySQL：9 SELECT 同 RR/单连接，并发 writer 不混读；原库所有表 checksum 不变，owned schema finally 清理。验证原 stateQuery plain delegate 只读、无 RM/HTTP 写、无新 Spring bean。
- 缺表/连接 503 不建表；固定退款键缺失不冒称无退款；不新增 User HTTP/Feign/API、DDL、索引或资金写入。

## 7. 交付边界

本文件只冻结 User 账户/冻结/固定退款键观察，后续 Pay/Order/Stock/TC/MQ 证据与 M2–M4 另行验收。本切片新增一个受保护只读诊断 GET，但不改变既有 UserTccStateQuery、资金写状态机或既有 User HTTP/Feign/API 契约。
