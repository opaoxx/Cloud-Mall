package com.cloudmall.user.domain.vo;

import com.cloudmall.user.domain.po.UserAddressPO;

/** 用户地址响应视图。 */
public class UserAddressVO {
  /** 地址主键。 */
  public Long id;

  /** 收货人姓名。 */
  public String receiver;

  /** 收货电话。 */
  public String phone;

  /** 地区及详细地址。 */
  public String detailAddress;

  /** 是否为默认地址。 */
  public boolean isDefault;

  /** 从地址持久化对象构造响应视图。 */
  public static UserAddressVO from(UserAddressPO address) {
    UserAddressVO view = new UserAddressVO();
    view.id = address.id;
    view.receiver = address.receiverName;
    view.phone = address.receiverPhone;
    view.detailAddress = address.regionDetail;
    view.isDefault = Boolean.TRUE.equals(address.defaultAddress);
    return view;
  }
}
