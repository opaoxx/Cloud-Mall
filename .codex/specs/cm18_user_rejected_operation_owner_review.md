# CM-18 16T113：User REJECTED operation owner proof

## 1. 结论与范围

GO。operation GET REJECTED 账本目前只证明拒款 snapshot 与退款集合，未证明对应 Legacy owner/无 TCC freeze；坏 attempt、APPLIED/TCC/ghost freeze 仍可能 200。

本批只为 original.state=REJECTED 增加 owner/freeze proof；APPLIED/T112、写服务与其他 GET 规则不改。

## 2. REJECTED owner gate

在原主行/source、linked/payment 集合、累计/REJECTED zero、selector/current membership 全部通过后、VO 返回前：

- 读取 attempt 一次；nonnull 必须 mode LEGACY、state 与 original.state 精确 REJECTED，user/pay/order/operation/ledger tuple 精确，xid/branch null，正合法 amount 数值等 original；
- attempt null 保历史兼容，但同 pay freeze point 必须 null；
- nonnull 非 LEGACY/未知/坏 tuple → `COMMON_CONFLICT/409` 固定“余额账本事实待核对”；
- attempt predicate 通过后同 pay `selectByPayNo` freeze 必须 null；任意 row（任意 state/meta/CI/foreign）同样 409；DAO 原样。

可将 Legacy attempt helper 泛化为 original.state 精确匹配；APPLIED 旧 caller 等价不变。TCC 不产生合法 REJECTED debit，nonnull TCC 按冲突处理。

## 3. 保持项与边界

主行/集合/marker 先于 owner；不读 current wallet、不写、不改 API/VO/SQL/TCC/Fence。REJECTED GET 新增 attempt 与有效/null freeze 各一次；APPLIED/T112 预算与语义保持。

不证明 receive、全账户、历史全域、source fencing 或跨服务因果。

## 4. 验收

- Unit：null/healthy Legacy owner、坏 mode/state/user/pay/order/key/xid/branch/amount、DAO、priority/read budget；REJECTED 失败固定 409，前门失败不读 owner。
- Native：真实余额不足 REJECTED 账本后 owner APPLIED/TRYING、wrong user/key/mode/TCC/unknown/XID/branch、CI pay、ghost freeze/null owner 均 409，四表 no-write；合法有/无 Legacy owner、current wallet 独立保持。
- 同 RR/资源清理，旧 T112 REJECTED zero-read 控制调整为 owner/freeze 读取；不启动 RM/TC/App。

Native 验证可使用 test-only Hikari owned datasource（maxPoolSize=6、minIdle=1、唯一 poolName，共享 static `@Bean` 且 `destroyMethod=""`），避免每条 JDBC 新建 TCP 导致 Windows TIME_WAIT/动态端口耗尽；管理员 DriverManager 连接仍保持少量、只读/CREATE/DROP 用途。AfterAll 必须在 try/finally 中核 active=0、关闭并断言 pool closed，精确 DROP 自有 UUID schema；保留原 source fingerprint、旧失败 XML/log 与 docker Unix-socket 清理证据，不写系统 netsh、监听端口或生产配置。

## 5. 交付边界

实现仅允许在 operation GET 原 selector/membership 后增加 REJECTED owner helper、泛化 Legacy state predicate及必要 JavaDoc/测试；不得修改 APPLIED/写路径或其它 scope。
