# CM-18 16T172：Pay TCC execution observation write guard

## 1. 结论与范围

GO。`PayTccExecutionStore.observe` 忽略 `updateObservation` affectedRows 后 readback；SQL state NOT IN 受 CI/PAD collation 影响时，Java exact terminal 与 SQL 可分歧，观察可能静默未保存。

本批只要求观察写严格 affectedRows=1，不归一化历史 state。

## 2. Exact guard

保留 locked/current、canonical terminal early return、XID/decision target、error code-point truncation、SQL、REQUIRES_NEW、锁和 API。调用 `updateObservation` 后：

- 返回值必须为 1；
- 0 或其它值抛既有 `PAY_TCC_EXECUTION_CONFLICT/409`；
- 不再以 readback 掩盖写入未发生；数据库异常原样传播，事务回滚。

非法/非规范终态 alias 不归一化；canonical terminal early return 仍无写，decide/bind 逻辑不改。

## 3. 验收

- Unit：affected 0/-1/2 无 readback且冲突，affected1 各 decision/target保持，DAO exception 原样；canonical terminal no-write。
- Owned native：CI/PAD alias 6 variants SQL0→conflict；精确 canonical active/terminal payload、diagnostic、毫秒/readback保持；受控同事务改 terminal 使 update0，冲突并回滚受控变化，Pay/Intent facts 保持。
- 使用 owned cloned execution DDL +真实 MyBatis/TransactionTemplate，不启动服务/TC/MQ，不写原库/资金事实；资源 UUID schema cleanup。

## 4. 交付边界

实现仅允许给 `observe` 的 updateObservation 加 affectedRows guard：非 1 失败路径不执行 readback，成功 1 保留原 readback 及返回 PO；必要 JavaDoc/测试同步。不得修改 SQL、state normalization、decision、API 或其它 Store。
