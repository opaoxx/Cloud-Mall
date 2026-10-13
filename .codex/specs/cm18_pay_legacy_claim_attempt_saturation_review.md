# CM-18 16T162：Pay Legacy claim attempt saturation

## 1. 结论与范围

GO。claim 当前 `attempts=attempts+1` 在 INT 最大值 2147483647 上触发 MySQL range error，阻断健康 Legacy recovery；attempts 只是恢复元数据，非资金状态。

本批只把 claim attempts increment 改为上限饱和。

## 2. Exact SET

仅将 claim SET 改为：

`attempts = CASE WHEN attempts < 2147483647 THEN attempts + 1 ELSE attempts END`

MAX 保持 MAX 但仍允许成功 claim、换 token、更新 lease/updated_at；0、7、MAX-1 保持原 +1。不得减少、重置、猜测资金状态；负/NULL 历史 attempts、DDL/migration 不在本批。

## 3. 保持项与验收

其它 WHERE、字段、参数、candidate SQL、Service/retryDelay（60 秒上限）、状态/RPC/TCC/DDL/API 保持。

- Native MAX × PENDING/ORDER_SYNC_UNKNOWN/REFUND_PENDING claim 健康，attempts MAX、owner/lease/updated 正确；busy/expired owner、MAX-1/边界重领。
- MAX APPLIED+PAID 可 SUCCESS，无新增 debit/paid；读故障按原 retry/lease 释放后补证恢复，不减少 attempts。
- 非 MAX checksum/identity/error/lease 语义与资源清理回归，不启动 App/RM/TC。

## 4. 交付边界

实现仅允许修改 claim SET attempts 表达式与 JavaDoc/测试；不得修改 WHERE、其它 SQL、状态机、Service 或数据库 schema。
