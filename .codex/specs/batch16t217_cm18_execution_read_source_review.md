# CM18 16T217：Pay TCC execution read 原 Pay 来源规格

## 1. 评审结论与范围

本批 **GO**。ExecutionStore.read 当前只核 execution 自身域；孤儿 execution、Legacy/错误 tuple execution 或本地 Pay 不匹配时，Coordinator recover 仍可能返回 cached terminal 或继续 Bridge。B2 原语义要求恢复核对原 tuple，但不要求 Pay 当前 status 仍 PENDING。本批只给 read 增加无锁 Pay 来源 point-read。

## 2. 最小来源门

在 execution 行存在且既有 persisted-domain validator 通过之后，调用现有 `PayRecordMapper.selectByPayNo(payNo)` 一次，只读验证：

- 结果必须恰好1行；
- `payment.payNo` 非 null 且与请求 payNo 精确相等；
- `debitMode` 精确为 `TCC`；
- userId/orderNo 使用 `Objects.equals` 与 execution 精确相等；
- payment.amount 非 null 且 `compareTo(execution.amount)==0`。

缺失、重复、别名 payNo、Legacy mode、任一 tuple NULL/不等或金额不等，均沿既有 `PAY_TCC_EXECUTION_CONFLICT/409`；不 trim、不 normalize 金额、不限制 payment.status。该门在 read 返回前执行，read 无写、无锁、无事务新增；缺 execution 404 与既有域/identity 优先保持。

claim、locked、observe、bind、decide、Coordinator、SQL、API 和其它 Store 不改；不新增 Pay 锁或全状态机规则。

## 3. 验收矩阵

### 3.1 Owned native

- 使用真实 ExecutionStore、Execution Mapper、PayRecordMapper 与 owned execution/Pay/Intent 三表；Coordinator/Bridge/Evidence/TC 为受控依赖，不启动业务 App、TC/RM、新监听或 Docker。
- 覆盖孤儿、重复 Pay、payNo alias、Legacy mode、user/order/amount NULL 或错误、金额不等；cached terminal 与非 terminal recover 在 read 前均 409，三表无写、无外部 RPC。
- 合法 TCC Pay 的 PENDING、SUCCESS、REFUNDED 等 status 均保留；tuple/金额数值等价保持，status 不成为拒绝条件。原 Pay/Intent source 指纹与 owned cleanup 保持。
- 该门只证明当前本地 read 窗口的 Pay 来源，不证明 read→RPC/CAS fencing、真实资金因果或历史修复。

### 3.2 Unit/Coordinator

- Unit mock PayRecordMapper 覆盖 0/1/2 行、foreign/alias、mode、tuple、amount、DAO 异常和优先级；断言 read 无写、无新增锁/事务。
- 真实 Coordinator 使用 Store/Mapper 或受控依赖验证 source conflict 在 Bridge/Evidence/observe/TC 前阻断；合法 TCC status/缺端既有 UNKNOWN/terminal 语义保持。

## 4. 明确边界

本批不校验全账户、其它 Pay 记录、全局 payNo 唯一性、Fence、User/Order 远端、读后漂移或资金状态机组合，不修改 Pay 表、不补历史、不把 status 纳入 source 门。源查询仅增加一处只读 Mapper 调用。
