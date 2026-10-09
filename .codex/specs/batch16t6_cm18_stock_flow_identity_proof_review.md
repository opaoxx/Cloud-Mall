# CM-18 16T6：普通库存流水原订单身份证明评审

## 1. 结论与范围

结论：**GO，限定修复普通 reservation GET 的 flow provenance 证明；只扩展既有单次 flows 汇总 SELECT 的返回列和私有读 guard，不改变写入、DDL、索引、API 路径、秒杀或其他 Mapper。**

16T4/16T5 以查询 `orderNo` 作为 WHERE 候选，但原汇总没有返回 flow 原始 `order_no`、flow type 合法性或数量合法性元数据。大小写/尾随空格/collation 别名、同 SKU 混合原订单以及 negative+positive 抵消可能伪装成合法终态，进而影响旧 Order fallback 的 `SUCCEEDED`。

## 2. 单 SQL provenance 投影

`StockSqlMapper.findReservationFlows(orderNo)` 保持原 WHERE 参数 `order_no=?`、GROUP BY `sku_id` 和单次 SELECT；在原数量汇总中新增：

```sql
MIN(order_no) AS original_order_no,
COUNT(DISTINCT BINARY order_no) AS order_identity_count,
SUM(CASE
      WHEN flow_type IS NULL
        OR BINARY flow_type NOT IN ('RESERVE','CONFIRM','ROLLBACK')
        OR quantity IS NULL
        OR quantity <= 0
      THEN 1 ELSE 0
    END) AS invalid_flow_count
```

不在 WHERE 中用 `BINARY` 过滤异常行；异常必须作为候选元数据返回，由 Service 保守阻塞，不能伪装成 NOT_FOUND。`MIN(order_no)` 仅在 `order_identity_count=1` 时可作为原身份代表；count 使用 binary distinct，阻止大小写/尾随空格/collation 别名被合并。

## 3. Service 证明门

普通非空 flows 分支中，每行在 16T4/16T5 数量和 snapshot proof 前先检查：

- `Objects.equals(queryOrderNo, original_order_no)`；
- `order_identity_count == 1`；
- `invalid_flow_count == 0`；
- 元数据必须是可精确读取的非负整数，缺失、非整数、负数或溢出均返回 `UNKNOWN/items=[]`。

通过 provenance 门后继续原 16T4 全 SKU同向数量聚合、16T5 request snapshot/hash/identity proof；失败不回显错误原值或错误订单号。原 replay 503 门保持，不能通过 `alreadyReserved` ACK。

本批不改变空 flows/no-request、空 flows/request、写状态机、请求字段、Redis、消息、秒杀 flows、Order/Pay 代码或任何数据库事实。非 RR 读取及读中竞争的保守 UNKNOWN 语义保持。

## 4. 验收矩阵

- Unit：合法单一原 order、case/尾随空格别名、同 SKU 混合原 order、unknown/NULL/空 flow_type、negative+positive 抵消、zero quantity、metadata 缺失/非整数/负数/溢出均覆盖；异常返回 UNKNOWN/items=[]。
- 真实 MySQL：新增投影字段验证原 order identity count、MIN、invalid count；合法三状态、request snapshot/hash、原 request/legacy 分支和 Order fallback 保持；源 Stock checksum 与 owned 资源清理通过。
- 旧 Order fallback 消费实际 Stock GET 结果时，identity/provenance 异常不能转为 `SUCCEEDED`，不发 Stock 写调用；重放仍沿 16T5 固定 proof unavailable 门。
- 保留 SQL 单次读取、原绑定参数、GROUP BY/数量语义、秒杀/写回归；不宣称 Stock 余额、原 Order 完整 SKU、TC/MQ 或 CM18 主项闭环。

## 5. 交付边界

本评审只冻结普通 flows 的原订单身份和 flow 元数据证明，backend 按本文及 API/DB 相邻条款实现；不增加 endpoint、DDL、索引、写状态或其他服务变更。
