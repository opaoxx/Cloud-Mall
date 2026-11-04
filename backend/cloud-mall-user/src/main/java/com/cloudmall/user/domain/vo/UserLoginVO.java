package com.cloudmall.user.domain.vo;

import java.util.Objects;

/** 登录成功响应。 */
public class UserLoginVO {
  /** Redis不透明Bearer Token。 */
  public final String token;

  /** 原用户公开资料。 */
  public final UserVO user;

  /** 构造与原Map非null值约束一致的响应。 */
  public UserLoginVO(String token, UserVO user) {
    this.token = Objects.requireNonNull(token);
    this.user = Objects.requireNonNull(user);
  }
}
