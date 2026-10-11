package com.cloudmall.pay.domain.vo;

/**
 * 单份交付或取消台账的不可变观察摘要；租约字段不授权恢复动作。
 *
 * @param present 台账是否存在
 * @param state 白名单状态，缺行null，未知值UNRECOGNIZED
 * @param identityMatched 原载荷与资金标识是否匹配，缺行null
 * @param attempts 已记录尝试次数，不推断可重试性
 * @param nextRetryAt 原重试时间，上海偏移
 * @param leasePresent 原租约持有者是否非空，不表示租约有效
 * @param leaseUntil 原租约截止时间，上海偏移
 * @param errorPresent 是否记录错误，不返回错误文本
 * @param xidSha256 原全局标识指纹，取消台账没有该字段
 * @param refundKeySha256 原退款键指纹，取消台账没有该字段
 */
public record PayTccTaskEvidenceVO(
    boolean present,
    String state,
    Boolean identityMatched,
    Integer attempts,
    String nextRetryAt,
    boolean leasePresent,
    String leaseUntil,
    boolean errorPresent,
    String xidSha256,
    String refundKeySha256) {
  /** 创建不可变台账摘要，不携带可重放凭证。 */
  public PayTccTaskEvidenceVO {}
}
