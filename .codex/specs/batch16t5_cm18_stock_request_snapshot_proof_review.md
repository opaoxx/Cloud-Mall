# CM-18 16T5：普通库存 request 原载荷只读证明评审

## 1. 结论与范围

结论：**GO，限定在 16T4 普通非空 flows 且存在 request 行的分支补充原载荷证明；不改变写入、空 flows 外层分支、秒杀路径或其他服务。**

16T4 只证明已观察 SKU 的数量和 request.status 一致，request 的 `items_snapshot/items_hash` 仍可能与 flows 不一致，导致缺 SKU、额外 SKU、重复 SKU 或错误数量被误视为整单终态。本批只在普通非空 flows+request 分支追加 snapshot/identity proof；未进入该分支的历史行为保持。

## 2. request 载荷与 hash 契约

- request 必须是 `order_no` 精确等于查询 orderNo、`scene` 精确为 `NORMAL`；不匹配返回 `UNKNOWN/items=[]`。
- `items_snapshot` 必须是非空 String；使用该 StockService 实例的独立 ObjectMapper 副本，启用 `FAIL_ON_TRAILING_TOKENS` 与 `FAIL_ON_READING_DUP_TREE_KEY`，不修改 global mapper。
- JSON 根必须是非空数组；每个元素只能包含 `skuId`、`quantity` 两个字段，字段必须是 native integral number；`skuId` 为正 Long，`quantity` 为正 int 范围。缺失/null/小数/负数/溢出/额外字段/重复键/尾随 JSON 均返回 `UNKNOWN/items=[]`。
- 规范化数组必须按 `skuId` 严格升序且无重复；重建 `List<StockLineDTO>` 后复用原 `toJson` 与 SHA-256 算法计算 hash，再与 request `items_hash` 比较。不能对 raw JSON 直接 hash，不能因空白、JSON key 顺序或 MySQL JSON 存储规范化改变而误判合法载荷。
- 重建数组的 SKU 集合和 quantity 必须与已观察 flows 一一对应：缺 SKU、额外 SKU、重复 SKU、数量差异任一存在均返回 `UNKNOWN/items=[]`。本批不验证超出当前 flows 的历史库存余额或完整 Stock 业务身份。

## 3. 与 16T4、外层分支和不变边界

- 仅在普通 `flows` 非空且 request 存在时执行 snapshot proof；flows 为空且无 request 的 `NOT_FOUND`、flows 为空但有 request 的原 snapshot 展示分支、秒杀 settlement/flows 分支均保持原行为。
- `normalReservationProof` 从 static 改为使用同一 StockService 实例的 mapper/ObjectMapper，以复用 writer canonical JSON/hash；不新增 endpoint、Mapper SQL、DDL、缓存、消息或写状态机。
- request proof 通过后仍必须满足 16T4：每 SKU 数值合法、完整同向终态、所有 SKU 同一状态、request.status 与聚合状态一致；任一 proof 失败返回 `UNKNOWN/items=[]`，不改原行。
- GET 仍非 RR；读取竞争可保守 UNKNOWN。Order fallback 只消费实际 Stock GET 结果，不由本批直接改变 Order/Pay。

### 3.1 已有重放响应门

进入已有相同 request/hash 的 `alreadyReserved()` 重放路径时，必须先检查 `reservationResult()` 返回非 null、data 非 null 且 proof status 精确为 `RESERVED`；只有该条件满足才可返回原 `ALREADY_RESERVED` 语义。proof 为 `UNKNOWN`、`CONFIRMED`、`ROLLED_BACK`、缺 data 或读取异常时，固定抛 `STOCK_RESERVATION_PROOF_UNAVAILABLE`/503，不输出 raw 错误、snapshot 或载荷，不修改状态、数量、request、flows、Redis 或消息，也不把 proof 失败强制改写为 ALREADY_RESERVED。合法 `RESERVED` 的重放 API 字段和状态保持。

## 4. 验收矩阵

- Unit：snapshot 非数组/空数组、缺字段/null、非整数、负数/溢出、额外字段、重复键、尾随 JSON、错误 order_no/scene、hash 错误、缺/多/重复 SKU、quantity 不同均 `UNKNOWN/items=[]`；合法 MySQL JSON 空白/key 顺序规范化控制样本通过；确认 ObjectMapper 全局配置不变。
- 真实 MySQL：复用 16T4 普通旧/有 request flows，覆盖完整原载荷、缺 SKU、额外 SKU、重复 SKU、错误 quantity/hash、request identity/scene 冲突；原 Stock 表 checksum、request/flows facts 前后不变。
- Order fallback：Stock GET 返回实际 proof 结果时，只有 snapshot+flows+request.status 全部一致才可让旧无 action Order 映射 `SUCCEEDED`；proof 失败保持 UNKNOWN，不调用 Stock 写接口、不推进 Pay/退款。Order Mapper 为替身，StockClient 只桥接实际 GET 结果，不宣称 HTTP/TC/真实资金全链。
- 保留 16T4 六类旧 writes、秒杀与 API 回归；修正旧 request mock 使其携带真实 canonical snapshot/hash，不降低新 proof 门。
- 专项覆盖相同 request hash 但 snapshot/flows 错配的重复 reserve：读到 UNKNOWN 时库存与 request facts 不变且不 ACK；直接 helper/service proof 失败也不能 coerce，合法 RESERVED 仍返回 ALREADY_RESERVED。

## 5. 交付边界

本文件只冻结普通非空 flows+request 的原载荷证据；不覆盖空 flows 外层、完整 Order SKU 快照、Stock 余额、秒杀身份、TC/MQ 或其他 CM18 M1 切片。
