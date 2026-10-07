# CM-20261007-20：秒杀恢复业务指纹失败语义评审

## 1. 评审结论与范围

结论：**GO，限定阻止业务 requestHash 计算失败时用 raw SHA 伪造有效指纹；不改变 common hash 算法、合法输出、状态枚举、schema、API 或历史 payload 迁移。**

当前 `requestHash(event,raw)` 在 activity/sku/user/地址等业务字段无法解析时 fallback 为 `sha256(raw)`。persist 可能因此把非法业务事件保存为 REPLAY_PENDING；verify 又可能以 `sha256("")` 自校验非法 suppliedHash，调用 Stock 或错误放行。

## 2. 业务指纹与 raw 存证分离

新增 private `tryRequestHash(event)` 或等价纯函数：

- 复用现有 `SeckillRequestFingerprint` 算法、正数 long 解析、addressSnapshot 校验和合法数值字符串兼容；
- 业务维度任一缺失、负数/零、非整数、溢出、地址非法或算法异常时返回 null；
- 合法业务字段只返回真实业务 hash，不改变现有 hash 输出。

raw SHA-256 仍可用于保存无法解析事件的 request_hash 存证或 legacy 证据，但必须明确不是业务指纹：

- persist：若 supplied requestHash 存在，保留它；否则使用有效业务 hash，业务 hash 失败时才用 raw SHA 作为存证；但业务 hash 为 null 时状态必须为 CONFLICT，不得 REPLAY_PENDING；
- verify：业务 hash 为 null 立即标记 CONFLICT/既有退避，不调用 Stock、不发送 Rabbit；不得用 raw SHA 或 `sha256("")` 作为业务 proof；
- matchesEvent：要求业务 hash 非 null，且 event supplied hash 与 Stock fact hash 按既有合法大小写规则匹配；fallback hash 永远不能证明业务归属。

## 3. 状态与兼容边界

- 非法 event 的 suppliedHash 即使恰好等于 raw SHA、空字符串 SHA 或伪造值，也不能通过业务指纹门；进入 CONFLICT 并保留原 payload/hash 存证。
- 合法 activity/sku/user 数值字符串、地址和请求字段继续沿现有解析/兼容契约；不把合法字符串数字误判为失败。
- 17U 严格 JSON、17T 原始 bytes、17I JSON 幂等、17P 严格保存和 17Q eventId 身份继续生效。
- 不自动修复历史 REPLAY_PENDING、CONFLICT 或错误 fallback hash；历史记录留待独立核验。
- 不新增错误码、状态、RPC/API 或表字段；Stock/Rabbit 只接收业务 hash 已证明的事件。

## 4. 验收矩阵

1. MySQL 真实 DLQ 保存：负数、零、非整数、溢出 user/activity/sku、坏 address、缺业务维度且无 suppliedHash 均 CONFLICT，不生成可重放 REPLAY_PENDING。
2. suppliedHash 伪造为 raw SHA/sha256("") 时，非法 event 仍 CONFLICT；persist/verify 不把 fallback 当 proof。
3. verify 业务 hash 缺失或失败时不调用 Stock、不发送 Rabbit；恢复记录沿既有 lease/退避/完成写保护。
4. 合法数值字符串、合法地址和真实业务 hash 基线正常保存/验真/重放；大小写兼容保持原规则。
5. 原恢复、ACK/NACK、JSON 严格解析、原始 bytes 和 eventId 幂等测试回归；原 payload/hash 存证字段不被覆盖。

## 5. 实施门与范围声明

实现限定在 persist/verify 共用的业务指纹纯函数和失败分支；不修改 common hash 算法、合法消息结构、Stock/Order API、状态机、历史迁移、lease 或 Rabbit 拓扑。本轮只消除“存证 hash 被当作业务证明”的错误。
