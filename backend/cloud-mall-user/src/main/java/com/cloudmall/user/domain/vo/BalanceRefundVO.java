package com.cloudmall.user.domain.vo;

import java.math.BigDecimal;

/** 余额退款结果。 */
public class BalanceRefundVO {
  /** 幂等退款操作键。 */
  public String refundOperationKey;

  /** 操作状态。 */
  public String state;

  /** 操作后余额。 */
  public String balance;

  /** 从账本构造退款响应。 */
  public static BalanceRefundVO from(String operationKey, String state, BigDecimal balance) {
    BalanceRefundVO result = new BalanceRefundVO();
    result.refundOperationKey = operationKey;
    result.state = state;
    result.balance = balance.toPlainString();
    return result;
  }
}
