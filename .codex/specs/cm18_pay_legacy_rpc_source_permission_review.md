# CM-18 16T151：Pay Legacy RPC source permission

## 1. 结论与范围

GO。三个 write RPC 前 `hasOwnedLegacyLease` 只核 token/TTL/mode/active；stage 或 PayRecord/intent tuple/phase 在此前读取后变化时，仍可能向远端发送旧 debit/paid/refund。

本批只增加 RPC 前本地 source/phase permission read，不宣称 check→RPC 窗口或远端 fencing。

## 2. Permission query

新增 internal `hasOwnedLegacyRpcPermission(expectedIntent, token, operation, now)` COUNT JOIN，单只读无锁查询：

- 双表 BINARY pay/user/order、amount exact；
- 双 LEGACY、exact token、lease未过期、恰好一行；
- operation phase 精确匹配：
  - `DEBIT`: Pay PENDING + intent INIT；
  - `PAID`: Pay ORDER_SYNC_UNKNOWN + intent DEBITED；
  - `REFUND`: Pay/intent 均 REFUND_PENDING。

先执行原 `hasOwnedLegacyLease`；false 保原“支付恢复领取资格已失效”且不发新 query。lease true 后 permission false 抛 Runtime “支付恢复原载荷或阶段不再允许远程写入”，沿原 catch/wait。

## 3. Callers and bounds

User.debit、Order.paid、User.refund 分别传 DEBIT/PAID/REFUND operation；RPC DTO、顺序、owner/lease、stage/retry/terminal、SQL/API 不改。alias/status/tuple drift 阻断；已发 RPC/已提交阶段不回滚。

## 4. 验收

- Native：三 RPC 健康各增加一次 permission query；current tuple/phase 在旧 lease check 期间 drift 时不发对应 RPC，之前已发/已提交 facts 保持；mode/owner/lease/缺行拒绝。
- Unit/owned：错误/DAO/lease false（新 query 0）、healthy phase、REFUND_PENDING no-regression 与 T146/T149 回归；资源 cleanup，不启动 App/RM/TC。
- 不宣称跨 SQL/远端原子性或 source fencing。

## 5. 交付边界

实现仅允许新增 internal permission COUNT Mapper、增强 lease helper/三 caller operation 参数及必要 JavaDoc/测试；不得修改 RPC/API/状态计算或旧 SQL 写。
