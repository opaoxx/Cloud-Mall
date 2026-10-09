package com.cloudmall.pay.domain.vo;

/**
 * 外部只读查询摘要；未经完整核验的ledgerApplied保持null。
 *
 * @param queryAttempted 是否已发起User GET
 * @param status 固定采集/核验状态
 * @param state User白名单状态，未观察或未识别时为null
 * @param identityMatched 是否全部身份和协议证据匹配
 * @param ledgerApplied 经核验的账本存在性，未核验时为null
 * @param xidSha256 外部原XID的指纹
 * @param branchIdSha256 外部原User分支的指纹
 */
public record PayTccUserEvidenceVO(
    boolean queryAttempted,
    String status,
    String state,
    boolean identityMatched,
    Boolean ledgerApplied,
    String xidSha256,
    String branchIdSha256) {}
