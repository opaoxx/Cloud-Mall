# CM-18 16T61：秒杀 startup 原订单号身份门

## 1. 结论与范围

本切片 GO，在 T58–T60 startup 补建条件之后，阻止 source `order_no` 空串、Java17 全 whitespace、literal `"null"` 或超 64 code points 被 INSERT/截断为 OPEN。T37 recovery candidate 不能处理此类错误 order source。

本批只修改 `insertMissingAcceptedOpen` WHERE；保留 T58/T59/T60、原 CI LEFT JOIN/unique 候选、SELECT/字段/UUID/time、参数/Java 调用、其它 SQL/DDL/API/Redis/事务。

## 2. Exact orderNo predicate

追加：

```sql
CHAR_LENGTH(r.order_no) BETWEEN 1 AND 64
AND BINARY r.order_no <> BINARY 'null'
AND HEX(CONVERT(r.order_no USING utf8mb4)) NOT REGEXP
  '^(09|0[A-D]|1[C-F]|20|E19A80|E2808[0-68-9A]|E280A[89]|E2819F|E38080)*$'
```

按 T37 generic order 语义精确检查：不 trim/casefold、不强加年月格式；保留 uppercase `NULL`、`null `、周围存在非空字符的空格、NBSP/U+2007/U+202F/U+0085/NUL、非 Java whitespace 和 emoji 64。source NULL 不准备；65 code points 仅 owned VARCHAR(128) 准备阶段演练，生产 VARCHAR(64) DDL 不改。

HEX/CONVERT 仅用于 WHERE，不改 SELECT 值；不新增 Java helper/query/DTO/API，不改变 CI candidate 检索或历史 source 修复语义。

## 3. 验收矩阵与边界

- Direct Mapper/primary `ensureTable` owned MySQL：空串、literal null、25 whitespace、mixed whitespace × 两入口均 0、facts 不变；Unicode64、ASCII/emoji边界和合法混合键创建 OPEN，原 order/full tuple/default time/idempotency 保持，第二次幂等 0。
- 65 code points 只在 owned VARCHAR(128) 准备并 finally 删除/恢复，避免目标 VARCHAR(64) 截断；production source DDL 不改。`StockConsistencyReconciler.initializeStorage` 仅核共享 Mapper 调用点，不整体启动 audit/accepted recovery/Redis。
- 保留 T31–T60、legacy/GET/transition/recovery/普通库存/Redis 回归；不处理 full Order existence/hash/provenance、CI alias 历史错行、fencing、TC/MQ 或 CM18 M1–M4。

本切片只冻结 startup order identity 空白/literal null/长度门；不改变其它 source 状态或受理迁移策略，CM20/CM21 仍等待 CM18 整体完成。
