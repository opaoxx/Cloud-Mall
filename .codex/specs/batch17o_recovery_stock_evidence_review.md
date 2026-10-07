# CM-20261007-20：秒杀恢复 Stock 受理证据分层评审

## 1. 评审结论与范围

结论：**GO，限定修复 `SeckillMessageRecovery.verifyStockFact` 对未知/缺字段 Stock 响应的状态分层；不改变 API、schema、状态枚举、时间门、lease 或历史 CONFLICT 记录。**

当前逻辑在 `NOT_FOUND/REJECTED` 之后先执行完整 `matchesEvent`。因此 Stock 返回 UNKNOWN/缺失 status 或已知 ACCEPTED 但暂缺核心字段时，会被永久标记 CONFLICT；“暂时没有足够证据”被错误当成“事实明确矛盾”，后续完整 Stock 事实无法重试发送。

## 2. 证据分层顺序

保留既有时间门、lease guard、Stock RPC 和 payload 原始事实：

1. 事件自身 orderNo、核心字段、requestHash 等非法：保持 CONFLICT，说明原事件无效；
2. Stock 响应为空、status 缺失/null/blank 或 status 为未认识的新状态：标记 VERIFYING，沿既有 30 秒退避，不先做冲突匹配；
3. status 为 `NOT_FOUND` 或 `REJECTED`：保持现有 QUARANTINED/5 分钟退避基线；
4. status 为 `ACCEPTED`、`PUBLISHED` 或 `ORDER_CREATED`：
   - event/fact 核心字段、orderNo、activityId、skuId、userId、idempotencyKey、requestHash 均完整且一致时允许发送；
   - Stock 核心字段缺失/blank 或 requestHash 缺失时标记 VERIFYING/30 秒退避，不能直接 CONFLICT；
   - 字段完整但与有效原事件不一致时标记 CONFLICT/5 分钟退避；
5. 完整匹配才允许 Rabbit 重发；所有失败路径不猜时间、不改 payload/eventId/hash、不调用额外 RPC。

事件自身字段非法仍优先 CONFLICT；时间门失败继续使用 17H 的 `ORDER_SECKILL_TIME_UNVERIFIED`/QUARANTINED 语义。状态未知和字段暂缺不能永久终止可恢复事实。

## 3. Lease、ACK 与范围

本轮沿用 17J/17K 完成写和发送前 lease 保护：VERIFYING/QUARANTINED 更新需要有效 owner/lease，迟到 worker 不得覆盖新 owner。验证失败释放 lease并按既有退避，下一轮重新读取 Stock 事实；不存在 lease/状态新写逻辑。

不修改 Rabbit ACK/nack、DLQ 原始载荷、Stock API、状态枚举、Order 消费者或历史已标记 CONFLICT 记录；不自动把存量 CONFLICT 重分类为 VERIFYING，不修历史事实。

## 4. 验收矩阵

1. Stock 返回 UNKNOWN、null status、blank status、新未知状态：VERIFYING + 30 秒退避，后续完整 ACCEPTED 事实可发送。
2. ACCEPTED/PUBLISHED/ORDER_CREATED 缺核心字段或 hash：VERIFYING，不永久 CONFLICT；补齐事实后可发送。
3. 完整字段与有效事件不一致：CONFLICT，保留原 payload，不发送。
4. NOT_FOUND/REJECTED：既有 QUARANTINED/5 分钟退避保持不变。
5. 事件自身非法字段、时间非法/错月：原有 CONFLICT/QUARANTINED 语义保持。
6. lease 过期、owner 替换和新 owner 竞争继续由 17J/17K 阻止迟到完成/发送；原 ACK/nack 与 Rabbit 替身回归通过。
7. 真实 MySQL recovery 台账 + Stock/Rabbit 替身验证未知→完整事实恢复、字段缺失恢复、明确冲突和隔离清理；不宣称真实 broker 网络故障已覆盖。

## 5. 实施门与范围声明

实现限定在 `verifyStockFact` 判断顺序和对应 Mapper 状态更新调用；不新增 RPC/API/schema，不扩展状态枚举、不修历史 CONFLICT、不修改时间门、payload、eventId、lease 或 Rabbit 拓扑。本轮只恢复“证据暂缺”记录的可重试性。
