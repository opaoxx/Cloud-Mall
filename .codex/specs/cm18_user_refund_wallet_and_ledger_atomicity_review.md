# CM-18 16T72：User refund wallet 与 ledger 原子提交

## 1. 结论与范围

GO。16T70 已证明原 DEBIT 来源，但 `refundInternal` 在 fresh refund 的最终写入前仍直接使用可能为 NULL、负数或非法精度的钱包余额，并忽略 `balanceLedgerMapper.insert(refund)` 的影响行数。由此可能产生坏 refund snapshot、SQL 溢出，或 credit 成功而无 APPLIED ledger 仍返回成功。

本批只补 fresh refund 的当前钱包域门和 ledger 写入结果门；existing refund replay 不进入新钱包/写入门。

## 2. Fresh 写入顺序

保持 caller/request、T70 原 DEBIT proof、disabled TCC proof、退款合计超额检查的既有顺序与错误优先级。`BALANCE_REFUND_EXCEEDED` 仍在 wallet 检查之前返回。

在 `sumAppliedRefunds` 与超额检查之后、任何 `creditBalance` 之前：

- 当前 `user.balance` 必须非 null、非负、无损落在 DECIMAL(18,2) 域；
- `user.balance + request.amount` 必须不超过 `MAX_BALANCE_MONEY`；
- 不舍入、不截断、不规范化金额，允许零余额、尾零和精确上限。

坏 wallet 或派生余额沿既有 `BALANCE_REFUND_FAILED`/409 语义返回，不执行 credit、ledger insert 或缓存写入。可将 T71/T70 的金额 helper 统一命名为 `validBalanceMoney`，不改变 18.2 数值域及原 DEBIT proof 语义。

## 3. Snapshot 与 ledger 原子性

wallet 校验通过后只计算一次 `newBalance = balanceBefore + request.amount`，同一值用于 `refund.balanceAfter` 与返回 VO；保留原始 `balanceBefore`、request amount 的数值/尾零语义。

`creditBalance` 返回值不为 1 时沿既有 `BALANCE_REFUND_FAILED`/409 返回；由于此路径没有有效 credit，不新增补偿写。

`balanceLedgerMapper.insert(refund)` 必须严格返回 1。其他返回值统一抛 Runtime `IllegalStateException`，使当前 `@Transactional(noRollbackFor = BizException.class)` 事务回滚已成功的 credit，不返回 APPLIED，不写缓存。不得改成可提交的 BizException，也不得补写第二条 ledger。

## 4. 保持项与非目标

不改 DTO/JSON、API 字段、SQL/DDL、锁顺序、事务注解、状态机、TCC/RM、Redis、消息、existing replay、退款合计算法、T70 source proof 或 disabled 404/active 409。只处理当前 fresh refund 的 wallet/snapshot/insert rowCount；不证明账户总额、全部退款因果、历史坏账修复或 Legacy/TCC 全链闭环。

## 5. 验收

- Unit：active/disabled fresh 覆盖 wallet null、负数、非法 scale、超容量、`balance+amount` 超容量、credit 返回 0、insert 返回 0/2；断言无不应有的 credit/insert/成功响应，insert 失败回滚。
- 边界：zero balance、无损尾零、精确容量上限与正常 fresh/replay 保持；existing replay 不读取或写入当前 wallet。
- Owned MySQL/HTTP：真实 wallet 异常返回固定 409，四张资金表事实不变；通过受控 Mapper fault 证明真实 credit 后 insert 非 1 会回滚 credit，不能丢失原账本或返回 APPLIED。测试专用宽 nullable 列/代理资源必须 finally 清理，不改生产 DDL。
- 保留 T70 原 DEBIT proof、active/disabled 错误码、source checksum 与资源清理；不启动 RM/TC/App，不运行 Maven 作为规格冻结前置。

## 6. 交付边界

实现仅允许修改 `UserServiceImpl` fresh refund 相关私有金额/写入门、必要 JavaDoc/步骤注释及对应测试；CM18 其他资金证据、CM20/CM21 仍未闭环。
