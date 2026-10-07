# CM-20261007-20：Stock 秒杀 outbox payload 身份一致性评审

## 1. 评审结论与范围

结论：**GO，限定修复 dispatcher 对 outbox payload 已有 eventId/requestHash 的一致性核验；不改变 schema、consumer、时间、lease、状态或新请求 payload 缺字段兼容。**

当前 dispatcher copy payload 后覆盖 eventId/requestHash，resolver 只核对 outbox 元数据、reservation 和 computed hash，可能静默修补历史 payload 内的冲突身份并发布。新 Stock request_payload 通常没有这两个字段，缺失必须继续兼容。

## 2. Optional identity 字段契约

Resolver 在完成已有 outbox/reservation/computed hash 核验后追加：

- payload 不含 `eventId`：允许继续原 envelope 填充行为；
- payload 含 `eventId`：必须是非空 String，且与外部 outbox `event_id` 使用 `String.equals` 精确相同；null、blank、数值/其他类型、大小写/尾空格/重音差异均冲突；
- payload 不含 `requestHash`：允许继续原 envelope 填充行为；
- payload 含 `requestHash`：必须是非空 String，且与外部 outbox hash 按既有合法十六进制 `equalsIgnoreCase` 规则匹配；null、blank、数值/其他类型或值不一致均冲突。

缺失字段表示旧/新 payload 兼容，不表示需要回写 source payload。存在字段不得被 dispatcher 静默覆盖修补；冲突抛既有 `SECKILL_EVENT_TIME_UNVERIFIED`/outbox 失败语义，禁止发送并沿原 UNKNOWN/DEAD 退避。

## 3. 不变边界

- 不改变 eventType、payloadVersion、orderNo、业务主体、occurredAt、reservation/resolver computed hash、Stock API、schema 或 consumer；
- 不改变新 request_payload 缺 eventId/requestHash 的正常发布；
- 不自动修改历史 JSON、不生成新 eventId/hash、不迁移存量 outbox；
- lease guard、完成 CAS、17AG 数据库异常隔离和 17AI 中断边界保持。

## 4. 验收矩阵

1. Resolver root eventId/requestHash 缺失：保持原 Map 不变并允许 envelope 补填。
2. 字段 null/blank/numeric/different eventId：不发布、不写完成、沿原退避；原 payload/hash 保持。
3. eventId case/trailing/重音差异拒绝；requestHash 合法大小写差异兼容。
4. payload 显式身份完整一致：正常发布；新 payload 缺字段：正常发布；两者均不改 source payload。
5. 真实 MySQL + Rabbit 替身验证显式冲突 payload 不发送、合法/缺失分别发送，metadata/lease/state 不被静默修补。
6. 17AG/17AI lease、数据库异常和中断回归保持；不宣称真实 broker 网络故障已覆盖。

## 5. 实施门与范围声明

实现限定在 `SeckillEventTimeResolver.resolve` 的 optional payload 字段核验和对应测试；不修改 dispatcher 的 envelope 覆盖结构、Stock 新请求格式、consumer、schema、状态机或历史数据。
