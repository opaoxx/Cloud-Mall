# CM-18 16T24：User 历史终态冻结归属只读证据评审

## 1. 结论与范围

结论：**GO，观察 User 当前可见 CONFIRMED/CANCELLED 冻结行的有界归属；不验证未知态/空取消、全账户因果或 CM18 主项闭环。**

新增 default-off `cloudmall.user.tcc-terminal-frozen-ownership-evidence.enabled=false` 与受保护 GET：

```text
GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/terminal-frozen-ownership-evidence
```

复用 T9 gate/local flag、token/caller/positive userId/ASCII payNo/noTx-noXID/no-store/Gateway 门；不改既有 User API/flags/RM/TC/写状态机。

## 2. Candidate 读取与上限

外层单 `readOnly=true,REPEATABLE_READ` Reader 调用现 Spring 代理 `UserTccReconciliationReadService`（REQUIRED 加入外层 RR），再由构造器创建的 plain `UserTccStateQuery` 仅用于 peer proof，并调用新增无锁 SELECT：`user_id=? AND state IN ('CONFIRMED','CANCELLED') ORDER BY updated_at,id LIMIT 101`。不以 BINARY WHERE 过滤 case/accent/trailing candidates，101 sentinel 超限立即缺证，不逐 peer 查询。

base `LOCAL_CONFLICT` 直接保留、不查候选；其他 base 缺证不解除且不给 total/hash。

## 3. Candidate 与 peer proof

每候选必须核 userId、ASCII payNo、orderNo、`operationKey=tcc-debit:<payNo>`、非空 XID、正 branch、terminal state、正 DECIMAL(18,2) amount、非负合法 snapshot 与 `balanceBefore-balanceAfterTry=amount`；重复 payNo/operationKey 为冲突，不输出其他 peer 原始 ID。

使用构造器 plain `UserTccStateQuery` peer.read：receive/attempt TCC/debit/ledger/Fence 原事实必须一致；CONFIRMED 要 ledgerApplied=true，CANCELLED=false；Fence 缺失保持原 terminal TTL 语义，不强造来源。每 peer 额外 `BalanceLedgerMapper.selectDebitByPayNo(payNo)` 检查全状态 DEBIT：取消存在任何 DEBIT 即冲突；确认要求原 tuple/key/type=DEBIT/state=APPLIED/amount/snapshot copy 同候选。DataAccess 重抛 wrapper 503；404 缺证、409 冲突、其他 Runtime 缺证，不回显异常。

## 4. 输出与分类

响应固定 `scope=USER_TERMINAL_FROZEN_OWNERSHIP_OBSERVED_ONLY`、`automaticActionsAllowed=false`、`otherDebitStatesNotInspected=true`、`attemptOnlyCancelledNotInspected=true`、`accountCausalityNotProven=true`、`terminalFenceMayBeExpired=true`，并含 `queryAttempted`、`observedCount`、`candidateLimit=100`、`candidatesComplete`、`validatedConfirmedCount`/`validatedCancelledCount`（Integer）、`vectorSha256` 与 `[PAY,ORDER,STOCK,TC,MQ]` 未查询列表。不存在 `validatedFrozenTotal`，不输出任何历史金额 total。

vector 按原 payNo SHA 升序拼接 `payHash:xidHash:branchHash:opHash:state:amount:before:afterTry;` 再 SHA；不输出 raw rows/keys/XID/errors/token。仅 base=`BLOCKED_EXTERNAL_EVIDENCE` 且全部候选完成时输出 validated counts/vector hash；源缺证时 counts/hash 均为 null。不比较当前钱包，不验证全账户因果。

最高 `BLOCKED_EXTERNAL_EVIDENCE`；base 缺证/候选缺证/冲突不抬高。不存在候选只表示当前 CI terminal 范围未观察到，不表示未知态/空取消/全局无资金。

## 5. 验收边界

覆盖 gate/no-store/Gateway、合法两 terminal/Fence 清理、错误身份/快照/state/ledger、100/101 上限、source 优先、vector 脱敏；owned MySQL 同 RR 单连接、并发新 terminal 不混读、原库 checksum/完整 facts/资源清理，旧 User/T12/资金回归。无 DDL/index/写/RM/TC/App/固定端口。
