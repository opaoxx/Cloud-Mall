# CM-18 16T74：disabled refund 的 TCC freeze proof

## 1. 结论与范围

GO。disabled refund 当前 `confirmedTccRefund` 只核 attempt tuple，未核 `balance_tcc_debit` 的本地 CONFIRMED 冻结事实；attempt amount 为 NULL 时还可能 NPE，缺失或错误 freeze 也可能放行退款。

本批只补 disabled fresh/existing replay 的本地 TCC freeze proof。active 路径不增加该查询；fresh 原 DEBIT proof、T71 请求金额、T72 wallet/ledger 原子门和 T73 replay proof 不改。

## 2. Proof 顺序与查询

在 `confirmedTccRefund` 的既有 attempt 门中，先增加 attempt.amount 非 null、正数、合法 DECIMAL(18,2) 检查；xid nonblank、branch positive 及既有 tuple 规则保持。坏 attempt 继续沿 disabled `COMMON_NOT_FOUND`/404，不新增错误码或查询。

attempt 门通过后，复用现有 `BalanceTccDebitMapper.selectByPayNo` 一次无锁 SELECT，在用户行锁事务内核对本地 freeze；不新增 SQL 文本、表、锁、Mapper API 或独立事务。DAO 异常原样传播，不伪装成无证据。

## 3. Freeze 精确字段门

冻结行必须同时满足：

- 非 null，state 精确 `CONFIRMED`；
- userId/payNo/orderNo、operationKey、xid、branchId 与 attempt/原 DEBIT/request 的对应值精确一致；
- amount 为正且合法 DECIMAL(18,2)，数值等于已证明原 DEBIT amount；
- balanceBefore/balanceAfterTry 非负且合法 DECIMAL(18,2)，与原 DEBIT 已核快照逐值相等，且 `balanceBefore - balanceAfterTry = amount`。

任一缺失、CI 别名、wrong branch/xid、错误状态、错误金额或错误 delta 沿 disabled `COMMON_NOT_FOUND`/404；不执行 credit、insert、缓存或其他资金动作。只证明本地 freeze 事实，不读取 receive/Fence/TC/RM，不声称跨服务或完整资金因果。

## 4. 保持项

disabled fresh 与 existing replay 均复用 `requireDisabledTccRefund`；existing replay 仍不进入 active fresh 写链。Legacy disabled 仍 404。SQL/锁顺序（新增既有无锁点读除外）、事务、DTO/API、状态机、TCC writer、source query、T70–T73 错误优先级保持；不修历史行、不新增表/DDL。

## 5. 验收

- Unit：attempt amount NULL/零/负/非法 scale/capacity 先 404 且不查询 freeze；freeze 缺失、wrong tuple、非 CONFIRMED、坏 amount/snapshot/delta 404；active 路径断言零新 freeze query。
- Owned MySQL/HTTP：真实 TCC confirm 后修改/删除 freeze 或 attempt amount nullable，disabled fresh/replay 返回 404，不 credit、不 insert，User/ledger/attempt/freeze facts 与 checksum 不变；健康 fresh/replay、branch、尾零、zero-after 保持。
- 复用现有 `selectByPayNo`，按真实无锁 SELECT 观察查询次数；资源 finally 清理，不启动 RM/TC/App，不要求真实远端 TCC。
- 保留 T70–T73 回归；不覆盖完整退款集合、账户总额、历史修复或全局 CM18 闭环。

## 6. 交付边界

实现仅允许调整 `UserServiceImpl.confirmedTccRefund` 及所需金额/字段私有门、JavaDoc/步骤注释与对应测试；不修改 mapper SQL、API、DTO、TCC 状态机或其他服务。
