# CM-20261007-20：秒杀恢复已提交结果的身份化收敛评审

## 1. 评审结论与范围

结论：**GO，限定修复 consumer 对恢复记录的 `markResolved` 身份校验；不改变表结构、状态机、lease、DLQ JSON 幂等或 Rabbit 拓扑。**

当前 `markResolved(eventId,now)` 只按 eventId 且仅允许 REPLAY_PENDING/REPLAYED，已被 consumer 证明同 hash/orderNo 的 COMMITTED 重复消息无法结束 QUARANTINED/VERIFYING；更危险的是仅 eventId 定位可能让不相关输入结束另一条恢复记录。

## 2. 新内部方法契约

将内部调用收敛方法固定为：

```text
markResolved(eventId, orderNo, userId, idempotencyKey, requestHash, now)
```

仅由 Order consumer 的首次成功创建和已 COMMITTED 幂等重放路径调用；不暴露 HTTP/Feign，不由恢复批处理自行调用。原 consumer 在进入该方法前仍必须完成既有 dedup hash/orderNo 核验，Recovery 不获得跨服务 dedup 证据。

## 3. Mapper 身份化 SQL

所有核验归 `SeckillMessageRecoveryMapper`，使用 event_id 索引定位并以精确 `BINARY event_id=BINARY ?` 匹配，禁止大小写不同 eventId 复用记录。SQL 还必须同时核对：

- request_hash 按既有 fingerprint 的大小写不敏感 64 hex 语义匹配；不放宽长度或字符集；
- JSON payload 中的 `orderNo`、`userId`、`idempotencyKey` 与传入已提交业务元组精确一致；JSON 数字/字符串类型差异不得被模糊转换；
- 必要时核对 payload 内 eventId/inner eventId 与参数，防止同 eventId 但主体不同；
- status 仅允许 `REPLAY_PENDING`、`REPLAYED`、`QUARANTINED`、`VERIFYING`；CONFLICT、RESOLVED、未知状态零更新；
- 成功更新为 RESOLVED 并清理 lease，保留首 payload、eventId、hash、创建时间和其他不可变事实。

SQL 必须采用安全参数绑定和 MySQL JSON 原生路径/类型比较，不用 Java 字符串拼接或 Jackson 猜测。更新结果为 0 时不抛出新的业务异常，调用方沿原幂等/重试语义返回。

### 3.1 固定 JSON 类型

新 Stock payload 的类型契约固定为：`userId` 为 JSON `INTEGER`，`orderNo`、`idempotencyKey`、payload/eventId、payload/requestHash 为 JSON `STRING`。Mapper 必须用 `JSON_TYPE` 和 JSON 原生值比较核对：

- `userId` 仅接受 JSON `INTEGER`，与绑定的 Long 值比较；JSON 字符串形式的旧 userId 不自动转换或收敛；
- 其他字段要求 JSON `STRING`，用 `JSON_UNQUOTE(JSON_EXTRACT(...))` 配合 `BINARY` 精确比较，保留大小写、Unicode 和空白差异；
- metadata requestHash 继续对合法 64 位 hex 使用 `LOWER(BINARY ...)` 等价大小写匹配，但 payload 内 requestHash、eventId 与入参/outer eventId 仍必须分别核对；
- inner eventId 和 inner requestHash 都必须存在且与对应持久/入参事实一致。

消费者入口虽可按旧逻辑解析字符串 userId，但 resolution Mapper 对历史异常字段类型保持保守：类型不符合上述契约时不自动 RESOLVED，保留原记录供人工/独立迁移处理。

## 4. 竞争与历史边界

- 不要求持有 recovery worker lease；合法 consumer 的已提交业务证据可以收敛 QUARANTINED/VERIFYING，迟到 recovery 完成仍受 17J 完成 CAS 的 owner/lease/state 保护。
- 同 eventId 但不同 orderNo/userId/idempotencyKey/hash 的输入不得 RESOLVED；首载荷和原状态保持。
- wrong-case/trailing eventId 不得命中；requestHash 大小写差异按既有 fingerprint 兼容，但 payload 业务元组仍必须精确匹配。
- 不从存量恢复记录批量猜测或自动修历史；没有 consumer COMMITTED 证据的 Recovery 记录继续隔离/重试。
- 不扩展到 lease/stale worker、JSON saveDlq 比较、旧 payload 迁移或新的恢复状态。

## 5. 验收矩阵

1. QUARANTINED/VERIFYING 同 hash/orderNo/user/key 的已提交 consumer 重复消息可 RESOLVED，清理自身旧 lease且保留其他字段。
2. 同 eventId 但不同 tuple、wrong-case/trailing eventId、hash 不同均零更新，不改变原记录。
3. CONFLICT、RESOLVED、未知状态不能被重新 RESOLVED。
4. requestHash 大小写兼容按既有 64 hex 规则工作，数字/字符串 JSON 归属差异仍拒绝。
5. 两个真实 consumer 调用路径（首次成功和已 COMMITTED 重放）分别验证；无 recovery lease 时也可由合法业务证据收敛。
6. 真实 MySQL 验证 event_id 索引定位、JSON 路径/类型、状态和完整字段保持；原恢复 owner 迟到完成不覆盖已收敛记录。

## 6. 实施门与范围声明

实现限定在 Mapper SQL、两个内部调用点和对应 MySQL 测试；不新增 schema/API/RPC，不改变首次消费事务、dedup、orderNo、payload、eventId 生成和 Rabbit 行为。本轮只修身份化 resolution，不宣称所有恢复租约或分布式完成竞态已闭环。
