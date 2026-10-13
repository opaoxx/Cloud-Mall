# CM-18 16T138：Pay Legacy SUCCESS debit recheck

## 1. 结论与范围

GO。`finishSuccess` 当前只依赖流程早期 User GET 或 debit POST ACK；在 Order.paid/后续读取期间原 DEBIT 可能删除、变 REJECTED 或带 refund marker，仍可能写 SUCCESS。

本批只给 SUCCESS terminal 前增加一次 User operation GET proof；FAILED/refund/TCC 不增加该读取。

## 2. Success proof

在 `finishSuccess` 本地 terminal transaction 之前、且 RPC 已在事务外完成后调用现有 `readDebitOperation(intent)`，复用 `validateDebitOperation` identity/amount proof，并额外要求：

- response 非 null；
- type DEBIT、state APPLIED；
- refundOperationKey 精确 null。

404/null body/非成功 envelope/错误 key/type/amount/state/已有退款 marker统一 Runtime “原扣款账本未证明支付成功”，沿现有 catch/retry，不 terminal、不猜退款、不撤回已发 debit/paid。其它 Feign/DAO fault 原样传播。

## 3. 调用与保持项

所有 `finishSuccess` 调用统一受该门；不新增 SQL/Feign 字段/API/flag/lease，User operation GET 复用现有接口。退款/FAILED 路径不加 GET，已发 RPC 不回滚，也不宣称 GET→terminal fencing。

## 4. 验收

- Unit/native：direct paid、after-paid 二次读、fresh debit ACK 三类 success × missing/NULL/body fault/key/type/money/state/refund marker；不 SUCCESS，正确末次 APPLIED 正常。
- 同 pay 补证后下一次恢复可 SUCCESS，不重复 debit；旧 fresh fixture 明确首次 404→POST APPLIED→终态 recheck APPLIED。SUCCESS RPC/GET 预算增加一次，退款预算不变。
- Owned Pay + controlled User/Order Feign，原 Pay checksum/资源清理，不启动服务/RM/TC；不宣称完整 source fencing。

## 5. 交付边界

实现仅允许在 `finishSuccess` 前增加纯 recheck helper/调用及必要 JavaDoc/测试；不得修改 refund/FAILED/claim/terminal transaction/API。
