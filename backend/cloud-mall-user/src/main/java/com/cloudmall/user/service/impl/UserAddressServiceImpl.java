package com.cloudmall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.AddressRequestDTO;
import com.cloudmall.user.domain.po.UserAddressPO;
import com.cloudmall.user.domain.vo.UserAddressVO;
import com.cloudmall.user.mapper.UserAddressMapper;
import com.cloudmall.user.service.UserAddressService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** 用户所属地址的查询、默认标志与增删改编排。 */
@Service
public class UserAddressServiceImpl implements UserAddressService {
  /** 用户收货地址事实。 */
  private final UserAddressMapper userAddressMapper;

  /** 注入地址访问器。 */
  public UserAddressServiceImpl(UserAddressMapper userAddressMapper) {
    this.userAddressMapper = userAddressMapper;
  }

  /** 查询当前用户的收货地址。 */
  @Override
  public List<UserAddressVO> addresses(Long userId) {
    // 1. 只读取当前用户的地址，按默认标志、地址标识倒序排列。
    List<UserAddressPO> addresses =
        userAddressMapper.selectList(
            new QueryWrapper<UserAddressPO>()
                .eq("user_id", userId)
                .orderByDesc("is_default")
                .orderByDesc("id"));
    // 2. 转换为既有字段的地址视图。
    return addresses.stream().map(UserAddressVO::from).toList();
  }

  /** 新增当前用户收货地址。 */
  @Override
  public UserAddressVO addAddress(Long userId, AddressRequestDTO request) {
    // 1. 沿原顺序先清除其他默认地址。
    if (request.isDefault) {
      clearDefaultAddress(userId, null);
    }
    // 2. 使用已认证身份保存新地址，主键由数据库生成。
    UserAddressPO address = toAddress(null, userId, request);
    userAddressMapper.insert(address);
    // 3. 返回包含回填主键的地址视图。
    return UserAddressVO.from(address);
  }

  /** 更新当前用户收货地址。 */
  @Override
  public UserAddressVO updateAddress(Long userId, Long addressId, AddressRequestDTO request) {
    // 1. 绑定调用方身份和目标地址。
    UserAddressPO address = toAddress(addressId, userId, request);
    // 2. 在同一次UPDATE中限定归属；未找到目标时不清理其他默认地址。
    if (userAddressMapper.updateOwnedAddress(address) == 0) {
      throw new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404);
    }
    // 3. 成功后清理其他默认标志并返回地址视图。
    if (request.isDefault) {
      clearDefaultAddress(userId, addressId);
    }
    return UserAddressVO.from(address);
  }

  /** 删除当前用户收货地址。 */
  @Override
  public void deleteAddress(Long userId, Long addressId) {
    // 1. 在同一次DELETE中限定地址和用户身份。
    int changedRows =
        userAddressMapper.delete(
            new QueryWrapper<UserAddressPO>().eq("id", addressId).eq("user_id", userId));
    // 2. 未删除所属地址时返回原404。
    if (changedRows == 0) {
      throw new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404);
    }
  }

  /** 取消用户的其他默认地址。 */
  private void clearDefaultAddress(Long userId, Long excludedAddressId) {
    UpdateWrapper<UserAddressPO> updateWrapper =
        new UpdateWrapper<UserAddressPO>().eq("user_id", userId).set("is_default", false);
    if (excludedAddressId != null) {
      updateWrapper.ne("id", excludedAddressId);
    }
    userAddressMapper.update(null, updateWrapper);
  }

  /** 将请求对象转换为地址持久化对象。 */
  private UserAddressPO toAddress(Long addressId, Long userId, AddressRequestDTO request) {
    UserAddressPO address = new UserAddressPO();
    address.id = addressId;
    address.userId = userId;
    address.receiverName = request.receiver;
    address.receiverPhone = request.phone;
    address.regionDetail = request.detailAddress;
    address.defaultAddress = request.isDefault;
    address.updatedAt = LocalDateTime.now();
    address.createdAt = address.updatedAt;
    return address;
  }
}
