# CM-18 16T94：User no-funds bounded DEBIT scope

## 1. 结论与范围

GO。`BalanceTccDebitServiceImpl.requireNoLedger` 仍先用单 PO `selectDebitByPayNo`；同 pay 多条 DEBIT 会在 T89 全 type 门前抛 TooManyResults，无法稳定返回既有 `IDEMPOTENCY_CONFLICT/409`。

本批只把第一段 DEBIT 读取改为有界 candidate list；不改变 T89 全 type scope、固定 key 门、调用范围或资金状态机。

## 2. 有界 DEBIT 读取

保留原顺序：先读同 pay 的 DEBIT scope，再读固定 `ledgerKey`，再进入 T89 全 type payment ledger scope。

第一段改用已有 `findDebitCandidatesByPayNo(request.payNo)` LIMIT2：保留 CI/全 state DEBIT 候选、按 id，不新增 SQL/Mapper/API/锁。

- list null → 原 `BALANCE_TCC_UNKNOWN`/409；
- 非空任意候选（null row、单行、多行、APPLIED/REJECTED/UNKNOWN、CI type/pay alias）→ 原 `IDEMPOTENCY_CONFLICT` 与 message “支付或账本键已有扣款事实”；
- 只有非 null 且 empty 才继续固定 key 读取。

DAO/DataAccess 异常原样传播，不转换为业务 conflict；LIMIT2 不按 state 过滤隐藏重复 DEBIT。

## 3. 保持项与调用边界

所有现有 `requireNoLedger` 调用复用该门：fresh Try、首次 Confirm、TRYING/CANCELLED replay、Cancel 与空取消；用户锁、enabled/status、attempt 优先级、T89 全 type scope、CONFIRMED/T88、资金写、TCC/Fence、API/DTO 保持。

不改固定 key 查询、不改 full payment ledger 查询、不修历史、不核 current wallet、不扩完整账户/全历史因果。

## 4. 验收

- Unit：null list、DAO fault、single/multiple/nullrow、坏 state/type/pay alias，旧固定 key 优先与 no-write 断言保持；非空 candidate 在 T89 前拒绝。
- Native：真实 User owned 事务覆盖 Try/Confirm/Cancel 适用入口的重复 DEBIT，返回 409、无 attempt/freeze/ledger/钱包写；empty 健康、other-pay、CONFIRMED/T88、invalid wallet/status 优先保持。
- 查询计数一替一，LIMIT2 三行仍冲突；source checksum、四表 facts、资源清理及 T89–T93 回归保持，不启动 RM/TC/App。

## 5. 交付边界

实现仅允许将 `requireNoLedger` 第一读替换为现有 bounded Mapper 方法、必要 JavaDoc/测试断言；不得新增 SQL/API/状态或修改其它 scope。
