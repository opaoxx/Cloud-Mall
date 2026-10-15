# CM18 16T222：Pay TCC execution owned snapshot 编排规格

## 1. 评审结论与范围

本批 **GO**。T220/T221 的 ordinary catch 若按 payNo 重新 observe，会在 begin/两 Try 期间 peer 替换 execution 或 Pay source 后把旧线程诊断写入新 owner。需要让 execute 持有 claim/bind 的原 PO snapshot，冲突时不重新读/接管。

## 2. Owned claim/bind API

### 2.1 `claimOwned(request)`

新增 REQUIRES_NEW 的 owned 方法，复用现有 claim 私有 kernel、锁/校验/fresh gate/insert：

- fresh winner 返回刚 insert 的原 `PayTccExecutionPO`；
- existing 合法 replay 返回 null；
- 原 `claim(request)` 保留 boolean API，委托同一 kernel 并以 returned PO 非 null 表示 winner；
- 新方法是 Store 内部 Java 语义入口，不新增外部 API/DTO/SQL；不重新 begin、不接管他人 owner。

### 2.2 `bindOwnedXid(expected,xid)`

新增 REQUIRES_NEW 方法：先沿原 XID 输入门，再执行 T218/219 的 `lockedSnapshot(expected)`，顺序为 Pay→execution→当前/expected persisted 域→八字段 anchor→Pay source；通过后复用原 bind kernel，成功读回原 bound PO，NULL readback 409。旧 `bindXid(payNo,xid)` 保留原签名/行为。

`lockedSnapshot` 不比较 mutable state/decision/diagnostic/recovery 进度，因此并发 NULL prebegin 的 `CLAIMED→BEGIN_UNKNOWN` 允许绑定；不放宽 anchor/source 域。

## 3. Coordinator 改动范围

仅 execute 使用 `claimOwned` 并保留 owned PO：null 进入原 recover；begin 后使用 `bindOwnedXid(owned,xid)`；inner/outer ordinary catch 使用 `observeSnapshot(owned,...)`。T220 conflict 传播、finally RootContext 清理保持。不得在 catch 中重新 read 最新 row，不改 decide/sendDecision/TC 或其他 observe 调用，不重 begin/接管。

## 4. 验收矩阵

### 4.1 Owned native

- 使用 actual Coordinator/Store/Mapper/owned Pay/Intent/Execution 与 Spring ProxyFactory + TransactionInterceptor/AnnotationTx REQUIRES_NEW；receipt/Bridge/Evidence/TC 为 mock，不启动 App、TC/RM、新监听。
- begin/receipt/Bridge/Evidence/bind 输入期间覆盖 4 个阶段 × 8 个变化（5 execution anchor+3 Pay source）=32；peer 替换后旧 snapshot 观察 409，peer facts 不被污染；TC begin 已发计数如实保持。
- ordinary non-drift commit/rollback/UNKNOWN、claim replay、NULL prebegin 并发保持；另有2个原 claim/prebegin controls（真实 insert id、原 PO 不变、SUCCESS 后 replay/旧接口兼容），合法 `CLAIMED→BEGIN_UNKNOWN` 可 bind；不重新 begin/接管。
- 本地有界事务交错不证明真实 TC/远端并发、read→RPC 全窗口或完整 fencing；源库/owned cleanup/fingerprint 保持。

### 4.2 Unit

- Store owner contract 覆盖 claim winner/replay、bind snapshot drift、source/anchor NULL、mutable progress；Coordinator mock 适配 T220/T221，断言 catch 使用原 PO，不重读 key。
- 覆盖普通异常/冲突/TC调用计数、finally unbind、claim/bind 优先级；三方法分别验证 fresh/replay/旧 bool、3个绑定/幂等控制与13个 drift/input/source/SQL0/NULL readback 变体；不新增组合状态机或外部 API 证明。

## 5. 明确边界

本批不改 SQL/schema/DTO、decide、sendDecision、非 execute 的 observe、外部 TC/RPC、历史修复或其它 Store API；不保护已发 RPC 后的窗口，不撤回已 begin/已发 Try，不宣称完整 Pay/User source 或 Seata fencing。

首次预检因旧 Coordinator 测试缺少新增 same/equals import 未执行测试，日志单独归档；补齐后 3 个相关类 9 方法为0失败/错误/跳过。完整专项仍在执行，预检与该局部结果不能替代最终全套结论。
