# CM-20260924-20：普通订单读取订单号精确身份评审

## 1. 评审结论与范围

结论：**GO，限定修复 `OrderServiceImpl.find(orderNo)` 对数据库 collation 命中别订单行缺少订单号身份核验的问题。**

共享 `find` 被 get、paymentState、cancel、paid、confirm、consumeTimeout 等路径使用。当前它把 `findOrder` raw row 直接映射为 `OrderVO`，没有核对存储 `order_no` 与请求 orderNo；CI/AI/PAD SPACE 可能让大小写或存储重音/尾空格别名进入后续 view、明细、鉴权、状态 CAS、Stock/action 流程。

本轮只在共享 `find` 增加原始订单号精确身份门，不修改 Mapper SQL、schema、collation、公共 API、状态、金额时间、认证策略、正常写路径、消息 ACK 或 list 查询。

## 2. find 身份门与顺序

保持 `tableFromNo(orderNo)` 的既有格式和月份校验。随后读取 `orderSqlMapper.findOrder(table, orderNo)` 的 raw rows：

1. raw rows 为空：沿既有 `NOT_FOUND/404` 返回；
2. 取首行的 `order_no` 原始值，只有它是 `String` 且 `Objects.equals(requestOrderNo, storedOrderNo)` 精确相等时才继续；
3. stored order_no 为 null、非 String、大小写/重音/尾随空格不同或其他订单号：按既有订单不存在 `NOT_FOUND/404` 返回；
4. 身份门通过后，才执行原 `view`、items 查询、后续鉴权和业务流程。

不得 trim、大小写转换、Unicode 归一化、从请求重建存储订单号或使用别行数据修正原行。订单号身份门必须位于 `view`/明细查询/用户授权/状态 CAS/Stock RPC 前。

## 3. 调用链与兼容边界

- get/paymentState/cancel/paid/confirm/consumeTimeout 等共享 `find` 的读取身份得到统一保护；本轮不声称 list 等其他 raw order 查询已形成全量身份闭环。
- 已有真实精确订单号读取、鉴权、状态转换和 action 写路径保持原语义。
- 非法月份、null、blank、包含 accent/trailing 的请求继续由既有 `tableFromNo` 在 SQL 前返回 404；本轮不把这些输入称作新增修复。
- SQL/连接异常继续原 DataAccess/异常传播，不猜成 404；真实无记录仍 404。
- 不修改 `findOrder` 的 SQL、排序、collation、明细查询方法、Mapper 签名或公共 HTTP 字段。

## 4. 验收矩阵

1. unit 构造首 raw row 的错单、null、非 String order_no：均 404，且不调用 `findOrderItems`、updateStatus、Stock/action 查询或写入。
2. 真实 MySQL fixture 验证存储 order_no 与请求 orderNo 的 case/accent/trailing collation 候选：get/paymentState 返回 404，cancel/paid/confirm/timeout 不执行订单 CAS、action 或 Stock；原表快照不变。
3. 精确匹配读取保持正常；已有合法状态转换、用户归属、跨用户 403、真实无记录 404 和 SQL 故障传播保持。
4. 回归 17BD Legacy Stock 身份、17BE action lookup、普通创建精确幂等重放/新订单读取，确认不会误拒绝合法行；原订单 checksum 和随机 fixture 清理完整。
5. Stock/RPC 为替身时只验证本地订单身份门，不宣称跨用户泄露、真实支付事故或网络故障演练。

## 5. 明确不承诺

本轮只绑定共享 `find` 的请求订单号与存储订单号，不修复 list/其他 raw 查询、字符集迁移、历史 alias 数据清洗、跨服务 exactly-once 或其他订单身份来源。
