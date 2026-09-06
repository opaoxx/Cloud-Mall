package com.cloudmall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.po.UserAddressPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.mapper.UserAddressMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.UserService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.PostConstruct;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

/** 用户领域业务实现，负责用户、地址、登录态和余额业务。 */
@RequestMapping("/api")
public class UserServiceImpl implements UserService {
  /** 密码摘要编码器。 */
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

  /** 用户事实数据 Mapper。 */
  @Autowired private UserMapper userMapper;

  /** 用户地址数据 Mapper。 */
  @Autowired private UserAddressMapper userAddressMapper;

  /** 登录态和余额幂等缓存。 */
  @Autowired private StringRedisTemplate redis;

  /** 创建由 Spring 注入依赖的用户服务。 */
  public UserServiceImpl() {}

  /** 初始化本地管理员账号。 */
  @PostConstruct
  public void ensureLocalAdmin() {
    // 1. 查询本地管理员是否已存在。
    // 2. 缺失时创建默认管理员账号和初始余额。
    // 3. 保持初始化操作幂等。
    long administratorCount =
        userMapper.selectCount(new QueryWrapper<UserPO>().eq("username", "admin"));
    if (administratorCount == 0) {
      LocalDateTime currentTime = LocalDateTime.now();
      UserPO administrator = new UserPO();
      administrator.id = System.currentTimeMillis();
      administrator.username = "admin";
      administrator.passwordHash = ENCODER.encode("admin");
      administrator.role = "ADMIN";
      administrator.balance = new BigDecimal("10000.00");
      administrator.status = 1;
      administrator.createdAt = currentTime;
      administrator.updatedAt = currentTime;
      userMapper.insert(administrator);
    }
  }

  /** 注册用户并返回用户标识。 */
  @PostMapping("/auth/register")
  public ApiResponse<?> register(@Valid @RequestBody Credentials credentials) {
    // 1. 校验用户名是否已被占用。
    // 2. 保存密码摘要、角色和初始余额。
    // 3. 返回新用户的公开信息。
    long duplicateCount =
        userMapper.selectCount(new QueryWrapper<UserPO>().eq("username", credentials.username));
    if (duplicateCount > 0) {
      throw new BizException("USER_DUPLICATE_USERNAME", "用户名已存在", 409);
    }
    LocalDateTime currentTime = LocalDateTime.now();
    UserPO user = new UserPO();
    user.id = System.currentTimeMillis();
    user.username = credentials.username;
    user.passwordHash = ENCODER.encode(credentials.password);
    user.role = "USER";
    user.balance = new BigDecimal("10000.00");
    user.status = 1;
    user.createdAt = currentTime;
    user.updatedAt = currentTime;
    userMapper.insert(user);
    return ApiResponse.ok(Map.of("userId", user.id, "username", user.username));
  }

  /** 校验凭据并创建 Redis 登录态。 */
  @PostMapping("/auth/login")
  public ApiResponse<?> login(@Valid @RequestBody Credentials credentials) {
    // 1. 按用户名读取用户事实数据。
    // 2. 校验账号状态和密码摘要。
    // 3. 写入两小时 Redis Token 并返回用户信息。
    UserPO user =
        userMapper.selectOne(new QueryWrapper<UserPO>().eq("username", credentials.username));
    if (user == null
        || !Objects.equals(user.status, 1)
        || !ENCODER.matches(credentials.password, user.passwordHash)) {
      throw new BizException("USER_LOGIN_FAILED", "用户名或密码错误", 401);
    }
    String token = UUID.randomUUID().toString();
    redis.opsForValue().set("auth:token:" + token, user.id + ":" + user.role, Duration.ofHours(2));
    return ApiResponse.ok(Map.of("token", token, "user", userView(user)));
  }

  /** 使当前登录 Token 失效。 */
  @PostMapping("/auth/logout")
  public ApiResponse<?> logout(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    // 1. 读取可选的 Bearer Token。
    // 2. 删除对应 Redis 登录态。
    // 3. 返回登出成功响应。
    if (authorization != null && authorization.startsWith("Bearer ")) {
      redis.delete("auth:token:" + authorization.substring(7).trim());
    }
    return ApiResponse.ok(null);
  }

