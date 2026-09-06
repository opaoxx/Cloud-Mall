package com.cloudmall.pay.domain.vo;

import java.math.BigDecimal;

/** 支付记录响应视图。 */
public class PayVO {
  /** 支付单号。 */
  public String payNo;

  /** 关联订单号。 */
  public String orderNo;

  /** 支付用户主键。 */
  public Long userId;

  /** 支付金额。 */
  public BigDecimal amount;

  /** 支付状态。 */
  public String status;

  /** 支付完成时间。 */
  public String paidAt;
}
