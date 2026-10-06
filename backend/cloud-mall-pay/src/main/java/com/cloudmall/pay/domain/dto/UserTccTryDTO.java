package com.cloudmall.pay.domain.dto;

import java.math.BigDecimal;

/** User TCC Try 的完整不可变载荷。 */
public class UserTccTryDTO {
  /** 支付号。 */
  public String payNo;

  /** 订单号。 */
  public String orderNo;

  /** 扣款金额。 */
  public BigDecimal amount;

  /** XID。 */
  public String xid;

  /** TCC 操作键。 */
  public String operationKey;

  /** 最终账本键。 */
  public String ledgerKey;
}
