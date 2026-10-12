# CM-18 16T76：User Legacy debit money 与 replay evidence

## 1. 结论与范围

GO。Legacy `debitFromLedger` fresh 路径当前在认领 attempt 前直接比较 `user.balance`，NULL 会 NPE，负数/非法精度/容量值可能进入扣款或形成坏 REJECTED/APPLIED snapshot；历史 attempt 与 existing ledger 的 amount、operation key、origin 和余额快照也未完整证明。

本批只收紧 Legacy debit 的当前钱包、attempt metadata、existing ledger tuple/snapshot 纯字段门。不新增 SQL、锁、接口、状态机、TCC/RM、Redis 或历史修复。

## 2. Fresh 顺序

保留 caller/request/DTO 金额门、用户读取、attempt/historical/existing 优先级。所有既有归属冲突判断完成后、attempt insert/任何余额写入/缓存前：

- `user.balance` 必须非 null、非负、无损落在 DECIMAL(18,2) 域；
- 不满足时沿 `BALANCE_DEBIT_UNKNOWN`/409，不能认作余额不足，且不 claim、写 ledger、更新余额或缓存。

request 已由 T71 共享 `validateMoney` 校验；本批不改其错误码/消息。

## 3. Attempt 事实门

`ensureLegacyAttemptMatches` 保持现有 mode、user/pay/order、operationKey/ledgerKey、xid/branch 及调用顺序；在 amount compare 前增加 attempt.amount 非 null、正数、合法 DECIMAL(18,2) 门。坏 attempt 沿 `IDEMPOTENCY_CONFLICT`/409，避免 NULL NPE；caller、request、User 与 TCC mode 等更早既有优先级不变。

## 4. Existing ledger replay 门

`ensureDebitMatches` 保持 user、DEBIT、pay/order 与请求 amount 的原协议，并补：

- existing.operationKey 精确等于 request.operationKey；
- existing.originalOperationKey 必须为 null；
- existing.amount 非 null、正数、合法 DECIMAL(18,2)，且与 request 数值相等。

上述 tuple 失败沿 `IDEMPOTENCY_CONFLICT`/409。冻结顺序为：tuple → attempt state mismatch（`BALANCE_DEBIT_UNKNOWN`）→ 非 APPLIED/REJECTED state（`BALANCE_DEBIT_UNKNOWN`）→ 两个已知 state 的 snapshot proof → snapshot 合法后，REJECTED 沿原 `PAYMENT_FAILED`，APPLIED 沿原 cache/result。坏 REJECTED snapshot 的新 UNKNOWN 覆盖旧不足标签；健康拒款仍保持原优先级。

当 existing state 已确定为 APPLIED 或 REJECTED 后、返回/缓存之前，追加 snapshot 门：

- APPLIED：balanceBefore/balanceAfter 非 null、非负、合法 18.2，且 `before-after=amount`；
- REJECTED：两余额同样合法，`before == after` 且 `before < amount`，证明原拒款确因余额不足；
- 任何 NULL、非法金额、错误 delta 沿 `BALANCE_DEBIT_UNKNOWN`/409，不返回成功或 PAYMENT_FAILED。

该门不读取当前 wallet；合法历史 replay 即使当前钱包为 NULL 也保持原 replay 语义。

## 5. 保持项与非目标

保 fresh rejected 的 attempt/ledger 写序、余额不足条件、`PAYMENT_FAILED`、APPLIED cache/result、无 attempt 的历史重放和现有 DAO 异常传播。不能借本批修复历史双记录、全账户因果、TCC、退款集合或历史账本。DTO/JSON、SQL、锁、事务、API、状态机、Redis/消息不变。

## 6. 验收

- Unit：fresh bad wallet；attempt amount NULL/zero/negative/scale/capacity；existing wrong/case operationKey、origin、amount、APPLIED/REJECTED NULL/坏 snapshot/delta；断言错误码优先、无不应有 claim/余额/ledger/cache 写。
- 健康：cent、尾零、MAX、真实余额不足 REJECTED、APPLIED replay、无 attempt 历史 replay；current wallet NULL 不影响历史 replay。
- Owned MySQL/HTTP：真实 Legacy fresh/replay/兼容入口坏事实返回固定 409，四表 facts/checksum 不变；合法拒款不重复扣款。测试专用 nullable/宽列 finally 恢复，不改生产 DDL，不启动 RM/TC/App。
- 保留 T70–T75 与既有资金回归；测试未通过不得宣称闭环。

## 7. 交付边界

实现仅允许调整 `UserServiceImpl` Legacy debit 相关纯字段 helper/门、必要 JavaDoc/步骤注释及对应测试，不扩展其它资金服务。
