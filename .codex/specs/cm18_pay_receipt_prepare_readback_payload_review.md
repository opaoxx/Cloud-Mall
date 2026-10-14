# CM18 16T199：Pay TCC receipt prepare readback 载荷规格

## 1. 评审结论与范围

本批 **GO**。Receipt Facade 在 claim/resource 后读取 receipt，只通过 Store 的 payNo 身份门；claim false 或 resource 返回后 receipt 的不可变 payload 漂移仍可能被直接转为 foreign VO。本批只补 prepare readback 的原 dispatch payload 门，并复用 Store 现有 validate 核心，不改变 claim、resource、回调或状态机。

## 2. Store payload helper

将 ReceiptStore 现有 validate 的不可变载荷比较抽为包内静态纯函数 `samePayload(stored, expected)`，并由 validate 复用。两参数 nullsafe；以下七字段必须精确匹配：

```text
payNo, xid, userId, orderNo, amount, operationKey, ledgerKey
```

字符串/Long 使用 `Objects.equals`；stored/expected amount 非 null，expected 文本用 `new BigDecimal`，数值使用 `compareTo == 0`，允许尾零和指数文本；`NumberFormatException` 返回 false。合法 state/branch 规则继续由原 validate 在 payload 通过后执行，不比较 receipt id、时间、state 或 branch。

原 existing receipt validate 中 amount null 不再 NPE，沿既有 `PAY_TCC_RECEIPT_CONFLICT/409` 明确拒绝；输入坏 amount 在更早 source proof/其它门触发的原异常保持，不新增金额词法域。

## 3. Facade prepare readback 门

保持 `bridge.prepare → receipts.claim → winner resource.prepare → receipts.read` 顺序、查询次数、资源注册、事务与 API。`receipts.read` 返回 null 时保留现有 `PAY_TCC_RECEIPT_UNKNOWN/503`。返回非 null 后，在 `PayTccReceiptVO.from` 前调用 `samePayload(receipt, dispatch)`；不一致抛既有 `PAY_TCC_RECEIPT_CONFLICT/409`，不得返回 foreign VO 或重发 resource。

Store public `read(payNo)` 的原 payNo 身份门、SQL/锁、claim fresh/replay、transition/callback 均不变。readback 允许 state/branch 进展，只保护七个不可变 payload。

## 4. 验收矩阵

### 4.1 Unit

- fresh winner 与 replay 分别覆盖七字段单边/多字段漂移、null amount、金额数值等价；foreign payload 不返回，winner 资源最多原一次，replay 不新增资源。
- 覆盖 healthy CLAIMED/TRYING/CONFIRMED/CANCELLED、branch 进展、missing receipt 503、existing NULL amount 409、原 payload/source/cancel 优先级。
- Store 的 samePayload 为纯函数，Facade 不重读重建 expected；资源与 Bridge 仅 mock，断言调用顺序。

### 4.2 Owned native

- 使用真实 ReceiptStore/Facade/Receipt、Payment、Dispatch Mapper 与 owned DDL；claim 使用程序化本地短事务，Bridge/resource 为受控替身，不启动业务 App、TC/RM 或真实 HTTP/LocalTCC。
- 旧 red：fresh resource 后修改 receipt.xid、replay claim false 后修改 operationKey，旧实现返回 foreign VO；修复后分别 409，不返回 foreign payload，后置 mutation 不再被 readback 改写。fresh 已尝试的唯一 resource 不撤回，replay 资源调用为0。
- control：七字段 × winner/replay、healthy 四状态和 branch 进展允许；payNo CI alias 继续由 Store.read 原身份门拒绝，missing receipt 保持 503/fresh winner 已尝试语义。
- 原库指纹、owned DROP、池关闭、T188–T198 证据保持；native 只证明本地 readback payload，不冒称真实 LocalTCC/TC/Spring 事务或 HTTP 并发。

## 5. 明确边界

本批不改变 Store read/lock、claim SQL/锁/事务、resource 注册、回调、状态/branch 规则、DTO/API 或 User/Bridge 证据；不修历史 receipt、不比较 mutable state/branch/时间、不扩展其它 getter 的 payload fencing。
