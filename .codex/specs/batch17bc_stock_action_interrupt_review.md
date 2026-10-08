# CM-20260924-20：库存动作恢复中断边界评审

## 1. 评审结论与范围

结论：**GO，限定为 `OrderStockActionRecovery` 的可观察中断 checkpoint 与中断异常隔离。**

当前恢复器没有在扫描、claim、Stock 查询/动作和完成写之间检查线程中断；阻塞调用抛出或包装 `InterruptedException` 时，既有 Runtime/数据库异常路径可能继续 retry、finish 或处理后续候选。

本轮只覆盖该恢复器，不扩展其他模块；保留 17AZ token/lease fencing、17BA 数据库隔离、17BB attempts 门、17AV/AW/AX/AY 身份/类型/key/error 契约。

## 2. 中断检查与停止语义

使用 `Thread.currentThread().isInterrupted()` 检查，不主动清除标志。检查到中断时立即返回当前恢复批次：

- 停止当前及后续候选；
- 不清理/延长 lease，不修改错误字段，不猜测已提交事实；
- 不追加 retry、finish、Stock 动作或相反补偿；
- 已发 RPC 或已完成数据库提交不可撤回，后续由有效 token/lease 扫描处理。

必须覆盖：

1. `recoverDue` 入口、findDue 前后；
2. 每候选 claim 前后、recoverOne 入口；
3. 每次 Stock GET 前后；
4. confirm/rollback 前后；
5. 所有 finish/retry 前后。

`stockStatus` 私有方法可声明抛出 `InterruptedException`：GET 前若已中断立即抛出，GET 返回后若线程已中断也抛出，使 recoverOne 统一恢复标志并返回。

## 3. 异常分类与 cause 检测

使用安全的 cause 链遍历识别明确 `InterruptedException`，以对象身份集合防止循环：

- 当前 flag 已设置，或 cause 链包含 `InterruptedException`：恢复中断标志并立即 return；不 retry、不 finish、不处理后续候选；
- 候选层 `DataAccessException` catch、recoverOne 的数据库 catch、业务 Runtime catch 和 retry 自身异常均须先执行该判断；
- 无中断证据时，findDue 数据库异常继续传播，候选 DataAccessException 继续沿 BA 隔离，业务/RPC/解析异常按原一次 retry；
- claim 的非数据库 RuntimeException 和 Error 仍传播，不吞 Error；
- `SocketTimeoutException`、普通 `InterruptedIOException`、Feign timeout 无明确 `InterruptedException` cause 时仍按原业务 retry，不自动判定取消。

## 4. 动作与持久化边界

- 中断发生在 Stock GET 返回、confirm/rollback 返回或完成写返回后，不根据未知结果补偿、重试或覆盖事实。
- 中断发生在 finish/retry 调用前，不发起该写；调用已开始且结果未知时，不再次写入。
- retry 使用本次 AZ claimOwner；中断后不换 token、不清租约。数据库异常仍遵守 BA，attempts 负值仍遵守 BB。
- 不改变 50 条扫描、30 秒 lease、原 `<` 到期条件、attempts 饱和、退避、状态、SQL/schema、Feign、线程拓扑或 Stock 调用顺序。

## 5. 验收矩阵

1. 入口预中断：不调用 findDue、claim、Stock 或 Mapper 写，线程标志保持。
2. scan/claim/GET/confirm/rollback/finish/retry 各 checkpoint 注入中断：不执行后续动作、不处理后行、不追加写；claim 后保留完整行和 lease。
3. 包装 `InterruptedException` 覆盖 scan、claim、Stock GET、动作、finish、retry；恢复 flag、停止批次、cause 循环不挂起。
4. 普通 Socket/Feign timeout 无明确中断 cause 时仍原 retry，后行继续；非 DB claim Runtime/Error 原传播。
5. 真实隔离 MySQL 验证 claim 后触发器/同步替身中断保留事实，已发动作或完成 SQL 后中断不追加更新；清除测试 flag 后新一轮可正常恢复，原库 checksum 和 fixture 清理保持。
6. Stock/RPC 和中断同步由替身注入，不宣称跨服务请求撤回或网络原子取消。

## 6. 明确不承诺

本轮只提供恢复器内部的可观察中断停止边界，不提供远端 RPC 撤回、已提交事务回滚、数据库/网络全链路取消或分布式 exactly-once。
