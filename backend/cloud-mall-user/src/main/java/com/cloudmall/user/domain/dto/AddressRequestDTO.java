package com.cloudmall.user.domain.dto;

/** 用户收货地址请求。 */
public class AddressRequestDTO {
  /** 收货人姓名。 */
  public String receiver;

  /** 收货电话。 */
  public String phone;

  /** 地区及详细地址。 */
  public String detailAddress;

  /** 是否设为默认地址。 */
  public boolean isDefault;
}
