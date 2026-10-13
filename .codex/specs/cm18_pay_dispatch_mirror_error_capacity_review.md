# CM-18 16T171：Pay dispatch mirror error capacity

## 1. 结论与范围

GO。`PayTccDispatchPersistence.mirror` 原样把 lastError 绑定到 VARCHAR(512)，长 Unicode/ASCII error 在 MySQL strict mode 下可中断 mirror 事务。

本批只在 mirrorState 调用处复用 PayErrorMessages code-point truncation；不改变 Bridge reason format 或状态协议。

## 2. Exact change

mirror 调用 Mapper 时仅将 `lastError` 参数替换为 `PayErrorMessages.truncate(lastError)`：null/empty/最多512完整 Unicode code points，不 normalize/fallback。保 `mirrorState` SQL、expected/current state 覆盖、branchId、terminal/PREPARED/TRY_ACCEPTED guard、REQUIRES_NEW、锁序、updatedAt、Legacy/T170。

不宣称当前 Bridge 已产生长异常，不改公共 API/DDL/错误原因格式。

## 3. 验收

- Owned bounded native mapper/TransactionTemplate：ASCII513、511ASCII+emoji-tail、null/BMP/512/513/emoji/mixed；真实 CHAR_LENGTH/OCTET_LENGTH 与完整 dispatch facts/state/branch/updatedAt 保持。
- reject terminal/branch drift/PREPARED/TRY_ACCEPTED unknown no-write，Mapper=0 保原 Runtime。
- Test 等级为真实 owned MyBatis mapper + local transaction，不启动 SpringBoot/TC/MQ；原 PayRecord/intent facts/fingerprint 与资源 cleanup 保持。
- 保留 Legacy Unicode/TCC 时序观察，不宣称真实 TC 链路。

## 4. 交付边界

实现仅允许 mirror call 参数截断、Pay util import/JavaDoc 与对应 unit/native tests；不得改 SQL、事务、状态机、Bridge、API/DDL。
