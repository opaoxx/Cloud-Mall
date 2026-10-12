# CM-18 16T59：秒杀 startup 受理正身份门

## 1. 结论与范围

本切片 GO，在 T58 exact ACCEPTED startup 补建门之后，增加 source `activity_id/sku_id/user_id` 正数校验，避免 0/负值受理行被补建为 OPEN 后再由 GET/写路径报告维度损坏。

本批只修改 `insertMissingAcceptedOpen` 的 WHERE；保留 T58 status predicate、原 SELECT/字段/UUID/time、LEFT JOIN、参数/签名、两个调用点、事务/Redis/API/DDL 和其它 SQL。

## 2. Positive identity predicate

在 T58 条件后追加：

```text
r.activity_id > 0
AND r.sku_id > 0
AND r.user_id > 0
```

当前三列是生产 signed BIGINT；0/负数不补 OPEN，1 与 Long.MAX_VALUE 边界允许并保持原 tuple/default revision/time/idempotency 行为。NULL/超范围历史坏值不通过本生产 DDL 门；不声称支持历史 DECIMAL/Float source 迁移。

本批不校验 idempotency blank/literal null、operation key、request hash、payload、Order/full source 因果；这些仍由既有 T36/T44/T46 等边界处理。不得新增 Java helper、query、DDL、Redis 或 API。

## 3. 验收矩阵

- Owned MyBatis/MySQL：三字段 0/负值共 12 组（direct Mapper 与 primary `ensureTable` hook）均返回 0，未新增 settlement、六表 facts 不变；exact 1/Long.MAX 边界创建一行 OPEN，第二次幂等 0，旧 T58 status/tuple/idempotency 行为保持。
- `StockConsistencyReconciler.initializeStorage` 仅核对共享 Mapper 调用点，不整体初始化 audit state、accepted recovery 或 Redis；不宣称完整 startup hook 矩阵已验收。
- source `seckill_reservation` 原库 checksum 不变，owned facts/资源 finally 清理；生产 DDL 不改。保留 T58/T31–T58、legacy/GET/transition/recovery/普通库存/Redis 回归。

本切片只冻结 startup 三项正身份门，不处理 idempotency/full payload/hash、历史 Decimal/Float 迁移、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
