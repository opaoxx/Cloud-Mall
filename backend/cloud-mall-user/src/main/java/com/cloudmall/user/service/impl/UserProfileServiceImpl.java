package com.cloudmall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.ProfileDTO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.UserVO;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.UserProfileService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/** 用户公开资料读取和可修改字段维护。 */
@Service
public class UserProfileServiceImpl implements UserProfileService {
  /** 用户资料事实。 */
  private final UserMapper userMapper;

  /** 注入用户资料访问器。 */
  public UserProfileServiceImpl(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  /** 查询当前用户资料。 */
  @Override
  public UserVO me(Long userId) {
    // 1. 读取调用方传入的已认证用户事实，缺失沿原404返回。
    UserPO user = currentUser(userId);
    // 2. 转为公开视图，保留金额字符串及空字段语义。
    return UserVO.from(user);
  }

  /** 更新当前用户允许修改的资料。 */
  @Override
  public UserVO update(Long userId, ProfileDTO profile) {
    // 1. 按用户身份更新允许修改的字段，显式null仍写入数据库。
    userMapper.update(
        null,
        new UpdateWrapper<UserPO>()
            .eq("id", userId)
            .set("nickname", profile.nickname)
            .set("phone", profile.phone)
            .set("avatar_url", profile.avatarUrl)
            .set("updated_at", LocalDateTime.now()));
    // 2. 重新读取更新后的资料，保持原读后响应语义。
    return me(userId);
  }

  /** 读取当前用户事实数据。 */
  private UserPO currentUser(Long userId) {
    UserPO user = userMapper.selectById(userId);
    if (user == null) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
    return user;
  }
}
