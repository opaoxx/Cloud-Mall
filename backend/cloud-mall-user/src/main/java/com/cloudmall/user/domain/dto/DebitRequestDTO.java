package com.cloudmall.user.domain.dto;

import java.math.BigDecimal;
import javax.validation.constraints.NotBlank;

/** 用户余额扣减请求。 */
public class DebitRequestDTO {
  /** 支付幂等键。 */
  @NotBlank public String paymentKey;

  /** 扣减金额。 */
  public BigDecimal amount;
}
