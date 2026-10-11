# CM-18 16T32：秒杀 settlement 写路径原订单身份校验

## 1. 结论与范围

本切片 GO，基线为 `6f3e061`，只修正 `SeckillQuotaSettlementService.settle` 写路径对 CI 候选误认原订单的问题。当前 `INSERT IGNORE ... SELECT` 与随后 `SELECT ... FOR UPDATE` 按 orderNo 返回候选，但锁定行在维度校验、操作键、状态幂等和 CAS 前没有核对其原始 `order_no`；大小写、重音、尾随空格或别订单候选可能进入写状态机。

本批只增加一个写 guard，覆盖 `confirm`/`rollback` 共同的 settlement 行；不改 T31 读 guard 以外的读取、不改 SQL、DDL、锁顺序、事务传播、Redis、RabbitMQ、恢复器、普通库存或业务 API。

## 2. Guard 位置与冲突语义

在既有 `insertOpenIfAbsent(orderNo, now())` 后调用 `findSettlementForUpdate(orderNo)`，若返回非 null settlement，必须立即执行：

```text
Objects.equals(orderNo, settlement.orderNo)
```

该比较必须使用锁定 PO 的原始 `orderNo`；null、非文本、大小写/重音/尾随空格不精确相等均视为身份冲突。冲突沿既有 `SECKILL_SETTLEMENT_CONFLICT` 409，消息固定且不包含请求或候选原始 orderNo、XID、operation key、维度或数据库错误。

Guard 必须位于 `validateSettlement`、operation key 派生、状态终态/幂等判断和任何 CAS 之前；不能让 alias 进入 `CONFIRMED`、`RELEASE_PENDING`、`RELEASED` 的写入或 noop 成功。冲突异常必须阻止 Stock confirm/rollback 继续普通路径。

若本次没有 settlement，既有 `insertOpenIfAbsent` 可能因 CI 候选从 ACCEPTED 受理事实插入一条错误来源的 OPEN；随后锁定行的 identity guard 必须抛冲突，使整个既有 REQUIRED 事务回滚，包括这次 INSERT。原 `seckill_reservation` 的 ACCEPTED 行不得被修改，不能把回滚后的缺失误报为普通 null 成功。

## 3. 事务与不变行为

- 保留 `insertOpenIfAbsent` 先于锁定查询的死锁规避顺序、单一 `@Transactional(REQUIRED)`、原 rollback 语义和同一 Spring transaction manager。
- 不新增 SELECT、SQL、BINARY predicate、参数、锁、事务传播或隔离级别；不移动/重写 Mapper 方法。
- exact orderNo 的正常 OPEN/CONFIRMED/RELEASE_PENDING/PROJECTION_BLOCKED/RELEASED 状态、同方向 noop、相反动作冲突、pending rollback、revision 和 operation key 规则保持。
- settlement 行缺失且原方法应返回 null 的普通/legacy route 保持；既有 `validateSettlement` 维度损坏仍沿原 500，不被本身份 guard 改成其它错误。
- T31 的 GET identity guard、`reservation`/`reservationResult` 输出、`ensureTable`/启动迁移、recovery、Redis/MQ 和普通库存路径不改。本批不处理其它 tuple/provenance、required-positive 或 CM18 M1–M4 缺口。

## 4. 验收矩阵

- Unit：confirm/rollback 各覆盖锁定行精确 orderNo、null、大小写/重音/尾随空格 alias、别订单；身份失败必须在 dims/status/noop/CAS 前返回既有 409，且无状态更新。
- Owned MyBatis + JdbcTemplate：实际同 Spring transaction manager 验证 alias 已存在 OPEN 行时两动作均冲突且全行不变；已存在 CONFIRMED/RELEASED noop 也不能绕过 guard；legacy ACCEPTED alias 无预先 settlement 时，错误 OPEN 插入后整体回滚，`seckill_reservation.status=ACCEPTED` 保持且无 settlement 残留。所有负例必须核对 owned 原有事实不变、临时 INSERT 无残留并完成 finally 清理；exact positive confirm/rollback 允许 settlement 按原协议发生状态/CAS/revision/operation 变化，不把正例要求写成全行 checksum 不变。源 Stock 原库只核对 checksum，禁止被测修改。
- 外层 Stock confirm/rollback/controller 通过真实 tx proxy 验证 409 不进入普通库存路径，不产生 `stock_flow`、`stock_sku`、普通 reservation request、缓存、Redis/MQ/RM/TC 写入；既有 `seckill_quota_settlement` INSERT 尝试允许在同一事务中回滚。Controller 可用 standalone MVC 加真实事务代理且无监听端口；若走 HTTP，只使用 finally 清理的随机 loopback，不启动真实业务 App 或固定端口。
- Exact positive confirm/rollback、同方向重试/noop、相反动作、pending/revision、合法 settlement 创建及 T31 读回归保持。旧 fixtures 必须使用与请求一致的原始 orderNo，不能通过修 fixture 弱化 CAS/幂等断言。
- source Stock 表始终以全字段 checksum 证明未被修改；owned 负例全字段/状态/时间/数量前后不变，正例只按原 settlement 协议核对预期状态/CAS/revision/operation 变化。测试插入只允许 owned 资源，结束 finally DROP/清理。

本切片只冻结写路径的原订单身份门，不宣称完整 settlement tuple、普通库存、TC/MQ 或 CM18 主项闭环；CM20/CM21 仍等待 CM18 整体完成。

## 5. 当前实现只读复核

当前 `SeckillQuotaSettlementService.settle` 仅在锁定行非 null 后新增原始 `orderNo` 精确比较，位置在 `validateSettlement`、operation key、状态 noop 和 CAS 之前；冲突使用固定 `SECKILL_SETTLEMENT_CONFLICT` 409，无 raw 标识。`insertOpenIfAbsent`、`findSettlementForUpdate`、事务传播及原状态写入顺序保持。生产复核结论为 GO；专项测试结果仍以 backend/qa 的最终绿证据为准。
