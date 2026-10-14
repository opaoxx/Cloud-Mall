# CM18 16T189：Pay TCC Abort 无扣款字段存在性规格

## 1. 评审结论与范围

本批 **GO**。`PayTccAbortService.requestRollback` 对 User `TRYING` 响应只检查 primitive `ledgerApplied`；字段缺失或 JSON null 会被 Jackson 映射为 false，且 `ledgerAppliedPresent=false`，从而可能提交 `decision=ROLLBACK`、`state=ROLLBACK_REQUESTED`。本批只补 User 无扣款布尔证据的存在性门，不扩展 Abort 状态机、User DTO、Fence 或其它消费者。

## 2. 最小生产修正

在 `requestRollback` 的现有 `TRYING` 状态条件中，于 primitive `user.ledgerApplied` 条件前增加 `!user.ledgerAppliedPresent`：

```java
if (!"TRYING".equals(user.state)
    || !user.ledgerAppliedPresent
    || user.ledgerApplied) throw pending();
```

缺字段和 JSON null 沿既有 `PAY_ORDER_SYNC_UNKNOWN/202` 消息返回，不调用 `store.requestRollback`，不写 execution、Pay、Cancel、Intent 或其它本地事实。显式 JSON false 继续允许原回滚决议；显式 true 继续 pending。`RootContext` 检查、preparedProof、User GET、tuple/金额/分支优先级和 SQL/事务/DTO/序列化保持。

## 3. 验收矩阵

### 3.1 Unit

- 用真实 Jackson DTO 验证 missing/null/true/false 的 `ledgerApplied` 与 `ledgerAppliedPresent` 组合；missing/null/true 必须在最终 Store 前被拒，false 保持一次 proof→User GET→requestRollback。
- 覆盖 User state、tuple、金额、XID/branch、preparedProof 失败、RootContext 非空等既有优先级；缺字段门不能抢在这些既有更高优先级之前，也不能绕过 RootContext/preparedProof。
- Presence 字段为 `@JsonIgnore`，不会改变响应 JSON；既有 public/claimed 或非 Abort 消费者的语义不改。

### 3.2 Owned native

- 复用 T187 owned fixture，使用真实 AbortService/AbortStore 与 Pay/Execution/Cancel/Intent Mapper；`dispatch`/`receipt`/Evidence classify 与 UserClient 为受控替身，不启动业务 App、TC/RM 或真实 Feign/User。
- 旧 red：真实 Jackson missing public 与 null claimed 曾在 Pay PENDING 上提交回滚并改变 rollback facts；修复后 missing/null/true × `NONE/COMMIT/ROLLBACK` decision × Pay `PENDING/ORDER_SYNC_UNKNOWN/FAILED` 的 public/claimed 入口均无六表写入。
- 显式 false 覆盖四种原合法场景，保持 `ROLLBACK_REQUESTED`/`ROLLBACK`、`nextRecovery`、owner/lease/attempts 不变且不把 Pay 置 FAILED；重复/COMMIT 既有门优先。
- native 只证明程序化本地 AbortStore 写入保护；不冒称真实 Fence、User 网络、TC 或完整回滚因果。原库指纹、owned 清理和资源关闭保持。

## 4. 明确边界

本批不改变 User 端状态计算、JSON 其它字段、AbortStore SQL、事务边界、RootContext、prepared proof、状态/decision 规则或其它消费者；不修历史 JSON、不扩展数字/字符串强转，不宣称跨服务 fencing 或真实 TC/RM 一致性。旧健康 fixture 直接设置 primitive 时必须明确表示显式 boolean，不能再代表字段缺失。
