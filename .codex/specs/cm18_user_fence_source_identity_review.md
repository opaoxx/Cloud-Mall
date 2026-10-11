# CM-18 16T15：User Fence 来源身份只读证明评审

## 1. 结论与范围

结论：**GO，限定在既有 `UserTccStateQuery.read` 对返回 Fence 行增加 exact source identity 门；不改变 Fence SQL 候选、RM/TC、写入、API、DDL、资金或历史 TTL 语义。**

当前 `findByBranch(xid,branch_id)` 读取按数据库 collation 候选后只检查 action/status，返回行的 `xid`/`branchId` 未二次核对。实际 `cloudmall_user.tcc_fence_log.xid` 为 `utf8mb4_0900_ai_ci`、NO PAD，生产证据覆盖大小写/重音 alias；尾随空格候选只在 owned legacy `utf8mb4_unicode_ci` PAD SPACE 数据库演练，不外推为原库行为。

## 2. 最小查询门

在既有 `fence != null` 分支中，除官方 actionName/status 校验外，必须同时要求：

```text
Objects.equals(fence.xid, xid)
Objects.equals(fence.branchId, attempt.branchId)
```

任一 NULL、异源 XID、大小写/重音候选返回行或 branch 不同，统一原 `USER_TCC_STATE_CONFLICT`/409；owned PAD SPACE 演练中若尾随空格返回候选，同样由 exact 门拒绝。生产 NO PAD 下尾随空格未匹配时仍按“无 Fence”原语义处理。数据库 SQL 仍按现有候选读取，不在 WHERE 增加 BINARY 过滤，不把候选异常隐藏为“无 Fence”。

Fence 缺失时保持原语义：TRYING 仍 409，已知终态缺 Fence 仍保留既有过期/历史 TTL 兼容规则。官方 actionName 与 status 映射保持；本批不清理、补写或修复 Fence。

## 3. 不变边界

- 只增加原 Query 的两个返回行字段比较；保留原 RR、SELECT、Fence 缓存禁用、状态机、金额/快照门和异常传播。
- 不修改 `TccFenceLogMapper.findByBranch` SQL、主键、索引、DDL、RM/Fence 写入、TC、HTTP/Feign、flags 或资金状态。
- 16T9–16T14 取证遇到该 409 时沿既有 source conflict/peer conflict 分类，不把错误 Fence 当作缺行或成功证据。

## 4. 验收矩阵

- Unit：known TRYING/CONFIRMED/CANCELLED 的 wrong/null xid、wrong/null branch、case/accent/trailing alias 均 409；合法 Fence 继续原结果；TRYING 缺 Fence 409，terminal 缺 Fence 保留历史语义。
- Owned MySQL：仅 owned Fence 字段演练 collation 候选，case/accent alias 对应生产 NO PAD 证据，尾随空格仅用 owned legacy PAD SPACE 对照；四个现有路由中，原 User state GET 只验证固定 409/code 并保持既有缓存契约，其余三个 User 诊断 GET 验证固定 409/code/no-store。原库 checksum、资金/Fence 行、无写入和资源清理保持。本批不声明 Pay Controller 的 HTTP 409；Pay 侧既有消费映射沿其原错误语义处理。
- 保留 User 旧 state/http/T9–T14 回归，原 Fence SQL 候选不因本批改为 BINARY WHERE 或过滤异常。

## 5. 交付边界

本评审只冻结 Fence 返回来源身份门，不扩展 Fence 生命周期、TC 决议、RM、资金写或 CM18 主项闭环。
