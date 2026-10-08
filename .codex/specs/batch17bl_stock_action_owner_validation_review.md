# CM-20260924-20：库存动作恢复 owner 输入校验评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderStockActionMapper` claim/finish/retry 的 owner 输入边界。**

当前 claim 未校验 owner，null、blank 或超过 `VARCHAR(128)` 的 owner 仍可能写入 lease/递增 attempts；finish/retry 的空 owner 门又无法完成该 lease，非 strict 模式还可能截断超长 token，破坏 fencing 身份。

本轮只处理当前 Mapper 三个方法的 owner 输入，不修改 schema、生成器、API、历史行、全局 owner 规则、恢复器、UUID 格式、时间、状态或其他字段。

## 2. validOwner 契约

三方法共用私有 `validOwner(String)`（名称可等价实现）：

- owner 非 null；
- `!owner.isBlank()`；
- Unicode code point 数量 `<=128`。

不 trim、不大小写折叠、不 Unicode 归一化、不限定 UUID 正则。合法的非 BMP 字符和包含空格但整体 nonblank 的 owner 原样绑定；BINARY 比较继续保留原区分大小写/重音/尾空格语义。

claim、finish、retry 在时间规范化或 JDBC 前先执行该门；非法 owner 直接返回 0、零 JDBC。owner 非法与 null/越界时间同时出现时，owner 门优先返回 0，不触发时间转换异常。

## 3. 方法与 fencing 边界

- claim：合法 owner 后沿 17BK normalizeActionTime、30 秒 lease、`<` 到期条件、attempts 饱和/非负门、原 SQL 和返回值执行；owner 仍写入原 `lease_owner`。
- finish/retry：合法 owner 后沿 17BK 时间规范化和 17AZ `lease_until > now`、`BINARY lease_owner`、状态、attempts、清 lease 与返回值执行。
- 超长/空 owner 不写 lease、不递增 attempts、不清理或改变原行。
- 不额外限定 owner 来源、token 格式或全局唯一性；正常 Recovery 的 JDK UUID v4 token 由既有流程满足该长度边界，但本批不修改或重新定义生成器。

## 4. 兼容与排除边界

- 保留 SQL 结构、状态、owner BINARY、30 秒 lease、`<`/`>` expiry、fresh now、DATETIME(3)、attempts、错误文本和 Service/API 契约。
- 不新增 schema/check、迁移、历史 repair 或全局 owner 规范；读/statusForOrder 不改。
- 不扩展主键、时间、actionType、operationKey、订单号或其他字段校验；这些由现有 BI/BJ/AZ/BB 等边界负责。
- 不把该输入门描述为正常 UUID 线程会自发产生 null/超宽 owner，也不宣称已解决全部分布式 token 问题。

## 5. 验收矩阵

1. 真实 MySQL strict/non-strict：owner null、空串、blank、129 ASCII/BMP/emoji 在 claim/finish/retry 均返回 0，整行、lease、attempts、key 和时间事实保持；owner 非法且时间 null 时仍零 JDBC/零 SQL。
2. 合法 owner（1/128 ASCII、BMP、emoji、nonblank 空格）claim 可写入原值，finish/retry 在两种状态下使用精确 owner 成功；大小写/重音/尾空格 alias 继续由 BINARY fencing 区分。
3. 回归过期/future lease、equal expiry、AZ takeover、17BK 时间边界、BI insert、reader、Recovery、默认 paid/cancel 和完整 checksum/fixture 清理。
4. 只验证当前 Mapper 输入边界；不宣称 schema 改动、生成器修复、历史行修复或全项目 owner 规范完成。

## 6. 明确不承诺

本轮只拒绝 Mapper 三个恢复方法的不可信 owner 输入，不修改 owner 生成算法、数据库列、分布式唯一性、网络 fencing header 或其他服务恢复器。
