# CM-20260924-12 商品索引可靠性评审

## 1. 评审结论

当前商品本地事务内直接 `convertAndSend`，没有等待 publisher confirm；消费者失败三次后只进入 DLQ，没有持久 ACK、自动恢复或存量重建。ES 当前没有 `cloudmall_product` 索引，而 MySQL 有 88,476 条商品事实（其中 88,475 条上架），RabbitMQ 商品索引 DLQ 有 2 条消息。该问题按 P1 稳定性缺陷处理。

本轮冻结的最小方案是：商品事实与 `product_index_outbox` 同事务且严格递增 `product.version`；提交后以 publisher confirm/returned + 数据库租约重试；消费者从当前 MySQL 事实投影带 external version 的 ES 文档，并在 `product_index_consumption` 写入持久 ACK；失败持续退避，DLQ 自动接管；旧消息不能按 JSON 盲目重放或使用不受限 Java 反序列化，优先依据商品事实、outbox 或 rebuild 重建，无法安全归属则隔离；88,476 条存量重建使用持久游标、限速和有界 ES Bulk 直接投影当前 MySQL 事实，不为每条存量商品制造 outbox/Rabbit 消息；商品 API 仍保持异步索引语义，ES 查询切换留给 CM-20260924-19。

商品索引 outbox 只承载 `product` 实体事件。分类新增、修改、停用和删除继续按现有分类 API 与分类事务执行，但本轮停止向商品索引交换机发送 `CATEGORY_CHANGED`；当前商品索引消费者对该事件的忽略不再作为正常契约。若未来索引文档需要嵌入分类名称，另行设计带 `entityType/entityId` 的事件模型和分类变更传播规则，不在本轮扩展 `product_id NOT NULL` 的商品 outbox。

## 2. 状态与契约

- outbox：`PENDING → PUBLISHED`；确认未知、returned 或状态落库失败为 `UNKNOWN` 并重试；`PUBLISHED` 必须带 `ack_deadline_at`，超时仍无 `APPLIED/STALE` ACK 时由 dispatcher 按同一 `eventId` 重新发布并退避；达到告警阈值可标记 `DEAD`，但仍继续扫描；不可变冲突为 `CONFLICT`。
- outbox 在商品本地事务内持久化完整 `payload_json`。`occurredAt`、原请求 `traceId`、`eventId`、事件类型、版本和 `source` 在首次写入时固定；dispatcher 和重试器原样发布同一 JSON payload，强制 `content_type=application/json`，不得用重试时的当前时间或 MDC 重建 envelope。
- 消费 ACK：`PROCESSING → APPLIED/STALE`；ES/MySQL 暂时不可用为 `RETRY`；不可解析、缺少商品 ID 或不可变载荷冲突为 `QUARANTINED`。只有 ACK 持久化成功后确认 RabbitMQ 消息。
- DLQ 证据：新 JSON 消息先完整写入 MySQL `raw_payload LONGBLOB` 与 SHA-256，应用上限 1 MiB；保存失败、超限或摘要不一致不得 ACK。新 JSON 若缺少原 outbox，校验稳定字段后用同一 `eventId` 从当前事实创建恢复 outbox；无法安全恢复则隔离。
- 事件 envelope：`eventId,eventType,occurredAt,productId,productVersion,source,traceId,payloadVersion`。消费者不信任快照，按 `productId`读取当前事实。
- `UPSERT` 表示当前商品上架且可检索；`HIDE` 表示下架、停用或删除。ES 文档保留 `productVersion` 和 `visible=false` tombstone，防止旧 UPSERT 复活；真实物理删除另行评审。backfill 事件携带读取时的版本，实时事件与 backfill 并发写入必须由 ES external version 或等价条件拒绝旧版本倒序覆盖。

## 3. 故障与恢复验收

