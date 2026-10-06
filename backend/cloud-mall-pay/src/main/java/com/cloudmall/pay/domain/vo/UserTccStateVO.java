package com.cloudmall.pay.domain.vo;

import java.math.BigDecimal;

/** User TCC 只读状态响应。 */
public class UserTccStateVO {
  /** 用户主键。 */
  public Long userId;

  /** 支付号。 */
  public String payNo;

  /** 订单号。 */
  public String orderNo;

  /** 金额字符串。 */
  public String amount;

  /** TCC 操作键。 */
  public String operationKey;

  /** 最终账本键。 */
  public String ledgerKey;

  /** XID。 */
  public String xid;

  /** User 分支标识。 */
  public Long branchId;

  /** User 业务状态。 */
  public String state;

  /** 是否存在 APPLIED 账本。 */
  public boolean ledgerApplied;

  /** 返回用于载荷校验的金额。 */
  public BigDecimal amountValue() {
    return new BigDecimal(amount);
  }
}
