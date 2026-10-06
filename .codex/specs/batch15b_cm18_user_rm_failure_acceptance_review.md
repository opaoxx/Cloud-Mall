# CM-20261006-18：15B User RM 故障验收规格

## 1. 评审结论

结论：**GO，执行 User RM 进程中断、TC 暂失和重启恢复验收；本轮不改业务代码、不接 Pay TCC。**

15B 只验证第 15A LocalTCC/Fence 适配在真实故障窗口下的收敛性。测试使用真实 Seata 1.5.2 TC、真实 MySQL、独立 User 测试库、稳定测试 appId 和独立 User RM 子 JVM。故障点全部位于 `src/test`：通过测试专用 loopback 控制通道暂停/杀死 RM，或控制测试客户端到 TC 的 TCP 代理；不新增生产 HTTP、配置故障开关或业务代码分支。

Pay 继续 LEGACY；不启动真实 Pay TCC，不把 Order/Stock 纳入 XID。本轮完成后只能宣称 User RM 故障矩阵通过，CM-18 仍未整体闭环。

## 2. 测试拓扑和隔离

- 真实 Seata 1.5.2 TC 使用现有测试实例；不停止、重启或修改共享 TC 容器。
- 每个测试批次创建随机数据库名，复制 `mall_user`、`balance_ledger`、`balance_debit_attempt`、`balance_tcc_debit`、`tcc_fence_log` 表结构和必要夹具。
- User RM 以独立子 JVM 启动，固定稳定 `appId`/resource group、唯一本地端口和唯一数据库连接；同一测试用例重启时复用相同 appId、XID、branch 和测试库。
- Parent TM 在测试进程中创建全局事务；测试专用 loopback 控制通道只用于让子 JVM 在已定义故障点暂停或退出，不提供生产映射。
- 所有 RM 子 JVM、测试线程和 TCP 代理在正常用例结束后清理；只要存在未决 XID、未决 Fence 或冻结额，必须保留数据库和 TC 记录，测试失败并输出恢复信息。

## 3. 故障点证明

禁止用固定 sleep 推断阶段。每个故障点必须通过测试 barrier、事务连接/锁、RM 日志和 MySQL 持久事实证明：

- Try 未提交：事务已进入业务方法但尚未 commit，持有用户行锁或测试 barrier 已确认；杀进程后核对事务回滚。
- Try 已提交：`balance_debit_attempt=TRYING`、`balance_tcc_debit=TRYING`、用户冻结额和 Fence TRIED 已可读，再杀进程。
- Confirm/Cancel 提交前：callback 已进入本地事务且未提交，或持有用户行锁并由控制通道确认；杀进程后核对本地事务回滚。
- Confirm/Cancel 提交后 ACK 前：业务行、账本/冻结额和 Fence 终态已提交，测试阻断 RM 对 TC 的返回或在返回前杀进程；不得把网络超时当作资金事实。
- TC 暂失：只断开测试客户端到 TC 的 TCP 代理并确认恢复，不停止共享 TC；TC 自身数据和容器保持不变。

## 4. 验收场景

### 4.1 Try 未提交时杀 RM

1. 开启全局事务并进入 User Try。
2. 测试 barrier 确认本地事务未提交后杀 RM 子 JVM。
3. Parent TM 回滚或等待 TC 终态。
4. 重启同 appId 的 RM，核对 `balance`、`frozen_balance`、attempt、TCC 行、账本和 Fence：不得有冻结、APPLIED ledger 或半完成 TRYING 资金事实。

### 4.2 Try 已提交后杀 RM

分别验证全局提交和全局回滚：

- Try 提交事实后杀 RM；TC 最终提交时，重启 RM 后 Confirm 只收敛一次。
- Try 提交事实后杀 RM；TC 最终回滚时，重启 RM 后 Cancel 只释放一次或保留唯一 tombstone。

必须复用原 appId、XID 和 branch；不得换 operationKey 重新尝试。检查没有双扣、双释放、重复 APPLIED 或冻结泄漏。

### 4.3 Confirm/Cancel 本地提交前杀 RM

在业务 callback 已进入本地事务但尚未提交时杀 RM：

- Confirm 场景核对余额冻结、TCC 状态和 ledger 均回滚到可重试事实；RM 重启后 TC 重试并只写一条 APPLIED ledger。
- Cancel 场景核对冻结未被部分释放；RM 重启后 TC 重试并只释放一次或写一次 tombstone。

不能因为第一次 callback 超时就自行标记成功或失败。

### 4.4 Confirm/Cancel 本地提交后 ACK 前杀 RM

在业务事实和 Fence 终态已提交、响应尚未返回 TC 时杀 RM。重启后让 TC 重试：

- Confirm 返回同一已确认账本事实，不重复扣减或插入账本。
- Cancel 返回同一已取消事实，不重复加回余额。
- 若 Fence 已被清理，完整业务载荷和终态账本核验一致时允许幂等成功；没有终态证据则返回 false，等待 TC 重试。

### 4.5 TC 连接暂失

通过测试专用 TCP 代理断开并恢复测试客户端到 TC 的连接，保持 TC 容器运行。验证：

- RM 本地事实不会因客户端暂时不可达而自行猜测提交或回滚。
- 连接恢复后使用原 XID/branch 收敛；TC 终态、Fence、业务表和余额一致。
- 断连期间无新的 Pay/Order/Stock 业务调用，且没有第二个 User branch。

## 5. 终态和未决处理

每个测试必须等待并确认 TC 终态属于已知成功/回滚终态后再清理夹具。测试不得调用 TC `getStatus=Unknown` 作为资金决议；Unknown 只能进入保留路径。

如果测试超时、RM 重启失败、Fence/业务状态冲突或冻结额非零：

- 保留测试数据库、完整 XID、branchId、appId、RM 日志和 TC 记录；
- 输出 `balance_debit_attempt`、`balance_tcc_debit`、`balance_ledger`、`mall_user`、`tcc_fence_log` 快照；
- 禁止删除数据库、删除 Fence、补写账本或人为释放余额；
- 将该用例标记为未决，不能宣称 15B 通过。

## 6. 通过标准

- 所有 15B 场景都使用真实 TC/RM/MySQL，且故障点有 barrier/锁/提交证据。
- 每个已知 TC 终态最终只产生一个业务终态；余额、冻结额、TCC 行、attempt、ledger 和 Fence 互相一致。
- RM 重启后可用原 XID/branch 完成 TC 重试；不产生第二 branch 或第二笔资金。
- 业务异常和本地事务回滚不留下部分冻结、部分释放、重复 APPLIED 或 Legacy/TCC 互斥破坏。
- 测试库和 TC 记录只在所有终态核对完成后清理；失败或 Unknown 场景保留现场。
- 第 15B 不要求 Pay TCC、Order/Stock 或完整支付闭环通过。
