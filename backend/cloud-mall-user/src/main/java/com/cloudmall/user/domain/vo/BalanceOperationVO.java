package com.cloudmall.user.domain.vo;

import com.cloudmall.user.domain.po.BalanceLedgerPO;
import java.math.BigDecimal;

/** 余额账本操作查询结果。 */
public class BalanceOperationVO {
  /** 幂等操作键。 */
  public String operationKey;

  /** 操作类型。 */
  public String type;

  /** 操作状态。 */
  public String state;

  /** 操作金额字符串。 */
  public String amount;

  /** 关联退款操作键。 */
  public String refundOperationKey;

  /** 从账本记录构造查询响应。 */
  public static BalanceOperationVO from(BalanceLedgerPO ledger, String refundOperationKey) {
    BalanceOperationVO result = new BalanceOperationVO();
    result.operationKey = ledger.operationKey;
    result.type = ledger.operationType;
    result.state = ledger.state;
    result.amount = ledger.amount.toPlainString();
    result.refundOperationKey = refundOperationKey;
    return result;
  }
}
