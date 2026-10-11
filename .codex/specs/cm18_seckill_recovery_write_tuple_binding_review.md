# CM-18 16T42：秒杀恢复写入原始 tuple 绑定

## 1. 结论与范围

本切片 GO，基线为 `4dc85ec`，补足 T41 SQL fence 尚未绑定的原始 immutable tuple：claim/readback 后，另一 writer 仍可能改变 id、活动/用户维度、幂等键、数量、revision 或 createdAt，而三条 UPDATE 仅凭 scalar order/op/owner/status/lease 继续写错事实。

本批只扩展 `markReleased`、`markProjectionBlocked`、`retry` 三个现有 Mapper 方法的原始 snapshot 条件；不新增查询、事务、DDL、公开 API、Recovery 分支、Redis/MQ、锁序或 T37–T41 规则。

## 2. Mapper 参数与 WHERE 绑定

三方法直接迁移为增加 `@Param("original") SeckillQuotaSettlementPO original`，保留全部旧 scalar 参数、SET 和 T41 fence 条件；不得保留不带 snapshot 的旁路。WHERE 在原 T41 条件后追加：

- `id = #{original.id}`、`activity_id = #{original.activityId}`、`sku_id = #{original.skuId}`、`user_id = #{original.userId}`；
- `quantity = #{original.quantity}`、`revision = #{original.revision}`、`created_at = #{original.createdAt}`；
- `BINARY order_no = BINARY #{original.orderNo}`、`BINARY operation_key = BINARY #{original.operationKey}`、`BINARY idempotency_key = BINARY #{original.idempotencyKey}`。

这些是普通 exact/BINARY 参数比较，不新增 SQL 预查询或参数规范化；任一 original/current 必要字段为 null 时自然影响条件为 0，不把 null 转为空值或猜测相等。原生产 mandatory 列仍由 schema 保证，null 仅 unit/owned 准备阶段演练，不 ALTER。

Recovery 三个写调用都传入原始扫描 candidate snapshot；异常 catch 的 retry 也传同一 original，不传 raw readback、不从 current 重建参数。scalar 参数与 snapshot 不一致时 affected rows=0，沿现有 false/CAS_UNKNOWN/retry 语义。

## 3. 保持的可变字段与剩余窗口

不绑定 `status` 之外的可变诊断字段：T41 已校验当前 status、owner、leaseUntil；`updatedAt`、`lastError`、lease 元数据等不纳入 immutable snapshot。写成功只表示本 UPDATE 在执行时同时满足原 snapshot、当前 lease/status 和 scalar 条件。

本批不阻止 Lua 已执行后的旧投影、不形成跨 SQL/Redis 原子 fencing，也不验证 ACCEPTED/Order 来源或完整资金 tuple；T40 claim 点读、T38 CAS readback、T41 lease fence 继续独立生效。

## 4. 验收矩阵

- 旧 unit：只迁移三方法新增末尾 `original` 调用参数并回归原业务协议；不以 mirror mock 声称 SQL 条件成立。NULL snapshot/NULL expected 字段由真实 SQL 结果验证 affected rows=0。
- Direct Mapper owned MyBatis/MySQL：三方法验证 42 组坏 snapshot（id、activity/sku/user、幂等 case/accent/trailing-space、quantity、revision、createdAt、order/op 不一致及 snapshot id null）、6 组健康 exact snapshot，以及 post-Lua release/block/error 三组；确认 SQL 不标记错误事实、不写错行。source NOT NULL 列不 ALTER，NULL 仅通过绑定参数/owned 准备值验证 0 行。
- T38 受控 writer 使用同一原 candidate snapshot时，第二次 CAS readback 仍按原 proof；T37–T41、扫描游标、claim/retry/Lua、普通库存和 source checksum 回归保持。
- 保留旧 scalar 参数顺序，唯一在末尾追加 `original` 参数；其它公开 API、字段 SET、DDL/index、事务/锁序不扩展。owned DB/Redis/线程资源 finally 清理，不启动业务 App、RM/TC 或固定端口。

本切片只冻结 recovery 写 UPDATE 的原始 tuple 绑定，不处理 Lua 前后完整 fencing、读后窗口、TC/MQ 或 CM18 M1–M4；CM20/CM21 仍等待 CM18 整体完成。
