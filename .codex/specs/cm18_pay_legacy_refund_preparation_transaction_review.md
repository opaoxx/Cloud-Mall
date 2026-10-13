# CM-18 16T147：Pay Legacy refund preparation transaction

## 1. 结论与范围

GO。`finishRefunded` 当前在 T141 terminal source lock 之外先写 `REFUND_PENDING`；末次 Order proof 之后本地 tuple 变化可留下坏 stage，随后 terminal 事务才拒绝。marker path 的 stage 0/第二写异常也可能残留外部准备写。

本批把 finishRefunded 的 preparation write 纳入 T141 REFUNDED terminal short transaction。

## 2. REFUNDED transaction ordering

`commitOwnedLegacyTerminal(..., "REFUNDED")` 在 source lock 通过后，按原时间/参数执行：

1. `markOwnedLegacyRefundPending`，返回必须 1；
2. 原 `markOwnedLegacyRefunded`；
3. 原 intent `finishOwnedLegacy`。

三写同一 terminal `REQUIRES_NEW` transaction，任一 0/异常整体 rollback。REFUNDED preparation 使用 fresh now；准备成功后重新取 terminalTime 以保原 stage→terminal 时间顺序。SUCCESS/FAILED 不增加准备写。

`finishRefunded` 不再在 transaction 外单独写 preparation；Order gate 仍在 transaction 外、且先于 preparation。`refundCancelled` 原有前置 stage（RPC 前）及 T143 semantics 保持；该 stage 与 finishRefunded 的 terminal 内准备不可宣称跨阶段原子。

## 3. 保持项与边界

RootContext guard、T141 source lock、T143 stage、T145 retry、User refund/RPC、claim/lease/API/SQL 及 catch 保持。源缺证/Order gate 失败不写 preparation；不撤回已发 RPC、不补退，不宣称 process/跨服务 fencing 完整。

## 4. 验收

- Unit/native：marker/post-refund × 双表 tuple 变化，Order gate 后 facts 不变；payment PENDING marker terminal write 0/trigger 异常整体回滚；三写同连接、RPC 外。
- 同 pay 补证/原 retry 下一轮恢复、healthy REFUNDED 与 T141/T143 回归；资源、checksum、owned cleanup。
- 不启动 App/RM/TC，不把外部 `refundCancelled` pre-stage 与 terminal short transaction 合并为一体。

## 5. 交付边界

实现仅允许把 `finishRefunded` preparation move 到 terminal REFUNDED 分支、更新时间顺序及必要 JavaDoc/测试；不得改 SUCCESS/FAILED、RPC、T141 source lock 或 stage helper。
