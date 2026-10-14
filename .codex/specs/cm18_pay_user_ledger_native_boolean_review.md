# CM18 16T191：User 账本字段原生 Boolean 解析规格

## 1. 评审结论与范围

本批 **GO**。`UserTccStateVO.setLedgerApplied(Boolean)` 已区分字段存在性，但 Jackson 默认 scalar coercion 会把数字 `0/1`、字符串 `"false"/"true"` 转成 Boolean，令 `ledgerAppliedPresent=true`，从而绕过 T188–T190 的 presence 门。本批只收紧该字段的 JSON token 类型，不改变 public setter、字段、序列化、missing/null 语义或其它 DTO。

## 2. 字段级反序列化契约

仅在 `UserTccStateVO.setLedgerApplied(Boolean)` 上使用字段级 `@JsonDeserialize(using = UserTccLedgerBooleanDeserializer.class)`。新增 deserializer 规则：

- `VALUE_TRUE` 返回 `Boolean.TRUE`；
- `VALUE_FALSE` 返回 `Boolean.FALSE`；
- 其它 token（数字、字符串、array、object、未知 token）先 `skipChildren()`，返回 `null`；
- JSON null 继续返回 `null`，由既有 setter 将 `ledgerAppliedPresent=false`、primitive `ledgerApplied=false`；
- 缺字段不调用 setter，保持既有 `present=false/false`。

deserializer 必须只作用于该 setter，不修改全局 ObjectMapper/Feign 配置，不改变其它 DTO 字段或响应序列化。setter、`@JsonIgnore ledgerAppliedPresent`、显式 Boolean setter 调用和 public JSON 字段保持。

## 3. 业务效果与边界

数字/字符串/数组/对象账本值均变为缺失语义，沿 T188–T190 各调用方原有 blocking/UNKNOWN/202/503 路径处理；不把 malformed scalar 当作显式 false。显式 JSON true/false、null 和缺字段保持既有 presence 与状态语义。只读 UserEvidence 若因此得到 incomplete evidence，继续原阻断，不新增状态机规则。

不改 SQL、Store、Service 状态机、Fence、事务、API 字段、User 端响应或其它消费者；不新增全局 coercion 规则，不扩展数字/字符串转换。

## 4. 验收矩阵

### 4.1 默认 Jackson Unit

新增 `UserTccStateJsonTest` 覆盖：数字 0/1、字符串 false/true、其它字符串、null、缺字段、array、object，以及 array/object 后续字段仍能继续解析。每种 malformed token 的 `ledgerApplied=false` 且 `ledgerAppliedPresent=false`；显式 Boolean 为 present=true；缺失/null 保持既有结果。

同时验证：

- `ledgerAppliedPresent` 不出现在 JSON；
- public Boolean setter 仍可直接传入 true/false/null；
- serializer 不改变既有 boolean 字段；
- 全局 ObjectMapper 对其它 Boolean/DTO 字段仍保留原 coercion；
- 嵌套 token 的 `skipChildren()` 不吞掉后续顶层字段或破坏 DTO 其它字段。

### 4.2 Owned native

- 复用 T188–T190 owned fixture 与实际 Jackson/既有 Store/Mapper；UserClient、各类证据替身和程序化本地事务按原测试边界使用，不启动业务 App、TC/RM 或真实 User HTTP。
- 旧 red 三条路径：rollback CANCELLED+数字0 曾实际 Pay FAILED，Abort TRYING+字符串 false 曾实际 ROLLBACK_REQUESTED，Bridge GET CANCELLED+字符串 false 曾实际终态镜像；修复后均不产生可信终态写入。
- malformed scalar × 三路径必须阻断；Bridge 可沿原 UNKNOWN 诊断路径，Abort/Rollback 沿各自既有 blocking；显式 true/false、missing/null 的既有矩阵继续通过。
- native 只证明真实 Jackson DTO 进入既有本地 Store/Mapper 前的字段语义，不冒称真实 HTTP、Feign、User、TC 或全资金因果。

## 5. 明确边界

本批不修历史 JSON、不改变 User 端生成字段、不迁移数据、不修改全局 ObjectMapper、不收紧其它 DTO、不改变 T188–T190 之外消费者的业务规则。Malformed ledger 值被视为缺证，后续各服务是否返回 UNKNOWN、202、409 或 503 完全沿既有调用方契约。
