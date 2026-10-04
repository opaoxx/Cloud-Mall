# CM-20260924-21 商品热度、搜索与销量指标评审

## 1. 评审结论

本批不能安全实现“实时浏览、实时搜索、实时销量统计以及 ES 排序完全一致”。
现有 `product_hot_stat` 是一次性目录迁移快照，业务源码没有在线写入链路；`sales`
排序还把 `view_count` 当作销量使用。库存确认、订单终态和商品热度统计分属不同
本地事务，当前没有可靠的事件契约把它们连接起来。

本批结论为 **NO-GO**：不得直接在 Product 服务中从订单或库存表推算销量，不得把
商品详情请求或搜索请求直接改成同步更新热度，也不得只更新 MySQL 而继续宣称 ES
排序实时一致。

## 2. 现场证据

### 2.1 迁移数据已经发生指标语义复用

`backend/database/migrate_hmall_catalog.sql` 将源目录的：

| 源字段 | 当前目标字段 |
| --- | --- |
| `item.sold` | `product_hot_stat.view_count` |
| `item.comment_count` | `product_hot_stat.search_count` |
| `item.sold` | `product_hot_stat.hot_score` |

这使浏览次数、搜索次数、销量和热度分数从建库开始就不是同一套语义。当前商品
SQL 的 `sort=sales` 使用 `product_hot_stat.view_count`，ES 的 `sort=sales` 使用
`viewCount`，两条路径虽然字段名相同，实际表达的是导入时的历史 `sold` 快照。

### 2.2 没有在线写入热度统计

全仓源码只找到 `ProductHotStatMapper` 的基础读取接口和商品列表/详情的读取逻辑，
没有对 `product_hot_stat` 执行浏览、搜索或热度累加的 Service、消息消费者或定时
聚合任务。当前数据库现场为：

- `product_hot_stat`：88,476 行；全部 `updated_at` 为 `2026-09-14 01:00:35.564`。
- 其中 `view_count=0` 的 88,455 行，`search_count=0` 的 88,465 行，`hot_score=0`
  的 88,455 行。
- `stock_sku` 中只有 1 行 `sold_quantity>0`，即 `product_id=100000011127` 的
  SKU `800000011127`，本地真实库存成交量为 2；该商品的热度快照却是
  `view_count=45,454`、`hot_score=45,454.000000`，两者明显不是同一事实。该 45,454
  来自源目录导入的 `item.sold`，不能当作本地真实成交量。
- `mall_order_202610` 当前没有 `PAID` 或 `COMPLETED` 订单；现有支付成功和已完成订单
  位于其他月份，不能用当前月份订单数替代商品销量。

因此当前高销量/高热度排序主要反映目录导入数据，不能作为在线成交或在线浏览统计。

### 2.3 库存确认和订单状态不能被当前实现直接推断

库存服务在 `stock_sku` 中以 `CONFIRM` 流水增加 `sold_quantity`，并在同一本地事务
更新库存版本；订单服务的 `PAID`、`COMPLETED` 状态又由订单状态机和恢复任务管理。
订单创建、支付、库存确认和恢复可能跨服务、跨事务，存在结果未知、重试和幂等台账。
Product 服务没有可安全读取的“每个商品已确认成交数量”事件或消费台账。

当前数据库还存在 13 条支付成功记录和跨状态订单，不能据此把订单明细汇总直接写成
商品销量：订单可能已支付但库存确认仍在恢复，取消/回滚也必须排除，重复消息不能
重复计数。

### 2.4 ES 排序目前只能得到静态快照

`ProductIndexMapper` 将热度字段与商品事实一起读取，`ProductIndexDocumentLoader`
把它们写入 ES；`ElasticsearchProductIndexWriter` 使用商品 `product.version` 的
`EXTERNAL_GTE` 版本保护。热度字段的变化不会递增 `product.version`，如果后续直接
发送一个完整商品 Upsert：

1. 热度事件读取到旧商品版本时，可能被新商品版本拒绝；
2. 热度事件与商品更新使用相同版本号时，可能以旧商品字段覆盖新商品字段；
3. 只更新 MySQL 热度表而不更新 ES 时，MySQL 空关键词排序和 ES 关键词排序立即分叉。

所以新增热度写入必须使用独立的指标版本/字段级更新协议，不能复用现有商品索引
事件的 `productVersion`。

## 3. 本批不允许的实现

- 不在 `ProductService` 中用商品详情请求次数直接更新 `view_count`。
- 不在关键词搜索请求中同步增加 `search_count`，也不把一次搜索的命中数直接当作
  商品搜索次数。
