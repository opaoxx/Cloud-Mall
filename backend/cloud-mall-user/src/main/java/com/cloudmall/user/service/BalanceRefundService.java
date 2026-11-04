package com.cloudmall.user.service;

import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.vo.BalanceRefundVO;

/** 余额退款业务契约。 */
public interface BalanceRefundService {
  /** 在原来源及累计额度证明通过后幂等退款，余额与账本同事务。 */
  BalanceRefundVO refundInternal(Long userId, Long callerId, BalanceRefundRequestDTO request);
}
