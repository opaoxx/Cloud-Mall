# CM-18 16T63：秒杀 reserve counter canonical 格式

## 1. 结论与范围

本切片 GO，修正 `seckill-reserve.lua` 对 stock/user/revision counter 仅 `^%d+$` 预检：user `02` 可通过后，stock DECR 可能先写、INCR user 失败；revision `03` 可在 stock/user/EXPIRE/accepted 已写入、operation marker 尚未写入时失败（原顺序为 revision INCR 后才 SET operation marker）。

本批只收紧三处 counter 文本形态，不改 accepted replay、TYPE/missing 默认、stockLimit/used/window/limit、ARGV/TTL/KEYS、写顺序、SQL/Java/API 或其它 Redis 资源故障语义。

## 2. Canonical gate

在任何 debit write 前，`stockValue`、`userValue`、`revisionValue` 必须分别满足：

```text
value == "0" OR value matches ^[1-9][0-9]*$
```

拒绝 `00/02/03/05` 和其它 leading-zero/padded 形式；保留 canonical zero/nonzero。现有 user/revision missing 默认 `0`、stock/type/missing、numeric limits、window/used/available 和 response codes 保持。非法值返回原 `-1`，在 `DECR/INCRBY/EXPIRE/SET/INCR` 前结束，避免脚本 partial write。

accepted marker TYPE=string 仍在最前返回 `3`，不读取或校验 counter；accepted replay 即使后来 counter 变为 noncanonical 也保持原 no-second-debit 语义。

## 3. 验收与边界

- Direct real Lua：三 counter 各覆盖 `00/05/02/03/long-padded` 等 bad variants；受理 marker/op 预先删除，真实脚本返回 -1，6 keys DUMP 与 6 张 owned DB facts 不变，无 partial write/异常伪造。
- Canonical zero/nonzero、missing user/revision default、limit/time、accepted replay 和现有 response codes 保持；accepted replay 具备 marker 时不重复 debit。
- 复用 T37 owned SQL/Redis fixture，不新增 Java mirror SQL/DDL/API；source checksum、owned keys/DB finally 清理。必要的 real release marker 兼容只验证 Lua 语义，不宣称完整 reserve/release 链。
- 不处理其它 ARGV/资源故障 rollback、跨 Redis/SQL fencing、full provenance、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
