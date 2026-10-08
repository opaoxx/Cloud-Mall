# CM-20260924-20：订单超时 Outbox 错误文本 Unicode 完整性评审

## 1. 评审结论与范围

结论：**GO，限定修复订单超时 outbox 失败路径的两层 Unicode 损坏。**

当前 `OrderOutboxDispatcher.compactError` 先将错误消息转 UTF-8，再按 1000 个字节截断并重新解码；中文/emoji 在字节边界可能变成 `U+FFFD`。随后 `OrderOutboxMapper.recordOutboxFailure` 又按 UTF-16 `length` 截断 `VARCHAR(512)`，可能拆分 surrogate pair 或过早丢弃非 BMP 字符。

本轮只保护 outbox `last_error` 的原始文本和最终列宽，不修改消息拓扑、confirm/dispatch 流、失败隔离、状态、attempts、where 条件、时间或其他 Mapper。

## 2. Dispatcher compactError 契约

`OrderOutboxDispatcher.compactError(Exception)` 保留现有兜底语义：

- `exception.getMessage()` 为 null 或 blank 时，返回 `exception.getClass().getSimpleName()`；
- 其他情况下直接返回完整 message；
- 删除 UTF-8 字节数组转换、1000 字节截断和重新解码；不 trim、替换、归一化或按字符另造错误文本。

完整 message 不在 Dispatcher 层复制为 UTF-8 截断文本，最终长度保护统一由 Mapper 完成。

## 3. Mapper 最终持久化契约

在 `OrderOutboxMapper` 增加私有 `clipOutboxError(String)`（名称可等价实现），`recordOutboxFailure` 的 error 参数必须经过该方法：

- null 返回 null；
- code point 数量不超过 512 时原样返回；
- 超过 512 时用 `offsetByCodePoints(0, 512)` 截取前 512 个完整 code point；
- 不拆有效 surrogate pair，不过滤、替换、trim、大小写折叠或 Unicode 归一化。

保留 `recordOutboxFailure` 的普通 SQL 和状态语义：`attempts=attempts+1`、传入的 `nextRetryAt`、当前 Shanghai `updated_at`，以及 `WHERE event_id=? AND status='PENDING'`。`PUBLISHED` 或其他非 PENDING 行仍返回 0、整行不变；`published_at` 继续保持原值（通常为 null）。

## 4. 兼容与排除边界

- 不修改 `order_outbox` schema、字符集、collation、列宽、索引、状态枚举或消息 payload。
- 不引入 common 共享工具；错误裁剪逻辑仅限 Dispatcher 的消息保真和 Outbox Mapper 的最终列宽。
- 不修改 attempts 递增、重试时间算法、PENDING/ PUBLISHED 条件、Rabbit publisher confirm、Rabbit 拓扑或异常隔离。
- 不修复其他 Mapper 的 UTF-16/字节截断，也不清洗历史 `last_error`。
- 不改变异常类型、类名兜底、事务边界或业务订单 checksum。

## 5. 验收矩阵

1. 真实隔离 MySQL strict/non-strict 下，直接调用 `recordOutboxFailure` 覆盖 null、空串、空白、短 emoji、511 ASCII + emoji、512/513 emoji、混合中文/ASCII/BMP 边界；最终保存文本按 512 code point 契约精确一致。
2. 真实 Dispatcher + Mapper 失败路径覆盖 333 中文加 emoji、251/512/513 emoji 和长混合 message；确认 Dispatcher 不产生 `U+FFFD`，Mapper 只在 512 code point 边界裁剪。
3. 失败写入保持 eventId、eventType、orderNo、payload、createdAt、publishedAt 等事实不变；PENDING 行仅按原算法递增 attempts、写入 error、nextRetryAt 和 updatedAt。
4. 非 PENDING 行、已发布行和不存在 eventId 返回原结果/0，整行不变；异常类名兜底在 null/blank message 下保持。
5. SQL/连接故障仍沿原异常语义传播；回归 Dispatcher 原 2 项 unit、17AT intent/普通订单相关测试、订单创建精度和原库 checksum，隔离数据库清理完整。

## 6. 明确不承诺

本轮只修订单超时 outbox 错误文本的 Unicode 保真与 512 code point 最终边界，不提供其他错误字段统一格式、不修历史文本、不改变消息投递可靠性或跨服务日志编码契约。
