# CM18 16T188：Pay TCC 回滚无扣款布尔证据存在性规格

## 1. 评审结论与范围

本批 **GO**。`UserTccStateVO` 已用 `@JsonSetter` 区分 `ledgerApplied=false` 与字段缺失/JSON null：后两者的 primitive 值都是 false，但 `ledgerAppliedPresent=false`。`PayTccRollbackProjectionService.verifiedProof` 当前只检查 `!user.ledgerApplied`，可能把未知的 no-ledger 证据当成明确无扣款，继续公开或 claimed Pay FAILED 投影。

本批只补回滚 proof 的字段存在性门，不扩展 User DTO、JSON 转换、其它证据服务或状态机。

## 2. 最小生产修正

在 `verifiedProof` 现有 CANCELLED/ledger 条件中增加 `!user.ledgerAppliedPresent` 拒绝条件，并沿既有 `pending()` 返回 `PAY_ORDER_SYNC_UNKNOWN/202`：

```java
if (!"CANCELLED".equals(user.state)
    || !user.ledgerAppliedPresent
    || user.ledgerApplied) throw pending();
```

缺少 `ledgerApplied` 字段和 JSON null 均拒绝；显式 JSON `false` 继续作为明确 no-ledger 证据，显式 `true` 继续拒绝。该门位于同一 User evidence 条件的 CANCELLED 状态门之后、原 primitive `ledgerApplied` 布尔门之前；其后的 tuple、XID、branch、key、金额等字段门顺序保持。proof/GET 次数、错误优先级、事务、锁、Mapper、Pay 写入和 API/序列化字段均不变。

`UserTccStateVO` 的 `@JsonSetter`、`@JsonIgnore ledgerAppliedPresent` 和 primitive false 默认语义不改；不新增对数字、字符串或其它 DTO 字段的强制转换。User 为 null、状态不在既有集合或其它合法缺证仍沿原 UNKNOWN/pending 语义。

## 3. 验收矩阵

### 3.1 Unit/受控 Coordinator

- public 与 claimed 两入口分别覆盖缺字段和 JSON null：真实 Jackson DTO 解析后 `ledgerApplied=false` 且 `ledgerAppliedPresent=false`，不得进入 PublicStateStore 投影、Pay 写或 User/TC 额外调用。
- 显式 `false` 在三种 execution decision 与 Pay `PENDING`/`ORDER_SYNC_UNKNOWN`/`FAILED` 组合下保持原投影/重入语义；显式 `true` 保持 pending/无写。
- User null、User state null、其它非白名单状态继续原 UNKNOWN，不因本批字段门改变；既有 tuple、XID、branch、金额、Fence 和取消/intent 优先级控制保持。
- claimed 非法 evidence 必须在 Store 前被拒，public 非法 evidence 必须在 `projectLocked` 写入前被拒；公开无租约入口的其它行为不变。

### 3.2 Owned native

- 复用 T187 owned fixture、实际 PublicStateStore 与 Pay/Execution/Cancel/Intent Mapper；UserClient、cancelledProof 为受控替身，不启动业务 App、TC/RM 或真实 Feign/User。
- 旧 red 记录 public missing 字段与 claimed JSON null 曾导致 Pay PENDING→FAILED；修复后 missing/null × 三 decision × Pay `PENDING`/`ORDER_SYNC_UNKNOWN`/`FAILED` 的两入口均不写 Pay、Execution、Cancel、Intent 等事实。
- native 仅验证程序化本地事务和实际 Pay Mapper 写保护，不冒称 HTTP/Feign/真实 User/TC 链路；原指纹、owned cleanup、资源关闭和历史 T187 证据保持。

## 4. 明确边界

本批不改变 User 端状态计算、不修复历史 JSON、不扩展其它 DTO presence 字段，不改变 public `projectFailed` 的既有契约之外的状态规则，也不宣称完整远端资金证明、TC fencing 或跨服务事务一致性。测试中直接赋值 boolean 的旧健康 fixture 必须明确表示“显式返回 false/true”，不能再代表字段缺失。
