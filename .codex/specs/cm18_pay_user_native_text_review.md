# CM18 16T193：User TCC 响应原生文本字段规格

## 1. 评审结论与范围

本批 **GO**。`UserTccStateVO` 的七个 public `String` 字段当前受 Jackson scalar coercion 影响，数字、boolean 等可被转换为文本；尤其 numeric `amount` 可能通过 `BigDecimal` 比较匹配原金额，进而影响 Rollback、Abort 或 Bridge 证据判断。本批只收紧 User 接收侧文本 token，不改变 public String 赋值/输出、全局 ObjectMapper/Feign、T191/T192 字段解析或业务状态机。

字段范围：`payNo`、`orderNo`、`amount`、`operationKey`、`ledgerKey`、`xid`、`state`。

## 2. 字段级反序列化契约

七个字段统一使用字段级 `@JsonDeserialize(using = UserTccNativeTextDeserializer.class)`。无状态 deserializer 规则：

- 仅 `VALUE_STRING` 返回 `parser.getText()`，逐字符保留，不 trim、normalize、大小写折叠或增加金额词法；
- 数字、boolean、array、object、其它 token 先 `skipChildren()`，返回 null；
- JSON null 和字段缺失保持 null；
- public String 直接赋值、文本输出和字段名保持；
- 不修改全局 ObjectMapper/Feign 或其它 DTO 的 scalar coercion。

因此数字 amount `10`、`10.00`、指数文本等只有在 JSON token 本身为字符串时才按原业务字符串进入 `BigDecimal`；数字 token 不再伪装成文本金额。其它文本字段的业务判断和错误优先级不变。

## 3. 业务与兼容边界

malformed text token 变为 null，沿既有 tuple、金额、key、state、Presence 和证据阻断路径处理；不新增错误码、状态、API、SQL、DTO 字段或金额格式规则。合法空串、空白、Unicode、数字文本、指数文本均逐字保留，由原业务逻辑决定是否合法。

T191 ledger Boolean、T192 正 Long 身份字段继续使用各自字段级 deserializer；不相互覆盖注解或改变 missing/null 语义。

## 4. 验收矩阵

### 4.1 Unit

- 覆盖七字段的 int、float、boolean、array、object、null、missing，结果均为 null；合法字符串包括空串、空白、Unicode、数字文本和指数文本，逐字相等。
- 覆盖 nested `skipChildren()` 后兄弟字段解析、public String 赋值、string/null 序列化以及普通 String 全局数字/boolean coercion 保持。
- 既有 T191/T192 typed Boolean/Long、presence、边界和原业务判断回归不变。

### 4.2 Owned native

- 复用 T188–T192 owned helper、真实 Jackson 与既有本地 Store/Mapper；UserClient、各类证据替身和程序化事务按既有边界使用，不启动业务服务、TC/RM 或真实 User HTTP。
- 旧 red：numeric amount int/decimal 分别在 Rollback、Abort、Bridge 路径造成 Pay FAILED、ROLLBACK_REQUESTED、CANCELLED 镜像；修复后两个 numeric amount 形态 × public/claimed Rollback、Abort、Bridge GET 均阻断，Rollback/Abort 沿既有 202，Bridge 沿原 TRY_UNKNOWN/无 branch/无 POST 诊断，不写可信结果。
- native 只验证 amount numeric 形态及三条路径；其余六个 String 字段的 malformed 类型由 Unit 覆盖，不冒称 native 已覆盖所有七字段。合法 string amount 与原三路径矩阵保持。
- 原库只读指纹、owned UUID 清理、池关闭和 T191/T192 历史证据保持。

## 5. 明确边界

本批不改变 User 端响应生成、全局 Jackson、其它 DTO、金额 scale/precision 规则、重复键、SQL、事务、TCC 或资金状态机；不修历史错误类型、不规范化文本、不把 malformed 值转为业务错误文本。各调用方对 null 的 blocking/UNKNOWN/202/409/503 行为沿既有契约。
