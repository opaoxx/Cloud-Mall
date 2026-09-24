package com.cloudmall.user.domain.vo;

import java.math.BigDecimal;

/** 余额扣款结果。 */
public class BalanceDebitVO {
  /** 幂等操作键。 */
  public String operationKey;

  /** 操作状态。 */
  public String state;

  /** 操作后余额。 */
  public String balance;

  /** 从账本构造扣款响应。 */
  public static BalanceDebitVO from(String operationKey, String state, BigDecimal balance) {
    BalanceDebitVO result = new BalanceDebitVO();
    result.operationKey = operationKey;
    result.state = state;
    result.balance = balance.toPlainString();
    return result;
  }
}
