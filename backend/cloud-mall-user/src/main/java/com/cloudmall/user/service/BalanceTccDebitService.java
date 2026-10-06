package com.cloudmall.user.service;

import com.cloudmall.user.domain.dto.BalanceTccDebitFinalizeDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitTryDTO;
import com.cloudmall.user.domain.vo.BalanceTccDebitVO;

/** User 本地余额 TCC 状态机契约。 */
public interface BalanceTccDebitService {
  /** 尝试冻结余额。 */
  BalanceTccDebitVO tryDebit(BalanceTccDebitTryDTO request);

  /** 确认已冻结余额。 */
  BalanceTccDebitVO confirmDebit(BalanceTccDebitFinalizeDTO request);

  /** 取消并释放已冻结余额。 */
  BalanceTccDebitVO cancelDebit(BalanceTccDebitFinalizeDTO request);
}