  /** 查询当前用户资料。 */
  @GetMapping("/users/me")
  public ApiResponse<?> me() {
    // 1. 从认证上下文获取当前用户。
    // 2. 通过 Mapper 读取用户事实数据。
    // 3. 转换为公开用户视图。
    return ApiResponse.ok(userView(currentUser()));
  }

  /** 按幂等支付键扣减用户余额。 */
  @PostMapping("/internal/users/{userId}/balance/debit")
  public synchronized ApiResponse<?> debit(
      @PathVariable Long userId,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @Valid @RequestBody DebitRequest request) {
    // 1. 校验内部调用者身份、支付幂等键和扣款金额。
    // 2. 通过 Mapper 的条件更新原子扣减余额。
    // 3. 缓存扣款结果并返回最新余额。
    if (!Objects.equals(userId, callerId)) {
      throw new BizException(ErrorCodes.FORBIDDEN, "内部用户身份无效", 403);
    }
    if (request == null
        || request.paymentKey == null
        || request.paymentKey.isBlank()
        || request.amount == null
        || request.amount.signum() <= 0) {
      throw new BizException(ErrorCodes.INVALID, "扣款参数不完整", 400);
    }
    String resultKey = "balance:debit:" + userId + ":" + request.paymentKey;
    String previousBalance = redis.opsForValue().get(resultKey);
    if (previousBalance != null) {
      return ApiResponse.ok(Map.of("balance", previousBalance));
    }
    int changedRows = userMapper.debitBalance(userId, request.amount, LocalDateTime.now());
    if (changedRows != 1) {
      throw new BizException("PAYMENT_FAILED", "余额不足", 409);
    }
    UserPO user = userMapper.selectById(userId);
    String balance = user.balance.toPlainString();
    redis.opsForValue().set(resultKey, balance, Duration.ofDays(30));
    return ApiResponse.ok(Map.of("balance", balance));
  }

  /** 更新当前用户允许修改的资料。 */
  @PutMapping("/users/me")
  public ApiResponse<?> update(@RequestBody Profile profile) {
    // 1. 获取当前用户并整理可修改字段。
    // 2. 通过 Mapper 更新用户资料和审计时间。
    // 3. 重新读取并返回更新后的资料。
    userMapper.update(
        null,
        new UpdateWrapper<UserPO>()
            .eq("id", AuthContext.requireUserId())
            .set("nickname", profile.nickname)
            .set("phone", profile.phone)
            .set("avatar_url", profile.avatarUrl)
            .set("updated_at", LocalDateTime.now()));
    return me();
  }

  /** 查询当前用户的收货地址。 */
  @GetMapping("/users/me/addresses")
  public ApiResponse<?> addresses() {
    // 1. 获取当前用户标识。
    // 2. 按默认标志和地址标识查询地址。
    // 3. 转换为前端地址响应。
    List<UserAddressPO> addresses =
        userAddressMapper.selectList(
            new QueryWrapper<UserAddressPO>()
                .eq("user_id", AuthContext.requireUserId())
                .orderByDesc("is_default")
                .orderByDesc("id"));
    return ApiResponse.ok(addresses.stream().map(UserAddressView::from).toList());
  }

  /** 新增当前用户收货地址。 */
  @PostMapping("/users/me/addresses")
  public ApiResponse<?> addAddress(@RequestBody AddressRequest request) {
    // 1. 获取当前用户并准备地址主键。
    // 2. 必要时清除原默认地址。
    // 3. 保存新地址并返回地址视图。
    Long userId = AuthContext.requireUserId();
    if (request.isDefault) {
      clearDefaultAddress(userId, null);
    }
    UserAddressPO address = toAddress(null, userId, request);
    userAddressMapper.insert(address);
    return ApiResponse.ok(UserAddressView.from(address));
  }

