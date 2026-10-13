# CM-18 16T149：Pay Legacy refund intent no-regression

## 1. 结论与范围

GO。intent 在较早观察后可能转为 REFUND_PENDING；T144/T146 source/owner 仍有效时，stage/retry 写可把退款方向降级为 DEBITED/INIT/ORDER_SYNC_UNKNOWN。T148 只保护 PayRecord first write，不能阻止 intent regression。

本批只给两个 Legacy intent owned SQL 增加退款方向保护，不改 Service 状态计算或通用方法。

## 2. SQL guard

在 `updateOwnedLegacyStatus` 与 `recordRetryOwnedLegacy` 原有 WHERE 末尾追加同一条件：

`AND (status <> 'REFUND_PENDING' OR BINARY ? = 'REFUND_PENDING')`

其中新增绑定参数为本次目标 status，必须 binary exact `REFUND_PENDING` 才允许保持/推进退款方向；目标 DEBITED、INIT、ORDER_SYNC_UNKNOWN 在当前 intent 已 REFUND_PENDING 时 affectedRows=0。保留原 SET、owner/deadline/mode/active/EXISTS、错误512截断、参数顺序及旧通用方法。

该保护保守受现有 status collation 影响：status 等价 alias 仍按原列语义阻断非退款目标，不做 trim/casefold/状态猜测。

## 3. 保持项与验收

T143 stage 第二写0 回滚 PayRecord，T145 retry 第二写0 回滚/保 lease，caller 保原退款 retry 方向；不抛新错误、不猜状态、不新增 RPC。健康 REFUND_PENDING stage/retry、其它正常状态与 T144–T148 保持。

- Native：INIT→intent 已 RFP（含 CI/尾空格 alias）后，stage DEBITED 或 retry INIT/ORDER_SYNC_UNKNOWN 不降级；实际 Order GET 期间转 RFP 时不 paid，补取消/库存后可退款。
- Unit/owned：目标 status exact、owner/deadline/mode/EXISTS 与 affectedRows；SQL/事务/资源 checksum，非退款 caller 回归。
- 不启动 App/RM/TC，不宣称全 process/source fencing。

## 4. 交付边界

实现仅允许修改两个 Legacy intent owned SQL WHERE/绑定参数与必要 JavaDoc/测试；不得修改旧通用方法、Service 状态计算、stage/terminal/retry transaction 或 API。
