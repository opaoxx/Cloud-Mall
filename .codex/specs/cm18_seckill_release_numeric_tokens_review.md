# CM-18 16T66：秒杀 release numeric token 精确格式

## 1. 结论与范围

本切片 GO，收紧 release Lua 六个只读 numeric token：ARGV3/4 的 settlementRevision/quantity、RESERVED acceptanceRevision、APPLIED savedRevision、metadata stockLimit/perUserLimit。当前 digits-only 可接受 `02/2.0/2e0` 或 `01/1.0/1e0`，并把 marker/meta 的非 canonical 文本当作真实回执/证据。

本批只替换原 numeric token 门；不移动 accepted witness/APPLIED early return，不改变 T62 writable counters、时间字段、KEYS/ARGV位置、写序、Java/SQL/API 或其它 Redis 资源边界。

## 2. 六个 token 规则

- ARGV3 必须原文本精确为 `"2"`，ARGV4 必须精确为 `"1"`；不接受 decimal、exponent、leading-zero、空白或其它数值等价写法。Java caller 原本即使用固定 String 参数。
- RESERVED `acceptanceRevisionText` 与 APPLIED `savedRevisionText` 必须匹配 `^[1-9][0-9]*$`，再沿原 `1..1000000000000` 数值范围；禁止 `01/04/1.0/1e0` 等 alias。
- metadata `stockLimitText` 与 `perUserLimitText` 必须匹配 `^[1-9][0-9]*$`，再沿原正数和 `<=1000000000000` 范围；不收窄为 Java int，不改变历史 metadata 数值域。

APPLIED accepted witness 和 early return 顺序保持：合法 accepted marker + APPLIED savedRevision 在当前 counters/meta 缺失或非 canonical 时仍可返回合法 replay receipt，不依赖 RESERVED counter gate；T50 accepted witness 仍先于 replay。

## 3. 拒绝与验收边界

任何六 token 格式/范围失败直接 Lua 返回 0，不产生 release/blocked partial write；Recovery 沿原 projection blocked/lease/诊断语义。合法边界 `1`/`1e12` 与真实首次 release 生成的 APPLIED replay保持。

Direct real Lua 覆盖坏 ARGV、两类 marker、两类 metadata；Recovery RP/PB 坏 marker/meta 不释放、不完成；healthy controls 和 APPLIED replay 保持。保留 T37–T65、source/tuple/lease/fencing/full provenance 未闭环边界。

本切片不新增 SQL/DDL/API/Java helper，不处理未知 Redis 资源故障、完整 receipt 因果、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
