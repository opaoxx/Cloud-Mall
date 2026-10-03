# CM-20260924-19 商品关键词检索切换 Elasticsearch 评审规格

## 1. 问题与评审结论

当前公开商品接口 `GET /api/products` 的 `keyword` 条件仍由
`ProductSqlMapper.appendProductConditions` 生成 `p.name LIKE '%keyword%'`，而商品索引
已经由 outbox、RabbitMQ 消费和持久化重建链路维护。88,476 条商品目录下，关键词检索
继续扫描 MySQL 商品事实，索引建设没有转化为查询收益。

本轮冻结一个可回归的读路径切换：**只要 `keyword` 非空白，就使用 ES 做关键词匹配；
无关键词请求继续使用现有 MySQL 列表路径。** ES 是检索候选和分页排序数据源，MySQL
仍是商品状态、价格、金额、详情和最终业务事实来源。关键词路径不允许因为 ES 不可用、
索引缺失、版本落后或结果不完整而静默回退到 MySQL `LIKE`，否则用户会得到延迟高且
与索引语义不一致的结果；这些情况必须返回明确的商品检索不可用错误并记录 traceId。

商品写入、outbox、消费 ACK、external version、tombstone 和存量重建沿用
`batch5_product_index_review.md`，本规格只定义查询契约和查询所需的最小索引字段扩展。

## 2. 现有调用链与事实

- `ProductController` 继承 `ProductServiceImpl`，`GET /api/products` 先调用
  `countProducts`，再调用 `findProducts`，随后逐条 `productView` 读取 SKU 和参数。
- `ProductSqlMapper` 目前把关键词绑定为 `%keyword%`，并按固定白名单生成
  `latest`、`price_asc`、`price_desc`、`sales`、`hot` 排序。
- `ProductIndexDocumentLoader` 从 `product` 读取 `id/category_id/name/main_image/
  description/price/status/version`；`ElasticsearchProductIndexWriter` 已使用
  `visible`、`productVersion` 和 `EXTERNAL_GTE` 写入索引。
- 现场对关键词“旅行箱”的语义基线已核对：当前接口的 MySQL `name LIKE` 上架命中
  12,293 条，当前 ES `multi_match + visible=true + track_total_hits=true` 命中
  15,883 条。两者分词/匹配集合不同；切换 ES 是经过本规格明确评审的搜索语义变化，
  不能把数量差异自动判为索引缺失或数据丢失。验收必须同时记录旧 LIKE 基线和新 ES
  语义结果，前端文案不能继续暗示两者严格等价。
- 当前 ES 文档缺少 `updatedAt`，也缺少 `viewCount`、`searchCount`、`hotScore`；
  只用现有文档字段无法准确重现公开 API 的五种排序。
- 前端存在两条商品列表调用路径：`SearchResults` 会传 `keyword`、`categoryId`、
  `status`、页码、页大小和用户选择的排序；基础 `Products` 页面只传关键词、分类、
  `status=1` 和分页，不传 `sort`。因此 API 缺省/未知排序必须按既有契约解释为
  `latest`，不能把某个页面当前的热门排序默认误写成全局 API 默认值，也不能只实现一个
  不带过滤和排序的“演示搜索”。

## 3. 查询范围与 API 契约

### 3.1 路由选择

1. `keyword == null` 或仅包含空白字符：保持 MySQL 路径，沿用现有分页、排序和
   `count/items` 契约。
2. `keyword` 去除首尾空白后非空：进入 ES 路径。关键词使用 `match`/`multi_match`
   语义匹配 `name` 和 `description`；查询必须按 MySQL 路径的原语义应用请求的
   `status`（传入才过滤，缺省不凭空增加上架过滤）和 `categoryId`，并保留商品 ID
   作为稳定 tie-break。面向商城的公开调用继续显式传 `status=1`，此时必须同时应用
   `visible=true`；管理员查询若省略 status 可以看到与既有 MySQL 路径相同的状态集合。
3. ES 路径的 `keyword`、过滤、排序和分页必须是同一个查询快照；`total` 必须来自
   同一套 ES filter，不得用 MySQL `LIKE` 的 count 与 ES 的 items 拼接。

### 3.3 事务边界

关键词路径的 ES 请求必须发生在任何 MySQL 事务之外。当前 `ProductServiceImpl.products`
整方法带 `@Transactional`，实施时必须拆分为：先在无事务阶段执行 ES 查询、精确 count、
排序和页内 ID 获取；再只对当前页 ID 开启一个短的 `REPEATABLE_READ` MySQL 事实校验/摘要
读取事务，事务结束后组装响应。ES 超时、分片失败或无效响应时不得先占用 Hikari
连接；MySQL 事实校验失败只影响当前页并按第 5 节重试/503。无关键词路径可以保留
现有 MySQL 事务，但不能让同一注解包住远程 ES 调用。

