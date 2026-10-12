# CM-18 16T68：User TRYING/CONFIRMED 全支付号 DEBIT scope

## 1. 结论与范围

本切片 GO，延伸 T67 的全 payNo DEBIT scope 到 User TCC `TRYING` 与 `CONFIRMED` 查询，防止固定 ledgerKey 无账本或单个正确账本掩盖同 payNo 其它 DEBIT。

本批只复用已有 `BalanceLedgerMapper.selectDebitByPayNo(payNo)` 纯 SELECT；不改 SQL/DDL/API/写状态机/RM/TC/Fence/TTL，也不合并 T24 terminal reader 的独立全 pay 检查。

## 2. TRYING gate

在 TRYING 原 `require(debit != null && ledger == null)` 后追加：

```text
require(ledgers.selectDebitByPayNo(receive.payNo) == null)
```

任意同 payNo DEBIT（任意 state/key/CI candidate）均为既有 `USER_TCC_STATE_CONFLICT` 409；无 DEBIT 保留 TRYING/冻结语义。其它 state 不新增该查询。

## 3. CONFIRMED scope proof

在 CONFIRMED 原 fixed ledger、amount、snapshot、identity/type/state 门通过后、Fence 查询及设置 `ledgerApplied=true` 之前，复用 `selectDebitByPayNo(receive.payNo)`；原 Fence 查询/校验位置不移动：

- candidate 必须非 null，exact userId/payNo/orderNo/operationKey=`receive.ledgerKey`、operationType=`DEBIT`、state=`APPLIED`；
- amount、balanceBefore/After 合法并与原 debit/receive 数值精确相等（保留 BigDecimal 尾零数值等值）；
- 不比较新 id/createdAt，不加 COUNT、新 SQL 或新字段；
- null、错 tuple/key/type/state/amount/snapshot、非唯一多行导致既有 selectOne/DataAccess 异常，沿原 `USER_TCC_STATE_CONFLICT` 或数据库异常渠道，不输出 CONFIRMED/ledgerApplied=true。

合法唯一 candidate 才保留 CONFIRMED/ledgerApplied=true。固定 key 缺失/坏、原 debit/fixed ledger 门失败仍先短路，不新增全 pay 查询。

若同 payNo 存在第二条 DEBIT，既有 `selectDebitByPayNo` 的 selectOne 先抛 DataAccess/TooManyResults：原 state GET 保持 generic 500，诊断 wrapper 按原固定 evidence-unavailable 503；下游 linked/payment/terminal candidate reader 不再执行，旧 LOCAL_CONFLICT 报告不适用，facts/no-write 保持。Terminal peer 在 fixed ledger 已存在但全 pay DEBIT scope 返回 null 时，原 Query 先抛 `USER_TCC_STATE_CONFLICT`/409，再由 Terminal reader 既有 catch 分类为报告 `LOCAL_CONFLICT` + reason `TERMINAL_PEER_PERSISTED_CONFLICT`；诊断报告 HTTP 仍保持原 200，不把报告 reason 当作 Controller HTTP 409，也不降级为 `BLOCKED_LOCAL_EVIDENCE` 或继续后续 peer 分类。

## 4. 不变边界与验收

- CANCELLED/T67、UNKNOWN 和其它 state 不改；写服务的 requireNoLedger、T24 terminal reader 独立检查不删除/合并。
- Unit：TRYING 各 DEBIT state/key 候选、CONFIRMED 缺/错/多行/DB fault、healthy fixed row；验证 409/异常、无写和合法 output。
- Owned SQL/HTTP：TRYING alternate key 与 CONFIRMED 两个 DEBIT 实际核查，RR/no-write/facts/checksum 保持；合法固定 row 仅增加 1 次全 pay SELECT，原状态/计数调整为真实查询数。
- 不改 DDL/Mapper SQL/API/locks/Fence/TTL，资源 finally 清理，不启动 App/RM/TC/固定端口。

本切片只冻结 TRYING/CONFIRMED 的全 pay DEBIT scope，不处理全账户因果、退款/历史全覆盖、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
