package com.cloudmall.pay.domain.vo;

/**
 * Order基本事实只读摘要；未经核验的外部身份只输出指纹。
 *
 * @param queryAttempted 是否已发起Order GET
 * @param status 固定采集分类
 * @param state 订单白名单状态，未识别时为null
 * @param identityMatched 基本身份和金额是否完整匹配
 * @param orderNoSha256 观察到的订单号指纹
 * @param userIdSha256 观察到的用户主键指纹
 * @param payAmountSha256 观察到的BigDecimal金额表示指纹
 */
public record PayTccOrderEvidenceVO(
    boolean queryAttempted,
    String status,
    String state,
    boolean identityMatched,
    String orderNoSha256,
    String userIdSha256,
    String payAmountSha256) {}
