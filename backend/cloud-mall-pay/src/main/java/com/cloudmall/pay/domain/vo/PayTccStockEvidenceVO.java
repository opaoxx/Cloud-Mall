package com.cloudmall.pay.domain.vo;

/**
 * Stock观察摘要，不等于原订单SKU或库存余额证明。
 *
 * @param queryAttempted 是否已发起Stock GET
 * @param status 固定采集分类
 * @param state 观察到的白名单状态，未识别时为null
 * @param identityMatched 原订单与完整观察向量是否通过本切片核验
 * @param observedItemCount 原数组长度，仅表示观察数量，未见数组时为null
 * @param orderNoSha256 Stock返回的原生文本订单号指纹
 * @param itemVectorSha256 完全验证后按SKU升序编码的明细向量指纹，否则null
 */
public record PayTccStockEvidenceVO(
    boolean queryAttempted,
    String status,
    String state,
    boolean identityMatched,
    Integer observedItemCount,
    String orderNoSha256,
    String itemVectorSha256) {}
