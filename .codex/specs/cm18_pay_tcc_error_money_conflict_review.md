# CM-18 16T173：Pay TCC execution evidence money conflict

## 1. 结论与范围

GO。PayTccExecutionEvidence.classify 对 execution/receipt/user amount NULL 或 malformed text 可能 NPE/NumberFormat，Coordinator recover 可将畸形资金载荷降为 UNKNOWN 并继续观察覆盖。

本批只补金额存在/解析安全门，不扩金额域或状态机。

## 2. Money gates

- entry 必须 require `execution != null && execution.amount != null`，否则既有 `PAY_TCC_EXECUTION_CONFLICT/409`；
- receipt 非 null 时 `pay.amount` 必须 non-null，否则同 conflict；
- User amount 比较改为 private `matchesUserAmount`：null/NumberFormat → false→既有 conflict，合法 BigDecimal 继续原 compareTo 数值语义；
- 不增加正数/18.2/scale/domain 规则，保 `10`/`10.000`/`1E+1` 等数值等价表示。

User/receipt NULL 缺失整体仍可沿原 UNKNOWN（仅对应端缺失）；完整 tuple/branch/Fence/终态/DAO/RR/Coordinator catch/API/写路径不改。

## 3. 验收与边界

- Unit：execution/receipt/user malformed/null money red；合法双端 CONFIRMED/取消/TRYING/UNKNOWN、identity/amount mismatch/Fence/branch controls；
- Coordinator recover + real Evidence/Mapper + controlled bridge/Store/TC substitutes：畸形金额由原 UNKNOWN 变 conflict 且不 observe/no TC，缺一端仍合法 UNKNOWN；不写原 DB/服务。
- 测试不启动真实资金/事务链，不宣称真实 TC/账户因果；保 T169 时序观察。

## 4. 交付边界

实现仅允许增加金额 null/parse guards 与 helper、必要 JavaDoc/测试；不得修改 SQL/RR/DTO/API/状态/Fence/金额域协议。
