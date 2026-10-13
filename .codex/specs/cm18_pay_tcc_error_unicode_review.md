# CM-18 16T170：Pay TCC error Unicode truncation

## 1. 结论与范围

GO。Pay TCC stores 的 error 字段仍以 UTF-16 `substring(0,512)` 截断，可能切断 supplementary pair。受影响入口为 PayTccCancelStore stage/updateState/finish、PayTccExecutionStore observe、PayTccDeliveryStore finish、PayTccExecutionRecoveryStore finishRecovery，共五处调用点。

本批只统一 Pay 模块 error message code-point truncation；不修改上一批 Legacy mapper helper。

## 2. Shared utility

新增 Pay 模块内部 `PayErrorMessages.truncate(String)`：

- null/empty 原样；
- 最多 512 Unicode code points，使用 codePointCount + offsetByCodePoints + substring；
- 不 normalize/trim/replace/fallback，不切断合法 surrogate pair。

五个 Store 入口调用同一 utility；Cancel 原 private truncate 可 delegate 或移除。SQL、事务注解、锁序、状态、lease、退避、receipt、TC/Feign、DDL/API 保持。

## 3. 验收边界

- 新增单元 class 通过 Store public 方法与 Mapper 参数捕获，覆盖 511 ASCII+emoji-tail、BMP/null/empty/512/513、纯 supplementary/mixed/combining 等输入 × 五入口；验证 state/owner/payNo/nextAt/5秒退避/毫秒 now/nullable 参数。
- terminal 不写、cancel 错 token/expired/terminal guard、recovery mapper=0 返回 false/CAS 语义保持。
- 本批测试等级为 Pay unit + Mapper argument capture，不宣称真实 TCC MySQL/TC 故障链；上一批 VARCHAR512 native 证据继续有效。
- 不混入 Legacy 时序测试，不修改原库/启动服务；资源无副作用。

## 4. 交付边界

实现仅允许新增 Pay 内部 utility、替换五处 truncation、必要 JavaDoc/单测；不得重构 Legacy、修改 Store SQL/API/TCC 状态机或其它错误协议。
