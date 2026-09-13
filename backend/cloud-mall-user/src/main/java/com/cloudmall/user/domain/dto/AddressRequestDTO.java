package com.cloudmall.user.domain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/** 用户收货地址请求。 */
public class AddressRequestDTO {
  /** 收货人姓名。 */
  @NotBlank(message = "收货人不能为空")
  public String receiver;

  /** 收货电话。 */
  @NotBlank(message = "手机号不能为空")
  @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号必须是11位数字")
  public String phone;

  /** 地区及详细地址。 */
  @NotBlank(message = "详细地址不能为空")
  public String detailAddress;

  /** 是否设为默认地址。 */
  public boolean isDefault;
}
