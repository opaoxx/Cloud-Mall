# CM-18 16T58：秒杀 startup exact ACCEPTED 补建

## 1. 结论与范围

本切片 GO，修正 startup `insertMissingAcceptedOpen` 使用默认 CI status 比较，可能把 status alias/padded value 的非精确受理行补建为 OPEN。两个 startup 调用者（`SeckillQuotaSettlementService.ensureTable`、`StockConsistencyReconciler.initializeStorage`）均复用该 Mapper 方法。

本批只在既有 INSERT…SELECT 的 WHERE 追加 exact status predicate；不改 Java 调用、参数、签名、字段/UUID/time SELECT、LEFT JOIN、DDL、其它 SQL、事务、Redis、API 或 source tuple 证明。

## 2. Exact status gate

保留原 coarse `r.status='ACCEPTED'`、唯一 order join 和 `s.order_no IS NULL` 条件，并追加：

```text
BINARY r.status = BINARY 'ACCEPTED'
```

只有原始 status 精确为 `ACCEPTED` 才允许 INSERT OPEN；PUBLISHED、ORDER_CREATED、PENDING、UNKNOWN、REJECTED、case/accent/trailing-space/padded alias 均返回 0，不创建 settlement 行。实现不扩展 PUBLISHED/ORDER_CREATED，不改变 T44/T45 source 集合在其它 claim/transition 语义中的兼容。

该谓词属于既有 startup INSERT 的 SQL 变更，可能改变读取/锁足迹；不承诺物理扫描、性能或并发锁压力不变。不存在额外 query、独立锁或 repair；生产 schema/source DDL 不改。

## 3. 验收矩阵

- Owned MyBatis/MySQL：准备 bad status alias/unsupported 状态，实际矩阵直接调用 Mapper SQL 与 primary `SeckillQuotaSettlementService.ensureTable` hook，结果 0、六张 facts 无 settlement 新行；exact ACCEPTED 只创建一行 OPEN，原 order/activity/sku/user/idem 字段与 default revision/time 行为保持；第二次调用 0、不重复创建。`StockConsistencyReconciler.initializeStorage` 仅复用同一 Mapper 方法的代码调用链核对，不整体启动 reconciler，也不演练其 audit state、accepted ledger recovery 或 Redis 流程，不宣称两个完整 startup hook 均已矩阵验证。
- source `seckill_reservation` 原库 checksum 不变，owned 库 finally DROP；不启动业务 App/固定端口。启动 hook 可在 standalone/真实 Mapper 事务上下文验证，不新增 Java API。
- T44–T57、claim/transition/recovery/GET/legacy/普通库存和 Redis/MQ 回归保持；不宣称 full source tuple、请求 hash、Order/资金因果、fencing 或 CM18 M1–M4 闭环。

本切片只冻结 startup 补建的 exact ACCEPTED status，不处理其它历史修复或 PUBLISHED/ORDER_CREATED 迁移策略；CM20/CM21 仍等待 CM18 整体完成。
