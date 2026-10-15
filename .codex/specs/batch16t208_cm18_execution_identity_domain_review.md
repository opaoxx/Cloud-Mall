# CM18 16T208：Pay TCC execution 身份域规格

## 1. 评审结论与范围

本批 **GO**。T207 已要求 execution amount 为正，但 userId 0/负或 orderNo 空白时，execution/receipt/User 三方一致仍可被 Evidence 判为 COMMITTED/ROLLEDBACK。Execution 创建入口已有正 userId、非 blank orderNo 门；恢复读路径需要同一基本域保护。本批只补 execution identity 域，不扩 payNo/XID/operation/ledger 词法或完整 source/funds 因果。

## 2. 最小入口门

在现有正金额门之后、User tuple/receipt/Fence 任何查询之前增加：

```java
require(
    execution.userId != null
        && execution.userId > 0
        && execution.orderNo != null
        && !execution.orderNo.isBlank());
```

失败沿既有 `PAY_TCC_EXECUTION_CONFLICT/409`，不进行 User tuple 比较，也不查询 receipt/Fence，不执行 observe/TC。只校验正 userId 与非 blank orderNo；不加长度/格式/trim/normalize，不改变 XID null 的合法 prebegin 语义，不新增 payNo/operation/ledger 规则。

execution tuple、receipt/User matching、state/decision、Fence、Coordinator、Store、SQL、事务、API 保持。前置 Bridge 读取/镜像不撤回，Coordinator execute 旧异常决议不扩展。

## 3. 验收矩阵

### 3.1 Unit/真实 Coordinator

- 覆盖 userId 0/-1、orderNo 空/ASCII 空格/TAB/LF，execution/receipt/User 其它字段完整；均在入口 409，无 receipt/Fence 查询。
- userId/orderNo null 只在 PO mock 中覆盖，不修改 NOT NULL DDL；userId/orderNo 的 7 个非法变体（userId 0/-1/null，orderNo 空/空格/TAB+LF/null）× 四 receipt state × 完整/双端缺失共56组均无查询与阻断。TAB 与 LF 是同一控制字符字符串变体，不计作两组。
- 真实 `Coordinator.recover` 使用 mock Store/Bridge/TC 依赖，COMMIT/ROLLBACK × userId 0/orderNo empty 共4组（receipt 默认 CONFIRMED），验证无 execution.observe、无 TC、无 receiptTry。Evidence 前 Bridge 动作不撤回。
- 正域 userId/original/different positive（用户1、带边界空格的非空订单、Long.MAX_VALUE user、order长度64）× receipt state 保留原映射；正域缺端仍 UNKNOWN。

### 3.2 Owned native

- 使用 owned execution/receipt 实际只读 RR Mapper，User 镜像从 execution 构造，使用实际 Fence Mapper 与 owned 行；Coordinator 依赖受控，不启动业务 App、TC/RM、官方 FenceHandler 或新监听。
- 旧 red：execution/receipt/User userId=0 与 CONFIRMED/Fence2 曾 COMMITTED；三方 orderNo 空且 CANCELLED/Fence3 曾 ROLLEDBACK。修复后 5 个非法变体（userId 0/-1、orderNo 空/空格/TAB+LF）× 四 receipt state 共20组均 409；另5个缺 receipt 组合仍入口 409，五表 facts 不变。Pay/Intent 固定 userId=7、orderNo=order-1；不访问 Order 服务表或声称其事实。
- 正域 × 四 receipt state 保留映射；正域 receipt missing 保持 UNKNOWN。owned 仅修改 execution/receipt 相关列，Pay/Intent 字段保持。
- native 只证明 Evidence 本地 identity 域门，不冒称 Pay source/User/TC 完整资金链路；新增场景使用实际 Fence Mapper，不调用官方 FenceHandler。旧 201–204 仅整体回归。

## 4. 明确边界

本批不改变金额 scale/precision/上界、XID prebegin、payNo/key 词法、receipt state/branch、Fence、Coordinator execute、Store、SQL、事务或 API，不修历史非法 execution，不撤回 Evidence 前 Bridge 读取/镜像。User 是已传入镜像，identity 门位于 tuple 比较前，不在本方法重新查询远端。真实 Coordinator+mock Store/Bridge/TC 仅覆盖 COMMIT/ROLLBACK × userId=0/orderNo empty 的4组；不宣称 Coordinator.execute 旧 catch/异常决议流程受到本批保护。
