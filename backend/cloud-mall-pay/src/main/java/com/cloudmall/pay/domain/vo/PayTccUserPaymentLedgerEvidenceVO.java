package com.cloudmall.pay.domain.vo;

/**
 * 已核有界同pay账本摘要，不包含原操作键或原行。
 *
 * @param queryAttempted 是否发起原User GET
 * @param status 固定采集状态
 * @param identityMatched 原身份与报告是否完整匹配
 * @param candidatesComplete 当前有界同pay候选是否完整
 * @param observedCount 已核同pay候选数，包含原DEBIT
 * @param validatedDebitCount 已核原DEBIT数
 * @param validatedRefundCount 已核REFUND数
 * @param validatedRefundTotalAmount 已核退款合计，不包含原DEBIT或推断结清
 * @param vectorSha256 已核脱敏向量
 */
public record PayTccUserPaymentLedgerEvidenceVO(
    boolean queryAttempted,
    String status,
    boolean identityMatched,
    boolean candidatesComplete,
    Integer observedCount,
    Integer validatedDebitCount,
    Integer validatedRefundCount,
    String validatedRefundTotalAmount,
    String vectorSha256) {
  /** 创建不持有原User JSON或原操作键的不可变摘要。 */
  public PayTccUserPaymentLedgerEvidenceVO {}
}
