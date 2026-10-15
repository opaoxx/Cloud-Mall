# CM18 16T212：Pay TCC execution read 完整载荷规格

## 1. 评审结论与范围

本批 **GO**。T210/T211 已校验 payNo/XID/state/decision，但 terminal cache 或 Bridge 503 路径仍可返回非正金额、userId 0、空 order 或错误 operation/ledger，绕过 Evidence 继续 TC。本批一次补齐 execution point read 的既有创建 payload 域，不再拆分字段轮次。

## 2. 最小 read payload 门

在现有 payNo/XID/state/decision 门之后增加私有纯检查 `hasValidRecoveryPayload(execution)`：

- payNo 非 null 且匹配 `[A-Za-z0-9_-]{1,64}`；
- userId 非 null 且 `>0`；
- orderNo 非 null、非 blank、Java UTF-16 长度 `<=64`；
- amount 非 null、`>0`、`<=9999999999999999.99`，且 `stripTrailingZeros().scale()<=2`；
- operationKey 精确为 `tcc-debit:`+payNo；ledgerKey 精确为 payNo。

金额先比较上限，再检查 scale，避免极端指数或精度值先转换/溢出；不 trim、round、normalize、不新增金额词法，不 parse XID，不推断 state/decision 组合。任一失败沿既有 `PAY_TCC_EXECUTION_CONFLICT/409`，早于 Bridge、terminal cache、observe、TC。

仅修改 `Store.read` 的 point-read payload 门；其它 Store 方法（claim、validateRequest、locked、observe、decide、bind）及 SQL/事务/Coordinator/Evidence/API 不改，不修历史，不新增来源查询。NULL XID prebegin 和合法缺行/decision 行为保持；bad payload 在 Bridge 前阻断，execute 旧异常决议流程不扩展。

## 3. 验收矩阵

### 3.1 Unit/真实 Coordinator

- Unit使用mock Mapper：native的17坏变体，加6个NULL、pay/order的65 ASCII字符、金额.001/超MAX/极端scale三个样本，共28 × 两state × 三decision × NULL/valid XID=336组，读409、一次查询、对象全字段不变、无其它Mapper动作。
- 真实Coordinator/Store配mock Mapper/Bridge/Evidence/TC/receipt：8坏变体 × COMMIT_REQUESTED/COMMITTED × XID2=32组，decision固定COMMIT，在Bridge前拒绝。其它决议的正常恢复由201–211既有整套矩阵回归，不声称本批新Coordinator组合覆盖ROLLBACK。
- 正6载荷模板（min/max/raw文本/Unicode订单/10.000/1E+1）× 四state × XID2=48组，返回同对象/全字段表示不变，不把raw组合作为合法状态机证明。NULL字段、ASCII超长及不可存金额仅PO mock，不变更DDL。

### 3.2 Owned native

- 使用真实 ExecutionStore/Mapper 与 owned execution、Pay、Intent 三表；Coordinator 真实，Bridge/Evidence/TC/receipt 为 mock；不启动业务 App、TC/RM、新监听或官方 FenceHandler。
- 旧red：COMMITTED金额0返回VO而非409，2个断言失败；COMMIT_REQUESTED错误operationKey经Bridge503继续TCcommit/诊断写，5个断言失败。17坏变体（pay空/标点、user0/-1、order空/space/emoji33、amount0/负、operation与ledger各空/other/case/尾space）× 两state × XID2=68组，decision固定COMMIT，read/recover均409、无Bridge/Evidence/TC且三表不变。
- 正4模板（min/max/raw文本/emoji32）× 四state RUNNING/COMMITTED/ROLLEDBACK/CONFLICT × XID2=32组，原字段/三表不变。只变更owned execution载荷，原Pay/Intent仍为种子值，不证明三表来源一致性。按变异payNo读取/恢复，避免把找不到行当作拒绝。emoji33是数据库可存的33字符/Java UTF-16 66；ASCII超长、NULL和不可存金额仅Unit，不修改DDL。
- read原无事务注解，native未套Spring事务代理；同owned数据源实际Mapper，不证明完整事务隔离或TC/RM链路。原NULL prebegin/404/支付号别名/合法决议重试与201–211整套回归，原库全表指纹、owned精确DROP与有界池关闭保持。
- 本批只证明 execution point read payload，不证明 Pay 原 source、User/Order、TC 或完整资金因果；原 201–211 仅整体回归。

## 4. 明确边界

本批不改变金额字段生产/写入口、state/decision 组合、XID、SQL、事务、Coordinator execute、Bridge/Evidence、其它 Store 方法、API 或历史数据；bad payload 在 Bridge 前阻断，不扩展读后窗口，不增加来源查询或 Fence 证明。
