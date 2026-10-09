package com.cloudmall.pay.domain.dto;

/**
 * 仅在内存中传递的原资金查询锚点；不得序列化到响应或日志。
 *
 * @param payNo 原支付号
 * @param userId 原用户主键
 * @param orderNo 原订单号
 * @param amountString 原金额字符串
 * @param xid 原全局事务标识
 * @param operationKey 原User TCC操作键
 * @param ledgerKey 原扣款账本键
 * @param payBranchId 原Pay分支
 * @param userBranchId 原User分支
 * @param executionState 原执行状态
 * @param decision 原已持久化决议
 */
public record PayTccEvidenceAnchorDTO(
    String payNo,
    Long userId,
    String orderNo,
    String amountString,
    String xid,
    String operationKey,
    String ledgerKey,
    Long payBranchId,
    Long userBranchId,
    String executionState,
    String decision) {}
