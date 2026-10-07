# CM-20261007-20：秒杀地址指纹 JSON 完整性评审

## 1. 评审结论与范围

结论：**GO，限定修复 common `SeckillRequestFingerprint.canonicalAddress` 的地址 JSON 完整性；不改变合法 hash 输出、API、状态、schema、历史迁移或其他 JSON parser。**

当前 canonicalAddress 的私有 ObjectMapper 可能忽略 addressSnapshot 尾随 token，或在根/嵌套重复 key 时以后值覆盖前值；canonical/hash 会把不同歧义地址合并为同一业务指纹。17U 只保护外层死信 JSON，17V 只处理 common 已抛出的业务 hash 异常，不能覆盖该地址字符串内部解析边界。

## 2. 局部严格指纹契约

仅在 `SeckillRequestFingerprint` 内部 ObjectMapper/reader 开启：

- `FAIL_ON_TRAILING_TOKENS`；
- `JsonParser.Feature.STRICT_DUPLICATE_DETECTION`。

不得修改 Spring 共享 ObjectMapper、全局 JsonFactory 或其他服务 parser 配置。`canonicalAddress` 继续要求地址 JSON 根值为对象，保留现有对象键排序、BigDecimal 规范化、数组顺序和 v1 hash 输出；合法 canonical/hash 向量必须完全不变。

尾随第二值/垃圾 token、根/嵌套重复字段、非对象 JSON、非法 JSON 均继续抛现有 `IllegalArgumentException` 语义，不产生 hash。

## 3. 服务边界

- Stock：地址指纹计算失败必须在 claim/Redis 预扣/outbox 前沿现有参数错误返回，不能创建受理事实或改变配额；合法地址行为不变。
- Order consumer：地址/业务 hash 计算失败沿现有事务异常回滚和 17V CONFLICT/重试语义，保留原消息证据，不创建订单或错误完成 dedup。
- Recovery/dispatcher：地址歧义导致的业务 hash 失败继续按现有无证据/冲突路径处理，不自动修复历史 payload，不新增状态或 hash 版本。
- 合法已存 hash 不迁移、不重算；历史歧义记录留待独立核验。

common 模块修改后，依赖它的 Stock/Order/相关服务必须重新构建并重启后才生效；本轮不承诺旧进程已加载新指纹规则。

## 4. 验收矩阵

1. common 单测覆盖根/嵌套重复字段、尾随 object/array/scalar/null/garbage、非对象和非法 JSON，均拒绝。
2. 合法地址 canonical/hash 固定向量、对象排序、BigDecimal、数组顺序、中文/emoji 保持完全一致。
3. Stock 真实 MySQL 入口验证非法地址在 claim/Redis/outbox 前失败，数据库与 Redis 无受理事实。
4. Order JSON/dispatch 测试验证地址歧义不创建订单、不错误 RESOLVED/REPLAYED；合法地址基线保持。
5. 全局共享 ObjectMapper 配置保持不变；其他 JSON 消费入口回归通过。
6. 发布验收确认 common/Stock/Order 重新构建并重启后均使用同一严格 canonical 规则；不将旧进程或历史数据自动视为已修复。

## 5. 明确排除

本轮不新增 API/状态/schema/hash 版本，不修改历史 payload，不迁移已存指纹，不改变 common hash 算法合法输出，不扩展到外层 DLQ JSON、lease、Rabbit 或其他业务 JSON parser。
