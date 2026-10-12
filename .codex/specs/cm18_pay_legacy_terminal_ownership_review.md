# CM-18 16T80：Pay Legacy terminal ownership

## 1. 结论与范围

GO。16T79 只令 intent 的 retry/finish 具备每次 claim 独立 token；PayRecord 的 `finishSuccess`、`finishRefunded`、`failWithoutDebit` 仍按 payNo 更新，迟到或过期执行可改写新的 claim 对应支付记录。

本批只为 Legacy 三个终态写增加 owner+lease deadline 资格门，不声称建立两表原子 fencing。

## 2. PayRecord owned updates

新增三个 Legacy 专用语义 Mapper 方法，保留旧方法签名供其它/TCC 调用者使用。每个方法使用单条 `pay_record` 与 `pay_debit_intent` 关联 UPDATE：

- join 两表同一 payNo；请求 payNo 精确匹配；两表 `debit_mode` 均用 `BINARY 'LEGACY'`；
- intent `lease_owner` 精确等本次 claim token，`lease_until > requestedNow`；
- intent status 必须是本终态允许的活动状态；
- Success/Failed 保留原 `PENDING`,`ORDER_SYNC_UNKNOWN` 允许集合；Refunded 保留原 `REFUND_PENDING`,`ORDER_SYNC_UNKNOWN`；
- SET 仅保留原各方法的 status/paid_at/updated_at 语义，参数与终态返回保持。

不新增独立 SELECT、FOR UPDATE、显式锁序或 schema 字段；关联 UPDATE 可能改变数据库锁足迹/计划，不承诺物理扫描或并发性能不变。

## 3. Intent owned finish 与调用顺序

新增 `PayDebitIntentMapper.finishOwnedLegacy`，条件保留本次 payNo、token、`lease_until > requestedNow`、BINARY `debit_mode='LEGACY'` 及终态前活动状态；SET 保留既有 finish 的 status、last_error 清除、next_retry、lease 清除和 updated_at。

`finishSuccess`、`finishRefunded`、`failWithoutDebit` 先调用对应 PayRecord owned update；返回值必须为 1，才调用 `finishOwnedLegacy`，其返回值也必须为 1。任一为 0 都抛原恢复链可处理的 Runtime，不用旧 `requirePayRecordStatus` 的同终态容错，不调用普通 `finish`，不得把失去 owner/deadline 的执行当作成功。失败后的 retry 仍使用本次 token，具体能否继续写由既有 owner/lease 条件决定。

本批只约束终态写。`markOrderUnknown`、`markRefundPending`、owner stage、远程 RPC 前的中间状态仍保留现有风险并记录为后续切片；不扩大到 TCC caller。

## 4. 保持项与剩余边界

不改旧 Mapper 方法、claim/lease 30 秒算法、token 生成、PayRecord 字段、intent 状态、SQL/API/Feign、User/Order RPC 顺序、Redis、消息、TCC/RM。不得把两个 UPDATE 宣称为跨表原子事务、完整 fencing、PayRecord 与 intent 读写窗口关闭或远端 source 证明。

## 5. 验收

- Unit：三个终态各验证 token、leaseUntil、LEGACY mode 与 affectedRows=0；owner/过期/模式漂移均拒绝，不能依赖同终态 readback 放行。
- Owned MySQL：A lease 过期后 B 重领，A 的终态写不得修改 PayRecord 或清 B intent lease；healthy owner/deadline 边界三终态按原 SET 生效。source Pay facts/checksum 与 owned 资源 finally 清理。
- 验收 terminal update 失败不做普通 terminal fallback；若 PayRecord update 已提交而 intent update 失败，只记录剩余非原子风险，不伪称自动回滚或完整 fencing。
- 不启动完整 App/RM/TC；保持 T79、T77 与既有 TCC/Legacy 回归。

## 6. 交付边界

实现仅允许新增三个 Legacy PayRecord owned Mapper 方法、一个 Legacy intent owned finish 方法、三处终态调用参数及必要 JavaDoc/测试；不改旧调用者和其它恢复阶段。
