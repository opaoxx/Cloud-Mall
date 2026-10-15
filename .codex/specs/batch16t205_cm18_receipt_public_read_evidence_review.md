# CM18 16T205：Pay TCC receipt public read evidence 规格

## 1. 评审结论与范围

本批 **GO**。T200 只在 prepare 的末次 readback 使用 state/branch evidence 门，`PayTccReceiptServiceImpl.read` 仍直接把 Store 返回 PO 转 VO；UNKNOWN、空 state、CONFIRMED branch 0 等坏证据可被公开读取。本批只给 public read 增加既有 helper 门，不改变 Store getter、prepare、callback、owner/Fence 或 payload 证明。

## 2. Public read 最小修正

保持 `receipts.read(payNo)` 一次读取及其既有 payNo identity guard。receipt 为 null 时仍抛 `PAY_TCC_RECEIPT_NOT_FOUND/404`；非 null 且 Store 已返回后，调用包内纯函数 `PayTccReceiptStore.hasValidStateAndBranch(receipt)`：

- state 必须 CLAIMED、TRYING、CONFIRMED 或 CANCELLED；
- branch null 仅 CLAIMED 允许；
- 其余三状态必须 branch 非 null 且正数；
- 任意非 null branch 必须正数。

门失败抛既有 `PAY_TCC_RECEIPT_CONFLICT/409`，不执行 VO 转换，不调用 Bridge/resource。Store read/lock、prepare、callback、owner/Fence、SQL、查询次数、事务、VO/API 签名不变。

## 3. 验收矩阵

### 3.1 Unit

- 复用 T200 mutation matrix 覆盖 state null/UNKNOWN/空/lowercase/尾空格、四状态 branch 0/-1、advanced null、CLAIMED null/positive/MAX；坏证据 409，正常 missing 404。
- 覆盖 Store.read identity conflict、DAO/lookup throw 优先，确保 public read 不吞 Store 原异常、不新增查询/资源调用；合法 CLAIMED null/1/MAX 与其它三状态正 branch 保持 VO。
- 不反射 private helper、不新增镜像实现；直接验证 public service 调用既有包内 helper。

### 3.2 Owned native

- 使用真实 ReceiptStore/Mapper 与 public ReceiptService，owned receipt 表隔离；Bridge/resource 为受控替身，不启动业务服务、TC/RM、官方 Fence 或新监听。
- red：UNKNOWN branch=8、CONFIRMED branch=0 等旧 public read 返回 VO；修复后 15 种非法组合均 409，四表 facts 不变且无 Bridge/resource 调用。
- 合法九组：CLAIMED null/1/MAX、TRYING/CONFIRMED/CANCELLED 正 branch（含1/MAX）保持 read VO；missing 404；case alias/Store identity conflict 与坏 state 保持 Store 原 409 优先。
- native 只证明 public read 本地状态/branch evidence，不扩 XID/user/order/amount 载荷、资金因果或读后稳定性。

## 4. 明确边界

本批不改变 Store read/lock、prepare readback、callback、owner/Fence、SQL、事务、状态推进、DTO/VO/API 或历史数据；不新增官方 Fence/TC/RM 调用，不将 public read 结果解释为完整资金证明。
