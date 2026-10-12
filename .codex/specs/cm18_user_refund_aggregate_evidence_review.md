# CM-18 16T75：User refund 关联集合证据

## 1. 结论与范围

GO。fresh refund 当前使用 SQL `SUM` 直接汇总 `original_operation_key` 关联行，负数可抵消、CI 别名/错 tuple 可混入，且 type/state 过滤会把坏候选隐藏后继续 credit。本批把原扣款之后、wallet 门之前的退款累计改为有界逐行证据。

只覆盖当前原扣款键的关联候选片段，不证明全账户、其它 pay、全局历史唯一性或完整退款因果。existing replay、disabled T74 优先级、T70–T74 其它规则保持。

## 2. 候选读取与上限

在原 DEBIT proof 通过后复用已有 `findLinkedRefundCandidates(original.operationKey)`，不新增 SQL/锁/DDL/接口。该查询保留原键的全部类型/状态 CI 候选并 LIMIT 101：

- null list 或 size > 100 fail closed，沿 active `BALANCE_REFUND_INVALID`/409；不把 null 当空集合，不 credit/insert。DAO exception 原样沿现有异常链传播，不转换为该业务错误码。
- 候选不要求或依赖正 `id`；id 只是排序/数据库主键事实，不作为本批资金证明字段。避免用缺失 id 掩盖仍应核验的候选。
- 候选 operationKey 必须非 null、非 blank、精确唯一，且不得等于原 DEBIT operationKey。生产唯一键由既有 CI UNIQUE 约束保证；逐行集合仍应拒绝重复精确 operationKey，不做 trim/casefold/别名归一。

## 3. 逐行证明与累计

每个候选必须同时满足：

- candidate 非 null，`operationType=REFUND`、`state=APPLIED`；
- userId、originalOperationKey、payNo、orderNo 与已证明原 DEBIT/request 精确匹配；CI 候选读回后仍做 Java exact 比较；
- amount 为正且合法 DECIMAL(18,2)；balanceBefore/balanceAfter 非负且同域；`balanceAfter - balanceBefore = amount`；
- 不允许 NULL、负/零金额、非法 scale/capacity、错误 delta、空/别名 original key、错身份/type/state。

所有行先完整验证，再以 `BigDecimal` 累加。负数、坏行或先前累计超过原金额都不能被过滤或用提前超额判断隐藏。累计值加本次 request amount 超过原 DEBIT amount 时，沿既有 `BALANCE_REFUND_EXCEEDED`/409；坏候选本身沿 active `BALANCE_REFUND_INVALID`/409。仅全部候选证明通过后才进入 T72 wallet/ledger 原子门。

## 4. 保持项与非目标

不改 `findLinkedRefundCandidates` SQL、参数、锁/事务、DDL、缓存、状态机或 API；旧 `sumAppliedRefunds` 可保留供其它调用，但不得在本 fresh proof 中被描述为完整证据或继续作为累计来源。不得查询全局 operationKey、其它退款集合、账户余额因果或修复历史行。

不改 active existing replay 的 T73 proof，也不改变 disabled existing/fresh 的 T74 source/freeze 404 优先级。候选读取异常原样按现有 DAO 异常链处理，不伪装为空集合。

## 5. 验收

- Unit：空/null、101 sentinel、负数抵消、wrong user/pay/order/original alias、REFUND/DEBIT/非 APPLIED、NULL/零/负/非法 scale/capacity、错误 delta、重复精确 operationKey；全部在 credit/insert 前固定失败。
- 健康：部分退款累计、尾零、100 条边界及与 request 合计恰好等于原 DEBIT 保持原成功；超过上限沿 `BALANCE_REFUND_EXCEEDED`。
- Owned MySQL/HTTP：真实 Legacy/TCC refund 后修改/插入坏关联行，控制器返回 409、无 credit/insert，源表 facts/checksum 不变；CI 候选必须由 exact Java 门拒绝而非 SQL 过滤成缺失。资源 finally 清理。
- 不宣称 full refund history、全局 CI 唯一性、账户总额或完整资金因果；保留 T70–T74 及旧回归。

## 6. 交付边界

实现仅允许替换 fresh refund 的 `SUM` 累计为既有候选读取与纯字段 helper、必要 JavaDoc/步骤注释及对应测试；不扩展源码、Mapper SQL、API 或其它资金路径。
