# CM-18 16T141：Pay Legacy terminal tuple lock

## 1. 结论与范围

GO。T84/后续 proof 在 RPC 后观察 tuple，但 T80 terminal 两表 UPDATE 只核 pay/mode/status/token/lease；外部 writer 可在终态短事务前或期间改变 user/order/amount，导致旧远端证据结清已变更事实。

本批只在本地 terminal 短事务中锁定并复核原 Pay tuple，不扩远端 User/Order 或提交后 fencing。

## 2. Owned source lock

新增语义 Mapper `lockOwnedLegacyTerminalSource(expectedIntent, token, now)`，单条无写查询：

- `pay_record` JOIN `pay_debit_intent` 按 BINARY payNo；
- intent BINARY LEGACY、active status、exact token、`lease_until > now`；
- 两表 userId、BINARY orderNo、amount 均精确等已验证 intent snapshot；
- `FOR UPDATE` 锁住两行并返回 PayRecord projection，缺行/不匹配返回 null。

不改现有四个 terminal write 方法签名、SET、条件、DDL/API；新 SQL 只用于本地 transaction source lock。

## 3. Terminal transaction ordering

`commitOwnedLegacyTerminal` 的 REQUIRES_NEW 本地事务中，先执行 source lock；null 抛 Runtime “支付终态原载荷或领取资格已变化”，事务不写。通过后按原顺序执行 PayRecord owned terminal update 与 intent finish，各必须 1；失败整体 rollback，原 catch/retry 保持。

锁保持至两次写结束并在 commit/rollback 后释放；不解绑 RootContext，不包含 RPC/claim/stage/retry。

## 4. 保持项与边界

不改 owner/deadline、T79–T140、SQL/API/字段/lease/远端 evidence；不声称读后到提交、跨服务、全局 fencing 已解决。现有 PayRecord/intent allowed status、终态 SET 与 TCC callers 保持。

## 5. 验收

- Unit：source missing/mismatch/zero write、terminal rollback、manager/owner priority；
- Owned MySQL：三终态 × 双表单边/双边 user/order/amount/CI order 变化，锁后独立连接 UPDATE 阻塞，终态两写后释放；异常回滚且锁释放，Pay fingerprint 保持。
- 使用 bounded Hikari/owned DB、资源 finally 清理，不启动 App/RM/TC；健康终态与 T80 回归保持。

## 6. 交付边界

实现仅允许新增 source-lock Mapper 与 terminal transaction 内调用、必要 JavaDoc/测试；不得修改四写方法 public/参数、远端调用或状态机。
