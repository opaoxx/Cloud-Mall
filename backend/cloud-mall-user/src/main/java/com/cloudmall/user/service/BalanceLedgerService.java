package com.cloudmall.user.service;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.dto.DebitRequestDTO;

/** 持久化余额账本操作业务契约。 */
public interface BalanceLedgerService {
  /** 兼容旧支付键的余额扣款入口。 */
  ApiResponse<?> debit(Long userId, Long callerId, DebitRequestDTO request);

  /** 按持久化操作键扣款，保留原事务与互斥语义。 */
  ApiResponse<?> debitInternal(Long userId, Long callerId, BalanceDebitRequestDTO request);

  /** 在只读RR快照中读取并证明账本操作。 */
  ApiResponse<?> balanceOperation(Long userId, String operationKey, Long callerId);

  /** 证明原资金来源并幂等退款。 */
  ApiResponse<?> refundInternal(Long userId, Long callerId, BalanceRefundRequestDTO request);
}
