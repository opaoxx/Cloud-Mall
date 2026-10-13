# CM-18 16T116：User refund multi-result classification

## 1. 结论与范围

GO。refund current row and source point reads still rely on single-result Mapper methods; historical duplicate rows surface as 500 before T115 counts, masking a stable refund evidence conflict.

本批只把精确多结果异常分类为既有 refund invalid 409，不改查询、锁、首行优先级或集合/global key proof。

## 2. Narrow wrapper

抽取 `readRefundLedgerCandidate(Supplier<BalanceLedgerPO>)`，复用 refund current/source point calls：

- direct `TooManyResultsException` → `BALANCE_REFUND_INVALID/409` 固定“退款累计证据不完整或不一致”；
- `MyBatisSystemException` 仅当其直接 cause 是 `TooManyResultsException` 时同样转换；
- 其它 wrapper cause、其它 Runtime、DataAccess/SQL exception、null 或唯一 row 原样返回/传播。

不得按 exception message 匹配、遍历任意 cause 链或广泛 catch/coerce SQLException；不改变调用次数、参数、User→source 锁顺序、disabled 404、T73 row proof、owner/global counts、额度、写入或 API。

## 3. 保持项与优先级

多结果分类发生在无法建立 current/source point proof 时，优先于后置 kernel/owner/global count；单 null 仍按原缺源/404/409，唯一 row 继续原字段 proof。T70–T115、fresh/replay/GET/disabled/TCC/SQL 写保持。

## 4. 验收

- Unit：current/source direct TooMany 与直接 MyBatis wrapper 两类型，active/disabled/fresh/replay；非目标 Runtime/DAO/SQL、null/unique 维持原对象/错误。
- Owned MySQL：三模式 × current/fresh-source/replay-source duplicate exact/case，旧 500 转 409、四表 no-write；仅 owned 临时移除 UNIQUE、clone、finally 删除额外 id 并恢复 index。
- 不通过 COUNT 声称完整 source/fencing；资源清理和原查询预算保持，不启动 RM/TC/App。

## 5. 交付边界

实现仅允许增加精确多结果 wrapper 并包裹 refund point reads、必要 JavaDoc/测试；不得修改 Mapper SQL、异常全局处理器或资金写路径。