- 不从 `mall_order_*`、`pay_record` 或 `stock_sku` 做无幂等的跨库汇总写入。
- 不把 `stock_sku.sold_quantity` 直接当成历史商品销量；当前迁移数据没有可验证的
  商品销量映射，且普通订单与秒杀订单的确认时机不同。
- 不通过给 `product.version` 加一来驱动热度更新；商品内容版本和指标版本必须分离。

## 4. 安全分阶段方案

### 阶段 A：冻结指标语义和历史快照

新增明确的指标模型，至少包含：

- `view_count`：已确认的商品详情浏览事件数；
- `search_count`：已确认的商品搜索曝光/命中事件数，需先冻结计数口径；
- `sold_count`：库存 `CONFIRM` 成功且经幂等台账确认的商品成交数量；
- `hot_score`：由上述指标按固定公式计算的派生值；
- `metric_version` 或每个指标的单调版本；
- `source_type/source_ref`：用于区分目录迁移快照与在线事件。

本轮最小语义修复拟先落地来源标记，不把来源标记误认为实时统计能力。字段契约为：

```sql
ALTER TABLE product_hot_stat
  ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN source_ref VARCHAR(128) NULL;
```

字段约束如下：

- `source_type` 是来源枚举字符串，当前至少允许 `UNKNOWN`、`LEGACY_IMPORT`；未来在线
  事件另行定义 `ONLINE_EVENT` 等值，不能用空字符串表示未知。
- `source_ref` 是来源数据集或事件台账的稳定引用。本次历史目录统一使用
  `source_type=LEGACY_IMPORT`、`source_ref='hm-item.item'`；它表示来源表，不宣称该值
  是某一笔本地订单。若以后需要逐行追踪，应新增明确的源主键字段或约定带主键的引用，
  不能偷偷改变本次固定引用的含义。
- 既有四参数 `HotStatVO` 构造和已有 JSON 字段保持不变；响应新增可选的
  `sourceType`、`sourceRef` 字段。新字段追加在响应对象中，旧客户端忽略未知字段，
  缺失统计行时返回 `sourceType='UNKNOWN'`、`sourceRef=null`。
- `ProductHotStatPO` 与 SQL 读取必须同步映射这两个字段。来源字段不得参与销量或热度
  数值计算，也不得被当成 `sold_count` 的别名。

存量迁移必须可重复执行且不能覆盖未来来源：

1. 迁移工具先以幂等方式增加列；数据库不支持条件 `ADD COLUMN` 时，必须由迁移版本表
   或启动前检查保证只执行一次，不能用异常吞掉权限/类型错误。
2. 首次回填只允许更新 `source_type='UNKNOWN' AND source_ref IS NULL` 的行，设置为
   `LEGACY_IMPORT/'hm-item.item'`。已有非 UNKNOWN 来源的行必须保持原值；重复执行
   更新影响行数应为 0。
3. `migrate_hmall_catalog.sql` 的商品热度 INSERT 必须显式写入两个来源字段。它虽然
   会重建演示目录，但不能依赖列默认值，避免将新导入误记为 UNKNOWN。该脚本重复运行
   后仍应得到相同的来源标记和行数。
4. 若无法确认某行来自 `hm-item.item`，保持 `UNKNOWN` 并产生审计告警，禁止按数值或
   `updated_at` 猜测来源。

历史 `product_hot_stat` 数据必须标记为 `LEGACY_IMPORT`，不能静默改名为在线浏览或
销量。由于当前没有可验证的历史成交明细，历史快照是否纳入新 `sold_count` 需要产品
决策；在决策前不得伪造回填。

### 阶段 B：先落地可重放的事件和台账

商品服务负责浏览/搜索事件的持久化 Outbox 与幂等消费；库存服务在 `CONFIRM` 本地
事务提交后，通过独立 Outbox 发布商品销量增量事件。事件至少包括：

`eventId`、`eventType`、`productId`、`skuId`、`quantity`、`orderNo`、`sourceRef`、
`occurredAt`、`traceId`、`payloadVersion`。

指标消费者必须先写唯一事件台账，再应用增量；重复事件返回已处理，未知结果进入
重试/隔离。回滚不能减少已经确认的销量，除非另有经过评审的退款/逆向事件语义。

### 阶段 C：独立更新 ES 指标投影

商品内容索引继续使用 `productVersion`。指标字段改用指标版本或 ES 的字段级更新，
并要求更新条件同时校验事件幂等键和版本。重建任务从 MySQL 读取商品内容与指标
快照，完成后核对 MySQL 与 ES 的指标版本和排序顺序。

来源标记作为本轮静态投影的一部分加入索引：

- `metricSourceType`：`keyword`，对应 `product_hot_stat.source_type`；
- `metricSourceRef`：`keyword`，对应 `product_hot_stat.source_ref`，为空时不写入或
  映射为可选字段，二者必须在查询和排序之外；