1. 商品事务回滚时没有 outbox；事务提交后进程退出仍由 outbox dispatcher 继续发布。新 dispatcher 必须显式使用 JSON converter 和 `content_type=application/json`，并通过真实 RabbitMQ envelope 验收。
2. confirm 丢失、returned、连接断开和 outbox 状态更新失败都重试同一 `eventId`，不产生重复事实。
3. 同一事件重复投递只产生一次 ES 应用；旧版本乱序到达只能 `STALE`，不得覆盖新版本。
4. ES 停机、MySQL 读取失败和 Bulk 部分失败进入 `RETRY`，恢复后继续；坏消息进入 `QUARANTINED`。
5. 事件已标记 `PUBLISHED` 但 ES 宕机时，超过 `ack_deadline_at` 的扫描会用同一 `eventId` 重发；新 JSON DLQ 消息由 listener 持久化为 `RETRY` 后确认，replayer 按原 envelope/事件号投回主队列，不能只依赖发布状态扫描。
6. 两条现存 DLQ 消息来自 `cloudmall.product.exchange`，原错误为旧消费者用 JSON 解析 Java 序列化字节失败（`Unexpected character code 172`）；实测格式为 `application/x-java-serialized-object`、Base64 Java 序列化（前缀 `rO0ABXNy...`），旧字节可见 `PRODUCT_CHANGED`。先把队列、交换机、content type、完整原始 payload、payload size、SHA-256 和受控证据写入 MySQL 隔离表，并在完整保存和校验成功后确认原消息；原始 payload 上限为 1 MiB，超限或写入失败不得截断后 ACK，必须保持未确认并告警。禁止不受限 Java 原生反序列化或按新 JSON envelope 盲目重放。优先根据商品事实、outbox 或 rebuild 重建；只有受控解析且核验稳定商品 ID 后才可按当前事实重建，其余保持 `QUARANTINED`。
7. 重建任务按商品 ID 游标、每批默认 200、每分钟限速，支持暂停/重启恢复；ES Bulk 仅使用 200–500 条有界批次，成功后推进游标，部分失败不推进并重试同一批，external version 防止实时事件与 backfill 倒序覆盖。任务周期检查 `indexExists`/代际；`COMPLETED` 后发现索引丢失时递增代次、重置游标为 0 并重新限速扫描，`FAILED` 保留错误并在退避后自动重试。
8. 对账核对 MySQL 商品总数/上架数、实时 outbox 未完成数、消费 ACK、ES 可见文档和 tombstone；记录 Bulk 延迟、部分失败、重试、版本冲突、rejected 和重建速率。

## 4. 迁移与边界

1. 先创建 `product_index_outbox`、`product_index_consumption`、`product_index_rebuild_job`，声明 `cloudmall_product` mapping（包含 `productVersion`、`visible` 和公开检索字段）。
2. 停止旧的直接发布路径或确保其不再产生新消息；盘点两条 DLQ，不能解析的消息不得自动猜测。
3. 启动一个带租约的 rebuild job，从 `product_id` 游标分批读取当前 MySQL 事实并直接调用有界 ES Bulk；实时事件继续共用 outbox/消费者链路，双方使用 external version 防止倒序覆盖。
4. 观察 MySQL/ES 数量和版本核对达到验收阈值后，才由后续 CM-20260924-19 评审切换搜索读路径。周期删除或重建索引后，必须验证已完成任务能自动创建新代次并从游标 0 恢复。

### 4.1 ES 旧版本兼容边界

若目标环境已有 `cloudmall_product` 文档，必须先检查文档版本语义。若文档由 ES 内部 `_version` 创建，不能直接把 MySQL `product.version` 当作 `external` 版本写入同一索引：ES 内部版本可能更大，导致所有写入返回 409，不能把这些 409 统一记录为 `STALE` 或宣布重建完成。

当前本地环境确认索引缺失，可以直接创建带 `productVersion`/`visible` 字段的新索引并使用 external version。已有索引环境必须采用独立索引迁移，例如创建 `cloudmall_product_v{generation}`，按当前 MySQL 事实执行限速 Bulk，并从第一批写入起统一使用 MySQL `product.version` 的 external version；完成数量、版本和可见性核对后，再由后续 CM-20260924-19 评审 alias 切换。旧索引保留只读，核对完成前不得删除。

如果经过离线审计能够证明旧文档已经保存可信的高基数外部版本，并且所有文档版本都可映射到 `product.version`，才允许在原索引继续写入；否则 409/版本冲突进入 `VERSION_SEMANTICS_INCOMPATIBLE` 告警和迁移失败状态，触发新索引迁移，不得静默降级为 `STALE`。验收必须覆盖：旧内部版本索引、已存在 external 版本索引、并发实时事件与 backfill，以及迁移期间的 409 分类。

本轮不定义 ES 查询 API 切换、不物理删除 tombstone、不接入真实第三方搜索集群，也不假设两条旧 DLQ 的载荷一定可解析。若旧 DLQ 消息无法取得稳定商品 ID，保留 `QUARANTINED` 并新增运维核验缺陷；若 88,476 条重建在本地资源下仍造成 ES heap/rejected 峰值，新增性能缺陷并调低速率或批量，而不放宽可靠性语义。
