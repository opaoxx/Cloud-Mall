# CM-18 16T132：Pay Legacy Order status whitelist

## 1. 结论与范围

GO。`readOrderState` 当前核 orderNo/userId/amount，但未限制 status；null/unknown/大小写或空白 status 可先触发 User debit、等待路径，或在 REJECTED 分支被误标 FAILED。

本批只在既有 envelope、identity、amount proof 后增加 Order status 白名单，不改 Order API/Feign、状态机或 SQL。

## 2. Status gate

`readOrderState` 仅接受精确 status：`PENDING_PAYMENT`、`PAID`、`COMPLETED`、`CANCELLED`。null、blank、大小写/空白 alias、其它字符串统一抛 Runtime `订单支付状态暂不可判定`，由既有 catch 按当前 intent 状态记录 INIT/ORDER_SYNC_UNKNOWN/REFUND_PENDING retry。

该门在所有 readOrderState 调用生效：首次订单读、REJECTED 订单读、Order.paid 后二次读、resolveAfterUncertainOrderWrite 二次读。坏首读不得发 User debit/Order paid/User refund 或 terminal，但 claim/owned retry 按既有链执行；坏二读保留已发 paid/stage，禁止继续 terminal/refund。

## 3. 保持项与边界

合法四态的原分支和 Stock action 判定保持；不改 source 读后变化、lease/owner、RPC headers、API/Feign fields、SQL/锁/TCC/RM。Order status proof 只针对当前响应，不宣称跨服务稳定快照。

## 4. 验收

- Unit：首读/二读 × null/unknown/case/space/合法四态；断言 RPC/terminal 优先级、retry 状态与合法行为。
- Owned Pay：仅真实 Pay 两表/Mapper 与 controlled User/Order Feign responses；坏首读无资金 RPC，坏二读不终态/退款，source checksum/资源清理保持。
- 不启动业务服务/RM/TC，不访问 OrderDB，不伪称跨服务全链路。

## 5. 交付边界

实现仅允许在 `readOrderState` 增加 status whitelist 与 JavaDoc/测试；不得修改 Order API、Feign、Stock 判定或其它恢复逻辑。
