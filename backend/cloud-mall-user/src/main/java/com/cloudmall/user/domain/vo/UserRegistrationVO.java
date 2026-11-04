package com.cloudmall.user.domain.vo;

import java.util.Objects;

/** 注册成功响应。 */
public class UserRegistrationVO {
  /** 数据库生成的用户身份，保持JSON数字。 */
  public final Long userId;

  /** 注册用户名。 */
  public final String username;

  /** 构造与原Map非null值约束一致的响应。 */
  public UserRegistrationVO(Long userId, String username) {
    this.userId = Objects.requireNonNull(userId);
    this.username = Objects.requireNonNull(username);
  }
}
