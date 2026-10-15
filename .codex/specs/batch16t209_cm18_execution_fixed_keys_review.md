# CM18 16T209：Pay TCC execution 固定业务键规格

## 1. 评审结论与范围

本批 **GO**。Execution 创建/validate 已固定 payNo 域 `[A-Za-z0-9_-]{1,64}`、`operationKey=tcc-debit:payNo`、`ledgerKey=payNo`，但 Evidence.classify 目前只核 execution/receipt/User 三方相等；三方共同携带错误 operation/ledger 或非法 payNo 仍可得到 COMMITTED/ROLLEDBACK。本批只把既有创建键契约补到 Evidence 入口。

## 2. 最小 Evidence 门

在正金额门之后、User tuple 比较与 receipt/Fence 查询之前增加：

```java
require(
    execution.payNo != null
        && execution.payNo.matches("[A-Za-z0-9_-]{1,64}")
        && Objects.equals(execution.operationKey, "tcc-debit:" + execution.payNo)
        && Objects.equals(execution.ledgerKey, execution.payNo));
```

失败沿既有 `PAY_TCC_EXECUTION_CONFLICT/409`，不进行已传入 User 镜像的 tuple 比较，也不查询 receipt/Fence，不执行 observe/TC。保留大写、小写、数字、下划线、连字符及长度1/64边界；不新增 XID 域、order 长度、金额 precision/scale/state/decision 规则，不 normalize 或大小写折叠。

execution tuple、receipt/User matching、Fence、Coordinator、Store、SQL、事务、API 保持。Evidence 之前 Bridge 读取/镜像不撤回，execute 旧异常决议流程不扩展。

## 3. 验收矩阵

### 3.1 Unit/真实 Coordinator

- Unit 覆盖16种字段坏形态 × 四 receipt state × User/receipt 完整或双端缺失=128；另有 positive Unit 12组、缺 User/receipt 2组和 prebegin 1组。Unit Mapper 使用 mock，不声称实际 Fence/数据库查询。
- 真实 `Coordinator.recover` 使用 mock Store/Bridge/TC 依赖，COMMIT/ROLLBACK × wrong operation/ledger 共4组，receipt 默认 CONFIRMED；验证不执行 `execution.observe`、不发 TC、不调用 receiptTry。User 是已传入的镜像，Evidence 不重新查询远端，Evidence 之前 Bridge 动作不撤回。

### 3.2 Owned native

- 使用 owned execution/receipt 实际只读 RR Mapper、实际 owned Fence Mapper；User 镜像和 Coordinator 依赖受控，不启动业务 App、TC/RM、官方 FenceHandler 或新监听。
- 旧 red：operationKey 错误且 confirmed/Fence2、ledgerKey 错误且 cancelled/Fence3 曾返回 COMMITTED/ROLLEDBACK；修复后固定键错误、非法 payNo 和其余矩阵均 409，五表 facts 不变。
- native 覆盖12个变体 × 四 receipt state=48，再加 payNo 空、operationKey other、ledgerKey other 三个 missing=51；positive payNo `A`、`Pay_1-a`、`P_+62a` × 四 state=12，加正键缺 receipt 1组。实际 Fence Mapper 用于 native，FenceHandler 不调用；本批只修改 owned execution/receipt 变体，Pay/Intent 字段保持，不冒称 Pay 原 source、User/Order 全因果。
- 新增场景不调用官方 FenceHandler，旧 201–204 仅整体专项回归；原库全表指纹、owned DROP、池关闭保持。

## 4. 明确边界

本批不改变金额、User/receipt/Fence、XID prebegin、state/decision、Coordinator execute、Store、SQL、事务或 API，不修历史非法 execution，不撤回 Evidence 之前 Bridge 读取/镜像。user/operation/ledger 固定键门只保护本地 Evidence 入口，不宣称完整 source/funds/TC fencing。