### 3.2 排序

公开参数仍只接受 `hot`、`sales`、`latest`、`price_asc`、`price_desc`；未知或缺省
排序继续按 `latest` 解释。ES 文档必须补齐并由同一商品版本投影维护：

| API 排序 | ES 字段顺序 |
| --- | --- |
| `latest` | `updatedAt DESC, id DESC` |
| `price_asc` | `price ASC, id DESC` |
| `price_desc` | `price DESC, id DESC` |
| `sales` | `viewCount DESC, updatedAt DESC, id DESC` |
| `hot` | `hotScore DESC, updatedAt DESC, id DESC` |

价格仍以 JSON 金额字符串返回，不能把浮点展示值作为排序依据。缺少热度统计行时，
`viewCount`/`searchCount`/`hotScore` 统一按零投影，和当前 MySQL `COALESCE` 语义一致。
ES 查询的 `from/size` 必须受现有 `pageSize <= 100` 和 `offset <= 100000` 限制；
超出边界继续返回 `COMMON_INVALID_ARGUMENT`，不能切换成无界 `scroll`。

### 3.3 响应与事实校验

响应继续为 `items/page/pageSize/total`，商品 ID、SKU ID 使用现有 JSON 字符串兼容
语义，金额为字符串，时间遵循 `+08:00`。ES 命中只携带商品摘要；商品详情仍通过
MySQL 事实读取 SKU/参数。

对返回的商品摘要，必须在 MySQL 事务快照中按 ID 批量读取当前商品事实并校验：

- 商品仍存在、`status` 与公开可见性满足请求；
- 价格、名称、分类和更新时间没有被新版本覆盖；
- ES 的 `productVersion` 不低于查询要求的索引版本。

如果校验发现单条记录在查询期间已下架或版本变化，不能把旧 ES 快照直接返回。实现
应在一次有界重试中重新查询 ES；仍无法得到一致页时返回 `PRODUCT_SEARCH_RETRYABLE`
（HTTP 503），并记录索引代次、查询版本和丢弃数量。若实现选择用 MySQL 批量事实
替换摘要，必须保持 ES 返回的排序顺序且不能补入未命中关键词的商品。

## 4. 索引字段与代际迁移

查询切换前，目标索引 mapping 至少包含：`id`、`categoryId`、`name`、`description`、
`price`、`status`、`visible`、`productVersion`、`updatedAt`、`viewCount`、
`searchCount`、`hotScore`。`name`/`description` 使用适合中文分词的 text 字段，并保留
一个用于精确过滤或排序的 keyword/数值字段；排序字段必须使用数值或 date 类型。

当前本地索引由 B1 以 MySQL `product.version` 的 `EXTERNAL_GTE` 语义构建，已知物理名
是 `cloudmall_product`，本地没有 ES 读者，且现有字段类型（`price` 为
`scaled_float`）已经冻结。新增 `updatedAt`、`viewCount`、`searchCount`、`hotScore`
均是新增 mapping 字段，不需要改变已有字段类型。因此本地本轮选择**原地扩展
`cloudmall_product` mapping 并受控全量回填**，不创建 v2/alias：它避免 writer 默认仍写
`cloudmall_product` 时出现 alias 未建、旧 alias 缺字段或切换期间事件丢失。原地方案只适用
于本地当前条件；已有 ES 读者、未知 mapping、非 external 版本或需要无停机发布的环境，
必须另行采用 `cloudmall_product_v2` + `cloudmall_product_read/write` 双 alias 方案。

原地回填的安全条件是：先部署能够写入新字段但仍以 `cloudmall_product` 为目标的 writer，
再运行按商品 ID 游标的有界 Bulk；每个文档继续使用 MySQL `product.version` 和
`EXTERNAL_GTE`。回填读取到旧版本而实时 outbox 已写入新版本时，ES 必须拒绝旧 Bulk
覆盖；回填失败或索引字段不完整时，搜索开关保持关闭，不得返回部分新语义结果。

本地发布顺序和开关必须可执行：

以下开关是本规格要求的新增运维配置；当前配置文件只有 dispatcher/rebuild 的间隔参数，
在实现这些开关前不得执行搜索切换。配置缺省值必须保持兼容（search=false、writer 物理
名为 `cloudmall_product`），并在启动日志打印最终生效值和索引名。

