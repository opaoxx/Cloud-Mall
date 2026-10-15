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

不改 claim/validateRequest/locked/SQL/事务/Coordinator/Evidence/Store/API，不修历史，不新增来源查询；NULL XID prebegin 和合法缺行/decision 行为保持。前置 Bridge 读取/镜像不撤回，execute 旧异常决议流程不扩展。

## 3. 验收矩阵

### 3.1 Unit/真实 Coordinator

- 覆盖 userId null/0/负、order null/blank/65、amount null/0/负/超额/超精度/极端 BigDecimal、operation/ledger null/错误；终态/active/terminal cache 均在 read 409，无 Bridge/Evidence/observe/TC。
- 真实 Coordinator/Store 配合 mock Bridge/Evidence/TC/receipt，COMMIT/ROLLBACK × 非法 amount/identity/key 组合验证不 observe、不发 TC；前置 Bridge 行为不撤回。
- 合法 pay/user/order/amount/key 边界、合法缺端/prebegin、尾零/指数文本的原数值语义保持；read 对象事实不变。

### 3.2 Owned native

- 使用真实 ExecutionStore/Mapper 与 owned execution、Pay、Intent 三表；Coordinator 真实，Bridge/Evidence/TC/receipt 为 mock；不启动业务 App、TC/RM、新监听或官方 FenceHandler。
- 旧 red：COMMITTED 非正金额曾直接返回 invalid VO；COMMIT_REQUESTED 错 operationKey 经 Bridge 503 曾继续 TC/诊断写入。修复后完整 payload bad matrix 均 409、不返回坏 execution，execution/Pay/Intent facts 不变。
- native 覆盖能存的 order emoji33（UTF-16 66）及金额/键边界；超长 order、NULL 字段、极端 Unit-only 值不修改 DDL。合法边界与缺行/terminal/prebegin controls 保持。
- 本批只证明 execution point read payload，不证明 Pay 原 source、User/Order、TC 或完整资金因果；原 201–211 仅整体回归。

## 4. 明确边界

本批不改变金额字段生产/写入口、state/decision 组合、XID、SQL、事务、Coordinator execute、Bridge/Evidence、Store API 或历史数据；不撤回 Evidence 前 Bridge 读取/镜像，不增加来源查询或 Fence 证明。
