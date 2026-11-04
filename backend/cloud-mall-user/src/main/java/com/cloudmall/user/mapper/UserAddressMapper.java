package com.cloudmall.user.mapper;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.user.domain.po.UserAddressPO;
import org.apache.ibatis.annotations.Mapper;

/** 用户地址表数据访问接口。 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddressPO> {
  /** 在同一次条件更新中限定地址和所属用户，保留原实体字段更新策略。 */
  default int updateOwnedAddress(UserAddressPO address) {
    return update(
        address,
        new UpdateWrapper<UserAddressPO>().eq("id", address.id).eq("user_id", address.userId));
  }
}
