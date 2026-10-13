# CM-18 16T150：Pay Legacy terminal refund guard

## 1. 结论与范围

GO。T149 保护 owned stage/retry，但 `finishOwnedLegacy` 仍可把当前 REFUND_PENDING intent 结束为 SUCCESS/FAILED，丢失退款方向。

本批只在 Legacy intent terminal finish SQL 增加退款方向保护。

## 2. finish guard

在现有 `finishOwnedLegacy` WHERE 末尾追加：

`AND (status <> 'REFUND_PENDING' OR BINARY ? = 'REFUNDED')`

末尾绑定本次目标 status：只有 binary exact `REFUNDED` 才允许结束当前退款方向；SUCCESS/FAILED 返回 affectedRows=0。保原 SET、last_error、lease clear、100年 nextRetry、owner/deadline/mode/active、参数前序和旧 `finish` 方法。

当前 status 列的原 collation 保守语义保留，目标 status 不 trim/casefold。

## 3. 保持项与边界

T141 terminal transaction/tuple lock、T143 stage、T145/T149 retry、SUCCESS/FAILED 第二写0回滚 PayRecord/paidAt、catch/refund direction、REFUNDED 三写正常保持。Fresh/durable REFUND_PENDING 由既有读取保方向。

不改 Service/API/SQL 其它方法、远端 source/fencing、历史修复或全局事务。

## 4. 验收

- Native：stale INIT→current REFUND_PENDING、CI/尾空格 alias × SUCCESS/FAILED 均零终态/完整 facts/lease 保持；exact REFUNDED 健康三写通过。
- 实际 SUCCESS 末次 User GET 期间 intent 转 REFUND_PENDING 时 PayRecord terminal rollback，catch 保 REFUND_PENDING；补取消后同 pay 可 REFUNDED，不重复退款。
- FAILED 通过受控 private terminal path 验证同样保护；资源 checksum/清理，不启动 App/RM/TC。

## 5. 交付边界

实现仅允许修改 `finishOwnedLegacy` WHERE/末尾绑定参数与必要 JavaDoc/测试；不得修改旧 finish、Service 状态计算、stage/retry 或其它 SQL。
