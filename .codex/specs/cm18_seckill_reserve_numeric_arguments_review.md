# CM-18 16T65：秒杀 reserve numeric ARGV 领域校验

## 1. 结论与范围

本切片 GO，修正 `seckill-reserve.lua` 对 ARGV[3]/[4]/[5] 直接 `tonumber`、以及 ARGV[2]/[7] 接受小数/指数/超大值的问题。坏 numeric 参数可能误分类窗口/限购，或把 `2.5` 当限购导致第三次受理。

本批只增加五个 numeric ARGV 的 lexical/range/time gate；保留 accepted response 3、KEYS/ARGV 位置、写序、T63 counter、T64 TTL、Java caller/API/SQL/其它 Lua 行为。

## 2. Time arguments

accepted marker TYPE=string 仍最先返回 3，不读取 numeric 参数。否则在原 window 比较前，对 ARGV[3]/[4]/[5] 做 canonical 文本和范围校验：

- `now`：`0`、`^[1-9][0-9]*$` 或 `^-[1-9][0-9]*$`，绝对值不超过 `1e12`；允许 -1 以保留既有未开始测试；
- `startAt`：`0` 或 canonical 正十进制，值不超过 `1e12`；
- `endAt`：canonical 正十进制，值不超过 `1e12`；
- parse 后要求 `endAt > startAt`；不做 epoch 年月/时区业务解析，不用 exponent/decimal/plus/leading-zero 文本。

校验失败返回原 `-1`、无 Redis write；合法 time 继续原 `now<start -> 4`、`now>=end -> 5` 优先顺序。Java 生产 caller 已提供 start>0/end>start，边界门保留合成测试所需 start=0/now=-1。

## 3. Limit arguments

window gate 返回 4/5 后不读取后续 limit；通过 window gate 后、任何 counter TYPE/GET 前校验：

- ARGV[2] per-user limit 与 ARGV[7] stock limit 均为 canonical 正整数 `^[1-9][0-9]*$`；
- parse 后均 `<=2147483647`，匹配现有 Java `Integer.parseInt` 正值契约；
- 非法、fraction、exponent、leading-zero、huge/empty/nonnumeric 返回 `-1`，不伪装限购/售罄，不发生 Redis write。

随后保留原 TYPE/counter/range/used/available、T63/T64 gates；合法结果 `0/2/4/5/1` 和 accepted `3` 不变。TTL 仍使用 T64 原 ARGV[6] 字符串门，不由本批重写。

## 4. 验收与边界

- Direct real Lua：time/limit 各维度覆盖 fraction、exponent、huge、inverted window、leading-zero、empty/non-numeric、边界 0/-1/1e12/Integer.MAX；坏值 -1 且 6 keys DUMP 不变；window 4/5、limit 2、stock 0 等优先级保持。
- 覆盖重复 accepted marker response 3、canonical controls、原 T37/T63/T64 回归；合法 Java caller 参数和写序保持。
- 不改 Java/SQL/API/DDL，不处理其它 ARGV/Redis 资源故障 rollback、full provenance、fencing 或 CM18 M1–M4；无业务 App/固定端口。

本切片只冻结 reserve 五个 numeric 参数领域，不做 epoch 年月语义或跨 Redis/SQL 原子承诺；CM20/CM21 仍等待 CM18 整体完成。
