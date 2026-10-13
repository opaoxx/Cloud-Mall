# CM-18 16T176：Pay evidence nullable states

## 1. 结论与范围

GO。PayTccExecutionEvidence 对 pay.state、present Fence.status、present User.state 直接使用 Set.contains；NULL 可 NPE，Coordinator catch 会把 Pay/Fence malformed 载荷降为 UNKNOWN/observe，而正常非法状态应 conflict。

本批只补 nullable guards，不扩状态集合、SQL/表/历史/DTO/Coordinator/RR/金额或其它身份来源。

## 2. Exact guards

- pay 非 CLAIMED 分支在任何 Set/state 使用前要求 `pay.state != null`；NULL 沿既有 execution conflict 409；
- present Fence 在 action/status predicate 前要求 `fence.status != null`；TRYING=1、CONFIRMED=2、CANCELLED=3/4 映射保持；
- `user == null` 或 `user.state == null` 仍返回 UNKNOWN；非 null 非接受白名单状态也仍 UNKNOWN，不扩大集合。

Fence null cleanup、pay canonical states、amount/tuple/branch/T173/T174 identity/error semantics 保持。

## 3. 验收边界

- Evidence unit：pay state NULL、CANCELLED Fence status NULL 应 conflict 且 JSON/输入字段不变；User state NULL/unsupported 正常 UNKNOWN，不 NPE；canonical states/Fence valid/invalid controls。
- Coordinator recover：两 malformed payload 无 observe/no TC/no receiptTry；缺 User/NULL user state 保持 UNKNOWN。
- DB NOT NULL 字段的 NULL 仅通过 Mapper mock 载荷模拟，不宣称生产表可 NULL；不做 native/service/DDL 改动。

## 4. 交付边界

实现仅允许新增三个 nullable guards 与必要 JavaDoc/unit tests；不得修改状态集合、全局异常、Coordinator flow 或数据库。
