package com.cloudmall.pay.domain.vo;

/**
 * 已核有界终态归属摘要，不含原历史金额或原行。
 *
 * @param queryAttempted 是否发起原User GET
 * @param status 固定采集状态
 * @param identityMatched 原身份与报告是否完整匹配
 * @param candidatesComplete 当前有界终态候选是否完整
 * @param observedCount 已核终态候选数
 * @param validatedConfirmedCount 已核确认行数
 * @param validatedCancelledCount 已核取消行数
 * @param vectorSha256 已核脱敏向量
 */
public record PayTccUserTerminalFrozenOwnershipEvidenceVO(
    boolean queryAttempted,
    String status,
    boolean identityMatched,
    boolean candidatesComplete,
    Integer observedCount,
    Integer validatedConfirmedCount,
    Integer validatedCancelledCount,
    String vectorSha256) {
  /** 创建不持有原User JSON或其它支付号的不可变摘要。 */
  public PayTccUserTerminalFrozenOwnershipEvidenceVO {}
}
