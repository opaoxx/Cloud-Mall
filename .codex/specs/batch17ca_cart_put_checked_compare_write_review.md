# CM-20260924-20：购物车 PUT/Checked Compare-Write 评审

## 1. 评审结论与范围

结论：**GO，限定修复 Cart PUT 数量和 checked 更新之间的 Redis 读改写覆盖问题。**

当前 PUT/checked 读取旧 PO 后直接 write，两个入口可能互相覆盖字段；删除后旧读快照还可能 HSET 复活已删除项。

本轮只改 PUT/checked 的目标 field compare-write，不扩展 add 之外的其他并发模型、ABA、客户端 timeout、持久清理重试或 CM15 闭环。

## 2. 入口更新契约

### 2.1 共同快照

两个入口每次从同一次 HGETALL 获取 raw JSON 和 PO，并复用 BW 全 hash identity 校验及 BZ 的原始 JSON compare-write helper。不得用重新序列化旧 PO 代替 expected raw bytes。

目标 field 必须存在：缺失时返回原 NOT_FOUND/404，不创建、不复活、不刷新 TTL。

### 2.2 PUT quantity

保留 request quantity<1 的 INVALID/400 门。目标存在且 identity 合法时，只修改 quantity；request.checked 非 null 时沿原语义同步修改 checked，否则保持原 checked；product 摘要、unitPrice、addedAt 和其他 metadata 保持。

### 2.3 checked

目标存在且 identity 合法时，只将 checked 设置为原 Boolean.TRUE.equals(body.get("checked")) 语义，保持 quantity、摘要、金额、时间和其他 metadata。

## 3. Compare-write 与结果边界

- 每个入口最多 3 轮：compare 返回 0 且确认未写入后，重新 HGETALL、校验完整 hash、读取最新目标 PO、重新应用本次局部变更，再 compare-write。
- 条件成功才 HSET + PEXPIRE 原 30 天 TTL；mismatch 不写、不刷新 TTL。
- 三轮确认冲突耗尽返回既有 COMMON_CONFLICT/409。
- Lua unknown/null/non1 结果沿 BZ INTERNAL/500“结果未知”；Redis DataAccessException 沿既有 503；两者均不自动重试、不猜测是否应用。
- 不调用 Product，不新增 DTO/VO/PO，不修改 Redis key/JSON 字段或 write 其他路径。

## 4. API 契约补充

购物车 PUT/checked 对目标 field 使用有限 compare-write；目标缺失返回 404，三轮确认冲突返回 COMMON_CONFLICT/409，脚本结果未知为 INTERNAL/500，Redis 依赖故障保持既有 503。请求和响应字段保持不变。

## 5. 验收矩阵

1. 真实 Redis DB15、两个 Cart 实例同快照并发：PUT quantity=7 与 checked=true 最终同时保留；checked 不覆盖 quantity，数量不回退。
2. 控制顺序验证 add 先写后 PUT/checked compare；已删除目标在两入口旧快照下均返回 404，不 HSET、不复活、不刷新 TTL。
3. raw JSON 空格、Long.MAX productId、金额字符串、时间和 null 字段保持；合法更新只改声明字段。
4. 预算、unknown/null、Redis DataAccess、缺失目标和原 BU/BV/BW/BY 回归；无额外 Product 调用。
5. 其他 refresh/preview/clearChecked、add、ABA/delete-readd、客户端 timeout、持久清理和 CM15 不在本批。

## 6. 明确不承诺

本轮只保护 PUT/checked 两个入口的有限 compare-write，不提供全 Cart 并发原子性、客户端 exactly-once、历史数据修复或购物车持久清理闭环。
