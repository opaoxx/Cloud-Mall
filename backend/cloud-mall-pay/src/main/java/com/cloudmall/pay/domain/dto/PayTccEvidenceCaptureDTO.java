package com.cloudmall.pay.domain.dto;

import com.cloudmall.pay.domain.vo.PayTccReconciliationVO;

/**
 * 同一Pay只读事务采集的报告和内部锚点；不作为HTTP响应类型。
 *
 * @param localReport 已脱敏的本地报告
 * @param trustedAnchor 可信原锚点，证据不足时为null
 */
public record PayTccEvidenceCaptureDTO(
    PayTccReconciliationVO localReport, PayTccEvidenceAnchorDTO trustedAnchor) {}
