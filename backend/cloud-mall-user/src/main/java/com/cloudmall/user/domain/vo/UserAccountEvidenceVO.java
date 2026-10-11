package com.cloudmall.user.domain.vo;

/**
 * 账户资金与该用户可见TRYING行的冻结总额，不证明所有资金账本关联。
 *
 * @param present 账户是否存在
 * @param status ACTIVE、DISABLED或UNRECOGNIZED，缺行null
 * @param balance 可用余额字符串
 * @param frozenBalance 账户冻结余额字符串
 * @param tryingFrozenTotal 原TRYING行合计，缺失/坏聚合null
 * @param frozenProjectionMatched 完整合法聚合是否与账户冻结额相等，无法核验null
 */
public record UserAccountEvidenceVO(
    boolean present,
    String status,
    String balance,
    String frozenBalance,
    String tryingFrozenTotal,
    Boolean frozenProjectionMatched) {
  /** 创建不可变摘要，不包含密码、电话或账户角色。 */
  public UserAccountEvidenceVO {}
}
