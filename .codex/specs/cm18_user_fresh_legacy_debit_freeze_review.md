# CM-18 16T104：User fresh Legacy debit freeze

## 1. 结论与范围

GO。Legacy fresh debit 在 attempt、bounded DEBIT、fixed key、T96 全 type payment ledger 均为空后仍未核同 pay 的 TCC freeze；TCC attempt 被删除但 freeze 保留时，可能再次 claim/扣余额。

本批只补 fresh Legacy debit 的 same-pay TCC freeze presence，不扩 receive/Fence、global cause、source fencing 或历史 replay。

## 2. Fresh freeze gate

在 T96 全 type payment ledger reliable empty 之后、当前 wallet 校验与 attempt claim 之前，复用 `BalanceTccDebitMapper.selectByPayNo(request.payNo)` 一次：

- 返回任意 non-null row（TRYING/CANCELLED/CONFIRMED/UNKNOWN、CI/foreign、坏 metadata）→ `IDEMPOTENCY_CONFLICT`/409 固定“支付号已有 TCC 冻结事实”；
- 仅可靠 null 才继续原 fresh wallet/claim/REJECTED/APPLIED 链；
- DAO/DataAccess 异常原样传播，不按 state/amount 过滤、不伪装为空。

## 3. 保持项

门只适用于真正 fresh Legacy debit；existing APPLIED/REJECTED replay、无 attempt 历史 replay、TCC 写、退款、GET、API、SQL、锁、状态与 Redis 不增加该读取。旧 caller/request/user/attempt/historical/fixed key/payment ledger 优先级与 T96 no-funds 语义保持。

不修历史 freeze/attempt，不核 Fence/receive，不宣称完整跨服务资金因果或 fencing。

## 4. 验收

- Unit：null/DAO/priority/replay/no-query；任意 freeze row 状态/metadata/alias 均 409 且不 wallet/claim/write/cache。
- Native：真实 TCC TRYING/CANCELLED 删除 owner 后保留 freeze、ghost state/CI/foreign freeze，Legacy 两入口返回 409，四表 facts/cache 不变；healthy fresh、已有历史、跨用户与余额不足回归。
- 资源 finally 清理，不启动 RM/TC/App；查询计数 fresh 仅增加一次。

## 5. 交付边界

实现仅允许在 T96 fresh empty 门后增加现有 freeze point read、必要 JavaDoc/测试；不得修改 replay、TCC、退款或资金写入。
