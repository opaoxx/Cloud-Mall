# CM-20260924-20：库存动作状态守卫评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderStockActionMapper` 对 action 状态的 collation 误匹配和 finish 目标值缺少白名单的问题。**

当前 SQL 的 `status IN ('PENDING','UNKNOWN')` 受 CI/AI/PAD SPACE 影响，存量小写、重音或尾空格状态可能被认作合法源状态；`finish` 还允许任意目标字符串写入并清理 lease。读取 helper 也可能直接返回坏/null status，混淆 paymentState 授权。

本轮只增加 SQL residual 状态守卫、finish target 门和读取状态白名单，不修改 schema、状态枚举类、写入状态机、恢复器调用、API/Feign、租约、计数、时间或历史行。

## 2. SQL source 状态守卫

findDue、claim、finish、retry 保留原 `status IN ('PENDING','UNKNOWN')` 候选条件以维持索引访问，再追加精确 residual 条件：

```sql
AND BINARY status IN (BINARY 'PENDING', BINARY 'UNKNOWN')
```

该 residual 必须位于 findDue 的 LIMIT 前，使小写、重音、尾空格或其他坏状态不会占用候选窗口；direct claim/finish/retry 对坏源状态返回 0，整行保持。不得仅用 BINARY 替换原候选 IN，避免改变原索引候选计划。

## 3. finish target 门

`finish` 保留现有 owner validOwner 门优先；owner 合法后、时间规范化和 JDBC 前，仅允许目标 status 精确为：

- `SUCCEEDED`
- `CONFLICT`

null、空、blank、大小写/尾空格变体、PENDING、UNKNOWN、其他字符串均返回 0，不执行 SQL、不清 lease、不改 attempts/错误/时间。合法 target 继续沿 AZ lease、BB attempts、原更新 SQL 和返回值。

## 4. 读取状态完整性

17BG 的 `readStatusForOrder` tuple helper 在 order/type/key/expectedType 门之后，再要求存储 status 精确属于：

- `PENDING`
- `UNKNOWN`
- `SUCCEEDED`
- `CONFLICT`

status null、大小写/重音/尾空格变体、其他值返回非 null `UNKNOWN`，不回退 Legacy、不写修复。真实表 `status NOT NULL` 的 null 仅用 unit seam 覆盖。无候选仍返回 null；旧签名和 expected overload 均复用同一读取守卫。

## 5. 保持与排除边界

- 保留原 status 候选 IN、索引候选、LIMIT、lease `<`/`>`、owner BINARY、attempts、时间、UUID、error、状态写入和事务语义。
- 不新增状态、schema、enum class、API、SQL 往返、repair、动作方向/key/字段/JSON 额外守卫。
- 不改变正常合法 PENDING/UNKNOWN/SUCCEEDED/CONFLICT 读取或合法 finish/retry；不修历史坏状态。
- 不扩展到其他服务、订单状态机、Pay/Stock、消息或数据库迁移。

## 6. 验收矩阵

1. 真实默认 CI/AI/PAD 表验证低 id 坏状态（小写、重音、尾空格等）不会通过 findDue LIMIT，后续合法行可扫描；direct claim/finish/retry 对坏源状态返回 0 且全行不变。
2. finish target 非法值覆盖 null、空、blank、大小写/尾空格、PENDING、UNKNOWN、宽字符串；owner 合法时 SQL 前零 JDBC，owner 非法仍保持 owner 门优先。
3. 合法 source `PENDING/UNKNOWN` 与 target `SUCCEEDED/CONFLICT` 在有效 lease 下正常完成；过期/接管/AZ/BB/时间 BK 回归保持。
4. 17BG 读取 tuple 合法但 status 坏/null 时返回 UNKNOWN、不查 Stock、不进入 Legacy；无 action 仍 null→17BD；真实 SQL 失败继续传播。
5. 回归 17BI/BJ/BK/BL、AV/AW/AX/AY/AZ/BA/BB、Reader、Recovery、Creation IT/unit、checksum/fixture 清理；不宣称历史坏状态自动修复或资金事故演练。
