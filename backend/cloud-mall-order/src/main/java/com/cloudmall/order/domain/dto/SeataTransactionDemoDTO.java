package com.cloudmall.order.domain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/** CM-18 Seata 受控演练请求。 */
public class SeataTransactionDemoDTO {
  /** 仅用于演练幂等的请求标识。 */
  @NotBlank
  @Pattern(regexp = "cm18-[a-z0-9-]{1,50}")
  public String txId;

  /** 受控故障注入点。 */
  @NotBlank
  @Pattern(regexp = "NONE|ORDER_AFTER_WRITE|STOCK_AFTER_WRITE")
  public String failurePoint;
}
