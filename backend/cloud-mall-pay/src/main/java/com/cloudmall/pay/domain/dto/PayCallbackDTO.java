package com.cloudmall.pay.domain.dto;

import java.math.BigDecimal;

/** 模拟支付回调请求。 */
public class PayCallbackDTO {
  /** 支付单号。 */
  public String payNo;

  /** 回调是否成功。 */
  public boolean success;

  /** 回调唯一标识。 */
  public String callbackId;

  /** 回调金额。 */
  public BigDecimal amount;

  /** 回调用户主键。 */
  public Long userId;
}
