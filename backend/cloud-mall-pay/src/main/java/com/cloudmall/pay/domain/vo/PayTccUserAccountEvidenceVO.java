package com.cloudmall.pay.domain.vo;

/**
 * 已验证User账户/固定退款的不可变摘要，未验证资金字段保留null。
 *
 * @param queryAttempted 是否尝试原User GET
 * @param status 固定采集状态
 * @param tccState 白名单User状态
 * @param identityMatched 原身份及四个指纹是否已核验
 * @param ledgerApplied 已核验的DEBIT存在性，未完成核验null
 * @param accountStatus 已核验账户状态
 * @param balance 已核验可用余额字符串
 * @param frozenBalance 已核验账户冻结余额字符串
 * @param tryingFrozenTotal 已核验原TRYING总额字符串
 * @param fixedRefundPresent 是否观察到固定退款键，未验证null
 * @param fixedRefundAmount 已核验固定退款金额，缺行或未验证null
 */
public record PayTccUserAccountEvidenceVO(
    boolean queryAttempted,
    String status,
    String tccState,
    boolean identityMatched,
    Boolean ledgerApplied,
    String accountStatus,
    String balance,
    String frozenBalance,
    String tryingFrozenTotal,
    Boolean fixedRefundPresent,
    String fixedRefundAmount) {
  /** 创建不可变摘要，不持有原User JSON或敏感标识。 */
  public PayTccUserAccountEvidenceVO {}
}
