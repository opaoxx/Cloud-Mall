package com.cloudmall.user.config;

import com.cloudmall.user.mapper.UserMapper;
import javax.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** 在任何 UserPO 查询前幂等补齐旧库的冻结余额列。 */
@Component
public class UserTableInitializer {
  /** 用户表结构访问器。 */
  private final UserMapper userMapper;

  /** 创建用户表初始化器。 */
  public UserTableInitializer(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  /** 检查并添加冻结余额列。 */
  @PostConstruct
  public void prepareTable() {
    if (userMapper.countFrozenBalanceColumn() == 0) {
      try {
        userMapper.addFrozenBalanceColumn();
      } catch (DataAccessException migrationFailure) {
        if (userMapper.countFrozenBalanceColumn() == 0) {
          throw migrationFailure;
        }
      }
    }
  }
}