1. 先以 `cloudmall.product.search.enabled=false`、
   `cloudmall.product.index.dispatch-enabled=true`、
   `cloudmall.product.index.consumer-enabled=true`、
   `cloudmall.product.index.dlq-replay-enabled=true`、
   `cloudmall.product.index.rebuild-enabled=false` 部署兼容代码；writer 目标固定为
   `cloudmall_product`。此时关键词仍明确走原 MySQL 模式，属于 feature flag 关闭状态，
   不得在日志中宣称 ES 搜索已生效。
2. 执行一次只增加新字段的 mapping 更新，验证现有字段类型和 `productVersion` 语义；
   失败则不启动回填并保持搜索关闭。
3. 打开 `cloudmall.product.index.rebuild-enabled=true`，运行持久游标回填。回填期间
   dispatcher/consumer 保持开启，商品写入可以继续；通过 external version 处理并发。
   监控 Bulk 部分失败、版本冲突、rejected、索引文档数和字段完整性。
4. 回填完成后暂停 `cloudmall.product.search.enabled` 的切换动作，先确认 MySQL 商品
   总数、上架数、ES 文档总数、可见数、版本抽样、mapping 和价格排序核对全部通过，
   且 outbox/消费 ACK 无异常。然后将 `cloudmall.product.search.enabled=true`，让非空
   keyword 进入 ES；空 keyword 仍走 MySQL。若配置中心不支持原子刷新，必须先停 product
   实例、更新同一份配置后再启动，避免实例间读路径不一致。
5. 搜索启用后继续保留 rebuild audit；发现字段缺失、版本倒退或 ES 异常时立刻关闭
   search flag，恢复到明确的 MySQL 关键词基线并告警，修复完成后重新全量核对再开启。

回滚步骤必须由运维开关驱动并可审计：

1. 将 `cloudmall.product.search.enabled=false`，确认所有 product 实例都已加载关闭状态；
   关闭期间公开接口明确使用 MySQL 基线，不能把它描述成 ES 故障时的隐式降级。
2. 若只是 ES 查询或 mapping 问题，保持 `cloudmall_product` 和 writer 不变，暂停
   rebuild，保存错误批次和游标，修复后从原游标重试；不得删除物理索引或执行 destructive
   重建。
3. 若发现回填写入了错误版本，先保持 dispatcher/consumer 开启以接收后续事实，暂停
   商品写入后按 product.version 从 MySQL 重建受影响商品，核对 external version 和
   outbox ACK，再决定是否重新开启搜索。
4. 只有在原地索引损坏且无法保留可信版本时才进入独立 v2 迁移；此时必须先关闭搜索，
   再执行本节前述 v2 alias/维护窗口流程。旧物理索引不能直接删除，直到 v2 核对和
   回滚窗口结束。

由于原地方案依赖本地索引 mapping 和 external version 的现场事实，若任一前置条件不
满足，发布流程必须切换到独立 `cloudmall_product_v2`；此时必须先创建 read/write alias、
让 writer 支持 write alias，再按维护窗口暂停写入、排空 outbox、完成增量重放、原子
切换 alias，不能直接把旧物理名改成 alias。

本地 `product_hot_stat` 当前已有 88,476 行，但全仓源码只见读取、未见在线更新；因此
`viewCount`、`searchCount`、`hotScore` 在本轮视为 MySQL 的静态事实快照，重建时按同一
快照写入 `cloudmall_product` 的新增字段。静态快照变化造成的 hot/sales 排序滞后必须记录指标，不在本轮新增热度
事件、修改 RabbitMQ envelope 或扩展 outbox。在线热度写入与索引更新属于 CM-20260924-21，
实施前需另行评审，不能把本轮索引查询切换描述为实时热度一致。

## 5. 一致性、滞后与故障语义

- 商品事务提交成功但 ES 尚未消费时，关键词查询以 ES 当前可见版本为准，并在响应观测
  中记录索引滞后；不承诺新商品立刻可被关键词检索。
- 下架、停用、删除必须通过 `visible=false` tombstone 或等价版本保护，旧 UPSERT
  不能重新显示商品。商城公开查询（`status=1`）必须同时过滤 `visible=true`；管理端
  省略 status 时，仍按既有接口允许的状态集合查询，但不能把 tombstone 当作上架商品。
- 索引不存在、索引代次不匹配、mapping 缺字段、ES 连接/查询超时、分片失败或结果
  解析失败：返回 `PRODUCT_SEARCH_UNAVAILABLE`（HTTP 503），日志包含 index generation、
  request/traceId 和有限错误摘要。
