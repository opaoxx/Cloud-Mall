# CM-18 16T163：Pay Legacy recovery attempt domain

## 1. 结论与范围

GO。T162 对 attempts 达到 MAX 做饱和，但负历史 attempts 仍可进入 due 候选，claim 后 -1 变 0 或继续负值，既占窗口又调用远端。

本批只过滤负领取累计，不修历史值、不迁移 nullable schema。

## 2. Three SQL gates

在 `findDue`、`findDueAfter` 与 `claim` 的 intent 条件追加 `intent.attempts >= 0`：

- 负 attempts 不候选、不 claim；
- NULL 在旧 schema 中按 SQL UNKNOWN 不匹配；
- 保 T162 CASE saturating increment、MAX 仍可领取/饱和，0/7/MAX-1 保原 +1。

其它 pair/key/numeric/blank/status/time/order/LIMIT/cursor/SET/参数保持，不逐次改写坏计数、不减少/猜资金结论。

## 3. 验收

- Native direct claim：-1/-7/INT_MIN 返回0/full facts；service 坏计数不 claim/no User/Order RPC，返回当前 status/元数据；
- Scan：100 negative 头 + healthy 0/7/MAX 尾过滤后 LIMIT/order/cursor 只读保持；pre-route 后 attempts 变负，claim0且不改 lease/metadata；
- 健康 attempts 0/7/MAX-1/MAX、饱和重领与 T158–T162 回归；资源 checksum/清理，不启动 App/RM/TC。

## 4. 交付边界

实现仅允许在 `findDue`、`findDueAfter`、`claim` 增加非负 attempts predicate，及可选 PO JavaDoc“非负领取累计次数，INT 上限饱和”；不得改类型、映射、API、迁移或历史数据。
