# CM-18 16T17：User receive 重放与分支绑定来源门评审

## 1. 结论与范围

结论：**GO，限定修复既有 `UserTccReceiveStore.claim`/`bindBranch` 的接收来源元数据门；不改变 INSERT/UPDATE SQL、DDL、API、flags、RM 或资金写入。**

当前 claim 在唯一冲突 readback 中对 stored.amount 直接 compare，legacy NULL 会 500；bindBranch 对非正 branch 可继续进入 Mapper，且 readback 未核 stored.payNo，CI payNo alias 可能更新/确认另一条原记录。

## 2. claim 门

在既有 INSERT IGNORE 与 `lockByPayNo` readback 之后、所有 non-null 字段比较前：

- `stored.amount == null` 必须沿既有 `IDEMPOTENCY_CONFLICT`/409；其他非 null compare 保持；
- 不重构 request 校验、金额规则或 claim SQL；坏旧 row 不进入 resource/query/RootContext 分支。

claim 仍保留 true winner/false replay、唯一身份/订单/XID/operation/ledger/金额比较与 REQUIRES_NEW 事务语义。

## 3. bindBranch 门

- `branchId != null && branchId <= 0` 在既有事务入口先抛 `IDEMPOTENCY_CONFLICT`/409，再调用 Mapper；允许原 branchId=null no-op。
- 原 SQL `UPDATE ... WHERE pay_no=? AND xid=? AND branch_id IS NULL`、CI 候选和 lock readback 保持，不新增 pre-SELECT/BINARY WHERE。
- readback 必须额外要求 `Objects.equals(stored.payNo,payNo)`、stored.xid 精确相等、stored.branchId 精确等于传入 branch；alias/rebind/NULL/wrong branch 409，REQUIRES_NEW 回滚整次 update（原时间/branch/facts不变）。
- 不新增 state 校验、入参重构、金额全量规则或资金/RM/TC 操作；正首次绑定/同分支重试和 branch null no-op 保持。

## 4. 验收矩阵

- Unit/HTTP：claim stored NULL amount 409 且 resource/query 不调用；bind branch 0/negative 不执行 SQL；alias payNo readback 409 且原行 branch/time/facts不变；合法 true winner/false replay、首次正 branch、同 branch retry、null no-op 保持。
- Owned User receive 表真实 Mapper/CGLIB REQUIRES_NEW：允许原 INSERT IGNORE、UPDATE、SELECT FOR UPDATE，验证 source checksum、单行完整性和 finally 清理。
- Actual HTTP prepare 使用合法 headers/DTO，坏 legacy NULL amount 409 且不 bind RootContext/resource/query；无真实 RM/TC/业务 App/固定端口。旧 state/http、T9–T16 和 root build 回归保持。

## 5. 交付边界

本评审只冻结 receive claim/bind 元数据保护，不改变 UserTccReceiveMapper SQL/DDL/API、LocalTCC 资源、资金状态机或历史修复。
