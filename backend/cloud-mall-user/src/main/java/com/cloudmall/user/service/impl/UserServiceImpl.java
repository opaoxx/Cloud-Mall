package com.cloudmall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.AddressRequestDTO;
import com.cloudmall.user.domain.dto.CredentialsDTO;
import com.cloudmall.user.domain.dto.DebitRequestDTO;
import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.dto.ProfileDTO;
import com.cloudmall.user.domain.po.UserAddressPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.UserAddressVO;
import com.cloudmall.user.domain.vo.UserVO;
import com.cloudmall.user.domain.vo.BalanceDebitVO;
import com.cloudmall.user.domain.vo.BalanceOperationVO;
import com.cloudmall.user.domain.vo.BalanceRefundVO;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/** 用户领域业务实现，负责用户、地址、登录态和余额业务。 */
@RequestMapping("/api")
public class UserServiceImpl implements UserService {
  /** 记录可选 Redis 加速缓存的写入失败。 */
  private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

  /** 密码摘要编码器。 */
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

  /** 用户事实数据 Mapper。 */
  @Autowired private UserMapper userMapper;

  /** 用户余额账本 Mapper。 */
  @Autowired private BalanceLedgerMapper balanceLedgerMapper;

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
    // 3. 仅将旧的默认密码迁移到新的默认密码，保持初始化操作幂等。
    long administratorCount =
        userMapper.selectCount(new QueryWrapper<UserPO>().eq("username", "admin"));
    if (administratorCount == 0) {
      LocalDateTime currentTime = LocalDateTime.now();
      UserPO administrator = new UserPO();
      administrator.id = System.currentTimeMillis();
      administrator.username = "admin";
      administrator.passwordHash = ENCODER.encode("admin123");
      administrator.role = "ADMIN";
      administrator.balance = new BigDecimal("10000.00");
      administrator.status = 1;
      administrator.createdAt = currentTime;
      administrator.updatedAt = currentTime;
      userMapper.insert(administrator);
      return;
    }
    UserPO administrator =
        userMapper.selectOne(new QueryWrapper<UserPO>().eq("username", "admin"));
    if (administrator != null && ENCODER.matches("admin", administrator.passwordHash)) {
      administrator.passwordHash = ENCODER.encode("admin123");
      administrator.updatedAt = LocalDateTime.now();
      userMapper.updateById(administrator);
    }
  }

  /** 注册用户并返回用户标识。 */
  @PostMapping("/auth/register")
  public ApiResponse<?> register(@Valid @RequestBody CredentialsDTO credentials) {
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
  public ApiResponse<?> login(@Valid @RequestBody CredentialsDTO credentials) {
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
    return ApiResponse.ok(Map.of("token", token, "user", UserVO.from(user)));
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
    return ApiResponse.ok(UserVO.from(currentUser()));
  }

  /** 按幂等支付键扣减用户余额。 */
  @PostMapping("/internal/users/{userId}/balance/debit")
  @Transactional(noRollbackFor = BizException.class)
  public ApiResponse<?> debit(
      @PathVariable Long userId,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @Valid @RequestBody DebitRequestDTO request) {
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
    BalanceDebitRequestDTO internalRequest = new BalanceDebitRequestDTO();
    internalRequest.operationKey = request.paymentKey;
    internalRequest.payNo = request.paymentKey;
    internalRequest.orderNo = request.paymentKey;
    internalRequest.amount = request.amount;
    return debitFromLedger(userId, callerId, internalRequest);
  }

  /** 按持久化操作键执行余额扣款。 */
  @PostMapping("/internal/users/{userId}/balance/debits")
  @Transactional(noRollbackFor = BizException.class)
  public ApiResponse<?> debitInternal(
      @PathVariable Long userId,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @Valid @RequestBody BalanceDebitRequestDTO request) {
    return debitFromLedger(userId, callerId, request);
  }

  /** 查询持久化余额操作，供支付恢复任务判断扣款事实。 */
  @GetMapping("/internal/users/{userId}/balance/operations/{operationKey}")
  public ApiResponse<?> balanceOperation(
      @PathVariable Long userId,
      @PathVariable String operationKey,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId) {
    assertInternalCaller(userId, callerId);
    BalanceLedgerPO ledger = balanceLedgerMapper.selectByOperationKey(operationKey);
    if (ledger == null || !Objects.equals(userId, ledger.userId)) {
      throw new BizException(ErrorCodes.NOT_FOUND, "余额操作不存在", 404);
    }
    String refundOperationKey =
        "DEBIT".equals(ledger.operationType)
            ? balanceLedgerMapper.selectFirstRefundOperationKey(ledger.operationKey)
            : null;
    return ApiResponse.ok(BalanceOperationVO.from(ledger, refundOperationKey));
  }

  /** 按唯一退款键恢复已应用扣款。 */
  @PostMapping("/internal/users/{userId}/balance/refunds")
  @Transactional(noRollbackFor = BizException.class)
  public ApiResponse<?> refundInternal(
      @PathVariable Long userId,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @Valid @RequestBody BalanceRefundRequestDTO request) {
    assertInternalCaller(userId, callerId);
    validateMoney(request.amount, "退款金额不合法");
    UserPO user = userMapper.selectActiveForUpdate(userId);
    if (user == null) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
    BalanceLedgerPO existing = balanceLedgerMapper.selectByOperationKey(request.refundOperationKey);
    if (existing != null) {
      ensureRefundMatches(existing, userId, request);
      return ApiResponse.ok(
          BalanceRefundVO.from(existing.operationKey, existing.state, existing.balanceAfter));
    }
    BalanceLedgerPO original =
        balanceLedgerMapper.selectDebitForUpdate(request.originalOperationKey);
    if (original == null
        || !Objects.equals(userId, original.userId)
        || !"APPLIED".equals(original.state)
        || !Objects.equals(request.payNo, original.payNo)
        || !Objects.equals(request.orderNo, original.orderNo)) {
      throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
    }
    BigDecimal refundedAmount =
        balanceLedgerMapper.sumAppliedRefunds(request.originalOperationKey);
    if (refundedAmount.add(request.amount).compareTo(original.amount) > 0) {
      throw new BizException("BALANCE_REFUND_EXCEEDED", "退款金额超过原扣款", 409);
    }
    LocalDateTime currentTime = LocalDateTime.now();
    BigDecimal balanceBefore = user.balance;
    if (userMapper.creditBalance(userId, request.amount, currentTime) != 1) {
      throw new BizException("BALANCE_REFUND_FAILED", "余额退款失败", 409);
    }
    BalanceLedgerPO refund = new BalanceLedgerPO();
    refund.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
    refund.userId = userId;
    refund.operationKey = request.refundOperationKey;
    refund.operationType = "REFUND";
    refund.originalOperationKey = request.originalOperationKey;
    refund.payNo = request.payNo;
    refund.orderNo = request.orderNo;
    refund.amount = request.amount;
    refund.state = "APPLIED";
    refund.balanceBefore = balanceBefore;
    refund.balanceAfter = balanceBefore.add(request.amount);
    refund.createdAt = currentTime;
    balanceLedgerMapper.insert(refund);
    return ApiResponse.ok(
        BalanceRefundVO.from(refund.operationKey, refund.state, refund.balanceAfter));
  }

  /** 在用户行锁保护下执行扣款及账本写入。 */
  private ApiResponse<?> debitFromLedger(
      Long userId, Long callerId, BalanceDebitRequestDTO request) {
    assertInternalCaller(userId, callerId);
    if (request == null
        || request.operationKey == null
        || request.operationKey.isBlank()
        || request.payNo == null
        || request.orderNo == null) {
      throw new BizException(ErrorCodes.INVALID, "扣款参数不完整", 400);
    }
    validateMoney(request.amount, "扣款金额不合法");
    UserPO user = userMapper.selectActiveForUpdate(userId);
    if (user == null) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
    BalanceLedgerPO existing = balanceLedgerMapper.selectByOperationKey(request.operationKey);
    if (existing != null) {
      ensureDebitMatches(existing, userId, request);
      if ("REJECTED".equals(existing.state)) {
        throw new BizException("PAYMENT_FAILED", "余额不足", 409);
      }
      cacheDebitResult(userId, existing);
      return ApiResponse.ok(
          BalanceDebitVO.from(existing.operationKey, existing.state, existing.balanceAfter));
    }
    LocalDateTime currentTime = LocalDateTime.now();
    BigDecimal balanceBefore = user.balance;
    BalanceLedgerPO debit = new BalanceLedgerPO();
    debit.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
    debit.userId = userId;
    debit.operationKey = request.operationKey;
    debit.operationType = "DEBIT";
    debit.payNo = request.payNo;
    debit.orderNo = request.orderNo;
    debit.amount = request.amount;
    debit.balanceBefore = balanceBefore;
    debit.createdAt = currentTime;
    if (balanceBefore.compareTo(request.amount) < 0) {
      debit.state = "REJECTED";
      debit.balanceAfter = balanceBefore;
      balanceLedgerMapper.insert(debit);
      throw new BizException("PAYMENT_FAILED", "余额不足", 409);
    }
    if (userMapper.debitBalance(userId, request.amount, currentTime) != 1) {
      throw new BizException("PAYMENT_FAILED", "余额不足", 409);
    }
    debit.state = "APPLIED";
    debit.balanceAfter = balanceBefore.subtract(request.amount);
    balanceLedgerMapper.insert(debit);
    cacheDebitResult(userId, debit);
    return ApiResponse.ok(
        BalanceDebitVO.from(debit.operationKey, debit.state, debit.balanceAfter));
  }

  /** 校验内部调用者身份。 */
  private void assertInternalCaller(Long userId, Long callerId) {
    if (!Objects.equals(userId, callerId)) {
      throw new BizException(ErrorCodes.FORBIDDEN, "内部用户身份无效", 403);
    }
  }

  /** 校验正金额，避免余额操作产生负数或零金额流水。 */
  private void validateMoney(BigDecimal amount, String message) {
    if (amount == null || amount.signum() <= 0) {
      throw new BizException(ErrorCodes.INVALID, message, 400);
    }
  }

  /** 校验重复扣款的请求载荷完全一致。 */
  private void ensureDebitMatches(
      BalanceLedgerPO existing, Long userId, BalanceDebitRequestDTO request) {
    if (!Objects.equals(existing.userId, userId)
        || !"DEBIT".equals(existing.operationType)
        || !Objects.equals(existing.payNo, request.payNo)
        || !Objects.equals(existing.orderNo, request.orderNo)
        || existing.amount.compareTo(request.amount) != 0) {
      throw new BizException("IDEMPOTENCY_CONFLICT", "扣款操作键对应的请求不一致", 409);
    }
  }

  /** 校验重复退款的请求载荷完全一致。 */
  private void ensureRefundMatches(
      BalanceLedgerPO existing, Long userId, BalanceRefundRequestDTO request) {
    if (!Objects.equals(existing.userId, userId)
        || !"REFUND".equals(existing.operationType)
        || !Objects.equals(existing.originalOperationKey, request.originalOperationKey)
        || !Objects.equals(existing.payNo, request.payNo)
        || !Objects.equals(existing.orderNo, request.orderNo)
        || existing.amount.compareTo(request.amount) != 0) {
      throw new BizException("IDEMPOTENCY_CONFLICT", "退款操作键对应的请求不一致", 409);
    }
  }

  /** 将已提交账本结果写入 Redis，仅用于加速后续读取。 */
  private void cacheDebitResult(Long userId, BalanceLedgerPO ledger) {
    try {
      redis
          .opsForValue()
          .set(
              "balance:debit:" + userId + ":" + ledger.operationKey,
              ledger.balanceAfter.toPlainString(),
              Duration.ofDays(30));
    } catch (RuntimeException cacheFailure) {
      log.warn("余额账本已处理，Redis 加速缓存更新失败，operationKey={}", ledger.operationKey, cacheFailure);
    }
  }

  /** 更新当前用户允许修改的资料。 */
  @PutMapping("/users/me")
  public ApiResponse<?> update(@RequestBody ProfileDTO profile) {
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
    return ApiResponse.ok(addresses.stream().map(UserAddressVO::from).toList());
  }

  /** 新增当前用户收货地址。 */
  @PostMapping("/users/me/addresses")
  public ApiResponse<?> addAddress(@Valid @RequestBody AddressRequestDTO request) {
    // 1. 获取当前用户并准备地址主键。
    // 2. 必要时清除原默认地址。
    // 3. 保存新地址并返回地址视图。
    Long userId = AuthContext.requireUserId();
    if (request.isDefault) {
      clearDefaultAddress(userId, null);
    }
    UserAddressPO address = toAddress(null, userId, request);
    userAddressMapper.insert(address);
    return ApiResponse.ok(UserAddressVO.from(address));
  }

  /** 更新当前用户收货地址。 */
  @PutMapping("/users/me/addresses/{id}")
  public ApiResponse<?> updateAddress(
      @PathVariable Long id, @Valid @RequestBody AddressRequestDTO request) {
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
    return ApiResponse.ok(UserAddressVO.from(address));
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
  private UserAddressPO toAddress(Long addressId, Long userId, AddressRequestDTO request) {
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
}
