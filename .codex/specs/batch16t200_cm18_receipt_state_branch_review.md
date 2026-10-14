# CM18 16T200：Pay TCC receipt state/branch 证据规格

## 1. 评审结论与范围

本批 **GO**。ReceiptStore 的 `validate` 使用 `Set.of(...).contains(stored.state)`，null state 会 NPE，且 branch 0/负数可被接受；Facade prepare readback 只核 payload 后直接转 VO，坏 state/branch 可返回。范围限定为 receipt 本地状态与 branch 证据门，不扩展 getter、callback、Fence、SQL 或事务。

## 2. 共享纯校验门

增加包内静态纯函数 `hasValidStateAndBranch(PayTccReceiptPO receipt)`，nullsafe：

- state 必须精确为 `CLAIMED`、`TRYING`、`CONFIRMED` 或 `CANCELLED`；
- branchId 为 null 仅在 `CLAIMED` 允许；
- `TRYING`、`CONFIRMED`、`CANCELLED` 必须 branchId 非 null 且 `> 0`；
- 任意非 null branchId（包括 CLAIMED）必须 `> 0`。

Store `validate` 在既有 payload/samePayload 成功后复用该门；坏 state/branch 沿既有 `PAY_TCC_RECEIPT_CONFLICT/409`，取代 null state 的 NPE。Facade prepare 在 receipt missing 的既有 `PAY_TCC_RECEIPT_UNKNOWN/503` 和 payload conflict 之后复用同一门，再执行 VO 转换。输入 dispatch 的 state/branch 不作为 stored receipt 替代，但 fresh claim 既有 source/fresh gate 保持。

existing receipt replay 优先于 fresh gate，已有 receipt 即使 Pay/anchor/marker/input 已推进仍只 validate/replay，不重新注册；fresh 的 payment/status/dispatch/anchor permission 门、claim/资源调用和事务不变。

## 3. 验收矩阵

### 3.1 Unit

- 覆盖 null/UNKNOWN/空状态、branch 0/负数、CLAIMED null/正 branch、TRYING/CONFIRMED/CANCELLED 正 branch、Long.MAX_VALUE；坏 stored 在 Store/Facade 均 409，不 NPE、不 VO 返回。
- 覆盖 fresh input state/branch、source/cancel 优先、existing replay 四状态、payload conflict 以及 readback missing 503；existing replay 不被 fresh 门误拒。
- `hasValidStateAndBranch` 为纯函数；Unit mock 资源只验证调用边界，不冒称本地事务。

### 3.2 Owned native

- 使用真实 ReceiptMapper/Store/Facade 与程序化 claim 短事务，Bridge/resource 为替身；不启动业务服务、TC/RM 或真实 HTTP。
- native 覆盖 UNKNOWN/空/别名状态与 branch 0/负数/advanced/null；数据库 NOT NULL 限制下的 NULL state 仅 Unit 验证。
- 合法 CLAIMED null/正 branch、其它三状态正 branch、Long.MAX_VALUE 和 state/branch 动态进展保持；坏证据 409、无新增 resource/写入。已 claim/resource 不撤回，测试只验证本地拒绝。
- 原库指纹、owned cleanup、池关闭及 T199 payload/claim/replay 证据保持；不宣称真实 TC 或完整资金因果。

## 4. 明确边界

本批不修改 public read、lock、callback、Fence、SQL、查询次数、事务、DTO/API、状态推进或历史数据，不改变 existing replay 优先级，也不把测试 fixture 中的 state/branch 补值解释为生产契约放宽。其它 receipt getter 的状态/branch身份问题仍属后续范围。
