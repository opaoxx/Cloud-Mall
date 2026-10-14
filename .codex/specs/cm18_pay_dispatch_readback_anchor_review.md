# CM18 16T197：Pay TCC dispatch readback 原锚点规格

## 1. 评审结论与范围

本批 **GO**。T195/T196 已保护 mirror 与 POST claim 前的 snapshot，但 Bridge 在 `markPostAttempted=false` 或 mirror 提交后直接把 readback PO 转为 VO，缺少原八字段身份核验；readback 期间若行被改成另一载荷，Bridge 仍可能返回 foreign payload。本批只补 Bridge readback anchor 门，不改变查询、CAS、镜像和外部调用。

## 2. 复用 anchor 与 readback 校验

将 `PayTccDispatchPersistence.sameAnchor` 从 private instance helper 改为包内可复用的静态纯函数，增加 expected/current nullsafe 处理；保留 T195/T196 的八字段规则：

```text
id, payNo, xid, userId, orderNo, amount, operationKey, ledgerKey
```

字符串/Long 使用 `Objects.equals`，金额两端非 null 且 `compareTo == 0`；mutable state、branch、created/updated、postAttemptedAt、lastError 不比较。该函数不访问 Mapper、不写状态、不改变金额或文本。

Bridge 两处 readback 使用原始 dispatch snapshot 调用该纯函数：

1. `postOnce` 的 `markPostAttempted=false` 路径中，当前行非 null 时先校验，再 `PayTccDispatchVO.from(current)`；不一致直接抛既有 `PAY_TCC_DISPATCH_CONFLICT/409`，不能返回 foreign VO 或继续 POST。current 为 null 的 `mirrorUnknown("派发租约丢失")` 分支逐字保持。
2. `mirror` 成功提交后，`updated` 非 null 时先校验，再 `PayTccDispatchVO.from(updated)`；不一致同样 409，不 fallback/再写。updated 为 null 的既有 unavailable 分支保持，不预设其最终 HTTP code 或改变外层 catch。

Bridge 传入的 expected 必须是调用前原 dispatch snapshot，不在 readback 期间重读构造。healthy mutable state/branch/time/diagnostic 变化以及金额数值等价不拒绝。Persistence、Mapper SQL、Feign、DTO/API、事务、状态机、TCC 不改。

## 3. 验收矩阵

### 3.1 Unit

- mirror readback 覆盖八字段单边/多字段漂移、null/foreign current、金额数值等价和 mutable progress；漂移 409，缺行保持既有分支，合法 progress/金额等价返回 VO。
- mark false readback 覆盖八字段漂移、0 RPC、current null 的原 `mirrorUnknown`；断言不返回 foreign VO。
- 保留单次 POST、claim、mirror、JSON、branch、terminal/PREPARED/TRY_ACCEPTED 既有测试；wrapper 传原 PO，不自行重读。

### 3.2 Owned native

- 使用真实 Bridge/Persistence/Dispatch Mapper 与 owned 表；在 actual 短事务结束后、Bridge readback 前通过 afterClaim/afterMirror hook 注入 owned drift；不冒称真实 HTTP 并发、TC/RM 或 Spring 全局事务。
- 旧 red：claim false 后改 `xid`、mirror 成功后改 `payNo` CI alias，旧实现返回 foreign VO；修复后均 409，foreign payload 不返回，facts 不再被 readback 改写。
- control：八字段 × claim-false readback 与 mirror readback（GET/POST、有效/空 response）均按 anchor；healthy mutable progress/金额等价允许；missing current 保原 `mirrorUnknown`/unavailable 真实分支。
- 原库指纹、owned 精确清理、池关闭和 T195/T196 证据保持；Bridge readback 只证明本地对象身份，不宣称远端 source、TC 或完整 fencing。

## 4. 明确边界

本批不改 `markPostAttempted`/`mirror` SQL、锁、CAS、状态或外部 User RPC，不修 claim→POST、POST→mirror 之后的其它窗口，不新增字段/表/API，不改变 null readback 的既有异常路径，也不扩展其它 Store getter 的身份校验。
