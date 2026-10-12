# CM-18 16T67：User CANCELLED 全支付号 DEBIT scope

## 1. 结论与范围

本切片 GO，修正 `UserTccStateQuery.read` 的 CANCELLED 分支只核固定 `ledgerKey`，漏查同 payNo 其它 operation key 的 DEBIT，导致存在 APPLIED/其它状态 DEBIT 时仍输出安全 CANCELLED。

本批只复用既有 `BalanceLedgerMapper.selectDebitByPayNo(payNo)` 纯 SELECT；不改 SQL/DDL/API/状态机/写链/RM/TC/TTL/Fence。全账户因果、其它退款和历史全覆盖仍不在范围。

## 2. CANCELLED gate

在 CANCELLED 分支原固定 ledger 查询 `require(ledger == null)` 之后、Fence 查询和最终 view.state 设置之前，追加：

```text
require(ledgers.selectDebitByPayNo(receive.payNo) == null)
```

该查询覆盖同 payNo 任意 DEBIT state/operation key/CI 候选；任何非 null 都沿既有 `USER_TCC_STATE_CONFLICT` 409。固定 key 冲突仍先短路，其他 state 分支不新增查询。

多行 DEBIT 若既有 Mapper selectOne 抛 TooManyResults/DataAccess 异常，沿原数据库异常渠道传播，不伪造 CANCELLED、不改 SQL 或新增 COUNT。合法 CANCELLED empty tombstone 只增加一次全 pay DEBIT SELECT；无 DEBIT 即可保留原 CANCELLED/ledgerApplied=false 和 terminal Fence/TTL 语义。

## 3. 不变边界与验收

- TRYING/CONFIRMED/UNKNOWN 不增加该查询；写服务已有 `requireNoLedger` 和 T24 terminal reader 的独立检查不删除、不合并。
- Unit：CANCELLED 两 mode × APPLIED/其它 state DEBIT、alternate key/CI alias、多行异常、缺金额、无 DEBIT、REFUND/其它 pay 合法；固定 key conflict 优先，DB fault 原样传播。
- Owned MySQL 同 RR：真实 alternate key/多状态 DEBIT、HTTP state 保持既有缓存契约且 409，T9 诊断 409/no-store；两者均无写入。合法 empty cancel 增加 1 次 SELECT，原 9→10 计数预期，source checksum/资金 facts 不变。
- 不改变 DDL/Mapper SQL/状态机/锁序/API/flags；不启动 App/RM/TC/固定端口，owned 资源 finally 清理。

本切片只冻结 CANCELLED 的全 pay DEBIT scope，不处理全退款/全账户因果、历史覆盖或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
