# CM18 16T182：Pay TCC 交付请求支付号身份校验规格

## 1. 评审结论与范围

本批 **GO**。问题是交付任务的 CI 支付号查询结果可能被当作请求支付号使用：`ensure` 在 execution 与支付记录相互匹配后仍可为别名请求新建或返回原支付号的 delivery；`claim` 的外层支付号条件可认领另一原拼写任务；`read` 可直接返回 CI 命中的异源行。本批只补请求支付号身份门，不扩展交付状态机、资金来源或 Fence 证明。

## 2. `ensure` 的请求身份门

在现有 execution 缺失、状态/决议不合法的提前返回之后，且在支付记录数量检查、`requirePayment`、delivery 查询和任何新建之前，增加：

```java
execution.payNo != null && Objects.equals(execution.payNo, payNo)
```

不满足时沿既有 `PAY_TCC_DELIVERY_CONFLICT/409` 返回，不创建、不读取或修改 delivery，不调用远程资源。execution 为 `null`、非 `COMMITTED` 或非 `COMMIT` 的原有返回优先级保持不变；payment/execution tuple、TCC 模式、支付状态、金额、operation/ledger key 等既有证明不放宽。`payNo` 不 trim、不大小写折叠。

## 3. `claim` 的最小 SQL 修正

仅将 `PayTccDeliveryMapper.claim` 外层支付号资格条件改为：

```sql
BINARY delivery.pay_no = BINARY #{payNo}
```

保留 execution/payment JOIN、delivery 与 execution 的全部 tuple、XID、operation/ledger、状态、支付状态、退款键、租约、到期时间及 attempts 上限条件；SET 列、参数顺序、affected rows、claim 后 readback 与事务边界均不变。CI 大小写别名请求必须得到 0 行并由 Store 返回 `null`，不得写 lease、attempts 或 `updated_at`。匹配键仍按原逻辑领取一次，第二次受 lease/状态门阻断。

## 4. `read` 的返回身份门

`PayTccDeliveryStore.read(payNo)` 保持一次 `selectByPayNo`：

- 返回 `null` 时仍返回 `null`；
- 非 `null` 返回必须满足 `row.payNo != null && Objects.equals(row.payNo, payNo)`；
- 不满足时抛既有 `PAY_TCC_DELIVERY_CONFLICT/409`，不返回异源 delivery，不继续调用方逻辑。

该门不改变 `claim` readback、`proof`、`stage`、`finish`、`ensure` 已证明路径、DTO 或 Service 行为。生产表 `pay_no` 的 NOT NULL 约束保持；foreign、space、null 行只在 unit 对象中验证。

## 5. 验收矩阵

### 5.1 Owned MySQL

- 使用实际 Delivery、Payment、Execution、Dispatch、Receipt、Intent Mapper 与 owned 原 DDL；不启动业务 App、TC/RM 或写原库。
- 以实际 MySQL CI 规则构造双向大小写别名：`ensure` 不创建/返回 delivery，`claim` affected rows 为 0，`read` 返回 409；Payment、Intent、Delivery 及其来源事实完整不变。
- canonical 请求覆盖三类 active delivery 状态、正常 create/re-entry、五类可领取 lease 场景、60 秒 lease、attempts 上限 31；缺 execution、非 committed、资金/模式/状态/tuple 不满足、未到期或已有 lease 仍按原零行/NULL语义。
- 真实 Store/受控 Service fallback 不得返回异源 terminal，不得发 Order/RPC 或写其它资金事实；匹配键的 create、read、claim、terminal 和 missing 行为保持。
- native 仅声称真实 CI 大小写别名查询。foreign、尾空格、对象 null、NOT NULL 列的 null 仅 unit 模拟，不宣称真实数据库可返回这些形态。

### 5.2 Unit

覆盖 `ensure` execution/pay foreign、case、space、null 以及共同 null 的优先级；`claim` canonical/alias/missing/lease/attempt；`read` foreign、case、space、null-payNo、null row。断言 conflict code、一次读取、无新建/租约/远程调用，并保留 uncommitted、missing、CANCEL_UNKNOWN 类既有控制。

## 6. 明确边界

本批不改变其它 Mapper SQL、锁顺序、状态/金额规则、source proof、Fence、claim readback、stage、finish、Service、DTO 或 API；不加全局唯一索引、不修历史别名、不宣称交付流程具备完整 tuple fencing、跨表原子性或真实 TC/远端因果证明。租约期间外部事实漂移及其它 getter 的身份问题仍属后续范围。
