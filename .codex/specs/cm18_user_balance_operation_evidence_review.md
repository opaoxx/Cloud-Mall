# CM-18 16T78：User balance operation evidence

## 1. 结论与范围

GO。`balanceOperation` 当前只按 operationKey 取行并直接返回，未证明 operation tuple、金额 snapshot、原扣款来源或退款集合；NULL/坏行可能 200 或 NPE，Pay Legacy refund recovery 因此会把不完整账本当终态证据。

本批只加强该既有 GET 的只读证据；不改变退款/扣款写路径、T77 Pay 逻辑或数据库结构。

## 2. 入口与快照

- caller 身份校验保持最先；主行缺失或 user 不匹配仍优先 404。
- 主行存在后，整个 GET 置于 `@Transactional(readOnly=true, isolation=REPEATABLE_READ)`；复用既有 Mapper 方法，不使用 `FOR UPDATE`，不写库/缓存，不新建 SQL/表/锁 API。
- 主行 key 必须精确等于 path operationKey，payNo/orderNo 非 null 且非 blank，amount 正且合法 DECIMAL(18,2)，balanceBefore/After 非负且同域。坏账本沿 `ErrorCodes.CONFLICT`/409 固定“余额账本事实待核对”，不暴露原值。

## 3. DEBIT 证据

DEBIT 主行必须是 state `APPLIED` 或 `REJECTED`、`originalOperationKey == null`，并通过既有 `isProvenLegacyDebitSnapshot` 等价规则：APPLIED `before-after=amount`；REJECTED `before==after && before<amount`。不读取 current wallet、attempt、freeze、TC 或账户总额。

DEBIT 在主行 proof 与退款集合 proof 完成后，才调用既有 `selectFirstRefundOperationKey`，保持原 selector SQL、limit 和排序策略。REFUND 则在自身主行 proof 后用 `selectByOperationKey(originalOperationKey)` 读取原 DEBIT source，并精确核 original key、DEBIT/APPLIED、origin null、user/pay/order、金额与 snapshot；source 缺失或错误同样是本 GET 的 CONFLICT/409，不伪装成 404。

两种主行都复用 T75 的 `findLinkedRefundCandidates` LIMIT101 集合证明：每行精确 REFUND/APPLIED/user/original/pay/order、正合法金额、合法余额 delta、非空唯一 operationKey；null/超限/坏集合或累计超过 source amount 统一转为本 GET 的 CONFLICT/409，DAO 异常原样传播。REFUND 还必须证明当前主行自身 operationKey、金额和 snapshot 属于该已验证集合；集合累计与 source amount 的比较在同一 RR 快照内完成。为保持 fresh 路径读取边界，可保留 `provenRefundedAmount(original)` 的单次 fetch 入口，并以候选列表 overload 复用同一逐行 kernel；不得新增 SQL。

marker 必须与同一已证明集合一致：集合累计为 0 时 selector 必须 null；累计大于 0 时 selector 必须非 blank 且属于已证明 refund operationKey 集合。不得更换 selector 的原 id 排序/字段选择。REJECTED 只能接受零 refund 集合与 null marker；APPLIED 可返回已证明 marker。REFUND 响应 marker 保持 null。

## 4. 保持项与错误边界

主行缺失/foreign 仍 404；主行或集合证据坏为固定 409，不伪装成 NOT_FOUND。T75 `BALANCE_REFUND_INVALID` 只在本 GET 的集合转换边界映射为该固定 CONFLICT，DAO/连接异常沿现有链传播。

不改 API/VO 字段、JSON 金额字符串、SQL 文本/参数、锁、写状态机、缓存、TCC/RM/TC、refund/debit 计算或 existing replay。GET 只证明 User 可见账本片段，不证明全账户、全历史、全局唯一性、当前 wallet 或 Pay finish 之后的跨服务窗口。

## 5. 验收

- Unit/HTTP：DEBIT/REFUND、APPLIED/REJECTED、foreign/key/type/state/NULL/坏金额/snapshot、集合 null/坏行/负数抵消/101/超额、marker null/alias/不在集合；固定 404/409、无写。
- Owned MySQL：真实 Legacy/TCC debit/refund 损坏事实在同一 RR snapshot 下返回 409；健康 partial/full、尾零、REJECTED、无 attempt、current wallet NULL 保持；source checksum 与资源清理不变。
- 验证同一连接只读查询和 selector 计数边界；不启动 RM/TC/App，不宣称完整退款因果。
- 保留 T70–T77、User operation API 字段和 Pay Legacy retry 回归。

## 6. 交付边界

实现仅允许修改 `balanceOperation` 只读事务与字段/集合 proof helper、必要 JavaDoc/测试；不得改 refund/debit 写入及其它资金路径。
