# CM-18 16T175：Pay execution lookup identity

## 1. 结论与范围

GO。PayTccExecutionStore point queries are CI by payNo; `read`/`locked` only check existence, so alias rows can be returned/updated as another payment.

本批只补 execution row 的 exact payNo identity，不改 SQL/collation/history。

## 2. Exact guards

- `read(payNo)`：null 保原 NOT_FOUND/404；non-null 必须 row.payNo 非 null 且 `Objects.equals(row.payNo,payNo)`，否则既有 `PAY_TCC_EXECUTION_CONFLICT/409`。
- `locked(payNo)`：null 或 row.payNo null/mismatch 均 conflict，在 bindXid/decide/observe 的任何早返回/CAS 前生效；匹配行保持原 state/decision/readback。

不 normalize/casefold，不改 claim validate、readback、SQL、state/decision/API 或其它 Store。

## 3. 验收

- Owned actual mapper：lowercase query 返回 uppercase original 行时 read/locked conflict；matching、missing404、locked409、bind/decide/observe controls 保持。
- Real Coordinator recover/Store：alias 冲突不 bridge/TC/observe、不写 Pay/Intent/Execution facts；资源/owned cleanup/source checksum。
- Unit foreign/space/null cases；unit null 不声称生产 DB 可 NULL。健康 terminal/readback/error 规则回归，不启动 App/TC。

## 4. 交付边界

实现仅允许给 `read` 与 `locked` 增加 exact payNo guard、JavaDoc/测试；不得修改 Mapper SQL、历史数据或全局异常处理。
