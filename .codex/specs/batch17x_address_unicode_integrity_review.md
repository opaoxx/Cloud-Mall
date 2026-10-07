# CM-20261007-20：秒杀地址 Unicode surrogate 完整性评审

## 1. 评审结论与范围

结论：**GO，限定修复 common `canonicalAddress` 对 JSON 字符串/字段名孤立 UTF-16 surrogate 的完整性校验；不改变合法 v1 hash、API、schema、历史数据或发布边界。**

严格 JSON reader 可以接受 `\uD800`/`\uDC00` 等孤立 surrogate；后续 Java String 按 UTF-8 编码时可能用替换字节，导致不同孤立代理与 `?` 等文本产生相同指纹，地址身份被错误合并。

## 2. 递归 Unicode 校验

在 `SeckillRequestFingerprint.canonicalAddress` 的既有解析和 normalize 流程内增加私有递归校验，不新增 public 类/API：

- 遍历根对象所有 String value、Map String key、嵌套 Map、List/数组中的 String；
- 每个 String 必须满足 UTF-16 surrogate 配对：high surrogate 后紧跟 low surrogate，low surrogate 不得独立出现；
- 孤立 high/low surrogate 抛既有 `IllegalArgumentException` 地址错误，不进入 canonical/hash；
- 合法 high+low surrogate 作为原始 Unicode code point 保留；不做 Unicode NFC/NFKC 或大小写归一化；
- 非 surrogate 字符、合法 U+FFFD、literal `?`、中文和 emoji 保持原语义。

Map key 也必须校验，不能只检查 value；数组顺序、对象排序、BigDecimal 规范化和严格重复字段/尾随 token 规则保持不变。

## 3. 服务边界

- Stock：地址指纹失败沿现有参数错误返回，发生在 claim/Redis/outbox 前，不创建受理事实；
- Order：消费者/恢复路径沿现有异常事务和 17V CONFLICT 语义，不创建订单或错误 RESOLVED/REPLAYED；
- 合法 high+low、U+FFFD、`?`、literal 与 `\uD83D\uDE00` 等等价合法表达的既有 v1 hash 必须保持；
- 不修改历史 payload/hash，不批量重算，不改变 API/schema/hash 版本；未重启的现有进程不视为已发布新规则。

## 4. 验收矩阵

1. common unit 覆盖根/嵌套/数组字符串、Map key 的孤立 high/low surrogate，均抛原 IllegalArgumentException。
2. 合法 surrogate pair、emoji、U+FFFD、`?`、中文和转义/字面等价表达 canonical/hash 保持固定向量。
3. Stock 真实 MySQL 验证孤立 surrogate 在 claim/Redis/outbox 前失败且零副作用；合法地址基线通过。
4. Order 真实 consumer/recovery 验证异常事务回滚、CONFLICT 保留和不错误完成；恢复证据不被重写。
5. 复用 17W 严格 JSON/地址测试和原库 checksum；验证不改变共享 ObjectMapper 或其他 JSON parser。

## 5. 明确排除

本轮不做 Unicode 归一化、历史迁移、UTF-8 原始 bytes 修复、API/schema/hash 版本、Rabbit/lease 变化或业务进程自动重启；仅防止孤立 surrogate 造成地址指纹碰撞。
