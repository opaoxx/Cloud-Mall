# CM-18 16T92：User CONFIRMED bounded DEBIT scope

## 1. 结论与范围

GO。UserTccStateQuery CONFIRMED 当前固定 ledger proof 后使用单 PO `selectDebitByPayNo`；同 pay 多条 DEBIT 会被 selectOne/映射异常放大为 HTTP 500 或诊断 503，不能稳定分类为持久事实冲突。

本批只把 CONFIRMED scope 读取替换为已存在的 T88 bounded candidate 查询，稳定缺失/重复分类；TRYING/CANCELLED 的 T90 no-funds scope 不改。

## 2. Bounded scope 读取

在 CONFIRMED 原 fixed ledger、amount/snapshot/origin/tuple proof 通过后，调用已有 `findDebitCandidatesByPayNo(payNo)`：全 CI 候选、全 state DEBIT、ORDER BY id LIMIT2，不新增 Mapper SQL/API/锁。

- list null、空、或 size != 1（含 LIMIT2 哨兵）统一原 `USER_TCC_STATE_CONFLICT`/409 与固定消息；
- DAO/DataAccess 异常原样传播，不把所有数据库错误转换为 conflict；
- 唯一 candidate 继续使用既有 `matchesDebitScope(candidate, original)`，不增加 id 或历史字段门。

该门仍精确核 user/pay/order/operationKey、DEBIT/APPLIED、金额、两余额 snapshot 与 origin NULL；不按 state 过滤以隐藏异常状态，不总结其它资金事实。

## 3. 调用边界与保持项

仅 CONFIRMED 分支替换现有 `selectDebitByPayNo` scope 读取，查询计数保持一替一；固定 ledger proof 失败时不调用新 scope。TRYING/CANCELLED 的旧 DEBIT 门、T90 全 type no-funds 门、attempt UNKNOWN、资金写与 TCC callback 保持。共享 reconciliation/frozen/terminal Reader 自然获得 409 分类，但不改变其额外独立读取。

不改 SQL 写、RR、API/VO、状态、Fence/Seata、资金服务、其它 User sources 或完整历史因果。

## 4. 验收

- Unit：CONFIRMED missing/single bad/multiple、APPLIED/REJECTED/UNKNOWN extra DEBIT、CI type/pay alias、null row、DAO fault；missing/multiple/nullrow 409，DAO fault 原样。
- Native RR/HTTP：真实 fixed CONFIRMED ledger 加 extra DEBIT 时 state GET 与诊断入口均沿既有 state conflict 分类返回 409，no-write/同连接断言保持；三行 LIMIT2 仍 conflict。真实缺表/数据库不可用仍沿既有 503。healthy CONFIRMED+REFUND 继续合法。
- 更新 CONFIRMED 查询计数一替一，未放宽 strict no-write；保留 T90/T91 与共享 Reader 回归、source checksum/owned 清理。
- 不启动 RM/TC/App，不宣称完整历史/账户因果或 getter fencing。

## 5. 交付边界

实现仅允许调整 UserTccStateQuery CONFIRMED scope 调用为现有 bounded Mapper、必要 JavaDoc/测试 fixtures；不得修改 TRYING/CANCELLED 或新增 SQL/API。
