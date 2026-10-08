# CM-20260924-20：库存动作错误文本 Unicode 完整性评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderStockActionMapper.finish` 与 `retry` 共用 `shorten` 按 UTF-16 `length` 截断错误文本的问题。**

当前 `shorten` 使用 `substring(0, Math.min(512, error.length()))`。511 个 ASCII 加一个 emoji 可能被拆成孤立 surrogate，512 个 emoji 会过早截断为 256 个；`order_stock_action.last_error VARCHAR(512)` 应按最多 512 个 Unicode code point 保存。

本轮只修改该 Mapper 的私有 `shorten` 逻辑，不扩展其他 Mapper，不修改 SQL、schema、owner/lease、状态、attempts、时间、Service、Feign 或历史数据。

## 2. shorten 契约

`finish` 和 `retry` 必须继续共同调用私有 `shorten(String)`：

- null 返回 null；
- code point 数量不超过 512 时原样返回，包括空串、空白、ASCII、BMP、中文、emoji 和既有非法输入；
- 超过 512 时使用 `offsetByCodePoints(0, 512)` 截取前 512 个完整 code point；
- 不拆有效 surrogate pair，不 trim、过滤、替换、大小写折叠或 Unicode 归一化。

## 3. Mapper 状态与条件边界

保留 `finish` 和 `retry` 的全部 SQL、参数、返回值和状态语义：

- `finish` 仍只允许 owner 匹配且源状态为 `PENDING/UNKNOWN`，写入传入目标状态并清理 lease；
- `retry` 仍只允许 owner 匹配且源状态为 `PENDING/UNKNOWN`，写入 `UNKNOWN`、传入 nextRetry、清理 lease；
- `lease_owner` 为 null 或不匹配、非 PENDING/UNKNOWN、id 不存在时仍返回 0，整行不变；遵循原 SQL 比较语义，不新增 lease_until 到期条件或 BINARY owner 比较；
- attempts、orderNo、actionType、operationKey、createdAt 和时间参数语义不变。

本轮不引入新的状态/租约校验或数据库错误吞咽。

## 4. 验收矩阵

1. 真实隔离 MySQL strict/non-strict 下，`finish` 与 `retry` 覆盖 null、空串、空白、短 emoji、511 ASCII + emoji、512/513 emoji、混合文本和 512/513 BMP；结果精确符合 512 code point 契约。
2. 512 个 emoji 完整保存，超长文本截取前 512 个完整 code point，不产生孤立 surrogate；合法短文本原样保存。
3. 真实 `OrderStockActionRecovery` 遇 Stock/RPC 长异常时，错误文本按前 512 code point 保存并进入原 UNKNOWN 退避；后续健康候选继续处理，不改变原身份/key/created/attempts/lease 语义。
4. 非 PENDING/UNKNOWN、owner 不同、`lease_owner` 为 null、id 不存在时，两方法均返回 0 且整行不变；不新增 lease_until 到期门或 BINARY owner 比较，真实 SQL 异常继续原传播语义。
5. 回归 17AV/17AW/17AX 身份与类型门、Action unit、普通订单创建与原库 checksum；隔离库清理完整。

## 5. 明确不承诺

本轮只修库存动作错误文本的 Unicode 截断，不统一其他错误字段、不修历史文本、不改变状态机、租约竞争、消息或跨服务日志编码契约。
