# CM18 16T203：Pay TCC receipt owner 精确唯一键规格

## 1. 评审结论与范围

本批 **GO**。rejected 分支的 `findOwners` 是四个不可变唯一键的 OR 查询，数据库 CI 排序规则可能返回仅大小写别名匹配的 owner；若该 owner state/branch 合法，LocalResource 会错误 ACK。本批只在既有 owner 循环中增加至少一个 Java 精确唯一键门，不把既有 OR 语义改成 AND，也不要求完整 payload 相等。

## 2. 精确唯一键门

增加共享私有纯谓词 `hasExactOwnerKey(owner, request, context)`，要求以下四个条件至少一个成立：

```text
Objects.equals(owner.payNo, request.payNo)
Objects.equals(owner.xid, context.xid)
Objects.equals(owner.operationKey, "tcc-debit:" + request.payNo)
Objects.equals(owner.ledgerKey, request.payNo)
```

owner 进入该门后仍按 T201 的 `hasValidStateAndBranch(owner)`、排除 CLAIMED、state advanced、与当前 context 分支不同等既有条件判断。四键均不精确匹配时不得提前 ACK；只要一个键精确匹配，保留原 OR 语义，即使其它字段不同或 owner 是合法不同请求，也不新增 payload/资金因果校验。

不修改 `findOwners` SQL、Fence/status/count 查询、锁序、receipt callback、Store、prepare/commit、事务、API 或状态机；纯 predicate 不新增显式 Mapper 调用，owner gate 查询顺序/次数保持，不改变 T201/T202 Fence 身份门。纯 alias owner 被拒后仍可进入原 verify callback，因此不得把“不新增查询”理解为所有输入的整条 callback SQL 总数与旧 ACK 完全相同。

## 3. 验收矩阵

### 3.1 Owned public rollback/query

- 复用 T201/T202 official Fence static datasource/transactionTemplate scope，使用真实 Receipt Mapper/Store/Callback/LocalResource 与程序化本地事务；保存并 finally 恢复 static，记录空队列，不启动项目服务、TC/RM、新监听或修改原库。
- red：仅 payNo 大小写 alias 或仅 xid 大小写 alias，其余三个唯一键为 foreign，owner branch=8、SUSPENDED Fence=7；旧实现 ACK，修复后 false、Pay/Intent/Dispatch/Receipt/Fence facts 不变。
- 四键分别覆盖“仅 CI alias+其它 foreign”与“四键全部 case alias”；全部不精确时拒绝。正控制为每个唯一键单独 exact、其它键 foreign，沿原 OR 语义可 ACK。
- 原 CLAIMED empty-cancel、35 bad owner、12 old-good、T202 Fence alias/missing/terminal 控制保持；数据库 NOT NULL 字段的 null、金额和完整字段坏数据不在本批扩展。

### 3.2 Unit/query 边界

- 通过公开 rollback precondition/query 矩阵验证四键 OR、Java 精确比较、owner state/branch/Fence/count 短路与 callback 事务边界；不反射 private helper、不用静态 Mock 镜像实现。
- 不把不同 payload 的合法唯一键冲突强行视为完整 payload conflict；本批只拒纯 CI alias 命中、保留至少一个精确 owner key 的既有行为。

## 4. 明确边界

本批不增加 SQL/锁/显式 Mapper 调用、修历史 receipt、不做四字段全等、source/Fence/资金因果闭环，不改变 owner OR 查询、回调、Store、prepare/commit、API、TC/RM 或全局唯一键。native 计数仅覆盖 LocalResource/Fence/receipt wrapper 门，不代表 Store 原 callback 的整条 SQL 计数；只证明本地 exact-key 门，不宣称真实并发 fencing。
