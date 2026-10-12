# CM-18 16T60：秒杀 startup 幂等键上下文门

## 1. 结论与范围

本切片 GO，在 T58/T59 startup 补建条件之后，阻止 source `idempotency_key` 空串、Java 17 `Character.isWhitespace` 全空白或 literal `"null"` 被 INSERT 为 OPEN。只修改 `insertMissingAcceptedOpen` WHERE，不改变 Java 受理、其它 source/legacy 读写、request hash/payload、Order 因果、fencing、Redis/API/事务或旧错误事实。

现行 source `idempotency_key` 为 VARCHAR(128) NOT NULL；本批不准备 NULL，不改生产 DDL，不把历史 Decimal/Float 或其它键迁移语义纳入。

## 2. Exact SQL context predicate

在 T58/T59 原条件之后追加以下 SQL 门：

```sql
CHAR_LENGTH(r.idempotency_key) BETWEEN 1 AND 128
AND BINARY r.idempotency_key <> BINARY 'null'
AND HEX(CONVERT(r.idempotency_key USING utf8mb4)) NOT REGEXP
  '^(09|0[A-D]|1[C-F]|20|E19A80|E2808[0-68-9A]|E280A[89]|E2819F|E38080)*$'
```

该表达式按 utf8mb4 code point 检查，不修改 SELECT 值、不 trim/casefold/normalize：

- 拒绝空串、literal `null`、Tab/换行/Java17 `Character.isWhitespace` 25 类全空白和其混合串；
- 保留 uppercase `NULL`、`null `、周围有非空白字符的包围空格、NBSP、U+2007、U+202F、U+0085、NUL、非空 emoji 等原键；
- 128 code points 与合法非空混合键沿原 tuple/time/idempotency 语义补建，129 由现行 VARCHAR(128) 约束处理，不改变生产 schema。

`HEX/CONVERT` 仅用于 WHERE 判定；不新增 Java helper、循环 query、独立 SQL、参数、DTO、API 或 Redis/事务行为。两个 startup caller 仍只复用同一 Mapper 调用点。

依据：[Java 17 Character.isWhitespace](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Character.html#isWhitespace(int))；[MySQL 8.0 HEX()](https://dev.mysql.com/doc/refman/8.0/en/string-functions.html#function_hex)。

## 3. 验收矩阵与边界

- Direct Mapper/primary `ensureTable` owned MySQL：empty、literal `null`、25 类 Java whitespace、mixed whitespace × 两入口均 0 行且六张 facts 不变；healthy nonblank、NBSP/2007/202F/0085/NUL、uppercase/null-space、周围空格和 emoji128 保持原 tuple/default time/idempotency，第二次幂等 0。
- 现行 BIGINT/status/source/order 条件和 T58/T59 正身份门保持；NULL source 不准备。129 长度只在必要的 owned VARCHAR(256) 准备并 finally 恢复，不改变生产 VARCHAR(128)。
- `StockConsistencyReconciler.initializeStorage` 只核复用同一 Mapper 调用点，不整体初始化 audit/accepted recovery/Redis；不宣称完整 startup hook、full source/request hash/Order 因果。
- source checksum、owned DB/facts、资源 finally 清理；不修改其它 SQL/DDL/API/Redis/事务/legacy，保留 T31–T59 回归。

本切片只冻结 startup 幂等键空白/NULL literal 过滤，不处理历史 repair、fencing、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
