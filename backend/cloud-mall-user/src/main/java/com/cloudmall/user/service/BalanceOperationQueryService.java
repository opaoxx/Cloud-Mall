package com.cloudmall.user.service;

import com.cloudmall.user.domain.vo.BalanceOperationVO;

/** 余额操作查询业务契约。 */
public interface BalanceOperationQueryService {
  /** 在同一只读RR快照中核对操作、原来源及关联事实后返回视图。 */
  BalanceOperationVO balanceOperation(Long userId, String operationKey, Long callerId);
}
