# CM-20260924-16 商品读接口性能与连接故障评审

## 1. 评审范围与结论

本规格只处理商品服务的公开读接口：

- `GET /api/products` 商品分页列表；
- `GET /api/products/{id}` 商品详情；
- 列表查询使用的商品筛选、排序、计数 SQL、分页边界和数据源观测。

本轮不切换 Elasticsearch 查询路径（该工作属于 CM-20260924-19），不扩大 Hikari 连接池，不引入缓存或新的中间件，不修改商品写入和索引 outbox 契约。

结论：先采用按排序分支裁剪 `product_hot_stat` 连接、补充经过 EXPLAIN ANALYZE 证明有效的复合索引，并限制深分页；同时补齐数据库连接获取失败和查询耗时的可观测性。历史连接故障在本轮没有复现，不能把本轮列表长尾下降或压测零错误表述为连接故障根因已经消除。

`api_spec.md` 当前文字把缺省/未知 `sort` 写成热门排序，但运行代码和现有调用链的缺省分支是最新排序。本轮冻结并保留运行中的默认/未知排序为 `latest`，不借性能修复改变公开响应顺序；后续若要切换为热门排序，必须另行修订 API 契约、前端调用和验收基线。`db_schema.md` 还记载了现场不存在的 `idx_status_updated(status,updated_at)`，索引迁移必须以实际 `SHOW INDEX` 为准并同步修正规格漂移。

## 2. 现有证据

2026-10-01 按原计划复测，商品 ID 改为现存 `317578`，关闭无关购物车线程：

| 场景 | 请求数 | 错误 | P95 | P99 |
| --- | ---: | ---: | ---: | ---: |
| 商品列表，5 线程约 30 秒 | 143 | 0 | 1,137 ms | 1,200 ms |
| 商品详情，5 线程约 30 秒 | 2,822 | 0 | 63 ms | 70 ms |

同一轮 Hikari 逐秒采样有 18 个样本处于 `active=10`，但 `pending=0`、`timeout_total=0`；MySQL `max_connections=151`，`Threads_running` 最大约 12，未出现连接上限。默认列表 EXPLAIN 对约 86,677 行执行全表扫描和 `Using filesort`；即使排序不使用热度字段，现有 SQL 仍无条件 `LEFT JOIN product_hot_stat`。计数查询走现有覆盖索引的全索引扫描。

历史 JMeter 记录中的 10 次 500 均在 2026-09-05 16:25:58.8 同一瞬间发出，约 30 秒后一起失败；原服务日志没有异常栈。该模式可能与连接获取、请求超时或运行环境瞬时故障有关，但当前证据不足以区分具体原因。

## 3. 不变的 API 契约

请求参数继续为 `keyword`、`categoryId`、`status`、`page`、`pageSize`、`sort`；响应继续为 `items/page/pageSize/total`。`sort` 保持 `hot`、`sales`、`latest`、`price_asc`、`price_desc` 五个值。

排序语义必须保持：

- `latest`：`updated_at DESC, id DESC`；
- `price_asc`：`price ASC, id DESC`；
- `price_desc`：`price DESC, id DESC`；
- `sales`：热度统计的 `view_count DESC`，再按 `updated_at DESC, id DESC`；
- `hot`：热度统计的 `hot_score DESC`，再按 `updated_at DESC, id DESC`。

未知或缺省排序继续使用当前运行中的 `latest` 默认分支，不得因优化改变结果顺序。计数和分页读取必须使用相同筛选条件；同一请求内不得因列表和计数读取不同快照而返回明显矛盾的 `total/items`。

## 4. 最小 SQL 改动

### 4.1 按需连接热度表

`latest`、`price_asc`、`price_desc` 只读取 `product`，不得连接 `product_hot_stat`。只有 `hot` 和 `sales` 需要 `LEFT JOIN product_hot_stat`，并继续以 `COALESCE` 把缺少热度行解释为零。当前缺省/未知排序归入 `latest`，因此默认列表也可以走不连接热度表的路径。

`countProducts` 不需要热度表，必须保持单表 `COUNT(*)`。商品关键字仍是 `%keyword%` 的包含匹配，本轮不通过 B-tree 索引宣称已经优化；关键词检索迁移 ES 时另行评审。

实现可在 Mapper 内使用两个固定 SQL 模板或受控的分支构造。排序字段仍只能来自固定白名单，不能把请求参数直接拼成 SQL。

### 4.2 第一阶段索引候选

在接近当前查询规模的副本上用 `EXPLAIN ANALYZE` 验证后，再执行以下最小迁移：

```sql
ALTER TABLE product
  ADD KEY idx_status_updated_id (status, updated_at, id),
  ADD KEY idx_category_status_updated_id (category_id, status, updated_at, id);
```

两个索引分别服务公开上架列表的状态过滤和分类过滤，并为 `latest` 的稳定排序提供候选路径。`idx_category_status` 不得在未完成线上/本地索引构建核对前删除；新索引上线后通过实际 `EXPLAIN ANALYZE` 和写入影响评估决定是否保留旧索引。当前规格中记载的 `idx_status_updated(status,updated_at)` 若实际不存在，应由这次迁移补齐或由数据库规格改为真实状态；不得把规格中的“已存在”当作现场事实。

