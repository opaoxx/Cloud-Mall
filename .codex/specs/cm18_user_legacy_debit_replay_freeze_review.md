# CM-18 16T107：User Legacy debit replay freeze

## 1. 结论与范围

GO。Legacy existing debit replay 已通过 T76 tuple/state/snapshot proof 后，仍未核同 pay TCC freeze；APPLIED 可 cache 成功，REJECTED 可返回 PAYMENT_FAILED，即使同 pay 有 ghost freeze。

本批只保护 existing Legacy debit replay 的 same-pay freeze presence，不扩 receive/Fence、退款集合或全资金因果。

## 2. Replay freeze gate

在 existing tuple → attempt state mismatch → known state → snapshot proof 全部通过后、REJECTED PAYMENT_FAILED/APPLIED cache/result 之前：

- 仅 attempt null 或已通过 LEGACY attempt 的 existing replay调用 `balanceTccDebitMapper.selectByPayNo(payNo)`；
- 返回任意 non-null row（任意 state/metadata/CI/foreign）→ `BALANCE_DEBIT_UNKNOWN`/409 固定“扣款来源存在TCC冻结事实”；
- 仅可靠 null 才继续健康 REJECTED/PAYMENT_FAILED 或 APPLIED cache/result；
- DAO/DataAccess 异常原样传播。

TCC/未知 attempt 不扩大该 Legacy gate；fresh T104、TCC/退款/GET/API/SQL/锁保持。

## 3. 保持项与验收

existing replay 不 claim、debit、insert、当前 wallet 读取或修改；历史无 attempt replay 也适用 Legacy freeze presence。T76/T104 其它优先级保持，source/freeze 不核 metadata/Fence。

- Unit：null/DAO/六种 freeze row、old T76 priority/no-write/read count；健康 current wallet NULL、APPLIED/REJECTED replay 保持。
- Native：真实 Legacy APPLIED/REJECTED/history-noattempt 加 ghost freeze state/CI/foreign，HTTP/service 均 409，四表/cache facts 不变；资源 finally 清理，不启动 RM/TC/App。
- 不宣称全历史、全账户或 source fencing。

## 4. 交付边界

实现仅允许在 existing Legacy replay 的 snapshot proof 后增加 freeze presence read、必要 JavaDoc/测试；不得修改 fresh/TCC/退款或写入路径。
