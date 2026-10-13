# CM-18 16T121：User receive multiple-result classification

## 1. 结论与范围

GO。T117–T120 四处 receive point read 依赖 payNo 唯一结果；遗留表缺唯一索引时 duplicate rows 会抛 TooManyResults/MyBatis wrapper，外层可能 500 而非按“已有 TCC 接收事实”冲突。

本批只统一这四处 receive existence read 的精确多结果分类，不改查询、锁、DDL、TCC、disabled 或历史数据。

## 2. Narrow helper

新增 private `hasTccReceiveConflict(payNo)`，内部调用现有 `userTccReceiveMapper.findByPayNo` 一次：

- row non-null → true；row null → false；
- direct `TooManyResultsException`，或 `MyBatisSystemException` 的 direct cause 是 TooMany → true；
- 其它 wrapper cause、nested/deep TooMany、unrelated Runtime、DAO/DataAccess/SQL exception 原对象传播；不遍历深层 cause、不按 message 匹配、不广泛转换。

四 callers 继续使用各自原语义与错误映射：fresh IDEMPOTENCY409、Legacy replay BALANCE_DEBIT_UNKNOWN409、REJECTED COMMON_CONFLICT409、APPLIED/refund 原 BALANCE_REFUND_INVALID/GET mapping。null/唯一 row、原优先级与读取预算保持。

## 3. 保持项与验收

不新增 SQL/读取/锁/DDL/API，不修历史、不按 receive metadata 过滤、不宣称全账户/Fence。TCC/disabled 路径预算不变，诊断 read service 不扩范围。

- Unit：direct/wrapped/direct-cause/deep/unrelated/root DAO、null/unique 与旧 priorities。
- Native：owned 临时移除 receive pay 唯一索引（其它索引保持），9 入口 × exact/CI duplicate，业务按 caller 返回 409、五表 no-write；finally 删除自有两行并恢复 index，source checksum/资源清理。
- 不启动 RM/TC/App；保留 T117–T120 与 User HTTP/读事务回归。

## 4. 交付边界

实现仅允许增加 receive point read helper 并替换四处调用、必要 JavaDoc/测试；不得修改 Mapper SQL、全局异常处理器或资金写路径。
