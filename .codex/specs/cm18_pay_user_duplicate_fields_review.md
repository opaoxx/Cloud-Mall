# CM18 16T194：User TCC 重复字段拒绝规格

## 1. 评审结论与范围

本批 **GO**。Jackson 默认对重复 JSON 字段采用最后值覆盖，导致同一 User 响应可同时携带矛盾的 `ledgerApplied`、身份、状态等值，最后一个值可能绕过 T191–T193 的单值类型门。本批只增加 `UserTccStateVO` 根对象的重复字段检测，不改变单值 token 规则、public Java API、序列化或业务状态机。

## 2. Class 级 decoder 契约

将 `UserTccStateVO` 改为 class 级 `@JsonDeserialize(using = UserTccStateDeserializer.class)`；移除七个文本字段、两个 Long 字段和 ledger setter 上的 field/setter 级 parser 注解，但保留 `@JsonSetter("ledgerApplied")` 与 `@JsonIgnore ledgerAppliedPresent`。新 decoder 是无状态、带 public 构造器与标准 JavaDoc 的 `JsonDeserializer<UserTccStateVO>`。

根对象规则：

1. root JSON `null` 仍返回 null；非 object root 交给 `context.handleUnexpectedToken`，不新增 trailing-root 策略。
2. 使用局部 `HashSet<String>` 仅跟踪以下 10 个已知字段：`userId`、`payNo`、`orderNo`、`amount`、`operationKey`、`ledgerKey`、`xid`、`branchId`、`state`、`ledgerApplied`。
3. 第一次出现已知字段时，按 T191/T192/T193 原规则解析：正 Long、原生 Boolean、原生 String；错误 token 消费完整值并产生 null/缺证语义。
4. 第二次出现同一已知字段，无论两值相同、不同、null、坏类型、先坏后好或先 null 后好，均标记 ambiguous，消费完整值但最终返回 null；escaped 字段名解码后与普通字段名相同也算重复。
5. `ledgerAppliedPresent` 是内部 `@JsonIgnore` 字段：保持既有忽略语义，不接受其值伪造 presence，也不把它加入 10 字段重复集合。
6. 其它未知字段沿 `context.handleUnknownProperty` 处理，保留 `FAIL_ON_UNKNOWN_PROPERTIES` 与 custom problem handler 行为；未知键重复不额外限制，nested object/array 中的名字不计入 root seen 集合。

decoder 可复用 T191/T192/T193 的三个无状态 parser/helper，但必须保持它们的 token、skipChildren、null 和数值边界语义；不得借全局 ObjectMapper coercion。遇到重复已知字段，即使值等价，也不能向 VO 写入可信最终值。

## 3. Java/API/业务边界

VO public 字段、Boolean setter、直接 Java 赋值、序列化字段和 JSON 名称保持。decoder 只影响 JSON 接收；全局 ObjectMapper、Feign、其它 DTO、Service、SQL、事务、状态机和 TCC 不改。重复已知字段最终以缺证/反序列化失败语义进入既有调用方：Rollback/Abort 沿原 202，Bridge 沿原 UNKNOWN/无 branch/无 POST 诊断；不新增错误码或专用 API。

## 4. 验收矩阵

### 4.1 Unit

- 10 个已知字段逐一覆盖同值/异值重复、先 null 后有效、先坏后有效、escaped name 重复；任何重复都不产出可信 VO。
- 覆盖未知字段兼容、未知重复、nested unknown 不污染 root seen、root null、非法 root、截断 JSON，并保持原 Jackson error/handler 行为。
- 验证 `ledgerAppliedPresent` 不能通过 JSON 内部字段伪造；显式 Boolean、正 Long、原生文本单值契约保持；public 程序化赋值与序列化保持。
- T191–T193 既有 typed token 矩阵继续回归，避免 class decoder 造成字段级语义漂移。

### 4.2 Owned native

- 复用 T188–T193 owned helper、真实 Jackson 与现有本地 Store/Mapper；User/证据替身和程序化事务按既有边界使用，不启动业务服务、TC/RM 或真实 HTTP/Feign。
- 旧 red：`ledgerApplied:true,false` 导致 Pay FAILED，`userId:8,7` 导致 Abort ROLLBACK_REQUESTED，`state:CONFIRMED,CANCELLED` 导致 Bridge CANCELLED/学习 branch；均由重复已知字段最后值覆盖造成。
- 修复 control：10 个已知字段各自等值 duplicate × public/claimed Rollback、Abort、Bridge GET；Rollback/Abort 沿原 202 且六表无写，Bridge 沿原 UNKNOWN、无新 branch、无 POST。合法单值、missing/null、T191–T193 typed 矩阵保持。
- native 只证明根 JSON decoder 与既有本地入口，不冒称真实 User/Feign/HTTP/TC 或全局资金因果。

## 5. 明确边界

本批不修历史 JSON、不改变未知字段策略、不限制未知键重复、不改变数组/对象嵌套规则之外的全局 parser、不新增字段/表/SQL/状态/API。重复字段检测只覆盖当前 10 个根已知字段；其它服务和接收 DTO 的重复键语义仍保持原状。
