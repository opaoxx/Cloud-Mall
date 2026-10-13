# CM-18 16T120：User APPLIED Legacy receive exclusion

## 1. 结论与范围

GO。`requireAppliedDebitOwnership` 已核 Legacy/TCC owner 与 freeze，但 attempt null/LEGACY 分支仍忽略同 pay `user_tcc_receive`；APPLIED DEBIT/REFUND GET、active fresh/existing refund 可继续成功。

本批只在 APPLIED owner proof 的 null/LEGACY 分支补 receive existence，不改 TCC owner、REJECTED/T119、disabled 或写路径。

## 2. Gate 规则

在原 attempt owner 与 freeze proof 通过后：

- attempt null 或 mode LEGACY 时调用现有 `UserTccReceiveMapper.findByPayNo(original.payNo)` 一次；任意 non-null row → active/fresh `BALANCE_REFUND_INVALID`/409 原 message，GET 通过外层已有映射转 `COMMON_CONFLICT/409` 固定“余额账本事实待核对”；
- 仅 null 才继续原 APPLIED GET/fresh/existing replay；
- TCC confirmed 分支不读取 receive；DAO/DataAccess 异常原样。

T117 表初始化复用；不核 receive metadata/state/Fence，不改全账户或 read-after-window 语义。

## 3. 保持项与验收

APPLIED GET、active fresh/existing refund、disabled 404、REJECTED/T119、TCC、T73–T119 的 source/owner/freeze/collection/wallet/写/API 保持；current wallet 不读。

- Unit：null/any receive/DAO/owner priority/预算；TCC 0 新 read；
- Native：四入口（DEBIT GET、REFUND GET、active fresh、active existing replay）× LEGACY/null owner × 六类 receive variant 均 409/no-write/facts unchanged；TCC owner（含合法 receive）作为健康 control，保持原结果且新增 receive read 为 0。GET 不读 current wallet；退款仍沿原 User 行锁/wallet 链，但新增门失败时不执行 credit，且不增加任何 wallet 读取。RR 同连接、资源清理保持。
- 不启动 RM/TC/App，不宣称完整 receipt/Fence/全资金因果。

## 4. 交付边界

实现仅允许扩展 `requireAppliedDebitOwnership` 的 null/LEGACY receive point read、GET 错误映射与必要 JavaDoc/测试；不得修改 TCC/REJECTED/disabled 或写路径。
