# CM18 16T214：Pay TCC execution command input 域规格

## 1. 评审结论与范围

本批 **GO**。`decide` 对 incoming decision null 使用 `Set.of(...).contains(null)` 可 NPE；`observe` 对 incoming state null 调用 terminal 可 NPE；`validateRequest` 先 `stripTrailingZeros` 再金额上限比较，极端 BigDecimal 可能抛算术异常而非既有 conflict。持久 state/decision null 已由 T213 locked 域门处理；本批只收敛 command 输入，且 decide 的 current XID NULL 与 incoming 非法值应在 locked/current 域通过后更早拒绝。

## 2. 最小命令门

### 2.1 `decide`

保持 `locked(payNo)` 及 current persisted domain 优先。其后一次校验 incoming：xid 必须非 null、非 blank、UTF-16 长度≤128；decision 必须精确 `COMMIT` 或 `ROLLBACK`；current.xid 必须非 null 且精确等于 incoming。current.xid 为 NULL 会因该精确比较自然拒绝，不新增持久字段组合约束。失败沿既有 conflict，禁止进入 Set、terminal、decision CAS 或 SQL。已绑定合法 XID、重复同向 decision、终态幂等及相反方向冲突保持。

### 2.2 `observe`

保持 `locked(payNo)` 优先；incoming observation `state == null` 时直接既有 conflict，再调用 terminal。持久 current.state null 已由 T213 locked 域门阻断。非 null incoming state 不新增白名单：TRYING、未知非 null 等仍按原非 terminal 映射 UNKNOWN/REQUESTED，terminal 仍保原规则。所有正常/异常对象、SQL0、readback 和错误截断保持。

### 2.3 `validateRequest`

仅将 amount 上限比较移到 `stripTrailingZeros().scale()` 之前，避免极端 scale 下溢；合法金额集合、BigDecimal 数值语义和不 normalize 行为保持。其它 request 字段、claim/写 SQL/事务/状态机不变。

## 3. 验收矩阵

### 3.1 Owned native

- 使用真实程序化 TransactionTemplate、ExecutionStore/Mapper 与 owned execution/Pay/Intent 三表；不启动业务 App、TC/RM、新监听或 Docker。
- 正式red为两类3测试3失败：native的NULL观察/NULL决议各因应BizException却NPE而失败（各MultipleFailures1，三表原本不变）；real Store.claim极端Java DTO金额因应BizException却ArithmeticException Overflow而失败。native command矩阵为11参数模式×四current state×三current decision×持久XID NULL/valid=264组，每组只selectForUpdate，无其它Mapper调用。长度129是Java命令入参，native与Unit均覆盖，不写DB。
- 正native观察覆盖6非NULL state（含TRYING/opaque）×三decision=18，核原合并结果/仅state/error/updatedAt可变/Pay与Intent保持；另2合法decision fresh+重复与1个NULL prebegin UNKNOWN控制。高精度及极端金额仅Unit真实Java DTO，非数据库字段变更。
- native 不宣称 Spring REQUIRES_NEW/Seata/TC 集成，Source/owned 指纹、DROP、池关闭保持。

### 3.2 Unit

- real Store + mock Mapper 覆盖上述 264 命令矩阵，确认 no SQL/readback、对象不改；locked 缺行/alias/domain 优先沿既有回归。
- 真实Java DTO配mock Mapper覆盖负金额6类（NULL/0/负/.001/超MAX/scaleMIN），0 Mapper；正金额4类捕获claim插入PO原BigDecimal引用，另2 fresh decision与6非NULL观察保持SQL/readback。ASCII129为入参，极端金额为可构造的BigDecimal，不把它们作为真实SQL可存值。

## 4. 明确边界

本批不重构其它command输入、不新增持久状态组合规则，不改SQL/事务/locked/Coordinator/Evidence/公开API/历史数据或读后fencing；只收敛本批非法调用输入的异常类型及早期无写行为。持久NULL由原域门处理，static terminal工具对直接NULL调用不在本批修改范围。
