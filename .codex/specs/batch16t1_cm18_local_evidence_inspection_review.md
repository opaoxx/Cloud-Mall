# CM-18 16T1：Pay 本地资金证据只读检查评审

## 1. 结论与位置

结论：**GO，先实施一个只读的 Pay 本地证据检查切片；它属于 CM18 M1，不代表 CM18 闭环，也不提前进入 CM-20/CM-21。**

本切片只回答 Pay 自有数据库中已经持久化了哪些协议和资金锚点，不查询 User、Order、Stock、TC 或 MQ，不重放任何资金动作。它用于把 `BEGIN_UNKNOWN`、无 XID、局部事实缺失和本地冲突稳定分类，供后续人工补证和真实四服务故障验收使用。

## 2. 内部 API 契约

新增受保护的内部 GET：

```text
GET /api/internal/payments/{payNo}/tcc-reconciliation
```

### 2.1 请求门

- 配置 `cloudmall.pay.tcc-reconciliation.enabled=false` 默认关闭；关闭时返回既有受控禁用错误，且在任何数据库访问前结束请求。
- `payNo` 只接受 ASCII 字母、数字、`_`、`-`，长度 1..64；不接受批量、通配符、分页或任意 SQL 条件。
- 现有 callback token 与明确 caller 标识 `cm18-reconciliation` 必须同时通过校验；任一失败在执行任何数据库查询前返回既有鉴权错误。
- Gateway 不开放该内部路径；不增加公网路由，不允许前端 Bearer Token 访问。该检查不要求开启业务 TCC 开关。

Controller 只做绑定和委托；`@Service` wrapper 必须按 enabled、callback token、caller、payNo 顺序完成所有入口校验，再调用独立的 `ReadService`。只有 ReadService 在校验通过后进入 `@Transactional(readOnly=true,isolation=REPEATABLE_READ)`，防止事务或连接在鉴权前建立。

### 2.2 响应 VO

成功响应只包含本地快照和分类：

```json
{
  "scope": "PAY_LOCAL_ONLY",
  "automaticActionsAllowed": false,
  "externalSystemsNotQueried": ["USER", "ORDER", "STOCK", "TC", "MQ"],
  "classification": "BLOCKED_LOCAL_EVIDENCE",
  "reasonCodes": ["EXECUTION_MISSING"],
  "snapshot": {
    "payNo": "...",
    "mode": "TCC",
    "paymentState": "PENDING",
    "orderNo": "...",
    "userId": 7,
    "amount": "10.00",
    "dispatch": {"present": true, "state": "PREPARED"},
    "receipt": {"present": true, "state": "TRYING"},
    "execution": {"present": false},
    "fence": {"present": true, "state": "..."},
    "legacyIntent": {"present": false},
    "errorPresence": {"dispatch": false, "receipt": false, "execution": false}
  },
  "identifiers": {
    "xidSha256": "...",
    "branchIdSha256": "...",
    "operationKeySha256": "...",
    "ledgerKeySha256": "..."
  }
}
```

`scope` 固定为 `PAY_LOCAL_ONLY`，`automaticActionsAllowed` 固定为 `false`。响应必须包含采集时间 `collectedAt`、固定 `reasonCodes` 和状态白名单字段。XID、branch、operation key、ledger key 只输出字段对应的 SHA-256 指纹，不输出原值；不输出 credentials、token 或 `last_error` 文本，只输出非敏感错误是否存在。金额保持 JSON 字符串。

Pay 记录查询结果分三类处理：零条候选返回既有 `NOT_FOUND`/404，不查询其他五类来源；超过一条候选或 payNo 别名/身份不匹配返回身份冲突/409，不暴露其他支付记录。缺表、连接失败或其他数据库不可用返回固定 `PAY_TCC_RECONCILIATION_UNAVAILABLE`/503，不返回 raw 数据库错误、不创建表。其他来源缺失在已确认 payment 身份后才进入本地阻塞分类。

本地快照完整也只能返回 `BLOCKED_EXTERNAL_EVIDENCE`，不得返回 `TCC_COMMITTED`、`TCC_CANCELLED`、成功、回滚或可扣款结论，因为本切片没有 User/Order/Stock/TC/MQ 事实。

## 3. 只读实现边界

