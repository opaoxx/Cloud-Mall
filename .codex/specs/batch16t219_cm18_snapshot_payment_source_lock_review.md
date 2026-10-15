# CM18 16T219：Pay TCC snapshot observation payment source lock 规格

## 1. 评审结论与范围

本批 **GO**。T217 的 execution read source 是无锁 point read，T218 snapshot 只保护 execution anchor；Bridge 期间 PayRecord 被删除、改 Legacy 或 tuple 改变时，旧 execution 证据仍可能写终态/UNKNOWN。本批只给 `observeSnapshot` 增加同一本地事务内的 Pay source 锁与域核验。

## 2. Pay→execution 锁序与来源门

在 `observeSnapshot(expected, state, error)` 中，`expected != null` 后先调用既有 `PayRecordMapper.selectForUpdate(expected.payNo)` 取 Pay 锁，再锁 execution；保持 claim 使用的 Pay→execution 锁序，避免先锁 execution 后锁 Pay 的死锁。Pay 锁持有至本地事务提交/回滚；Pay DAO 异常自然先于 execution 锁传播。

调用/校验顺序固定为：Pay 查询取锁 → execution locked/原 persisted 域 → expected 原域 → execution 与 expected 八字段 anchor 比较 → 校验锁得 Pay 列表。最后一步 Pay 列表必须恰好一行，并满足 T217 source 规则：payNo 非 null 且精确等于 expected.payNo、debitMode 精确 TCC、userId/orderNo 精确相等、amount 非 null 且与 expected.amount 数值相等；不限制 Pay status。source 校验不提前短路 execution/anchor 域，避免 expected amount 缺失等坏输入触发错误比较；失败沿既有 conflict。

然后复用 T218 state/decision/terminal merge 和 SQL affected-row 语义。旧 `observe(payNo,...)`、claim/bind/decide/read、SQL、API 不改；不新增 DDL/查询文本，不修历史，不扩 TC/RPC/旧 key 诊断。

## 3. 验收矩阵

### 3.1 Owned native

- 使用 T218 的 proxied execution observation Store：Spring ProxyFactory + TransactionInterceptor/AnnotationTx 验证 REQUIRES_NEW，本地 peer 使用程序化 TransactionTemplate；不是 Seata 全局代理。Bridge/Evidence/TC 为 mock，owned Pay/Intent/Execution 三表真实 Mapper。
- 两条 red：Bridge 窗口另一工作线程改 Pay.mode/amount，execution 全字段保持；旧实现仍写 COMMITTED/UNKNOWN，修复后 Pay source conflict、execution/Pay/Intent facts 不被旧观察改写。
- 覆盖12种可存 Pay source 偏差 × COMMITTED/ROLLEDBACK/UNKNOWN 三 incoming result=36组拒绝，execution 不变；另6种 Pay status × 三观察结果=18组健康控制，金额数值等价保持。
- 锁探针在 execution 已锁定、Pay source 已校验且 `updateObservation` 调用前，由 owned 第二连接尝试 Pay `FOR UPDATE`，设置 session `innodb_lock_wait_timeout=1`，期望1205；测试必须 rollback、恢复 session 参数、关闭线程/连接。该探针证明本地 Pay→execution 持锁顺序，不证明真实 TC/远端并发/完整 TOCTOU fencing。
- PayRecord unique/NOT NULL DDL 不改；来源 NULL/重复仅 Unit mock，不在 native DROP 索引或伪造 schema。

### 3.2 Unit

- `observeSnapshot` 依次验证 Pay source、execution lock、anchor；source null/duplicate/DAO fault 使用 mock，断言顺序、异常对象、无 merge/写。新增独立测试覆盖19种坏源 × 三观察=57拒绝。
- 覆盖6种 Pay status × 三金额表示 × 三观察=54保持，另有 valid anchor/SQL0/terminal/旧 observe 控制；旧 observe 非 snapshot 语义保持。Unit Mapper mock，不冒称 actual Pay 锁。

## 4. 明确边界

本批只保护指定 snapshot observation 事务内的 Pay source 与 execution anchor，不改变 read source、Coordinator 前置 Bridge、其它 Store/SQL、TC/RPC、历史 repair、Pay 全账户因果或 read→RPC fencing。新增 Pay 锁是该方法必要的本地锁序，不扩全流程锁定。
