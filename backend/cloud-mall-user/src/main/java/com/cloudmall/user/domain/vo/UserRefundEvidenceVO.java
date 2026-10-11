package com.cloudmall.user.domain.vo;

/**
 * 固定退款键观察；缺行不表示不存在其他退款，部分退款不授权自动补退。
 *
 * @param present 固定键候选行是否存在
 * @param state APPLIED或UNRECOGNIZED，缺行null
 * @param amount 原退款金额字符串
 * @param balanceBefore 原退款前余额字符串
 * @param balanceAfter 原退款后余额字符串
 * @param identityMatched 原支付归属与原操作键是否匹配，缺行null
 * @param operationKeySha256 原退款操作键指纹
 * @param originalOperationKeySha256 原扣款键指纹
 */
public record UserRefundEvidenceVO(
    boolean present,
    String state,
    String amount,
    String balanceBefore,
    String balanceAfter,
    Boolean identityMatched,
    String operationKeySha256,
    String originalOperationKeySha256) {
  /** 创建不可变固定键摘要，不携带可重放操作键。 */
  public UserRefundEvidenceVO {}
}
