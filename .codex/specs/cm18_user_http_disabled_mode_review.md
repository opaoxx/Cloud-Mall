# CM-18 16T130：User HTTP disabled TCC mode

## 1. 结论与范围

GO。HTTP/RM 可开启而 `cloudmall.user.tcc-debit.enabled=false` 时，fresh prepare 仍可能先提交 CLAIMED receipt，再由 Local Try 返回 PAY_DEBIT_MODE_UNAVAILABLE/503，永久消耗派发权。

本批复用既有 debit flag 的 observed value，阻断 disabled fresh winner；不新增 flag、不改 HTTP 字段或 TCC settle。

## 2. Mode observation gate

`UserTccHttpConfiguration` 读取现有 `cloudmall.user.tcc-debit.enabled`，以显式 constructor 参数传入 `UserTccHttpService`；所有旧 direct constructors/callers 显式传 true，禁止无 mode overload。

`prepare` 保留一次用户读取、认证/载荷校验与 T129 status 参数，调用四参 `receives.claim(userId, request, user.status, debitEnabled)`：

- winner=0：已有 receipt replay，保持 query.read，不因当前 flag false 拦截 TRYING/CONFIRMED/CANCELLED；
- winner=1：原 INSERT/lock/payload proof 通过后，flag=false 抛 `PAY_DEBIT_MODE_UNAVAILABLE/503`，REQUIRES_NEW 回滚 receipt，不绑定 RootContext、不调用 LocalTCC resource；
- winner=1、flag=true：沿原 prepare/LocalTCC/state/bind。

flag 是 prepare 已观察值；不承诺读取后 flag 漂移的 fencing。无 SQL/锁/HTTP DTO/API/DDL 变化。

## 3. 保持项与验收

用户 status disabled 门、existing replay、HTTP/TCC configuration 条件、RM/TC、GET/state、receive DDL 与 T129 行为保持。

- Unit：mode false fresh/replay、winner/status 优先、constructor/config 传值；false fresh 无 resource/query/bind/receipt，replay 仍只读。
- Native：HTTP active user mode=false fresh 无 receipt/resource，重新开启同请求 TRYING 成功；再关闭 mode replay 只读；caller RootContext 保持。
- 资源/六表 facts finally 清理，不启动 RM/TC；不宣称 mode/status read→claim fencing。

## 4. 交付边界

实现仅允许新增已有 flag 的 constructor 传递、claim mode 参数及 winner gate、必要 JavaDoc/测试；不得改其它 TCC/API/资金路径。
