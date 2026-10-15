# CM18 16T214：Pay TCC execution command input 域规格

## 1. 评审结论与范围

本批 **GO**。`decide` 对持久 decision null 使用 `Set.of(...).contains(null)` 可 NPE；`observe` 对 current state null 调用 terminal 可 NPE；`validateRequest` 先 `stripTrailingZeros` 再金额上限比较，极端 BigDecimal 可能抛算术异常而非既有 conflict。另 decide 的 current XID NULL 与 incoming NULL 可走到 SQL0，命令输入应在 locked/current 域通过后更早拒绝。

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
- NULL state observe、NULL decision decide 的旧 red 均曾 NPE；修复后沿既有 409、三表不变。极端 amount 的 request 仅 Unit 验证，避免不可存/不可构造数据污染 native。
- native command 矩阵覆盖 NULL/blank/超长 incoming XID、非 canonical decision、持久 XID NULL/valid、active/terminal，并保持缺行/坏 persisted domain 优先；正常 observe/COMMIT/ROLLBACK、NULL prebegin、合法金额等价回归。
- native 不宣称 Spring REQUIRES_NEW/Seata/TC 集成，Source/owned 指纹、DROP、池关闭保持。

### 3.2 Unit

- real Store + mock Mapper 覆盖 decide/observe 的 null state/decision、非法 incoming token/decision、current XID NULL、Mapper 0/异常对象优先；确认无后续 SQL/写。
- `validateRequest` 覆盖极端 scale、0/负/超额及合法尾零/指数文本；非法均既有 conflict，不产生 ArithmeticException。

## 4. 明确边界

本批不重构 command 输入、不新增 token/状态/decision 组合规则、不改变 claim/locked/observe/decide SQL、Coordinator/Evidence、API、历史数据或读后 fencing；只收敛非法输入/持久 null 的异常类型与早期无写行为。
