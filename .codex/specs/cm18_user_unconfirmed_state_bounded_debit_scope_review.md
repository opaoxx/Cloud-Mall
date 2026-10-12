# CM-18 16T93：User unconfirmed bounded DEBIT scope

## 1. 结论与范围

GO。TRYING/CANCELLED 当前仍使用单 PO `selectDebitByPayNo`；同 pay 多条 DEBIT 会在 state GET/诊断入口先触发 TooManyResults/500 或 503，使 T90 的全 type no-funds 门无法分类异常。

本批只把未确认状态的固定 DEBIT scope 读取替换为有界 candidate list；CONFIRMED/T92、UNKNOWN、资金写与 TCC callback 不改。

## 2. Bounded DEBIT scope

TRYING/CANCELLED 分支原固定 DEBIT 读取改用已有 `findDebitCandidatesByPayNo(payNo)` LIMIT2：保留 CI、全 state DEBIT 候选、按 id，不新增 SQL/Mapper/API/锁。

新增 `requireEmptyDebitScope`：list 必须非 null 且 empty；null/非空（单行、多行、APPLIED/REJECTED/UNKNOWN、type/pay alias）统一现有 `USER_TCC_STATE_CONFLICT`/409。DAO/DataAccess 异常原样传播，不转换为 409/503。

该门保持 fixed key/receive/attempt/source proof 优先；通过后继续 T90 `requireEmptyPaymentLedger` 全 type/state LIMIT101 空集合门。两次查询各保原一读预算，不过滤异常状态隐藏重复 DEBIT。

## 3. 调用与保持项

仅 TRYING/CANCELLED 使用该 bounded DEBIT 门；attempt incomplete/UNKNOWN 路径、CONFIRMED/T92 bounded candidate、Terminal reader 自己的其它单行读取不变。state conflict 在 Fence/诊断报告前稳定为 409，真实缺表/DAO 异常仍按原 503/异常链。

不改 SQL 写、RR、状态、RootContext、API/VO、TCC/Fence、wallet、source fencing 或历史 repair；不宣称完整账户/全历史因果。

## 4. 验收

- Unit：TRYING/CANCELLED bad single/null/multiple candidate、状态/type/pay alias、DAO fault、旧 fixed key 优先；所有坏 scope 409 且无资金写。
- Native RR/HTTP：三未确认形态（TRYING freeze、CANCELLED freeze、attempt-only）× extra DEBIT/alias/multiple，state GET 与诊断均 409，六表 facts/no-write/同连接保持；single DEBIT 409 控制与 T90 全 type orphan 门同时回归。
- 两行 LIMIT2 不因异常 state/type 过滤而放行；查询计数一替一、DB503 仍 503。资源 finally 清理，不启动 RM/TC/App。
- 保留 T90–T92、共享 Reader、TCC 回归；不修改 CONFIRMED/UNKNOWN。

## 5. 交付边界

实现仅允许替换 TRYING/CANCELLED 的单 DEBIT 读取并新增纯空 scope helper、JavaDoc/测试断言；不得扩展其它状态、API 或资金路径。
