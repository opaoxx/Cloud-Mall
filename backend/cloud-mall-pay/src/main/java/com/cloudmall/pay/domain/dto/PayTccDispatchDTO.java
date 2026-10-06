package com.cloudmall.pay.domain.dto;

import java.math.BigDecimal;

/** Pay 到 User 的 TCC 一次性派发载荷。 */
public class PayTccDispatchDTO {
  /** 支付号。 */
  public String payNo;

  /** 用户主键。 */
  public Long userId;

  /** 订单号。 */
  public String orderNo;

  /** 扣款金额。 */
  public BigDecimal amount;
}
