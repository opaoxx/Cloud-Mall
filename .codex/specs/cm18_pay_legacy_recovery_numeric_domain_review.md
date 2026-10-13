# CM-18 16T160：Pay Legacy recovery numeric domain

## 1. 结论与范围

GO。T159 只证明两表 pair 相等；双方 userId/amount 同为非正时仍进入 due candidate/claim，坏行占窗口并在 Service 后置 409。

本批只在 recovery candidate/claim 的 payment 条件加入非正 numeric domain gate。

## 2. Three SQL predicates

在 `findDue`、`findDueAfter` payment JOIN/WHERE 及 `claim` payment EXISTS 中追加：

- `payment.user_id > 0`；
- `payment.amount > 0`。

与 T159 pair exact 条件组合，先过滤非正 payment 事实再 LIMIT/afterId/claim。保 BIGINT/DECIMAL 既有容量、数值比较/尾零、phase CI、mode/key/time/order/参数/SET；不增加字符串域、scale/capacity、snapshot 或 fencing 检查。

## 3. 保持项与验收

不改 Service `requireLegacyRoute`、其它 SQL/API/TCC、claim 后 source drift 或合法双方漂移。共同非法字符串/类型迁移/其它域仍非本批。

- Direct claim：两表共同 user 0/负/Long.MIN、amount 0/负（含 DECIMAL(18,2) 负边界值）均 claim0/full facts；本批未测试容量越界写入或新增容量门。
- Process：预 route 后双方 coherent 非正 claim0、COMMON_CONFLICT/409、无 lease/attempt/RPC；
- Scan：100 bad numeric 头 + healthy 尾先过滤再 LIMIT，facts 不变；healthy user/amount max/尾零与 T158/T159 保持。

## 4. 交付边界

实现仅允许修改 `findDue`、`findDueAfter`、`claim` 的 payment numeric predicates 与 JavaDoc/测试；不得改其它状态/域或业务流程。