- Service 使用独立 ReadService 的 `@Transactional(readOnly=true, isolation=REPEATABLE_READ)`，在同一已提交快照中调用六个专用只读 Mapper：`pay_record`、`pay_tcc_execution`、`pay_tcc_dispatch`、`pay_tcc_receipt`、Pay Fence、`pay_debit_intent`。其中前五类沿现有无锁 MyBatis SELECT 入口，`PayDebitIntentMapper` 是 JdbcTemplate Mapper，复用其无锁 `findByPayNo`；支付记录新增一个只读 `LIMIT 2` Mapper 方法。Service 不持有 SQL、JdbcTemplate 或 QueryWrapper。
- SELECT 按 `payNo` 精确匹配，禁止 `FOR UPDATE`、claim、lease、Store.read、`bridge.recover`、coordinator RPC、TC 查询或任何远程调用。候选 payment 只允许有限 `LIMIT 2` 以发现唯一性/冲突，不做无界扫描。
- 不调用会 observe、镜像写入、注册 branch、推进决议或触发恢复的既有 Store/bridge/coordinator 方法；本切片不能通过复用高层 recovery API 产生隐式副作用。
- 不新增表、视图、列、索引、资金写、重试、Rabbit 操作、TC 操作或 schema migration。诊断结果只在响应中生成，日志仅记录安全的 payNo 和分类。
- 任何已确认 payment 后的本地事实缺失、模式冲突、载荷不一致或 XID/branch 绑定缺失必须给出固定 reason code，不能用 null/空集合伪造合法状态；payment 零行/多行和数据库不可用按 2.2 的 HTTP 错误处理，不伪造分类快照。

## 4. 分类契约

本切片只允许以下分类：

| classification | 使用条件 | 固定 reason code 示例 | 自动动作 |
| --- | --- | --- | --- |
| `LEGACY_NOT_INSPECTED` | Pay 持久模式为 LEGACY；本接口不替 Legacy 资金下结论 | `LEGACY_PROTOCOL` | 无 |
| `EXECUTION_MISSING` | payment 身份已确认，dispatch/receipt 锚点存在但 execution 缺失 | `EXECUTION_ROW_MISSING` | 无 |
| `BLOCKED_BEGIN_UNKNOWN` | execution/dispatch 需要 XID，但 XID 缺失或 BEGIN 结果未知 | `BEGIN_XID_MISSING` | 无 begin、Try 或新 branch |
| `LOCAL_CONFLICT` | 本地 pay/mode/tuple/state/XID/branch/operation/ledger 互相冲突 | `LOCAL_TUPLE_CONFLICT` | 无资金写入 |
| `BLOCKED_LOCAL_EVIDENCE` | payment 身份已确认，但其他本地来源缺失、不可读或无法组成一致快照 | `LOCAL_EVIDENCE_MISSING` | 受控阻塞 |
| `BLOCKED_EXTERNAL_EVIDENCE` | Pay 本地快照可读，但尚未查询外部资金/订单/库存/TC/MQ 事实 | `EXTERNAL_EVIDENCE_NOT_QUERIED` | 受控阻塞 |

分类按 `LOCAL_CONFLICT` → `BLOCKED_BEGIN_UNKNOWN` → `EXECUTION_MISSING`/`BLOCKED_LOCAL_EVIDENCE` → `LEGACY_NOT_INSPECTED` → `BLOCKED_EXTERNAL_EVIDENCE` 的优先级计算；冲突优先于缺失，缺 XID 的 BEGIN_UNKNOWN 不能被 execution 缺失覆盖。这些分类不是资金终态。特别是 `BLOCKED_BEGIN_UNKNOWN` 和 `BLOCKED_EXTERNAL_EVIDENCE` 不得转换为提交、回滚、退款或失败；后续补证必须复用原 payNo、原 XID/branch 和原载荷。状态和错误文本只允许白名单展示，未知状态不回显原始值。

## 5. 验收矩阵

- Unit：disabled、鉴权失败、caller/token 任一缺失、payNo 非法均在任何 Mapper 调用前拒绝；验证 `automaticActionsAllowed=false`、外部系统列表固定、原始 XID/branch/operation/ledger 字段不出现在响应或普通日志，响应只含对应 fingerprint 字段。payNo 本身允许按业务标识显示，不能以“响应全文不得出现与 ledgerKey 相同字符串”作为断言。
- Unit：六个本地来源分别缺失、BEGIN_UNKNOWN/无 XID、tuple/state 冲突、LEGACY 完整行、TCC 本地完整行均返回固定分类和 reason code；不调用 Store、bridge、coordinator、TC 或远程 client。
- 真实 MySQL 最小隔离库：六表 owned 数据库在检查前后 checksum、行数、租约、attempts、决议、资金字段完全一致；已提交和已回滚本地快照都只能得到本地分类或 external blocked，不能输出 TCC 终态。
- SQL 缺表/连接错误沿 503 原渠道；检查结束清理 owned 库和诊断日志，不启动业务应用、不新占端口、不接 Gateway/TC/MQ。

## 6. 后续边界

16T1 完成只表示 M1 的本地取证入口和受控阻塞基础可用。它不替代外部 Collector、人工补证、四服务真实故障矩阵、发布切换或回退验收；CM18 只有在 closure plan 的 M1–M4 全部通过后才可完成。
