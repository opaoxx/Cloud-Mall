package com.cloudmall.user;

import static org.mockito.Mockito.mock;

import com.cloudmall.user.config.UserAccountInitializer;
import com.cloudmall.user.controller.UserController;
import com.cloudmall.user.mapper.UserAddressMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.BalanceLedgerService;
import com.cloudmall.user.service.impl.AuthenticationServiceImpl;
import com.cloudmall.user.service.impl.UserAddressServiceImpl;
import com.cloudmall.user.service.impl.UserProfileServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 生成主键测试的真实账号服务装配；只有该测试不涉及的资金和Redis边界使用替身。 */
record UserAccountTestFixture(UserController controller, UserAccountInitializer initializer) {
  /** 使用给定真实或替身Mapper构造生产服务和HTTP委托。 */
  static UserAccountTestFixture create(UserMapper users, UserAddressMapper addresses) {
    return new UserAccountTestFixture(
        new UserController(
            new AuthenticationServiceImpl(users, mock(StringRedisTemplate.class)),
            new UserProfileServiceImpl(users),
            new UserAddressServiceImpl(addresses),
            mock(BalanceLedgerService.class)),
        new UserAccountInitializer(users));
  }
}
