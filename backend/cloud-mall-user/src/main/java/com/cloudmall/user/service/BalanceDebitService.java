package com.cloudmall.user.service;

import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.DebitRequestDTO;
import com.cloudmall.user.domain.vo.BalanceDebitVO;

/** 余额扣款业务契约。 */
public interface BalanceDebitService {
  /** 按原支付键兼容协议扣款，拒款账本仍随BizException提交。 */
  BalanceDebitVO debit(Long userId, Long callerId, DebitRequestDTO request);

  /** 按持久化归属和操作键扣款或重放，意外写入失败回滚。 */
  BalanceDebitVO debitInternal(Long userId, Long callerId, BalanceDebitRequestDTO request);
}
