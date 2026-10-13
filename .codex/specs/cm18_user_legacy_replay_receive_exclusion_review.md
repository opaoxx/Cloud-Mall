# CM-18 16T118：User Legacy replay receive exclusion

## 1. 结论与范围

GO。T107 existing Legacy debit replay 已证明 tuple/state/snapshot 且 same-pay freeze 为空，但仍忽略 `user_tcc_receive`；同 pay CLAIMED/坏 receive 可让 APPLIED cache 成功或 REJECTED 返回 PAYMENT_FAILED。

本批只补 existing Legacy replay 的 same-pay receive existence gate；fresh T117 不改。

## 2. Replay receive gate

在 existing tuple → attempt state → known state → snapshot → T107 freeze-null 门之后、REJECTED PAYMENT_FAILED/APPLIED cache/result 之前，读取现有 `UserTccReceiveMapper.findByPayNo(request.payNo)` 一次：

- 任意 non-null row（CLAIMED、unknown state、CI pay、foreign、坏 key/meta）→ `BALANCE_DEBIT_UNKNOWN`/409 固定“扣款来源存在 TCC 接收事实”；
- 仅 null 才继续健康 REJECTED/PAYMENT_FAILED 或 APPLIED cache/result；
- DAO/DataAccess 异常原样传播，不按 state/meta/flag 过滤。

## 3. 保持项与边界

existing/history replay 的 attempt/historical/fixed-key/T76/T107 优先级保持；fresh T117 receive gate、TCC/退款/GET、wallet/claim/写/API 不改。existing receive failure 不读取 current wallet、不写 attempt/ledger/cache。

不核 receive metadata/Fence，不修历史，不宣称全账户/source fencing 或完整 TCC 因果。

## 4. 验收

- Unit：null/DAO/CLAIMED/unknown/CI/foreign/bad receive，坏 source/freeze/row 优先、无 write/cache；健康 APPLIED/REJECTED 与当前 wallet NULL 保持。
- Native：Legacy existing APPLIED/REJECTED/history-noattempt 加 receive 变体，HTTP/service 409、五表 facts/receive 不变；fresh/T117、TCC/退款/GET 回归。
- 资源 finally 清理，不启动 RM/TC/App；existing 仅新增一次 receive read。

## 5. 交付边界

实现仅允许在 existing Legacy replay 的 T107 freeze 后增加 receive point read、必要 JavaDoc/测试；不得修改 fresh、TCC、退款或写入路径。
