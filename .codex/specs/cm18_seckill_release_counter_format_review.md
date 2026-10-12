# CM-18 16T62：秒杀 quota release counter canonical 格式

## 1. 结论与范围

本切片 GO，修正 release Lua 对 stockText/revisionText 仅使用 `^%d+$` 的预检：05/03 可通过文本检查，但 Redis INCRBY 不接受 leading-zero 数字，可能先递增 revision 后在 stock 写入时失败，造成脚本部分写。

本批只收紧 RESERVED 分支两处 counter 文本形态；不改 APPLIED replay、accepted witness、KEYS/ARGV、数值范围、metadata/时间检查、写顺序、SQL/Java/DDL/API/Redis 资源语义。

## 2. Canonical counter gate

在 RESERVED 分支原 counter 预检中，stockText 与 revisionText 必须各满足：

```text
value == "0" OR value matches ^[1-9][0-9]*$
```

因此拒绝 00、05、03、其它 padded/leading-zero 形式；保留 "0" 及正常非零 canonical decimal。既有 tonumber 范围、stockLimit/perUserLimit、activity revision、stock capacity 和 metadata checks 不变。

APPLIED replay 在 counter 检查前沿 T50 accepted witness 和 APPLIED saved revision 分支直接返回，合法 APPLIED 回执不要求当前 stock/meta/activity revision counter 格式，也不重复 INCR。

## 3. 原子与剩余风险边界

非法 counter 在任一 Redis 写前返回 0；direct Lua/Recovery 不应产生 partial revision/stock write。若其它运行时 Redis/SQL/跨 store 窗口失败，本批不新增 rollback/fencing 保证，不改变既有 blocked/retry/receipt 语义。

## 4. 验收矩阵

- Direct real Lua：stock/revision 00/05/03/padded-long 与正常 controls 覆盖，返回 0，6 keys DUMP 与 6 张 owned DB facts 不变，不伪造脚本异常。
- Real recovery：malformed counter 沿原 projection blocked/lease/diagnostic 语义，Redis 不发生 partial mutation；合法 zero/nonzero controls 和 T50 APPLIED replay 保持 positive/read-only。
- 保留 T37–T61、accepted witness、source/tuple/lease/SQL/API/DDL/普通库存回归；不声称跨 Redis/SQL fencing、完整 APPLIED provenance 或资源故障 rollback。

本切片只冻结 RESERVED counter 文本 canonical 预检，不处理 metadata numeric token、全局 Redis 资源、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
