# CM-18 16T109：User refund replay bounded DEBIT source

## 1. 结论与范围

GO。fresh refund 已有 T97 bounded DEBIT source，但 active/disabled existing replay 在原 source/cap/owner/TCC proof 后仍忽略同 pay 第二 DEBIT、CI alias 或未知状态。

本批提取 fresh source scope 为纯 helper，并复用于 existing replay；不新增 SQL/API/写入/集合/receive/Fence 或全账户因果。

## 2. Replay source scope

`requireRefundDebitScope(original, request, userId)` 必须与 fresh T97 使用同一 LIMIT2 candidate list、唯一 candidate、id/tuple/amount/snapshot copy predicate。

- fresh：原位置、查询次数、source/owner 优先级保持；
- active existing：T105 source/cap → T108 owner 通过后，调用 helper；新增一次 existing bounded read；
- disabled existing：T74 original/TCC 404 → T73 row → T106 cap 通过后，复用已读 original 调用 helper，不重新读 source。

缺失/多行/nullrow/错 copy/CI alias/坏 state 沿 `BALANCE_REFUND_INVALID`/409 原 message，DAO 原样传播。只证明当前同 pay DEBIT 范围，不核当前 wallet、集合、receive/Fence 或全历史。

## 3. 保持项与验收

existing active/disabled 合法 replay 才进入 VO，不能 credit/insert；fresh、T75 aggregate、T98–T108 owner/freeze、disabled 404、SQL/锁/API/TCC/Redis 保持。

- Unit：missing/multiple/copy/DAO/source/T73 priority/query count/current wallet NULL；active/disabled/replay budgets 正确。
- Native：active Legacy/TCC/disabled TCC 已退款后插第二 DEBIT APPLIED/REJECTED/UNKNOWN/CI/3row，退款 POST 与直接 service 调用均 409 且四表 facts 不变；不改 operations GET。other-pay 正常，healthy replay/100 边界保持。
- 资源 finally 清理，不启动 RM/TC/App；旧 T97/T106 合法 replay 断言按新增一次 scope read 同步。

## 4. 交付边界

实现仅允许提取并复用 fresh bounded source helper、接入 active/disabled existing、必要 JavaDoc/测试；不得修改 owner/freeze、集合或写入路径。
