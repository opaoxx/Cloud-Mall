# CM18 16T192：User 身份字段原生正 Long 解析规格

## 1. 评审结论与范围

本批 **GO**。`UserTccStateVO.userId` 与 `branchId` 当前为 public `Long`，Jackson 默认允许小数截断和字符串数字转换；例如 `7.9` 可变成7、`"31"` 可变成31，可能让远端身份或分支错误匹配 Pay 锚点。本批只收紧这两个 User 状态响应字段的 JSON token 类型和正数域，不改其它 DTO、字段赋值、序列化或业务状态机。

## 2. 字段级反序列化契约

仅在 `UserTccStateVO.userId` 与 `branchId` 字段添加字段级 `@JsonDeserialize(using = UserTccPositiveLongDeserializer.class)`。deserializer 规则：

- 仅接受 `VALUE_NUMBER_INT`，读取为 `BigInteger`；数值必须 `> 0` 且 `<= Long.MAX_VALUE`，再返回 `longValue()`；
- float、字符串、boolean、array、object、未知 token 先 `skipChildren()`，返回 `null`；
- JSON null 和字段缺失保持 `Long == null`；
- 不改变 public Long 直接赋值、JSON integral 序列化、字段名或其它 DTO 的全局 Jackson coercion。

两个字段共用无状态 deserializer；不能通过全局 ObjectMapper、Feign 配置或 primitive 转换绕过。`ledgerApplied` 的 T191 字段级布尔解析保持不变。

## 3. 业务边界

非原生整数或非正/越界身份字段变为 null，并沿各调用方已有 tuple/branch/证据失败路径阻断；不新增错误码、状态、API 或金额规则。显式正整数 1、7、`Long.MAX_VALUE` 保持原行为。其它 User 响应字段（amount、state、keys、ledger presence）和 User 端生成/序列化契约不改。

## 4. 验收矩阵

### 4.1 Unit

- `UserTccStateJsonTest` 覆盖 userId/branchId 原生整数边界 1、7、`Long.MAX_VALUE`；0、负数、越界、float、文本、boolean、null、array、object 均返回 null。
- 覆盖嵌套容器 `skipChildren()` 后兄弟字段仍正常解析；public Long 直接赋值及 integral JSON 序列化保持；全局 ObjectMapper 对普通 Long 的 `7.9`/`"31"` coercion 保持，证明未改全局配置。
- T191 的 ledger 原生布尔、missing/null/presence 断言全部保持；Malformed identity 与 ledger presence 独立验证。

### 4.2 Owned native

- 复用 T188–T191 owned fixture、真实 Jackson 与既有 Store/Mapper；UserClient/证据替身和程序化事务沿各测试既有边界，不启动业务 App、TC/RM 或真实 User HTTP。
- 旧 red：rollback CANCELLED 的 branch `31.9` 曾变成31并实际 Pay FAILED；Abort TRYING 的 userId `7.9` 曾变成7并实际提交 ROLLBACK；Bridge GET CANCELLED 的 branch `7.9` 曾变成7并实际终态镜像。修复后四类 malformed identity 在 rollback/Abort/Bridge 入口均阻断，不写可信结果。
- 修复后覆盖 userId/branchId fractional、text、其它非原生 token；ledger 使用原生 false 并明确 `ledgerAppliedPresent=true`，避免把本批身份缺证与 T191 混淆。既有正整数矩阵、显式 true/false、missing/null 回归保持。
- native 只证明 User JSON 进入现有本地证据/Store 前的身份解析，不冒称真实 User/HTTP/TC 或完整资金因果。

## 5. 明确边界

本批不修改其它 DTO、User 端 API、全局 Jackson/Feign 配置、金额/状态/账本字段、重复键、SQL、事务或资金状态机；不修历史字符串/小数响应、不扩展“可接受数字”到科学计数或文本协议。字段级 null 结果的具体 blocking/UNKNOWN/409/503 继续沿调用方既有契约。
