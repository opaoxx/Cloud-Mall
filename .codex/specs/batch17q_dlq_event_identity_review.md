# CM-20261007-20：秒杀死信 eventId 精确身份评审

## 1. 评审结论与范围

结论：**有限 GO，限定修复 saveDlq 在 MySQL 非二进制 event_id 唯一键/排序规则下的误命中；不迁移 schema，不自动修复历史 eventId。**

当前 `event_id` 默认 utf8mb4 非二进制排序可能把大小写、重音或尾部空格不同的输入视为唯一键重复。DuplicateKey 后只按 `WHERE event_id=?` 读取，可能拿到语义不同的原记录；若 hash/JSON 恰好相同，会误返回幂等成功，或把原记录污染为 CONFLICT 后错误 ACK。

## 2. 精确重复契约

保留现有唯一键和普通 INSERT：

1. DuplicateKeyException 后查询只需投影 `id,event_id,request_hash,payload=CAST(? AS JSON) AS payload_matches`，复用 17I 的 MySQL 原生 JSON 等价计算，不把整个 JSON 读回应用层；
2. 查询可继续利用原 `event_id=?` 索引定位候选，但 Java 必须用 `String.equals` 对入参 eventId 与数据库返回 event_id 做精确大小写、重音、尾空格比较；
3. 查询为空，或返回 event_id 与入参不完全相等时，抛出明确冲突/重试异常，不进入 JSON/hash 幂等，不更新任何原记录，不按持久成功 ACK；原始消息沿既有 publisher confirm 重投/nack；
4. 只有精确同一 eventId 才允许进入 17I 的 request_hash/JSON 等价幂等判断；
5. 精确同 eventId 但 hash/JSON 不同，冲突 UPDATE 必须按原 `id` 加 `BINARY event_id=BINARY ?` 定位，避免排序规则命中其他记录。

冲突更新仍保留首载荷、eventId、hash和原始创建事实；不得改 eventId、合并别名、覆盖 JSON 或自动修复历史行。

## 3. 不迁 schema 的边界

- 不修改 event_id 列字符集、排序规则、长度、索引或唯一键；大小写/重音/尾空格别名在数据库唯一键层仍可能无法共存，属于需重试或人工核验的事实冲突；
- 不把 accent/case/trailing 变体当作同一事件，即使 hash/JSON 等价也不返回 0；
- 不自动改输入 eventId，不迁移既有恢复记录，不新增状态/API/表字段；
- 主键 id 碰撞但 eventId 不存在继续按 17P 失败语义处理。

## 4. 验收矩阵

1. 真实 MySQL 验证大小写、重音、尾空格不同 eventId 在 DuplicateKey 后不会误幂等、不会污染原行；原始字节沿重投路径保留。
2. 精确同 eventId、同 hash/JSON 等价仍返回 0；同 eventId 异 hash/JSON 按原 id+BINARY eventId 更新 CONFLICT。
3. 精确比较覆盖 Unicode、空白、大小写和重音；数据库排序规则候选误命中时 Java 精确检查拒绝。
4. 并发等价重复与 PK 碰撞基线保持：只保存一条首记录，别名或冲突不覆盖首载荷。
5. 实际 consumeDeadLetter 验证别名/冲突不误 ACK，publisher confirm 重投仍使用原始 eventId/payload；测试不将替身 Rabbit 视为真实 broker 网络验收。

## 5. 实施门与范围声明

实现限定在 Mapper 重复查询投影、Java 精确身份检查和冲突 UPDATE 条件；不扩展到 schema/collation migration、历史数据修复、lease、JSON 比较、Rabbit 拓扑或新的 recovery 状态。
