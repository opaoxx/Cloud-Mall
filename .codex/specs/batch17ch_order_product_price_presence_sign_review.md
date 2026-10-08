# CM-20260924-20：订单商品价格存在性与非负校验评审

## 1. 结论与范围

结论：**GO，限定普通创建和秒杀首次消费对 Product `unitPrice` 的最小事实门。**

CF/CG 已绑定 Product SKU 身份，但当前仍可能把 null 价格交给金额/SQL，或把负价格写入订单并完成状态。本批只要求原始 `BigDecimal` 存在且数值非负。

不新增价格上限、精度、scale、格式或支付可用性承诺；不改变 Cart 价格语法门、Product 管理写接口、Pay、普通意图时序、CF/CG 身份门、消息字段、数据库、SQL、Schema、状态机或历史数据。

## 2. 普通订单创建

在 CF Product response 和 SKU 精确身份门之后、金额计算、intent、Stock、订单/明细/outbox 写入之前执行：

- `unitPrice != null` 且 `unitPrice.signum() >= 0` 才允许继续；
- null 或负数按既有 `ErrorCodes.INTERNAL`/500 返回，不以 NPE 结束，不 fallback 或规范化价格；
- 第一项合法、后项价格非法时，整个创建失败，不产生 intent、Stock、订单相关本地事实或 Cart POST；17CE 已完成的前置 Cart 只读可存在；
- 合法零、负零、科学计数法、尾随零和原 scale 保持原 BigDecimal 对象/表示，不 trim、setScale、round 或转换。

直接订单幂等投影重放不查询 Product、不核价；投影缺失的晚期 intent fallback 保持原有 Product 读取、金额验证和 fallback 查询顺序，本批非负价格门作用于该原有 Product 读取之后，不增加二次 GET、重新定价、额外 fallback 后核价或前移 SQL。

## 3. 秒杀首次消费

在 CG Product response/code/SKU 身份门之后、创建 `OrderPO`、金额、明细、outbox、dedup `markCommitted` 和 recovery `markResolved` 之前执行同一非负规则：

- null 或负数抛受控 `IllegalStateException`，沿既有 outer catch 保留 cause，交由 Rabbit retry/DLQ；
- 本次 claim 事务回滚新增秒杀本地事实，Stock/Cart 不调用；已有 COMMITTED 重放仍在 Product 前收敛、不重新核价；
- 合法零和正数保持原对象、scale、时间、quantity=1 和现有写入流程。

## 4. 验收与边界

- 普通真实 MySQL/事务测试覆盖首项/后项 null、负价，确认无 intent/Stock/订单相关写入；合法零、科学表示、高 scale 尾零保持原金额表示。
- 秒杀真实事务测试覆盖 null/负价无订单、明细、outbox、COMMITTED/Resolved，并核对新增 claim 回滚和既有 held recovery 事实；修正价格后可按原事件成功。
- 单测确认普通 null 为 500 而非 NPE，秒杀异常保留 cause；CF/CG、普通幂等、CE、时间和错误分类回归。
- 本批不定义最大金额、decimal precision/scale、负零文本、metadata 或真实网络故障语义；零价仅保持原创建兼容性，不承诺后续支付成功。
