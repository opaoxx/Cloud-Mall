package com.cloudmall.user.domain.dto;

import javax.validation.constraints.NotBlank;

/** 用户注册和登录请求。 */
public class CredentialsDTO {
  /** 登录用户名。 */
  @NotBlank public String username;

  /** 登录密码。 */
  @NotBlank public String password;
}
