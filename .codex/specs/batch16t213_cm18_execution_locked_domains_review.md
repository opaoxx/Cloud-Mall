# CM18 16T213：Pay TCC execution locked 域复用规格

## 1. 评审结论与范围

本批 **GO**。T210–T212 只保护 public `read`；`locked` 仍只核 payNo，因此 bindXid/decide/observe 可在已存坏行上 early return 或 CAS，例如 amount=0 仍 observe 终态、blank XID 与同 blank 入参仍 decide。范围限定为把既有 read 域门复用于锁内入口。

## 2. 共享 persisted execution validator

将 read 中既有校验按原顺序抽为私有纯函数 `validatePersistedExecution(row,payNo)`（可将 `hasValidRecoveryPayload` 改准确私有命名但不做其它重构），包含：

1. payNo 非 null 且与请求精确相等；
2. XID NULL 保留 prebegin，非 NULL 必须非 blank 且 UTF-16 长度≤128；
3. state 为既有9值，decision 为既有3值；
4. 完整 payload/fixed-key 域：payNo canonical、userId 正数、orderNo 非 blank/≤64、amount 正数/最大 DECIMAL/scale≤2、operationKey/ledgerKey canonical。

`read` 保持 null→404 后调用该 helper；`locked` 保持 null/缺行既有 conflict 后调用同一 helper。任何 bindXid/decide/observe 的 early return、decision CAS、observation update 或其它 SQL 前都必须先通过该 helper。只复用既有单字段/载荷域，不新增 state/decision/XID 组合规则、不 normalize、不修历史、不加来源查询。

## 3. 验收矩阵

### 3.1 Owned native

- 使用真实程序化 TransactionTemplate、ExecutionStore/Mapper 与 owned execution/Pay/Intent 三表；不套 Spring 代理，不启动业务 App、TC/RM、新监听或 Docker。
- red：amount=0的observe(COMMITTED)原实现写终态，空串XID+同空串入参的decide(COMMIT)原实现写请求态和COMMIT决议，两测试各3断言失败。修后28坏变体（payload17/XID空白3/state4/decision4）×两seed state×三action=168组409/三表不变。bind输入固定有效t213-xid，decide输入取当前XID（可为空白）；某些bind组合旧版已有mismatch拒绝，不声称168组旧版全放行。
- 读取/锁定坏域覆盖 NULL 字段由 Unit；native 仅使用 schema 可存值。4 个合法 payload 边界 × bind/decide/observe=12 组验证实际 TransactionTemplate/Mapper 写入与重复幂等；合法状态/字段、affected=1、SQL0、NULL prebegin、404、locked conflict、terminal 幂等保持。
- T172 旧 `executionObservationZeroRowsDoNotReturnStaleRecord` 的 CI/PAD 坏 state 现由 locked validator 先拒绝，应改断言为 SQL 未调用/门前拒绝；不得弱化。`executionObservationSqlZeroRollsBackWithoutReadback` 使用 valid locked row，在同事务受控 DB 变化导致 SQL0 时继续保留原 rollback/readback 断言。

### 3.2 Unit

- 真实Store、mock Mapper：44坏变体（payload28/XID4/state6/decision6）×两seed state×XID NULL/valid×三action=528组，只有selectForUpdate，无更新或readback、无其它Mapper动作，原对象不改。NULL/ASCII129/极端金额只PO mock，不改DDL。
- 正6载荷模板×三action=18个early-return/幂等控制，无更新/readback；bind为void，不把测试helper返回当业务返回。另3个fresh bind/decide/observe控制保持原SQL1和所需readback，无新查询。
- PayTccErrorUnicodeTest的EXECUTION夹具补齐user/order/amount/operation/ledger五个合法字段，原Unicode诊断和异常断言不改，无新增测试方法。
- 断言 shared validator 保持原 read 顺序与 error code，不把 helper 变成新的组合状态机或来源证明。

## 4. 明确边界

本批不改 public API、Mapper SQL、锁类型、REQUIRES_NEW、claim/validateRequest、Coordinator/Evidence、状态/decision 组合或历史数据；不保证合法但异源行、read→RPC 窗口或完整 fencing。重点是 locked 入口在任何写/early-return 前拒绝已知坏 persisted domains。

程序化事务验证本地锁与commit/rollback，不证明Spring代理的REQUIRES_NEW传播或Seata事务。调用参数target/decision等其它额外规则不扩；不增加原Pay/User来源或资金因果证明。
