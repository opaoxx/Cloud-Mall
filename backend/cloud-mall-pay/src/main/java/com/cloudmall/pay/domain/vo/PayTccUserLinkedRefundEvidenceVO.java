package com.cloudmall.pay.domain.vo;

/**
 * 已核有界关联退款摘要，不含原退款键或账本行。
 *
 * @param queryAttempted 是否发起原User GET
 * @param status 固定采集状态
 * @param identityMatched 原身份与报告是否完整匹配
 * @param candidatesComplete 当前有界关联候选是否完整
 * @param observedCount 已核关联候选数
 * @param validatedTotalAmount 已核关联退款总额，不代表全系统结清
 * @param vectorSha256 已核脱敏向量
 */
public record PayTccUserLinkedRefundEvidenceVO(
    boolean queryAttempted,
    String status,
    boolean identityMatched,
    boolean candidatesComplete,
    Integer observedCount,
    String validatedTotalAmount,
    String vectorSha256) {
  /** 创建不持有原User JSON或原退款键的不可变摘要。 */
  public PayTccUserLinkedRefundEvidenceVO {}
}