  /** 更新当前用户收货地址。 */
  @PutMapping("/users/me/addresses/{id}")
  public ApiResponse<?> updateAddress(@PathVariable Long id, @RequestBody AddressRequest request) {
    // 1. 校验地址属于当前用户。
    // 2. 更新地址字段和默认标志。
    // 3. 返回更新后的地址视图。
    Long userId = AuthContext.requireUserId();
    UserAddressPO address = toAddress(id, userId, request);
    if (userAddressMapper.updateById(address) == 0) {
      throw new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404);
    }
    if (request.isDefault) {
      clearDefaultAddress(userId, id);
    }
    return ApiResponse.ok(UserAddressView.from(address));
  }

  /** 删除当前用户收货地址。 */
  @DeleteMapping("/users/me/addresses/{id}")
  public ApiResponse<?> deleteAddress(@PathVariable Long id) {
    // 1. 获取当前用户标识。
    // 2. 按用户归属删除地址。
    // 3. 地址不存在时返回统一业务错误。
    int changedRows =
        userAddressMapper.delete(
            new QueryWrapper<UserAddressPO>()
                .eq("id", id)
                .eq("user_id", AuthContext.requireUserId()));
    if (changedRows == 0) {
      throw new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404);
    }
    return ApiResponse.ok(null);
  }

  /** 读取当前用户事实数据。 */
  private UserPO currentUser() {
    UserPO user = userMapper.selectById(AuthContext.requireUserId());
    if (user == null) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
    return user;
  }

  /** 取消用户的其他默认地址。 */
  private void clearDefaultAddress(Long userId, Long excludedAddressId) {
    UpdateWrapper<UserAddressPO> updateWrapper =
        new UpdateWrapper<UserAddressPO>().eq("user_id", userId).set("is_default", false);
    if (excludedAddressId != null) {
      updateWrapper.ne("id", excludedAddressId);
    }
    userAddressMapper.update(null, updateWrapper);
  }

  /** 将请求对象转换为地址持久化对象。 */
  private UserAddressPO toAddress(Long addressId, Long userId, AddressRequest request) {
    UserAddressPO address = new UserAddressPO();
    address.id = addressId == null ? System.currentTimeMillis() : addressId;
    address.userId = userId;
    address.receiverName = request.receiver;
    address.receiverPhone = request.phone;
    address.regionDetail = request.detailAddress;
    address.defaultAddress = request.isDefault;
    address.updatedAt = LocalDateTime.now();
    address.createdAt = address.updatedAt;
    return address;
  }

  /** 用户注册和登录请求。 */
  public static class Credentials {
    /** 登录用户名。 */
    @NotBlank public String username;

    /** 登录密码。 */
    @NotBlank public String password;
  }

  /** 用户资料更新请求。 */
  public static class Profile {
    /** 用户昵称。 */
    public String nickname;

    /** 联系电话。 */
    public String phone;

    /** 头像地址。 */
    public String avatarUrl;
  }

  /** 余额扣减请求。 */
  public static class DebitRequest {
    /** 支付幂等键。 */
    @NotBlank public String paymentKey;

    /** 扣减金额。 */
    public BigDecimal amount;
  }

  /** 收货地址请求。 */
  public static class AddressRequest {
    /** 收货人姓名。 */
    public String receiver;

    /** 收货电话。 */
    public String phone;

    /** 地区及详细地址。 */
    public String detailAddress;

    /** 是否设为默认地址。 */
    public boolean isDefault;
  }

  /** 将用户持久化对象转换为既有用户响应字段。 */
  private static Map<String, Object> userView(UserPO user) {
    return Map.of(
        "userId",
        user.id,
        "username",
        user.username,
        "role",
        user.role,
        "roles",
        List.of(user.role),
        "nickname",
        user.nickname == null ? "" : user.nickname,
        "phone",
        user.phone == null ? "" : user.phone,
        "avatarUrl",
        user.avatarUrl == null ? "" : user.avatarUrl,
        "balance",
        user.balance.toPlainString());
  }

  /** 地址响应视图。 */
  private record UserAddressView(
      Long id, String receiver, String phone, String detailAddress, boolean isDefault) {
    /** 从地址持久化对象构造公开视图。 */
    private static UserAddressView from(UserAddressPO address) {
      return new UserAddressView(
          address.id,
          address.receiverName,
          address.receiverPhone,
          address.regionDetail,
          Boolean.TRUE.equals(address.defaultAddress));
    }
  }
}
