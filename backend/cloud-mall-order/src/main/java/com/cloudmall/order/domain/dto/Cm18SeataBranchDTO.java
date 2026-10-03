package com.cloudmall.order.domain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/** CM-18 Stock 分支请求，仅传递受控演练标识。 */
public class Cm18SeataBranchDTO {
  /** 与 order 侧相同的演练请求标识。 */
  @NotBlank
  @Pattern(regexp = "cm18-[a-z0-9-]{1,50}")
  public String txId;

  /** 创建分支请求。 */
  public Cm18SeataBranchDTO() {}

  /** 使用演练请求标识创建分支请求。 */
  public Cm18SeataBranchDTO(String txId) {
    this.txId = txId;
  }
}
