package com.cloudmall.user.domain.dto;

import java.math.BigDecimal;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 用户服务内部余额退款请求。 */
public class BalanceRefundRequestDTO {
  /** 幂等退款操作键。 */
  @NotBlank public String refundOperationKey;

  /** 原扣款操作键。 */
  @NotBlank public String originalOperationKey;

  /** 支付号。 */
  @NotBlank public String payNo;

  /** 订单号。 */
  @NotBlank public String orderNo;

  /** 退款金额。 */
  @NotNull public BigDecimal amount;
}
