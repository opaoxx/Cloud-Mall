package com.cloudmall.user.service;

import com.cloudmall.user.domain.dto.CredentialsDTO;
import com.cloudmall.user.domain.vo.UserLoginVO;
import com.cloudmall.user.domain.vo.UserRegistrationVO;

/** 账号认证业务契约。 */
public interface AuthenticationService {
  /** 注册用户并返回数据库生成身份。 */
  UserRegistrationVO register(CredentialsDTO credentials);

  /** 核验账号密码并保存两小时登录态。 */
  UserLoginVO login(CredentialsDTO credentials);

  /** 按既有Bearer头语义删除登录态。 */
  void logout(String authorization);
}
