# CM-20261007-20：秒杀死信严格保存与静默截断防护评审

## 1. 评审结论与范围

结论：**GO，限定修复 `SeckillMessageRecoveryMapper.saveDlq` 的严格保存边界；不改变 JSON 幂等、状态机、lease、ACK/nack、schema 或历史数据。**

当前 `INSERT IGNORE` 可能把 event_id/request_hash/status 的超长值、NULL 或非严格模式下的隐式转换静默接受，返回 1 后 consumer ACK，造成恢复证据变形。本轮改为普通 `INSERT`，只对唯一键重复做原有幂等分支。

## 2. Java 输入守卫

`saveDlq` 在执行 SQL 前拒绝会破坏列契约的输入：

- eventId 非 null、非空白，UTF-16 字符串的 Unicode code point 数不超过 128；
- requestHash 非 null，code point 数不超过 64；不新增“必须 64 hex”要求，保留原精确字符串契约；
- status 非 null、非空白，code point 数不超过 20；不擅自扩展状态枚举；
- payload 非 null，仍交给 MySQL JSON 列验证其 JSON 合法性；
- 其他 required 参数按既有 Mapper/数据库约束处理。

宽度检查使用 code point 计数，避免用 Java `length()` 对非 BMP 字符与 MySQL 字符长度产生误判。输入失败必须在 SQL 前抛出明确参数异常，不留下行、不按持久成功直接 ACK、不进入重复比较；上层可在延迟重投 publisher confirm 成功后按原消息处理 ACK。

## 3. INSERT 与重复分流

使用普通参数绑定 `INSERT`，移除 `INSERT IGNORE`：

1. 插入返回 1 即首次保存成功；
2. 仅捕获 Spring `DuplicateKeyException`，随后按 eventId 查询原记录并执行 17I MySQL JSON 等价比较与 request_hash 精确比较；相同返回 0，差异沿原 CONFLICT 更新；
3. DuplicateKeyException 后按 eventId 查不到原记录时，抛出原有“事件号冲突后未找到记录”异常，不能假设成功；该情况覆盖主键 id 碰撞但 eventId 不存在；
4. 其他 DataAccessException、JSON 类型/CAST/数据库连接错误直接传播，不能吞掉或转成首次成功；上层沿既有延迟重投和 ACK/nack 处理原始字节。

普通 INSERT 在并发相同 eventId 下仍由唯一键裁决，只保存一条首载荷；异载荷/异 hash 继续保留首记录并标记 CONFLICT。不得覆盖首 payload、eventId、hash、状态、lease 或退避字段。

## 4. 数据库与兼容边界

- 在 MySQL 严格模式和非严格模式下，Java 宽度/非空守卫都必须先拒绝非法输入，不能依赖 SQL mode 产生一致结果；
- payload 仍由 MySQL JSON 列校验，非法 JSON 的数据库异常继续传播，不新增状态；
- 不扩大列宽、不改 schema、不迁移历史截断记录、不修复已有变形 payload；
- 不改变 17I 的 JSON 原生等价比较、eventId 唯一键/既有比较规则和 request_hash 精确契约；本轮不扩展 eventId 比较的其他问题。

## 5. 验收矩阵

1. 真实 MySQL 严格模式验证 eventId/hash/status 超长、NULL、payload 非法均不留行；旧行重复输入完全不变。
2. 主键 id 碰撞但 eventId 不存在时 DuplicateKey 分支失败，不返回幂等成功。
3. 同 eventId/hash/JSON 等价并发保存只保留一条；异 payload/hash 进入原 CONFLICT，首载荷不变。
4. 实际 consumeDeadLetter 保存失败时保留原字节并沿既有重投；只有延迟重投 publisher confirm 成功后才按原消息 ACK，不以持久保存失败误 ACK。
5. 普通合法保存、17I JSON 比较、17J/K/L lease/快照和原库 checksum 回归通过。

## 6. 明确排除

本轮不新增状态/API/schema，不改变历史 payload，不改 lease/stale worker、发送网络竞态、JSON 比较语义或 Rabbit 拓扑；只防止新保存发生静默证据变形和误 ACK。
