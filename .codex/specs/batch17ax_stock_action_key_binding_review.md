# CM-20260924-20：库存动作操作键绑定评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderStockActionRecovery` 未核验 `operationKey` 与原订单号/动作类型绑定的问题。**

Stock 端既有 `validateActionKey` 约定稳定操作键必须等于 `orderNo + ":" + type`，但恢复器在库存状态已经是目标终态时会直接 `finish`，跳过 Stock 写接口的校验；损坏、别单、反方向、空 key 因此可能被误标为成功。RESERVED 分支也会把错误 key 发送给 Stock。

本轮只增加恢复器本地的订单号和操作键身份门，不修改 Stock/Feign API、SQL、schema、状态枚举、owner/lease、attempts、扫描、中断或 17AV/17AW 规则。

## 2. 操作键身份契约

`recoverOne` 通过 17AW actionType 精确门后、计算方向、任何 Stock GET 或 `finish` 前，必须同时满足：

1. `action.orderNo` 非 null 且非 blank；
2. `Objects.equals(action.operationKey, action.orderNo + ":" + action.actionType)` 精确相等。

比较区分大小写，保留重音和尾随空格差异，不 trim、不大小写转换、不重建或修正持久化 key。以下均为非法：null/blank key、其他订单号、反方向 key、大小写差异、尾随空格、重音差异和 null/blank orderNo（即使字符串拼接后看似形成 `:CONFIRM`/`:ROLLBACK`）。

身份失败抛出 `IllegalStateException`，沿现有 RuntimeException catch 进入 UNKNOWN retry 和原退避；不得查询 Stock、调用 confirm/rollback、finish SUCCEEDED/CONFLICT 或修改原字段。

## 3. 恢复流程边界

### 3.1 首次目标状态查询

即使 Stock 当前状态可能已经是目标终态，key/orderNo 身份门也必须先通过；不能用“目标状态 GET”替代本地动作身份核验。身份有效后，继续 17AV 的 Stock response `orderNo` 精确门和原目标/相反状态处理。

### 3.2 RESERVED 动作与写后查询

身份有效且状态为 RESERVED 时，继续使用持久化原 `operationKey` 对应方向执行一次 confirm/rollback；Stock 端再次校验该 key。写入后第二次状态查询仍沿 17AV 身份门，目标/相反状态和 finish CAS 保持原语义。

身份无效时不发任何动作；已执行过的历史远端动作不撤回、不重发、不猜测结果，原本地 action 只按 UNKNOWN retry 等待后续处理。

## 4. 兼容与排除边界

- 不新增 Mapper 查询、字段、错误码、状态或筛选 SQL；不修改 `order_stock_action` 历史行。
- 保留 claim attempts、owner/lease、retry/nextRetry、finish CAS、17AV response identity 和 17AW actionType guard。
- 不额外校验 operationKey 长度、格式正则、request hash、user/items 或库存流水归属；只执行非 blank orderNo 和精确字符串绑定。
- 不修复历史非法 key；它们下一次恢复只会安全进入 UNKNOWN retry，需人工或后续批次处理。
- 不修改 Stock `validateActionKey`、Feign body、消息、Seata 或其他服务。

## 5. 验收矩阵

1. unit 覆盖 CONFIRM/ROLLBACK 下 key 为 null、空串、空白、别单、反方向、大小写差异、尾随空格、重音差异，以及 null/blank orderNo；在 RESERVED、目标终态、相反终态、REJECTED 等状态下均不查询 Stock、不写动作、不 finish，只 retry 一次。
2. 真实隔离 MySQL 通过参数更新模拟历史损坏 key 和 blank orderNo，确认 action 的 id、orderNo、actionType、operationKey、createdAt 等原字段保持，UNKNOWN、attempts、retry、owner/lease 遵循原 claim/退避语义；后续健康合法 CONFIRM/ROLLBACK 行可正常完成。
3. 合法 key 在目标终态、RESERVED、写后目标/相反状态场景保持 17AV/17AW 的完整流程和原 operationKey；不重复远端动作。
4. 回归 17AV 两方向身份 IT、17AW 非法类型测试、原 action unit/普通订单创建测试、原订单 checksum 和隔离库清理；Stock/RPC 替身不宣称真实网络故障验证。

## 6. 明确不承诺

本轮只防止恢复器使用未绑定的 order/action key 误完成或误发动作，不提供历史 key 清洗、Stock 端全量事实归属校验、在途 RPC 撤回或分布式 exactly-once 保证。
