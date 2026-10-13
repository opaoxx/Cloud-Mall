# CM-18 16T117：User Legacy fresh receive exclusion

## 1. 结论与范围

GO。Legacy fresh debit 在 T104 freeze、T96 ledger 等旧门通过后仍忽略 `user_tcc_receive` CLAIMED/坏历史；同 pay 已有 TCC receive 可继续 claim/扣余额或写 REJECTED。

本批只增加 same-pay receive existence exclusion，不核 state/metadata/Fence/receive 完整身份。

## 2. Fresh receive gate

在 T104 freeze gate 后、wallet/claim 前，复用现有 `UserTccReceiveMapper.findByPayNo(request.payNo)` 一次：

- 任意 non-null row（CLAIMED、unknown state、CI pay、foreign、坏 key/meta）→ `IDEMPOTENCY_CONFLICT`/409 固定“支付号已有 TCC 接收事实”；
- 仅 null 才继续原 fresh Legacy wallet/claim/REJECTED 链；
- DAO/DataAccess 异常原样传播，不按 state/meta/flag 过滤。

existing/history/TCC/refund/GET 不增加 receive query；不改 T104、T96、owner/freeze、SQL 写、锁、API/DTO、RM/TC。

## 3. Table initialization

由于 HTTP configuration 默认关闭，`user_tcc_receive` 不能只由 `UserTccHttpConfiguration` 条件 Bean 创建。`BalanceTccTableInitializer` 构造器增加现有 `UserTccReceiveMapper`，`prepareTables` 幂等调用其 `ensureTable`；不改变 HTTP enabled flag、RM 注册或其它表 DDL。普通启动只准备现有来源表，不启动 HTTP 资源。

## 4. 验收

- Unit：null/DAO/old gate priority/normal Legacy existing-history receive zero read；无 receive row 保持。
- Native：Legacy 两入口 × sufficient/insufficient wallet × CLAIMED/known/unknown/CI/foreign/bad receive，均 409、bank 四表与 receive facts 不变；explicit ensureTable owned case 验证默认 off 也可读，循环删除自有 receive id/恢复资源。
- 健康 fresh、REJECTED、TCC/退款/GET、初始化三类表、source checksum 保持；不启动 HTTP/RM/TC、不宣称 receive/Fence 全因果。

## 5. 交付边界

实现仅允许注入现有 ReceiveMapper、fresh existence read 与 initializer ensureTable、必要 JavaDoc/测试；不得扩展 receive metadata proof 或其它资金路径。
