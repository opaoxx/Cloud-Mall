# CM-18 16T49：秒杀 legacy reservation context 维度精确校验

## 1. 结论与范围

本切片 GO，补充 T31–T33 legacy reservation 对 `activity_id/user_id/idempotency_key` 的来源维度校验。当前主 ACCEPTED GET 与 Stock fallback 只核 order/SKU，缺失或错误 context 仍可能返回 RESERVED/REJECTED/items 并进入 Order fallback。

本批增加共享纯 read validator（可放 `stock.util`），只处理 Map 数值/幂等字段；不改 SQL、Redis、schema、DTO/API、事务、状态机或 source query。

## 2. Context validator

`activity_id`、`user_id` 必须为 Number 且不得是 Float/Double；以 `new BigDecimal(value.toString()).longValueExact()` 无损转换，并要求正值。byte/short/int/long/BigInteger/整数 BigDecimal 至 Long.MAX_VALUE 合法；null、文本、浮点、小数、0、负数、溢出或转换异常非法。

`idempotency_key` 必须是 String，非 null、非 literal `"null"`、非 blank、code point 数不超过 128；不 trim、不 casefold。包围空格、uppercase `NULL`、`null ` 和 128 个 emoji 沿原事实语义合法。

两路径均在既有 order identity 和 SKU 门之后调用；T31 错 order 与 T33 SKU 错误优先。主路径仅对 `ACCEPTED` Map 执行 context 门，非法返回既有 `SECKILL_SETTLEMENT_CORRUPT` 500/固定“秒杀受理事实维度无效”；主非 ACCEPTED 仍 return null 交 outer。Stock outer 对所有 legacy source status 的非法 context 返回请求 orderNo echo、`UNKNOWN/items=[]`，不落普通 fallback。

## 3. 不变边界

- 不新增 SQL/query，使用现有 Map 字段；不改变 accepted/legacy source 状态、SKU 数值、order identity、普通 flows/request、Redis/MQ、DDL、Controller/API 或全 tuple 因果。
- 不证明 request hash/payload、Order/库存完整因果；只阻止缺失/错 context 伪造 legacy reservation 结果。
- 合法 Long.MAX_VALUE/Unicode 128 和旧状态/items 映射保持；错误字段不回显 raw value。

## 4. 验收计划

- Unit：activity/user 数值类型、null/0/负数/小数/Float/Double/超范围、idempotency null/literal null/blank/超长/Unicode边界；identity/SKU/context 优先级和主 500/outer UNKNOWN 语义。
- 真实 T33 MySQL/Controller/Order fallback：现行 BIGINT 0/negative、VARCHAR blank/literal null 直接验证；合法 Long.MAX/Unicode 128 及 old SKU 复合 fault 保持。Source checksum、owned facts 前后不变，坏 outer 不变成 SUCCEEDED。
- 旧合法 unit Map 补齐 activity/user/idempotency 三字段，不新增 SQL；owned nullable/超限值仅准备阶段演练，不改生产 DDL。保留 T31–T48、普通/Pay/Gateway 回归，资源 finally 清理。

本切片只冻结 legacy context dimensions，不处理 full tuple、request provenance、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
