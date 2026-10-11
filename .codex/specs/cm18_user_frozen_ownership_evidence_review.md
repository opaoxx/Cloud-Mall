# CM-18 16T12：User 当前 TRYING 冻结归属只读证据评审

## 1. 结论与范围

结论：**GO，证明 User 当前可见 TRYING 冻结行的归属与总额；不改变 16T9 账户观察、16T11 退款候选、User 资金写状态机或其他服务。**

本切片只覆盖当前 `balance_tcc_debit.state=TRYING` 候选，不验证 CONFIRMED/CANCELLED 历史行、全 User 财务因果、TC/MQ 或全系统冻结。

## 2. 入口与 RR 读取

新增 default-off：

```text
cloudmall.user.tcc-frozen-ownership-evidence.enabled=false
```

新增内部 GET：

```text
GET /api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/frozen-ownership-evidence
```

- 复用 16T9 local flag 与无 DB `validateForRead`；token、caller=`cm18-reconciliation`、positive userId、ASCII payNo、无 SpringTx/SeataXID 在连接前校验；成功/Biz/Long 绑定错误均 no-store，Gateway 禁止。
- 新 Reader 在单外层 `@Transactional(readOnly=true,isolation=REPEATABLE_READ)` 调用现有 Spring 代理 `UserTccReconciliationReadService`（REQUIRED 加入新外层 RR），并由构造器创建 peer 用的 plain `UserTccStateQuery` delegate，再调用新增 `BalanceTccDebitMapper.findTryingOwnershipCandidates(userId)`：无锁纯 SELECT，`user_id=? AND state='TRYING' ORDER BY updated_at,id LIMIT 101`，不以 BINARY WHERE 过滤候选。
- 不调用写、RM、RootContext、TC、HTTP、恢复器；候选超过 100 只缺证，不逐 peer 查询、不输出 total/vector hash。

## 3. Candidate 与 peer proof

每个候选先核对：userId 精确、payNo ASCII text/正长度、`operationKey=tcc-debit:<payNo>`、orderNo 非空且<=64、XID 非空且<=128、branch 正、state 精确 TRYING、amount 正且 DECIMAL(18,2)、balanceBefore/balanceAfterTry 非负且差值等于 amount；重复 exact payNo 或 operationKey 为冲突，不输出其他 peer 原始 payNo/XID/branch/key。

原 payload 只来自候选 PO；用现有五 Mapper 构造 plain `UserTccStateQuery` delegate.read(userId,operationKey,xid)。该 peer proof 必须满足 receive/attempt mode=TCC/debit 与原 tuple、DEBIT 不存在、Fence status=1；不绑定 RootContext、不注册 RM/branch、不写任何事实。404 → `FROZEN_PEER_RECEIVE_MISSING` 缺证；409 → `FROZEN_PEER_PERSISTED_CONFLICT` 本地冲突；其他 Biz 或未分类 Runtime → `FROZEN_PEER_EVIDENCE_UNAVAILABLE` 缺证，不输出字段/错误；DataAccessException 必须重抛给 wrapper 固定 503，不能把数据库异常伪装为无冻结。

peer 返回后再次精确核对 user/pay/order/amount/operation/ledger(payNo)/XID/branch/state TRYING、ledgerApplied=false；任何不符为冲突。

## 4. 总额与分类

- 所有候选完成且<=100 时，sum(amount) 必须同时等于 16T9 base account.frozenBalance 与 base tryingFrozenTotal；不相等为 `FROZEN_OWNERSHIP_TOTAL_CONFLICT`。其他交易冻结非零正常，不要求全账户归因。
- 0 候选只表示当前 user 的 TRYING 候选范围未观察到，不表示全局无冻结/资金行为。
- base `LOCAL_CONFLICT` 直接返回 `queryAttempted=false`，不查询候选；账户/frozen/tryingTotal 缺失、格式/金额范围错误固定缺证，不抛异常。base 其他 source 缺证保持 base 分类，不抬高、不因候选完整而消除，也不给 total/hash。最高 `BLOCKED_EXTERNAL_EVIDENCE`。

响应固定 `scope=USER_TRYING_FROZEN_OWNERSHIP_OBSERVED_ONLY`、`automaticActionsAllowed=false`、`terminalFrozenRowsNotInspected=true`、baseEvidence、`queryAttempted`、`observedCount`、`candidatesComplete`、`validatedFrozenTotal`、`vectorSha256`，以及 `externalSystemsNotQueried=[PAY,ORDER,STOCK,TC,MQ]`。vector 按 peer payNo SHA 升序拼接 `payHash:xidHash:branchHash:opHash:amount:before:afterTry;` 再 SHA-256；不输出 raw rows/keys/error/token/XID。超 100、坏候选、peer 缺证/冲突或 base 缺证不输出 total/hash。

## 5. 验收与交付边界

- Unit：gate/no DB、native money/identity、缺 source、partial source、peer 404/409、opposite state、duplicate/alias、总额冲突、100/101、vector 脱敏。
- Owned User MySQL：同 RR/单连接，合法两个 other TRYING、缺 receive/attempt/Fence、坏绑定、CI state 候选、窗口 writer 新增 freeze 不混读；原表 checksum/完整 row facts 不变，owned finally 清理。
- 只新增本批独立 default-off 诊断 flag/GET，不改既有 User API/Feign、旧 flags、资金写 SQL/DDL/index，不启动 App/TC/RM/固定端口；不验证历史 CONFIRMED/CANCELLED、余额因果、Pay/Order/Stock/MQ 或统一包。
