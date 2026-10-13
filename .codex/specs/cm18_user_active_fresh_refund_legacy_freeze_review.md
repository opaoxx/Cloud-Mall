# CM-18 16T101：User active fresh refund Legacy freeze

## 1. 结论与范围

GO。T99 已证明 non-null LEGACY attempt 的 owner tuple，但同 pay 仍可能残留 TCC freeze；T100 只保护 attempt=null，因此有效 Legacy owner 仍可能被幽灵 freeze 陪同并继续退款。

本批把 T100 的 freeze presence 门扩展至 active fresh 的 null 或 LEGACY attempt，不扩展 receive/Fence、全历史或 source fencing。

## 2. 门位置与规则

在 T99 owner predicate（或 T98 TCC predicate）通过后、T75 aggregate/wallet/credit 前：

- active `attempt == null || "LEGACY".equals(attempt.mode)` 时，复用 `balanceTccDebitMapper.selectByPayNo(request.payNo)`；只有 null 才继续历史/LEGACY fresh，任意 row（CONFIRMED/TRYING/CANCELLED/UNKNOWN、CI/foreign、任意 metadata）沿 `BALANCE_REFUND_INVALID`/409 原 message；
- active TCC 仅沿 T98 原 confirmed/freeze proof，不新增第二次 freeze read；
- unknown/non-LEGACY mode 仍先沿 owner predicate 失败，不执行此 presence read；
- DAO/DataAccess 异常原样传播。

T100 null owner 的读数与兼容语义保留；Legacy 有效 owner 新增一次 freeze SELECT。source proof、T97 bounded DEBIT、T75、wallet/ledger、disabled 404、existing replay 不改。

## 3. 非目标与保持项

不核 freeze metadata、state、身份或 Fence，不修 ghost receive、不改历史、不新增 SQL/API/写入/锁/TCC/Redis；不宣称完整 Legacy/TCC owner、全资金因果或跨服务 fencing。

## 4. 验收

- Unit：null/LEGACY owner 的 null、各 state、CI/foreign freeze、DAO fault、bad owner 优先、查询计数；TCC/unknown 不额外读，existing replay 不读。
- Native：真实 Legacy debit + ghost freeze 六状态/CI/foreign，active fresh HTTP/service 返回 409，四表 facts 不变；healthy Legacy、TCC、disabled/replay 与 T98/T99 回归保持。
- 资源 finally 清理，不启动 RM/TC/App；空历史兼容与 no-write 断言不放宽。

## 5. 交付边界

实现仅允许扩展 T100 presence 条件至 null/LEGACY、必要 JavaDoc/测试；不得修改 T99 owner predicate、T98 TCC proof、T75/wallet 或其它入口。
