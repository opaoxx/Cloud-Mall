# CM-20260924-20：库存动作首次写入校验评审

## 1. 评审结论与范围

结论：**GO，限定为 `OrderStockActionMapper.insert` 的写前物理身份和必填字段校验。**

当前 plain `INSERT` 依赖 MySQL strict/non-strict 行为，非法 orderNo、actionType、时间或非正主键可能在 strict 模式报错、非 strict 模式截断/默认化，留下不可信 action tuple；即使字段在物理上可写，未知 actionType 和空身份也会被后续读取/恢复门拦截但仍污染事实。

本轮只保护首次 action 写入，不修改 schema、HTTP/API、状态机、时间精度、月份、Product 指标、恢复器、Stock/Feign、事务或历史行。

## 2. insert 写前契约

`insert(long id, String orderNo, String actionType, LocalDateTime occurredAt)` 在执行任何 SQL 前必须拒绝：

- `id <= 0`；
- orderNo 为 null、blank 或 Unicode code point 数量超过 64；
- actionType 不是精确 `CONFIRM` 或 `ROLLBACK`；
- occurredAt 为 null。

非法输入抛 `IllegalArgumentException`，不执行 JDBC、不留下行、不改变既有事实。orderNo 非 blank 的空格、大小写、重音和非 BMP 字符按原值保留，不 trim、不归一化、不清洗 surrogate；只做 code point 计数。

精确合法输入继续由同一 plain INSERT 写入：

- `operation_key = orderNo + ":" + actionType`；
- `status='PENDING'`、`attempts=0`；
- `next_retry_at/created_at/updated_at` 均绑定同一 occurredAt；
- 返回 JDBC 原行数。

64 code point orderNo 加分隔符和合法方向 key 均自然满足既有 VARCHAR(128)；本轮不额外校验 key 长度、年份/月、DATETIME(3) 精度或时间范围。

## 3. 数据库异常与幂等边界

- 保留 plain INSERT、唯一键冲突的 `DuplicateKeyException` 原传播、表不存在/连接/约束/JSON 等数据库异常原传播；不把错误转成 0。
- 不新增 INSERT IGNORE、repair SQL、默认值、schema CHECK 或迁移。
- 非 strict 模式也必须在 SQL 前拒绝本轮非法字段，不能依赖数据库截断/默认化行为。
- 生产业务入口只传合法正主键、合法方向、原订单号和真实时间；测试构造历史坏 action 时，先插入合法行再在隔离夹具中更新坏字段，不增加业务 repair 路径。

## 4. 与既有读取/恢复边界

- 合法写入继续沿 AV/AW/AX/AY/AZ/BA/BB/BG 读取、动作、租约、attempts 和中断语义。
- 不改变已有 action 的 statusForOrder、paymentState、Recovery 或 Stock 调用顺序。
- 不自动修复历史非法 type/key/orderNo/time；读取门继续 UNKNOWN/fail closed。
- 不扩展为订单年月、时间精度、Product 实时指标或其他服务写入校验。

## 5. 验收矩阵

1. 真实 MySQL strict/non-strict 下，非法正负 id、required null、blank/超宽 orderNo、未知/blank actionType、null occurredAt 均在 SQL 前拒绝且零行；不伪造 schema 可为 NOT NULL 时间列写 null 的真实夹具。
2. 合法 64 ASCII/BMP/非 BMP code point orderNo、`CONFIRM/ROLLBACK` 和空格但 nonblank 原值成功写入；原 orderNo/type/key、status、三列时间绑定和行数正确。
3. 精确重复/唯一键冲突仍传播原 DuplicateKeyException；表缺失、连接、约束等数据库错误原传播，不吞错。
4. 回归 AW/AX/BG 及 action reader、Recovery、transaction、creation 测试；历史坏 type/orderNo 夹具通过合法插入后参数更新构造，生产无修复 SQL。
5. 正常 paid/cancel 不误拒绝，原订单 checksum、随机 fixture 清理和 Stock/Feign/API 契约保持。

## 6. 明确不承诺

本轮只防止新 action 写入产生物理非法或方向不可信 tuple，不清洗历史坏行，不修年月/时间范围/precision、Product 指标、跨服务事实或实际资金事故。
