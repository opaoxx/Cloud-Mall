# CM-18 16T161：Pay Legacy recovery blank key

## 1. 结论与范围

GO。T159/T160 pair/numeric gates 仍允许双方共同空白 pay/order keys；Service 后置 blank 拒绝会让坏行占 candidate LIMIT 或先 claim。

本批只在 findDue/findDueAfter/claim 增加 Java 17 `String.isBlank` 等价的 SQL key gate。

## 2. Exact whitespace pattern

冻结单一 Java constant pattern（无起始空格）：

`[^\\x{0009}-\\x{000D}\\x{001C}-\\x{0020}\\x{1680}\\x{2000}-\\x{2006}\\x{2008}-\\x{200A}\\x{2028}-\\x{2029}\\x{205F}\\x{3000}]`

在三条 SQL 的 payment pay_no/order_no 条件加入 `REGEXP_LIKE(..., ?, 'c')`；每条 SQL 按文本位置绑定两个 pattern 参数（pay、order），保持 pair equality、numeric/mode/status/time/order/LIMIT/cursor/SET。NULL 结果不满足 gate；不使用 SQL `[:space:]`、trim、casefold、长度/格式新门。

实际 pattern 必须以项目 JDK17 `Character.isWhitespace` 枚举与 MySQL ICU 只读 SELECT 对照：NBSP U+00A0、U+0085、U+2007、U+202F 等 Java 非 blank 保持可候选；mixed 字符串按是否含非 whitespace 保持 Java isBlank 语义。

## 3. 保持项与验收

不改其它 SQL、参数语义、phase CI、T159/T160；不宣称完整 key/domain/snapshot/fencing。

- Direct claim：共同空 pay/order、ASCII/control/Java whitespace、Unicode whitespace variants → 0/full facts；
- Process pre-route 后 blank pair → claim0/409/noRPC/lease；
- Scan：100 blank 头 + healthy 尾先过滤再 LIMIT；pattern 参数顺序/NULL DB 语义验证。

## 4. 交付边界

实现仅允许修改三条 recovery SQL 的 key REGEXP predicates/绑定及常量 JavaDoc/测试；不得新增字符串 helper、API、DDL 或其它业务门。
