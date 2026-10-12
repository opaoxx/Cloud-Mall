# CM-18 16T64：秒杀 reserve TTL 参数域校验

## 1. 结论与范围

本切片 GO，修正 `seckill-reserve.lua` 在 counter 检查后先 DECR/INCR user、再对坏 ARGV6 执行 EXPIRE 的 partial write 风险。ARGV6 的 `1.5/060/bad/empty/0/-1` 或超大文本不应进入任何 debit write。

本批只新增 TTL 参数域预检；不改 Java caller、时间窗口、ARGV/KEYS、response code、accepted early return、T63 counter gate、写顺序、SQL/API 或其它 Lua 资源故障语义。

## 2. TTL gate

在既有 TYPE、window、counter、stockLimit、used-limit、available 检查之后、任何 `DECR/INCRBY/EXPIRE/SET/INCR` 前，校验原始 `ARGV[6]`：

```text
ARGV[6] matches ^[1-9][0-9]*$
&& tonumber(ARGV[6]) > 0
&& tonumber(ARGV[6]) <= 1000000000000
```

非法 TTL 返回原 `-1`，无任何 Redis write；合法 TTL 继续把原 `ARGV[6]` 字符串传给 EXPIRE，不转成其它文本、不改变 TTL 写序。`1e12` 秒作为安全域上限，显著高于正常 MySQL DATETIME(3) 活动窗口，并避免秒转毫秒的 long overflow；不扩展为时间业务规则。

accepted marker TYPE=string 的 response `3` 仍最先返回，不读取或校验 TTL；window/limit/available 原 response `4/5/2/0` 优先于 TTL gate，保持旧契约。T63 counter/metadata/ARGV 其它字段门保持。

## 3. 验收与剩余边界

- Direct real Lua：15 个 bad TTL（zero/negative/fraction/leading-zero/nonnumeric/empty/huge>1e12）返回 -1，6 keys DUMP 与 6 张 owned DB facts 不变；合法 1/60/1e12 成功，EXPIRE 使用原字符串。
- accepted replay、window/limit/stock controls、missing user/revision defaults、source/marker/TTL cleanup 和 T37/T63 回归保持；不启动业务 App。
- 不处理非 TTL ARGV、Redis/SQL 资源运行时失败、跨 Redis/SQL fencing、full provenance 或 CM18 M1–M4；不宣称整个 reserve 脚本具备运行时 rollback。

本切片只冻结 reserve EXPIRE 参数域，CM20/CM21 仍等待 CM18 整体完成。
