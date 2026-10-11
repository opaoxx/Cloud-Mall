package com.cloudmall.pay.domain.vo;

/**
 * 已核当前冻结归属摘要，未完成时不输出数量/金额/指纹。
 *
 * @param queryAttempted 是否发起User GET
 * @param status 固定采集状态
 * @param identityMatched 原锚点和完整证据是否匹配
 * @param candidatesComplete 当前有界TRYING候选是否完整
 * @param observedCount 已核候选数量
 * @param validatedFrozenTotal 已核冻结金额文本
 * @param vectorSha256 已核脱敏向量
 */
public record PayTccUserFrozenOwnershipEvidenceVO(
    boolean queryAttempted,
    String status,
    boolean identityMatched,
    boolean candidatesComplete,
    Integer observedCount,
    String validatedFrozenTotal,
    String vectorSha256) {
  /** 创建不持有原User JSON或其它支付号的不可变摘要。 */
  public PayTccUserFrozenOwnershipEvidenceVO {}
}
