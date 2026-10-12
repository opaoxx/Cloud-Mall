# CM-18 16T71：User Legacy 请求金额域

## 1. 结论与范围

GO。`UserServiceImpl.validateMoney` 当前只检查非空与正数；Legacy 扣款、退款及兼容入口因此可把 `0.001`、`10.001` 或超过 DECIMAL(18,2) 容量的请求送入余额 SQL，产生数据库隐式舍入、容量异常或请求金额与实际资金不一致。

本批只收紧该共享请求金额门，不改变 DTO/JSON、数据库、锁序、事务、状态机、缓存、TCC 校验或 API 契约。

## 2. 共享金额规则

在现有 `validateMoney(amount, message)` 中追加同一金额域：

- amount 必须非 null、严格大于 0；保留现有 `INVALID`/400 及调用方原 message。
- `amount.stripTrailingZeros().scale() <= 2`，因此 `0.010`、`10.000` 与 `0.01`、`10.00` 按数值等价接受；禁止舍入、截断或补零后再写入。
- amount 不得大于既有 DECIMAL(18,2) 上限 `9999999999999999.99`。允许该上限及其合法尾零表示。
- 非法精度或容量必须在任何 Mapper 查询、余额条件更新、账本/attempt 写入和缓存前拒绝。

常量可由 `MAX_REFUND_ORIGINAL_MONEY` 改为准确表达共享请求域的名称（例如 `MAX_BALANCE_MONEY`），数值保持不变；不得新增另一套金额上限。

## 3. 调用顺序与覆盖边界

- 保持 caller identity 校验先于金额校验。扣款/退款沿各自现有位置调用共享门；不得把金额门提前到身份门之前。
- Legacy fresh debit 与 fresh refund 均在任何 Mapper/资金写之前执行该门。
- Legacy 已有 replay 请求也执行该门，随后仍沿原 operationKey/payNo 幂等和冲突协议；本批不改变 replay 的查询范围或结果。
- 退款的 T70 原 DEBIT proof、退款合计、disabled TCC 归属及 `confirmedTccRefund` 顺序保持；本批只校验请求金额，不替代原来源证明。
- TCC debit/confirm/cancel 的既有请求验证与资金路径不扩展；不得把本门改成历史账本金额修复或全账户金额校验。

## 4. 非目标与保持项

不改 DTO 注解、JSON 金额字符串语义、SQL/DDL/Mapper、锁与事务、余额不足优先级、状态码/消息、缓存、Seata/RM、Redis、消息及 Legacy/TCC 状态机。`null`、零、负数仍沿现有 400；本批新增非法 scale/capacity 同样沿原调用方 `INVALID`/400。合法 `0.010`、`10.000`、`0.01`、上限值及尾零值不得误拒。

## 5. 验收

- unit：扣款与退款 fresh/replay 分别覆盖 null/zero/negative、scale 3、超过上限；断言身份失败仍优先，非法金额不触发任何 Mapper/余额写/账本写/缓存。
- unit/HTTP：覆盖合法尾零、`0.01`、`10.000`、上限及余额不足，保持原结果与金额数值语义。
- owned MySQL：真实 Legacy debit/refund 非法金额返回 400，四张资金表、余额、冻结、账本、attempt/source checksum 不变；合法边界保持原资金结果。测试专用 nullable/宽列演练须 finally 清理，不修改生产 DDL。
- 保留 T70 source proof、disabled 404、active 409 及 TCC/历史回归；不启动 RM/TC/App，不伪称完整账户因果或退款总额证明。

## 6. 交付边界

本规格只冻结请求金额域门，CM-18 其它资金证据切片及 CM20/CM21 不因本批提前关闭。实现前须先记录 bugfix_log，生产改动仅限共享金额 helper、相关测试与必要 JavaDoc/步骤注释。
