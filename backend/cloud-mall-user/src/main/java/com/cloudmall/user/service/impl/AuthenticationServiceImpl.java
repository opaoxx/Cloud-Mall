package com.cloudmall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.CredentialsDTO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.UserLoginVO;
import com.cloudmall.user.domain.vo.UserRegistrationVO;
import com.cloudmall.user.domain.vo.UserVO;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.AuthenticationService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** 用户注册、凭据校验及Redis登录态维护。 */
@Service
public class AuthenticationServiceImpl implements AuthenticationService {
  /** 用户身份及密码事实。 */
  private final UserMapper userMapper;

  /** 登录态存储，保持两小时Token契约。 */
  private final StringRedisTemplate redis;

  /** 原BCrypt密码摘要策略。 */
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

  /** 注入用户持久化和登录态依赖。 */
  public AuthenticationServiceImpl(UserMapper userMapper, StringRedisTemplate redis) {
    this.userMapper = userMapper;
    this.redis = redis;
  }

  /** 注册用户并返回用户标识。 */
  @Override
  public UserRegistrationVO register(CredentialsDTO credentials) {
    // 1. 校验用户名是否已被占用。
    long duplicateCount =
        userMapper.selectCount(new QueryWrapper<UserPO>().eq("username", credentials.username));
    if (duplicateCount > 0) {
      throw new BizException("USER_DUPLICATE_USERNAME", "用户名已存在", 409);
    }
    // 2. 保存密码摘要、角色和初始余额，由数据库回填用户主键。
    LocalDateTime currentTime = LocalDateTime.now();
    UserPO user = new UserPO();
    user.username = credentials.username;
    user.passwordHash = ENCODER.encode(credentials.password);
    user.role = "USER";
    user.balance = new BigDecimal("10000.00");
    user.frozenBalance = BigDecimal.ZERO;
    user.status = 1;
    user.createdAt = currentTime;
    user.updatedAt = currentTime;
    userMapper.insert(user);
    // 3. 返回新用户的公开身份。
    return new UserRegistrationVO(user.id, user.username);
  }

  /** 校验凭据并创建 Redis 登录态。 */
  @Override
  public UserLoginVO login(CredentialsDTO credentials) {
    // 1. 按用户名读取用户事实数据。
    UserPO user =
        userMapper.selectOne(new QueryWrapper<UserPO>().eq("username", credentials.username));
    // 2. 校验账号状态和密码摘要，失败不访问Redis。
    if (user == null
        || !Objects.equals(user.status, 1)
        || !ENCODER.matches(credentials.password, user.passwordHash)) {
      throw new BizException("USER_LOGIN_FAILED", "用户名或密码错误", 401);
    }
    // 3. 写入两小时Redis Token后返回登录信息，写入失败沿原503传播。
    String token = UUID.randomUUID().toString();
    try {
      redis
          .opsForValue()
          .set("auth:token:" + token, user.id + ":" + user.role, Duration.ofHours(2));
    } catch (DataAccessException redisFailure) {
      throw new BizException(ErrorCodes.REDIS_UNAVAILABLE, "依赖服务暂时不可用，请稍后重试", 503);
    }
    return new UserLoginVO(token, UserVO.from(user));
  }

  /** 使当前登录 Token 失效。 */
  @Override
  public void logout(String authorization) {
    // 1. 读取可选的 Bearer Token。
    if (authorization != null && authorization.startsWith("Bearer ")) {
      // 2. 删除对应登录态，失败保持原503；缺少Bearer头时无需访问Redis。
      try {
        redis.delete("auth:token:" + authorization.substring(7).trim());
      } catch (DataAccessException redisFailure) {
        throw new BizException(ErrorCodes.REDIS_UNAVAILABLE, "依赖服务暂时不可用，请稍后重试", 503);
      }
    }
  }
}
