# CM-18 16T81：Pay Legacy intermediate ownership

## 1. 结论与范围

GO。16T80 只保护 SUCCESS/REFUNDED/FAILED 终态；Legacy 中间 `markOrderUnknown`、`markRefundPending`、intent `updateOwnedStatus` 与 `recordRetry` 仍可能按 payNo/旧 owner 写入，且失租约后仍可能发出 User/Order RPC。

本批只保护 Legacy 中间阶段的当前 token、未过期 lease 与 mode；不宣称跨两表原子 fencing。

## 2. Owned intermediate Mapper

新增 Legacy 专用 PayRecord owned 方法：

- `markOwnedLegacyOrderUnknown`；
- `markOwnedLegacyRefundPending`。

两者使用单条 `pay_record` JOIN `pay_debit_intent` UPDATE，核 BINARY payNo/token、`lease_until > now`、两表 BINARY `debit_mode='LEGACY'`、intent active status，以及各自原 payRecord allowed status/SET。保留旧方法和 TCC caller；关联 UPDATE 的锁足迹/计划可能变化，不承诺性能或物理扫描不变。

新增 intent `updateOwnedLegacyStatus` 与 `recordRetryOwnedLegacy`，核 token、deadline、BINARY LEGACY，并用 EXISTS/等价 JOIN 确认 pay_record 为 Legacy；SET/退避、清租约和错误截断保持旧方法语义。新增 `hasOwnedLegacyLease(payNo, token, now)` 只读资格检查，精确检查同一 token、未过期 lease、两表 Legacy 与 active status；不得把普通 owner 查询结果或同终态 readback 当资格。

## 3. 正常流程与 RPC 前门

- 当前中间 owned PayRecord/intent 写必须恰好影响 1 行；0 行立即沿现有 Runtime/retry，不用旧方法 fallback，也不继续 RPC。
- 在每个远程写 RPC 前调用 `hasOwnedLegacyLease`：User debit、Order paid、User refund 均在调用前检查；失去 owner、过期、mode 漂移或 active 状态不符则抛 Runtime，不发该 RPC。
- 保留原 User/Order RPC 顺序、请求 DTO、T77 ledger proof、状态判断与成功返回。
- `markRefundPending`/`updateOwnedStatus` 成功后才进入退款 RPC；refund 失败仍 REFUND_PENDING。不得因同状态而绕过 owner/deadline。

## 4. Catch/retry 与边界

catch 中的 ORDER_SYNC_UNKNOWN/REFUND_PENDING 记录改用 owned order-unknown/retry 方法，均为 best-effort：返回 0 时不回写他人，不再调用普通无 owner 方法；原异常与重试状态计算保持。中间 owned 写与资格检查之间、资格检查与远端实际发出之间仍可能存在窗口，远端已发出后不能回滚；本批不宣称完整 lease fencing、跨表原子性或 clock-skew 解决。

不改 T79 token、T80 terminal helpers、claim/30 秒 lease、旧 Mapper、API/Feign/DTO、SQL schema、Redis、消息、TCC/RM。失败 claim 不发 RPC。

## 5. 验收

- Unit：owned stage/lease/mode/active 条件返回 0 时不调用普通 fallback；三 RPC 前资格为 0 时均不发出；catch cleanup 0 不修改他人。
- Owned MySQL：initial User GET、Order GET 或 callback 受控期间改变 owner/expiry/mode，旧流程不得继续 debit/paid/refund；新 intent 全字段保持，healthy owner/deadline 与退避 retry 正常。
- 验收 join UPDATE/资格 SELECT 的真实返回与资源清理；不启动完整 App/RM/TC，不伪称跨表原子或远端写 fencing。
- 保留 T77–T80、Legacy/TCC/PayRecord source checksum 及既有回归。

## 6. 交付边界

实现仅允许新增上述 Legacy owned Mapper 方法、三处 RPC 前资格门、catch owned best-effort 调用及必要 JavaDoc/测试；不扩展其它恢复阶段或服务。
