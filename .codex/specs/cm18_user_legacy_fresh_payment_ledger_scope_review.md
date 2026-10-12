# CM-18 16T96：User Legacy fresh payment-ledger scope

## 1. 结论与范围

GO。Legacy `debitFromLedger` 在 attempt、bounded historical DEBIT、fixed-key/existing 旧门均缺证后，可能直接使用 wallet 并 claim；同 pay 的孤立 REFUND、未知 type/state、CI pay alias 或 other-user ledger 可被忽略并形成新扣款/REJECTED。

本批只保护真正 fresh debit 的当前 pay ledger 存在性，不归因历史行、不验证账户总额或完整资金因果。

## 2. Fresh scope 门

在以下既有门全部通过后、wallet 校验与 attempt claim 之前，复用已有 `findPaymentLedgerCandidates(request.payNo)` LIMIT101（全 type/state、CI pay、按 id）：

- caller/request/user 已校验；
- attempt metadata 与 T76 门已通过；
- T95 bounded historical DEBIT 读取为空；
- fixed operationKey lookup 无 existing replay。

规则：

- null list → `BALANCE_DEBIT_UNKNOWN`/409，固定“扣款归属缺少对应账本事实”；
- 任意非空候选（null row、单/多行、REFUND、未知 type/state、CI pay、other user）→ `IDEMPOTENCY_CONFLICT`/409，固定“支付号已有资金账本事实”；
- 仅可靠 empty 才继续原 wallet/REJECTED/APPLIED fresh 链。

DAO/DataAccess 异常原样传播，不转换为业务错误；不按 metadata 过滤候选，不把坏行伪装为空。

## 3. Replay 与保持项

existing operationKey replay 必须在新 fresh scope 前返回既有 ensure/state/snapshot/cache 结果，不能读取 payment-ledger scope；合法 REFUND 历史也不拦 existing replay。无 attempt 历史 replay 仍沿 T95 bounded DEBIT/existing 规则，不触发 fresh scope。

不改 caller/request/user/attempt/historical/fixed-key 优先级、wallet 门、claim/写入、SQL/DDL/API、TCC/退款/Redis。T89/T94/T95 其它 scope 与 TCC/confirmed/replay 逻辑保持。

## 4. 验收

- Unit：null list、DAO fault、empty、任意 null/nonempty 候选、旧门优先、existing replay 不查新 scope；断言 no-write/no-cache。
- Native MySQL/HTTP：两 Legacy 入口 × 正常/不足钱包 × same-pay orphan REFUND/unknown/type/CI/other-user/101 candidates，均在 wallet/claim 前 409；四表 facts、wallet、cache 不变。
- 健康 empty、合法 fresh APPLIED/REJECTED、历史 replay 与 T95/T89 回归；资源 finally 清理，不启动 RM/TC/App。
- 查询计数如实反映仅 fresh 增加一次全 type SELECT；不宣称全历史或账户因果。

## 5. 交付边界

实现仅允许在 fresh 旧门之后加入已有 `findPaymentLedgerCandidates` pure existence check、必要 JavaDoc/测试；不得改 existing replay、T95 historical query、写 SQL 或其它资金路径。
