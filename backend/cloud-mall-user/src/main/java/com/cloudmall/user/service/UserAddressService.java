package com.cloudmall.user.service;

import com.cloudmall.user.domain.dto.AddressRequestDTO;
import com.cloudmall.user.domain.vo.UserAddressVO;
import java.util.List;

/** 用户地址业务契约。 */
public interface UserAddressService {
  /** 按默认标志和ID倒序读取所属地址。 */
  List<UserAddressVO> addresses(Long userId);

  /** 按原顺序清理默认标志并保存新地址。 */
  UserAddressVO addAddress(Long userId, AddressRequestDTO request);

  /** 只更新所属地址，成功后清理其他默认标志。 */
  UserAddressVO updateAddress(Long userId, Long addressId, AddressRequestDTO request);

  /** 删除所属地址，缺失或不属于当前用户时返回404。 */
  void deleteAddress(Long userId, Long addressId);
}
