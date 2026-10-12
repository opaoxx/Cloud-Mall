# CM-18 16T95：User Legacy bounded debit history

## 1. 结论与范围

GO。Legacy `debitFromLedger` 当前以单 PO `selectDebitByPayNo` 读取 payNo 历史 DEBIT；同 pay 重复 DEBIT 会抛 TooManyResults，而不是稳定按既有冲突/缺证协议分类。

本批只替换这一历史读取为 bounded candidate list；不扩展 T76 金额/snapshot 门、T89/T94 no-funds、退款、TCC 或全历史因果。

## 2. Bounded history read

在 caller/request/user/attempt 既有优先级之后，复用已有 `findDebitCandidatesByPayNo(request.payNo)` LIMIT2（CI、全 state、按 id）：

- list null 或唯一 candidate 为 null → `BALANCE_DEBIT_UNKNOWN`/409 固定“扣款归属缺少对应账本事实”；
- size > 1 → 原 `IDEMPOTENCY_CONFLICT`/409 固定“支付号已绑定其他扣款操作”；
- 唯一非 null candidate 才进入原 operationKey exact 检查；正确 key 保持旧 historical replay/冲突路径，别名/错误 key 沿原 conflict。

DAO/DataAccess 异常原样传播，不转换为业务错误。可靠 empty 才令 historical=null；随后继续既有 fixed-key lookup、existing state/snapshot、wallet 与 attempt/ledger 写入顺序。

## 3. 保持项与调用边界

旧 attempt 先核，caller/request/user 校验优先；新读取一替一，不新增 SQL/DDL/API/锁。APPLIED/REJECTED existing replay、无 attempt 历史重放、fresh write/cache、T76 money proof 与状态机保持。

不新增 operationKey 全 tuple proof，不修重复历史、不验证所有账户资金因果、不改变 TCC/退款/Redis/消息。

## 4. 验收

- Unit：null list、唯一 null row、DAO fault、single exact/wrong/CI key、multiple rows；断言缺证 UNKNOWN、重复/错误 key 原 conflict、旧优先级与 no-write。
- Native：Legacy 两 HTTP/Service 入口 fresh、APPLIED replay、REJECTED replay、无 attempt 历史，覆盖重复 DEBIT 状态/CI/三行；真实四表 facts、wallet/cache/source checksum 不变。
- 健康 single exact、余额不足、尾零/MAX 与原协议保持；资源 finally 清理，不启动 RM/TC/App。
- 保留 T70–T94 回归；不宣称全历史 source/fencing。

## 5. 交付边界

实现仅允许替换 `debitFromLedger` historical 单行读取为现有 bounded Mapper 方法、必要 JavaDoc/测试；不得扩展其它资金路径。
