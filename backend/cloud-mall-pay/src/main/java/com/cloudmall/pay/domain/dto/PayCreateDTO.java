package com.cloudmall.pay.domain.dto;

import java.math.BigDecimal;

/** 创建模拟支付请求。 */
public class PayCreateDTO {
  /** 关联订单号。 */
  public String orderNo;

  /** 请求支付金额。 */
  public BigDecimal payAmount;
}
