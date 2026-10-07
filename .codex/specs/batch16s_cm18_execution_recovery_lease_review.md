# CM-20261007-18：16S execution recovery 过期 lease CAS 保护评审

## 1. 评审结论

结论：**GO，限定修复 execution recovery 完成 CAS 的过期 lease 边界；不改变恢复候选、claim、资金状态或决议逻辑。**

当前 `PayTccExecutionRecoveryMapper.finishRecovery` 只按 payNo/token 更新 `recovery_owner`、`recovery_lease_until` 和 `next_recovery_at`，没有精确 BINARY 比较，也没有要求 `recovery_lease_until` 仍晚于当前时间。过期但尚未被新 worker 接手的旧 owner 可以清理 lease 并推迟重试；该方法同时被普通 execution recovery 与 16G rollback projection 共用，可能影响两条恢复链。

## 2. 修复范围

### 2.1 Mapper

保持 `finishRecovery` 的业务字段和调用用途不变，新增 `@Param("now") LocalDateTime now`，WHERE 必须满足：

```sql
BINARY pay_no = BINARY #{payNo}
AND BINARY recovery_owner = BINARY #{token}
AND recovery_lease_until IS NOT NULL
AND recovery_lease_until > #{now}
```

过期、等于、NULL、大小写不同 owner 或旧 owner 在新 owner 已接管后均返回 0，不修改任何恢复字段。不得修改 execution state、decision、XID、dispatch、receipt、Pay 状态或 User 事实。

### 2.2 Store

保留 `PayTccExecutionRecoveryStore.finishRecovery(String payNo,String token,LocalDateTime nextAt,String error)` 四参数 public 方法及所有现有调用方。方法内部按 `Asia/Shanghai` 取一次 `DATETIME(3)` 语义的当前时间，并把该值传给 Mapper `now`；不新增业务参数、不让调用方绕过 lease 校验。错误长度限制和原 nextAt 退避语义保持不变。

### 2.3 不变边界

- 不修改 `findDue`、`findDueRollbackProjection` 的候选 SQL、批量上限、轮换优先级或预算。
- 不修改 `claimRecovery`/`claimRollbackProjection` 的 lease 获取规则。
- 不重新 begin/Try，不调用 TC，不以缺 XID 或 BEGIN_UNKNOWN 猜测取消。
- BEGIN_UNKNOWN、XID 缺失和决议未知仍按现有保守边界跳过/等待。
- 普通资金 recovery 与 16G rollback projection 继续复用同一 finish 方法，但共享本地 CAS 必须拒绝迟到 owner。

## 3. 竞争语义

旧 worker 在 RPC 返回后调用 finish：

- lease 仍有效且 owner 精确匹配：正常写入 next_recovery_at/error 并清理 lease；
- lease 已过期但尚未接管：finish 返回 false，完整恢复快照保持不变；
- 新 worker 已 claim：owner 不匹配，finish 返回 false，不清理新 lease、不覆盖新退避；
- owner/token 仅大小写不同：BINARY 比较失败；
- `recovery_lease_until = now`：严格失败，避免边界 worker 写入。

## 4. 验收矩阵

1. 真实 MySQL 固定 now 验证有效 lease finish 成功，过期、等于、NULL、大小写 owner finish 均返回 0 且全快照不变。
2. 过期但未接手的旧 owner finish 失败，随后新 owner 可 claim 并继续恢复。
3. 新 owner 已接管后旧 owner finish 失败，不清理 owner/lease，不改变 next_recovery_at/error/attempts。
4. 普通 COMMIT/ROLLBACK recovery 和 16G ROLLEDBACK projection 分别覆盖 finish 回归；不改变 state/decision/XID/Pay/User 事实。
5. 真实 Automatic/rollback 自动恢复用例回归通过；单测确认 Store 四参数 public API 未变。
6. `git diff --check`、相关 Maven 测试和全仓构建通过。

## 5. 实施门

实现前冻结固定时间和全快照对比测试；提交前必须通过真实 MySQL lease 边界及旧 owner/新 owner 竞争验收。本轮不宣称 BEGIN_UNKNOWN、缺 XID 或 TC Unknown 已解决。
