# CM-18 16T179：Pay receipt lookup identity

## 1. 结论与范围

GO。ReceiptStore.read/lock 直接返回 CI 查询行；请求 pay-1 可能得到 PAY-1 receipt，Facade/Callback 可读取或操作异源终态。

本批只补两个 getter 的 exact payNo identity，不改 claim/transition/callback SQL。

## 2. Getter guards

`read(payNo)` 与 `lock(payNo)` 各保原一次 Mapper 查询：

- row null 原样返回 null；
- row non-null 必须 payNo non-null 且 `Objects.equals(row.payNo, payNo)`；不匹配抛既有 `PAY_TCC_RECEIPT_CONFLICT/409`；
- 不 normalize/casefold，不改状态/字段/DTO。

Facade read 缺失继续 404，prepare 读缺失继续 503；Callback 缺失 continue 原 confirm/cancel false/try conflict 语义。匹配终态不误改。

## 3. 验收与边界

- Native owned Receipt Mapper/Store/Facade：CI case 双向 × CLAIMED/TRYING/CONFIRMED/CANCELLED，read/lock 拒绝且 Pay/Intent/Dispatch/Receipt facts 不变；匹配返回原对象/VO，missing 保原。
- Unit foreign/case/space/null 对象模拟；NULL request/original 仍不伪造匹配。
- Callback+Store lock 缺失/alias 保原 false/conflict，真实 TCC/服务不启动；资源 checksum/cleanup。

## 4. 交付边界

实现仅允许在 ReceiptStore read/lock 增加 payNo guard、JavaDoc/测试；不得修改 Mapper SQL、claim/transition/callback/Fence/API。