- `ProductIndexDocument`、loader、writer 和 ES mapping 必须一起扩展，不能只改 mapping；
- 现有 `cloudmall_product` 的新增 keyword 字段通过受控 PUT mapping 后再按商品 ID
  有界全量回填。回填仍使用商品 `product.version` 的 `EXTERNAL_GTE`，并核对静态来源
  字段；不能以删除重建替代回填，也不能在搜索开关开启前接受部分字段缺失。

来源字段的更新不改变商品索引版本语义。未来在线指标投影仍必须使用独立 metric
version/字段级更新；不能因为本次增加 provenance 字段就直接复用商品 version。

### 阶段 D：分阶段开启排序

先在 MySQL 路径对照验证 `sales`、`hot`、价格和最新排序，再开启 ES 关键词排序。
必须覆盖并发事件、重复事件、消息乱序、商品内容更新与热度更新同时发生、库存确认
恢复以及 ES 重启。任何指标版本落后或排序字段缺失都应告警并关闭 ES 指标排序开关。

## 5. 可独立闭环的最小语义修复

在完整实时链路完成前，最小安全修复应限定为“消除错误承诺”，包括：

1. 给 MySQL 热度行增加 `source_type/source_ref`，按上述只更新 UNKNOWN 行的条件把
   可确认的存量标记为 `LEGACY_IMPORT/'hm-item.item'`；新导入 SQL 显式写入来源。
2. 给 `HotStatVO` 和 ES 文档增加来源字段，完成 mapping 扩展及全量回填，并验证
   MySQL 与 ES 的来源字段逐商品一致；旧响应字段、排序数值和金额/ID 格式不变。
3. 当前前端静态目录的排序标签使用“历史销量排序”和“历史热度排序”，并在热度详情
   展示“数据来源：历史目录导入”。在列表响应尚未携带逐商品来源字段前，不做按商品
   动态切换标签；未来混合来源必须先扩展列表摘要契约再动态展示，不能猜测。
4. 增加只读新鲜度审计：定时检查 `updated_at`、来源标记、MySQL/ES 来源字段完整性
   和（版本字段落地后）`metric_version`。默认告警阈值为 24 小时、审计间隔为 5 分钟；
   审计只读并记录指标，不更新热度数值、来源字段或索引，避免把告警任务变成隐式修复。
5. 在实时事件和独立指标版本尚未完成前，禁止把 `view_count` 重命名或解释为
   `sold_count`，也禁止新增部分在线累加逻辑。

### 5.1 最小修复的阻断条件

以下任一条件不满足时，来源标记修复只能停留在规格/迁移准备阶段，不能打开 ES
指标排序或宣称热度语义已修正：

- 无法确认现有表结构是否支持幂等加列，或列已存在但类型/默认值不匹配；
- 存量行无法由 `hm-item.item` 来源证据覆盖，不能将 UNKNOWN 行批量标记为历史导入；
- ES mapping 增加字段失败、商品文档无法逐商品回填或 MySQL/ES 来源值不一致；
- 前端仍使用“实时销量/实时热度”等文案；
- 只读审计拥有写权限，或失败时会自动修改热度/来源/索引数据。

这个最小修复可以独立验证，不改变订单、库存、支付和现有索引事件协议；真正的实时
浏览/搜索/销量与 ES 排序一致必须以阶段 A 至 D 的规格评审和回归为前置条件。

## 6. 验收门槛

- 迁移快照与在线指标来源可区分，历史数据没有被伪造为实时数据；
- 相同 `eventId` 重放不会重复增加任意指标；乱序事件不会降低指标版本；
- `CONFIRM`、取消、回滚、支付结果未知和恢复任务均有明确的销量口径；
- MySQL 和 ES 的 `sales`/`hot` 排序在同一指标版本下逐页一致；
- ES 只读到已完成的指标投影，更新失败可重试、隔离和审计；
- 全仓 Maven 测试、接口契约检查、JMeter 阶梯压测及 ES 重启恢复验证通过后，才能
  打开实时指标排序。

## 7. 归属与后续任务

- Product：指标模型、浏览/搜索事件、聚合与 ES 指标投影。
- Stock：`CONFIRM` 成交事件和 Outbox，保证一次确认对应一次可重放事件。
- Order/Pay：明确支付、完成、取消、退款与库存确认之间的销量业务口径。
- PM/架构：确认历史导入 `sold/comment_count` 的保留策略和 `hot_score` 公式。

在上述跨服务契约评审完成前，CM-21 保持开放，不能以“已把字段写入 ES”作为修复完成。