本轮不预先增加 `price`、`view_count`、`hot_score` 的全部组合索引。热度和价格排序是否值得增加专用索引，必须以各排序的扫描行数、排序耗时、索引大小及写入代价为依据单独决定；`product_hot_stat` 的排序索引还要证明能在商品状态过滤和连接后减少成本。

### 4.3 分页边界

服务端先以 `long` 计算 `(page - 1) * pageSize`，检查正数、乘法溢出和 JDBC `LIMIT` 可接受范围，再转换为驱动需要的整数类型。建议冻结 `pageSize` 上限 100、`offset` 上限 100,000；超过上限返回 `COMMON_INVALID_ARGUMENT`，不得整数溢出后回到负偏移或执行不可控深分页。

当前前端目录在 88,476 条商品规模下远小于该边界。若后续需要无限滚动或更深的运营后台分页，另行设计基于 `(updated_at,id)` 的游标接口，不能在本轮悄悄改变现有页码接口。

## 5. 连接池与错误归因

不能以当前 `active=10` 就扩大 Hikari `maximumPoolSize`。当前 `pending=0`、`timeout_total=0` 且 MySQL `Threads_running` 最大约 12，证据不足以说明连接池容量是瓶颈；盲目扩容可能把等待转移到 MySQL 并增加上下文切换。

商品读路径需要补充以下低开销观测：

- Hikari `active`、`idle`、`pending`、`timeout_total`，带服务实例和时间戳；
- 商品列表计数、列表读取、详情读取分别记录耗时、结果行数、筛选类型和排序类型；
- 连接获取失败、连接超时、SQL 执行超时分别按异常类型计数并记录 traceId/requestId；
- 统一异常处理器记录完整服务端堆栈，响应只返回错误码和用户可读消息，不返回堆栈。

若商品读 SQL 获取连接、数据库连接或执行超时，响应使用新增的 `PRODUCT_QUERY_UNAVAILABLE`（HTTP 503），表示本次商品读结果无法可靠确定；参数错误继续 400，商品不存在继续 404。不要把所有 `DataAccessException` 都改成 503：SQL 语法、映射或数据约束错误仍应记录为内部错误并告警，避免掩盖实现缺陷。

历史 500 的根因只有在以下注入测试至少一项通过后才能收敛：

1. 连接池耗尽并超过获取超时，验证客户端收到 `PRODUCT_QUERY_UNAVAILABLE`、日志含异常类型和 traceId；
2. MySQL 连接被阻断/重启，验证同一错误归因和恢复后的成功请求；
3. SQL 执行被锁或延迟超过阈值，验证查询超时指标和响应契约。

未完成注入前，Bug 日志应继续写“历史连接故障未复现，根因未定位”，不能写成已消除。

## 6. 迁移策略

1. 在测试或备份副本创建候选索引，记录创建耗时、表锁影响、索引大小和 `information_schema.statistics` 结果。
2. 对无筛选、status、categoryId、五种 sort、keyword 以及深分页请求分别执行 `EXPLAIN ANALYZE`；保存扫描行数、实际耗时和是否仍有 filesort。
3. 先部署按需 hot join 与分页边界，再验证结果集、排序稳定性和 `total` 不变；再在维护窗口执行索引迁移。
4. 迁移后以真实目录规模执行列表/详情阶梯压测，至少覆盖 1、5、10、连接池上限附近并发；同时采集 Hikari、MySQL `Threads_running`、错误码和查询耗时。
5. 只有当新索引在目标请求上稳定降低扫描/排序成本，且写入延迟与索引空间在可接受范围内，才保留并提交 schema 变更。回滚使用 `ALTER TABLE ... DROP INDEX`，但必须先确认没有其他查询依赖该索引。

## 7. 验收条件

### 功能与结果一致性

- 五种排序、关键词、分类、状态、第一页/中间页/边界页的 `items` 与修复前基线一致；同一排序的相同快照中无重复或漏项。
- `pageSize > 100` 被限制或拒绝；非正 page/pageSize、offset 溢出和 offset 超过 100,000 返回 `COMMON_INVALID_ARGUMENT`，不执行 SQL。
- 计数查询不连接 `product_hot_stat`；非热度排序的列表 SQL 不连接 `product_hot_stat`；热度/销量排序仍保留缺失热度行的零值语义。

### 性能与连接观测

- 在同一目录、同一数据量和同一压测脚本下，默认列表 P95、P99、扫描行数和 filesort 结果有可记录的改善；不能只以零错误作为性能结论。
- 详情 P95 不得因列表优化出现回归；商品写入和索引 outbox 的事务行为不变。
- 复测必须记录 Hikari `pending/timeout_total`、MySQL `Threads_running` 和连接上限；不得用扩大连接池掩盖 SQL 长尾。
- 注入连接获取失败、数据库断连和 SQL 超时中的至少两类，均得到可区分的服务端日志/指标；客户端对确定的读依赖不可用返回 `PRODUCT_QUERY_UNAVAILABLE`/503。

## 8. 与后续批次的边界

- CM-20260924-19 负责公开关键词检索切换 Elasticsearch，本规格不把 MySQL `LIKE` 改成 ES。
- CM-20260924-22/23 负责秒杀压测和库存缓存重建资源评估，本规格只定义商品读压测观测项。
- 商品列表的游标分页、商品读缓存和热度统计模型变化需要新的 API/架构评审。
