# CM-20260924-20：秒杀冲突原始 JSON 证据评审

## 1. 结论与范围

结论：**GO，新增冲突台账的原始 UTF-8 文本证据列，解决 MySQL JSON 大数精度损失。**

本批保留既有 `payload JSON`、唯一键、幂等字段、状态和冲突流程，新增可空 `raw_payload LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin`。`payload` 继续作为结构化查询视图，`raw_payload` 保存同一次 ObjectMapper 序列化得到的完整有效 UTF-8 JSON 字符串。不得从历史 JSON 反向伪造 raw 值。

不改变 Service、DTO、消息、指纹、状态、ACK、历史冲突记录、Rabbit 解码器或 JSON 解析范围；raw 不是 Rabbit 原始 body，也不能还原转换器之前已经丢失的数字/字节。

## 2. 双写与兼容

- 新冲突写入必须在同一次 INSERT 中绑定同一字符串到 `payload`（JSON）和 `raw_payload`（LONGTEXT）；JSON 无法接受的输入仍沿既有序列化/冲突错误路径，不降级为 payload-only。
- 历史行 `raw_payload=NULL` 合法保留；人工取证优先 raw，raw 为空时明确标记为 legacy JSON 视图，不声称原文保真。
- 重复冲突沿现有唯一键和 JSON/hash 幂等语义，不能用新 raw 覆盖已存在行；原字段、状态和审计时间保持既有规则。
- PO 增加 raw 映射和注释，明确 `payload` 是结构化视图、`raw_payload` 是当前序列化文本证据；不改外部 DTO/API。

## 3. Mapper 建表与迁移

Mapper 初始化必须早于 Rabbit consumer lifecycle：

1. `ensureTable` 的新表定义包含 raw 列；已有表通过 `information_schema` 检查后执行无 `USE` 的 `ALTER TABLE ... ADD COLUMN raw_payload ... NULL`；
2. race 只接受 MySQL 1060 duplicate-column，并重新查询确认列类型、字符集、排序规则正确；已有错误类型不得默认通过；
3. 缺列、类型不符、ALTER/INSERT 失败均向外传播，不降级 payload-only、不 ACK；初始化失败阻止依赖该台账的消费启动；
4. 独立迁移脚本使用显式选定数据库（例如 `cloudmall_order`），不包含 `USE`，可重复执行且不改业务行；迁移前保留 DDL 备份，迁移后核对列、索引和行数。

不新增索引或改变既有唯一键；raw 不参与幂等匹配，避免改变重复冲突语义。

## 4. 验收、部署与回滚

- 独占测试库验证旧表两次迁移、新表创建、重复执行、并发增列、错误类型拒绝、INSERT 双写原文和事务失败原子性。
- 真实 MySQL 验证普通 ASCII/中文/emoji/转义和超过 U64 的大数：payload 允许现有 JSON 视图变化，raw 必须保持同一次序列化字符串；历史 raw=NULL 查询明确 legacy。
- 不修改业务 Java 服务行为前先完成迁移脚本和 Mapper schema 检查；部署时先增列再启消费。回滚只移除未使用的新列/回退代码，禁止删除或回填历史业务行；若新列写入失败，保持未 ACK/重试并修复 schema。
- raw 只证明当前 Map/ObjectMapper 序列化结果；接收前 decoder 舍入、非法 UTF-8、容量限制和原始 Rabbit body 保真不在本批承诺。

## 5. API/数据库边界

该列仅是内部冲突审计证据，不新增公开 API、Feign 字段、消息字段或状态；现有冲突表的 `payload`/hash/唯一键/ACK 语义保持不变。

