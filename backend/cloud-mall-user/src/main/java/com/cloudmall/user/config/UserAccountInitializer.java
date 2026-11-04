package com.cloudmall.user.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.mapper.UserMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import javax.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** 核对生成主键部署前置，并幂等准备本地管理员。 */
@Component
@DependsOn({"userTableInitializer", "balanceTccTableInitializer"})
public class UserAccountInitializer {
  /** 用户事实与身份结构访问器。 */
  private final UserMapper userMapper;

  /** 与登录相同的原BCrypt摘要策略。 */
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

  /** 当前数字JSON契约的安全整数上限，仅用于启动兼容检查。 */
  private static final long MAX_SAFE_IDENTIFIER = 9007199254740991L;

  /** 本批有限并发新增的最低安全余量。 */
  private static final long IDENTIFIER_HEADROOM = 64L;

  /** 注入账号启动检查依赖。 */
  public UserAccountInitializer(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  /** 初始化本地管理员账号。 */
  @PostConstruct
  public void ensureLocalAdmin() {
    // 1. 仅只读核部署前置，未迁移时明确拒启动，禁止隐式修改业务表。
    requireCompatibleIdentifiers();
    // 2. 查询本地管理员是否已存在。
    // 3. 缺失时创建默认管理员账号和初始余额。
    // 4. 仅将旧的默认密码迁移到新的默认密码，保持初始化操作幂等。
    long administratorCount =
        userMapper.selectCount(new QueryWrapper<UserPO>().eq("username", "admin"));
    if (administratorCount == 0) {
      LocalDateTime currentTime = LocalDateTime.now();
      UserPO administrator = new UserPO();
      administrator.username = "admin";
      administrator.passwordHash = ENCODER.encode("admin123");
      administrator.role = "ADMIN";
      administrator.balance = new BigDecimal("10000.00");
      administrator.frozenBalance = BigDecimal.ZERO;
      administrator.status = 1;
      administrator.createdAt = currentTime;
      administrator.updatedAt = currentTime;
      userMapper.insert(administrator);
      return;
    }
    UserPO administrator = userMapper.selectOne(new QueryWrapper<UserPO>().eq("username", "admin"));
    if (administrator != null && ENCODER.matches("admin", administrator.passwordHash)) {
      administrator.passwordHash = ENCODER.encode("admin123");
      administrator.updatedAt = LocalDateTime.now();
      userMapper.updateById(administrator);
    }
  }

  /** 检查数据库生成身份的结构和本批有限安全余量，不执行DDL或写后伪回滚。 */
  private void requireCompatibleIdentifiers() {
    if (userMapper.countCompatibleIdentifierColumns() != 2
        || userMapper.countIdentifierPrimaryColumns() != 2
        || !safeIdentifierRange(userMapper.maximumUserIdentifier(), userMapper.nextUserIdentifier())
        || !safeIdentifierRange(
            userMapper.maximumAddressIdentifier(), userMapper.nextAddressIdentifier())) {
      throw new BizException("USER_IDENTIFIER_SCHEMA_INCOMPATIBLE", "用户及地址主键尚未完成兼容迁移或安全余量不足", 503);
    }
  }

  /** 空表MAX为零，计数器必须为正；未知或接近JS精度边界时不开放本批新增。 */
  private boolean safeIdentifierRange(Long maximum, Long next) {
    return maximum != null
        && next != null
        && maximum >= 0
        && next > 0
        && maximum <= MAX_SAFE_IDENTIFIER - IDENTIFIER_HEADROOM
        && next <= MAX_SAFE_IDENTIFIER - IDENTIFIER_HEADROOM;
  }
}
