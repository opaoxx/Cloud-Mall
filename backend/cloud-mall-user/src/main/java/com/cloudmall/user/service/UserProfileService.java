package com.cloudmall.user.service;

import com.cloudmall.user.domain.dto.ProfileDTO;
import com.cloudmall.user.domain.vo.UserVO;

/** 用户资料业务契约。 */
public interface UserProfileService {
  /** 读取已认证用户的公开资料。 */
  UserVO me(Long userId);

  /** 更新允许修改的资料并重读结果。 */
  UserVO update(Long userId, ProfileDTO profile);
}
