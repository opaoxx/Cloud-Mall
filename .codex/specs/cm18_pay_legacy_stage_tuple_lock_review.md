# CM-18 16T144：Pay Legacy stage tuple lock

## 1. 结论与范围

GO。T143 stage transaction 已使两写原子，但只核 owner/status/lease/pay；User/Order RPC 前 payment/intent user/order/amount 变化仍可能推进 DEBITED/REFUND_PENDING。

本批只在两组 stage 两写前锁定本地原 tuple，不改 T143 transaction 或 terminal T141。

## 2. Stage source lock

新增独立 `PayRecordMapper.lockOwnedLegacyStageSource(expectedIntent, token, now)`：单 JOIN/FOR UPDATE 投影，精确核双表 pay/user/BINARY order/amount、双 LEGACY、active status、token 与未过期 lease；返回缺行/null表示资格或载荷变化。

在 `commitOwnedLegacyStage` 的 stage transaction 中、两次 stage UPDATE 前调用；null 抛 Runtime “支付阶段原载荷或领取资格已变化”，本阶段零写，原 catch/retry 保持。锁住两行直至 T143 两写 commit/rollback。

不改 T141 terminal source SQL/方法；不包远端 RPC、claim/retry/terminal。该锁只保护 stage 开始时 tuple，不保证 process 全程、RPC 已发出后或提交后的 fencing。

## 3. 保持项与验收

T143 两写原子、RootContext guard、stage/terminal/API/SQL/lease/TCC 保持。stage 错 tuple 不宣称 process 零写（此前 RPC/其它 stage 可能已发生）。

- Unit/native：两 stage × payment/intent/both user/order/CI-order/amount 24 变体，stage zero/no write；健康 amount/owner/mode 保持。
- Owned MySQL：独立 writer 在 lock 后阻塞，commit/rollback 后释放；Order paid/refund RPC 前 tuple drift 阻断后续 stage/RPC，原 retry 保持；资源 cleanup/source checksum。
- 不启动 App/RM/TC，不宣称跨服务 source fencing。

## 4. 交付边界

实现仅允许新增 stage source-lock Mapper、在 T143 helper 内调用及必要 JavaDoc/测试；不得修改 T141 SQL、terminal、RPC 或状态机。
