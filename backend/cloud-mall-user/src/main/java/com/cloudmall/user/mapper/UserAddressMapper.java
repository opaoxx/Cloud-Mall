package com.cloudmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.user.domain.po.UserAddressPO;
import org.apache.ibatis.annotations.Mapper;

/** 用户地址表数据访问接口。 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddressPO> {}
