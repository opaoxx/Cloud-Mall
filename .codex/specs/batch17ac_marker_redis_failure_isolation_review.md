# CM-20261007-20：Stock marker Redis 失败隔离评审

## 1. 评审结论与范围

结论：**GO，限定隔离 `recoverPendingMarkers` 单条 Redis marker GET 失败；不改变 MySQL 扫描、游标、状态、outbox、Redis guard 统计、API 或 Rabbit 拓扑。**

当前每条 Redis GET 位于候选处理外层，连接异常或 GuardedStockRedisTemplate 转换出的 `REDIS_SERVICE_UNAVAILABLE`/503 会中断同批后续记录，使已提交 MySQL outbox 也无法继续由独立 outbox 扫描收敛。

## 2. 单条 GET 异常边界

每条候选的 Redis marker GET 使用小范围 try/catch，仅隔离以下可预期依赖故障：

- Spring DataAccessException/Redis 连接或命令失败；
- BizException code=`REDIS_SERVICE_UNAVAILABLE` 且 HTTP 503。

捕获后记录 warn 并 continue：

- 不把失败解释为 marker 缺失；
- 不调用 markUnknown、accept、释放配额或修改 MySQL reservation；
- 不回退 17AB cursor；
- 不阻断同批后续候选；
- 不新增额外重试、熔断或指标，沿现有 GuardedStockRedisTemplate 统计。

其他 BizException、RuntimeException 和 Error 继续传播，保留原编程/业务错误边界；MySQL 扫描查询失败仍传播并结束本轮。

## 3. 正常路径与 outbox 独立性

- Redis GET 成功返回 null/marker 缺失时，继续原 grace/markUnknown 逻辑；
- Redis GET 成功返回匹配 marker 时，继续原 accept/outbox CAS 逻辑；
- Redis GET 失败不代表 marker 缺失，也不调用 accept；
- 即使本轮全部 marker GET 失败，仍必须进入原独立 `seckill_order_outbox` 扫描/投递流程，不要求先确认 Redis marker；
- 已存在且已提交的 outbox/PUBLISHED 事实不因 Redis 暂时不可用被回退或改写。

## 4. 验收矩阵

1. 真实 MySQL 两条 pending reservation：首条 Redis GET DataAccessException，后条 marker 正常，后条按原逻辑处理，首条整行保持；
2. Redis raw connection exception 与 Guarded Redis 503 BizException 分别验证：失败行不 markUnknown/accept，后续行可继续；
3. 预提交 outbox/PUBLISHED 与全部 marker GET 失败并存时，独立 outbox 扫描仍继续，PUBLISHED 不被修改；
4. 其他 BizException/RuntimeException/Error 继续抛出；MySQL scan failure 继续传播；
5. Redis GET 成功 null 的旧 grace 基线保持；17AB cursor 已读即前移，不因单条 Redis 失败回退；
6. Redis、acceptance、Rabbit 使用替身时只验证本地状态/CAS，不宣称共享 Redis 容器或真实网络故障闭环。

## 5. 实施门与范围声明

实现限定在 marker GET 的单条异常隔离 try/catch；不改 Redis 模板 guard、统计、重试、MySQL schema、状态枚举、outbox、游标、API 或共享容器。本轮只避免单条 Redis 失败阻断同批和独立 outbox 恢复。
