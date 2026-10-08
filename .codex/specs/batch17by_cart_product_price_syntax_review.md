# CM-20260924-20：购物车商品价格语法评审

## 1. 评审结论与范围

结论：**GO，限定修复 Cart currentSku 接受不可解析 unitPrice 并在 preview 写回/结算前失败的问题。**

BV 已保护 code 和 skuId，但合法身份响应仍可能携带 null、空串、空白、NaN、Infinity 或非数字 unitPrice；add/all 可写入错误价格，preview 可能先 write 再由 BigDecimal 解析失败，污染 hash/TTL。

本轮只保护 unitPrice 的 BigDecimal 语法，不修改 Product API/JSON、金额字段、BU/BV、Cart key/TTL/Lua、并发 RMW、持久清理或其他字段校验。

## 2. price 语法门

在既有 response/data/code/skuId 身份门之后，currentSku 必须：

1. unitPrice 非 null；
2. 执行 new BigDecimal(unitPrice) 语法解析；
3. 解析成功后返回原始 SkuView，不把字符串 trim、setScale、normalize 或转换回写。

unitPrice null、空串、blank、NaN、Infinity、字母或其他 NumberFormatException 均抛既有 INTERNAL/500“商品价格数据不可信”。解析异常作为 suppressed 证据保留；不新增错误码。

合法 BigDecimal 语法（包括 "12.30"、"0.00"、"1E+1"、负数或极端精度/范围）只做语法验证，不新增 sign/scale/precision/金额范围限制；金额计算继续使用原始字符串构造 BigDecimal。

## 3. 调用路径边界

- add：价格语法失败发生在当前 item write/expire 前，不能污染当前 hash/TTL；请求 SKU 的既有 Product 查询顺序保持。
- all/refresh：当前 SKU 价格失败按既有 >=500 传播；前序合法 SKU 已更新不在本批整批回滚。
- preview：价格语法失败发生在 write 前，不写当前 item、不刷新 TTL、不把错误价格计入 total/payAmount；前序合法 item 的部分刷新语义保持。
- response null/data null/404、Feign 异常、BV code/skuId 门、BU 数量/field identity 门保持。

## 4. 兼容与排除边界

- 不校验 productId/name/skuSnapshot，不新增 DTO、字段、错误码、API、SQL、schema 或 Product range 契约。
- 不修合法极端 BigDecimal 的业务金额范围，不改变金额字符串结构或 JSON；金额范围另行评审。
- 不改变并发 RMW、Cart 持久清理、Redis Lua、Product/Cart 网络或事务边界。

## 5. API 契约补充

购物车读取商品事实时，除 code=0 和 skuId 身份外，unitPrice 必须是可由 BigDecimal 解析的原始金额字符串；null/空白/非数字价格按既有 INTERNAL/500 处理，不写入或刷新当前项、不计入 preview 金额。合法字符串不做 scale/格式归一化，本条不定义金额范围上限。

## 6. 验收矩阵

1. 真实 Redis 隔离库 + Product 替身覆盖 add/已有 add/all/preview 的 null、empty、blank、NaN、Infinity、alpha 等 bad price：500，hash/PTTL 保持，无错误价格/金额。
2. 合法 "12.30"、"0.00"、"1E+1" 保留原字符串逻辑，正常摘要、金额和 response 结构保持。
3. unit 验证解析异常 suppressed、零 HSET/expire、原 404/Feign 异常/BU-BX 门回归。
4. 不把本轮描述为金额范围、全批 preview 事务、Product 字段完整性或购物车持久性闭环。
