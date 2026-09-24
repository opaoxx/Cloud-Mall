package com.cloudmall.user.domain.dto;

import java.math.BigDecimal;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 用户服务内部余额扣款请求。 */
public class BalanceDebitRequestDTO {
  /** 幂等操作键，通常等于支付号。 */
  @NotBlank public String operationKey;

  /** 支付号。 */
  @NotBlank public String payNo;

  /** 订单号。 */
  @NotBlank public String orderNo;

  /** 扣款金额。 */
  @NotNull public BigDecimal amount;
}
