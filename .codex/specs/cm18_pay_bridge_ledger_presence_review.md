# CM18 16T190：Pay TCC Bridge 账本存在性规格

## 1. 评审结论与范围

本批 **GO**。`PayTccBridgeServiceImpl.validateState` 目前只检查 primitive `ledgerApplied`，缺失或 JSON null 会表现为 false，可能让 User `TRYING` 获得 `TRY_ACCEPTED`，或让 User `CANCELLED` 进入 Pay `CANCELLED` 镜像。该问题属于 Bridge dispatch mirror 证据门，不是 Abort 回滚决议或 Pay FAILED 投影。

## 2. 最小生产修正

在现有 branch mismatch 检查之后、旧 `ledgerApplied` 一致性判断之前增加：

```java
if (!state.ledgerAppliedPresent) throw unavailable("User账本证据缺失");
```

缺字段/JSON null 沿现有 `PAY_TCC_UNKNOWN/503` 路径处理：外层 POST/GET 按原流程转 `mirrorUnknown`，不直接改写为 HTTP 202，不改变已有诊断持久化语义。该门只作用于已通过载荷、非 UNKNOWN、branch 正数与 branch mismatch 检查的状态；`UNKNOWN` 的现有早返回保持，不在该位置新增 Presence 门。

Presence 失败不获得可信状态或 branch 学习。已经消耗的 POST 权限不回收、不重发；已有 `TRY_ACCEPTED` 的 mirrorUnknown/状态降级规则保持。终态、PREPARED、disabled 等更早返回、永久 branch 冲突 409、tuple/金额/XID/operation/ledger 优先级均不变。

`UserTccStateVO` DTO、JSON 序列化、SQL、表、事务、DispatchPersistence、其它消费者和 TCC/RM 不改。显式 false 仍代表明确 no-ledger，显式 true 仅允许与 CONFIRMED 状态匹配。

## 3. 验收矩阵

### 3.1 Unit

- 真实 Jackson JSON missing/null 的 TRYING/CANCELLED 响应分别覆盖 POST/GET；确认 Presence 失败沿原 UNKNOWN/503 mirror 路径，dispatch payload 不变。
- 显式 false 的 TRYING/CANCELLED 与显式 true 的 CONFIRMED 保持原 mirror；UNKNOWN 早返回、branch mismatch 409、tuple/金额/XID 错误和终态/PREPARED 控制优先。
- Presence 字段为 `@JsonIgnore`，不出现在 JSON；Bridge 原 TRY_ACCEPTED 不降级控制保持。
- Unit 的 Persistence 为 mock，仅验证调用/返回顺序，不将 mock 当事务证据；程序化短事务验证归入 native，且不将其当 Spring/TC 集成。

### 3.2 Owned native

- 使用真实 Jackson、Bridge、DispatchPersistence 及 owned Pay/Dispatch Mapper；UserClient 与 cancel gate 为受控替身，不启动业务 App、TC/RM 或真实 HTTP/Feign。
- 旧 red：GET missing TRYING 曾返回/镜像 `TRY_ACCEPTED`，POST null CANCELLED 曾生成错误终态镜像；修复后缺证 × TRYING/CANCELLED × GET/POST 均沿原 UNKNOWN 处理，payload、Pay/Intent 及 dispatch facts 不出现新的可信状态写入。POST 已消耗的权限保持原语义。
- 显式 false 的 TRYING/CANCELLED、显式 true 的 CONFIRMED、UNKNOWN early return、终态/PREPARED、branch409 和 disabled 控制保持；只验证实际 owned mapper 与程序化短事务的镜像行为。
- native 只证明 dispatch mirror 的本地证据门，不证明 User/Fence/TC/网络或全局资金因果。

## 4. 明确边界

本批不把缺失 Presence 扩展到 UNKNOWN 早返回，不改变原 `TRY_ACCEPTED` 重试/降级、不回收 POST 权限、不增加 SQL/字段/状态/API，不修历史 dispatch 或其它 Bridge/Abort/rollback 消费者，也不宣称完整 TCC fencing 或跨服务事务一致性。