- ES 结果为空是合法的“无匹配”，返回 `total=0`；不能把 ES 查询异常伪装为空列表。
- 不允许在关键词路径下静默回退 MySQL `LIKE`。若未来要提供管理员强制事实查询，
  应新增显式内部参数或管理接口，不能改变公开 API 的故障语义。
- ES `total` 必须使用精确计数或明确的 `track_total_hits` 上限策略；本接口需要精确
  `total`，因此不能返回近似总数。若精确计数超过受控耗时，返回 503 并告警。

## 6. 性能与资源边界

- 查询只返回公开摘要字段，禁止 `_source` 无界返回 SKU、参数或内部字段。
- `size` 最大 100，from 最大 100,000；默认超时建议 2 秒，并通过 Micrometer 记录
  ES 查询、计数、MySQL 事实校验、结果丢弃和重试耗时。
- 关键词查询不得将全量 ES 命中 ID 拉回 JVM 后再排序；排序和分页由 ES 完成，批量事实
  校验只处理当前页，不能形成第二个无界扫描。
- 需要深分页时另行设计基于 `search_after` 的游标接口，不能改变现有页码接口。
- 记录 ES 命中总数、返回条数、查询耗时、拒绝/超时、索引代次和索引滞后；与 MySQL
  空关键词路径分别建立指标，避免把两条链路混在一个 P95 中。

## 7. 测试与提交门槛

### 单元和契约测试

1. 路由：空白关键词走 MySQL，非空关键词严格走 ES；ES 异常不会调用 MySQL LIKE。
2. 过滤：关键词与 `status/categoryId` 组合只返回满足 filter 的文档。
3. 五种排序、同值 ID tie-break、缺热度行按零处理、页码边界和精确 total。
4. 旧版本事件、下架 tombstone、索引滞后和同一索引代次的重复查询。
5. ES 不可用、mapping 缺字段、精确 count 超时、响应解析失败均返回规定 503；无匹配
   返回 200 空页，不能混淆。
6. 金额、ID、名称、分类和时间字段的 JSON 契约与现有 API 回归。

### 集成和真实验收

- 使用当前 88,476 条目录在 `cloudmall_product` 上完成受控 mapping 扩展和全量回填，
  核对 MySQL 上架数与 ES `visible=true` 文档数、版本分布和抽样关键词结果；若现场
  前置条件不满足而采用 v2，则必须改用独立代际和 alias 验收。
- 选取中文、英文、空白、特殊字符、无结果、热门/销量/价格/最新排序进行 API 对照；
  对照基线必须由受控 MySQL 查询产生，不把当前 LIKE 的错误故障路径当成 ES 结果依据。
- 对 `price` 的排序逐页与 MySQL `DECIMAL` 顺序核对，特别覆盖相同整数部分、两位小数、
  零值和边界金额；ES `scaled_float(scaling_factor=100)` 只能在全部样例顺序一致后接受。
- 原地回填验收必须证明没有旧版本覆盖新版本：Bulk 版本冲突均为可解释的旧写，商品
  版本/状态/热度快照与 MySQL 对齐，搜索开关开启前 mapping、字段完整性、价格排序和
  查询抽样全部通过。若改用 v2，则额外证明维护窗口内 outbox 无未处理记录、两个 alias
  原子切换和新写入可从 write alias 投影；任一校验失败都关闭 search flag。
- 运行 1、5、10、20 并发关键词阶梯，记录 P50/P95/P99、错误码、ES heap、rejected、
  查询总数和 MySQL 连接指标；同时验证空关键词仍走旧路径且无性能回归。
- 在 ES 停机、重启、索引删除或 alias 切换期间验证 503/恢复；不使用 `FLUSHDB`、
  `FLUSHALL` 或删除共享业务数据做测试。

实现应拆成可回溯提交：先补齐索引字段/代际重建和查询客户端，再切换读取路径，最后
补充压测与观测。每阶段通过 Maven 全量测试、`git diff --check`、接口契约检查后提交。

## 8. 明确不在本轮

- 不修改商品写事务、outbox 记录、RabbitMQ envelope、消费 ACK 和重建租约语义；这些
  沿用 `batch5_product_index_review.md`。为支持 `cloudmall_product_write` alias 而调整
  writer 的目标索引配置属于本轮代际切换配套，不得改变上述事件契约。
- 不把无关键词的商品列表、详情、分类接口切换到 ES。
- 不新增商品读缓存、游标 API、分词插件、第三方搜索服务或管理员专用搜索接口。
- 不通过扩大 MySQL 连接池掩盖关键词查询问题；MySQL LIKE 历史实现保留作无关键词
  和故障对照基线，直到本规格实现并完成实测。
