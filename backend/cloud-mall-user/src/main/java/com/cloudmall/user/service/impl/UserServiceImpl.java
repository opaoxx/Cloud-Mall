package com.cloudmall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.AddressRequestDTO;
import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.dto.CredentialsDTO;
import com.cloudmall.user.domain.dto.DebitRequestDTO;
import com.cloudmall.user.domain.dto.ProfileDTO;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.UserAddressPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.BalanceDebitVO;
import com.cloudmall.user.domain.vo.BalanceOperationVO;
import com.cloudmall.user.domain.vo.BalanceRefundVO;
import com.cloudmall.user.domain.vo.UserAddressVO;
import com.cloudmall.user.domain.vo.UserVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.UserAddressMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.UserService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.PostConstruct;
import javax.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 用户领域业务实现，负责用户、地址、登录态和余额业务。 */
@RequestMapping("/api")
public class UserServiceImpl implements UserService {
  /** 记录可选 Redis 加速缓存的写入失败。 */
  private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

  /** 密码摘要编码器。 */
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

  /** 资金请求金额与钱包/账本快照的DECIMAL(18,2)容量上界，不舍入或截断金额。 */
  private static final BigDecimal MAX_BALANCE_MONEY = new BigDecimal("9999999999999999.99");

  /** 关联退款读取采用100加一哨兵，超限必须补证后再处理。 */
  private static final int MAX_REFUND_CANDIDATES = 100;

  /** 用户事实数据 Mapper。 */
  @Autowired private UserMapper userMapper;

  /** 用户余额账本 Mapper。 */
  @Autowired private BalanceLedgerMapper balanceLedgerMapper;

  /** LEGACY/TCC 共享扣款归属，所有扣款入口必须核对该事实。 */
  @Autowired private BalanceDebitAttemptMapper balanceDebitAttemptMapper;

  /** 原TCC冻结/确认事实，仅用于禁用用户退款的本地来源证明。 */
  @Autowired private BalanceTccDebitMapper balanceTccDebitMapper;

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
    user.frozenBalance = BigDecimal.ZERO;
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
    try {
      redis
          .opsForValue()
          .set("auth:token:" + token, user.id + ":" + user.role, Duration.ofHours(2));
    } catch (DataAccessException redisFailure) {
      throw new BizException(ErrorCodes.REDIS_UNAVAILABLE, "依赖服务暂时不可用，请稍后重试", 503);
    }
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
      try {
        redis.delete("auth:token:" + authorization.substring(7).trim());
      } catch (DataAccessException redisFailure) {
        throw new BizException(ErrorCodes.REDIS_UNAVAILABLE, "依赖服务暂时不可用，请稍后重试", 503);
      }
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

  /** 在同一只读快照证明主账本、原扣款与有界关联退款，供支付恢复判断事实。 */
  @GetMapping("/internal/users/{userId}/balance/operations/{operationKey}")
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ApiResponse<?> balanceOperation(
      @PathVariable Long userId,
      @PathVariable String operationKey,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId) {
    // 1. 保持调用者、主行缺失和foreign的原优先级，再证明主行字段。
    assertInternalCaller(userId, callerId);
    BalanceLedgerPO ledger = balanceLedgerMapper.selectByOperationKey(operationKey);
    if (ledger == null || !Objects.equals(userId, ledger.userId)) {
      throw new BizException(ErrorCodes.NOT_FOUND, "余额操作不存在", 404);
    }
    requireBalanceOperationEvidence(isProvenBalanceOperationRow(ledger, userId, operationKey));
    // 2. REFUND必须观察实际原DEBIT；缺原来源不能伪装成主操作404。
    BalanceLedgerPO original = ledger;
    if ("REFUND".equals(ledger.operationType)) {
      original = balanceLedgerMapper.selectByOperationKey(ledger.originalOperationKey);
      requireBalanceOperationEvidence(
          original != null
              && isProvenBalanceOperationRow(original, userId, ledger.originalOperationKey)
              && "DEBIT".equals(original.operationType)
              && "APPLIED".equals(original.state)
              && Objects.equals(original.payNo, ledger.payNo)
              && Objects.equals(original.orderNo, ledger.orderNo));
    }
    // 3. 复用T75原逐行kernel，并在该快照内核累计及当前退款membership。
    List<BalanceLedgerPO> candidates =
        balanceLedgerMapper.findLinkedRefundCandidates(original.operationKey);
    BigDecimal refundedAmount;
    try {
      refundedAmount = provenRefundedAmount(original, candidates);
    } catch (BizException invalidRefundEvidence) {
      if ("BALANCE_REFUND_INVALID".equals(invalidRefundEvidence.getCode())) {
        throw new BizException(ErrorCodes.CONFLICT, "余额账本事实待核对", 409);
      }
      throw invalidRefundEvidence;
    }
    requireBalanceOperationEvidence(refundedAmount.compareTo(original.amount) <= 0);
    requireBalanceOperationEvidence(
        !"REJECTED".equals(original.state) || refundedAmount.signum() == 0);
    // 4. DEBIT保留原selector；REFUND返回的marker仍NULL，不读当前钱包或修旧行。
    String refundOperationKey = null;
    if ("DEBIT".equals(ledger.operationType)) {
      refundOperationKey = balanceLedgerMapper.selectFirstRefundOperationKey(ledger.operationKey);
      requireBalanceOperationEvidence(
          refundedAmount.signum() == 0
              ? refundOperationKey == null
              : refundOperationKey != null
                  && !refundOperationKey.isBlank()
                  && containsRefundOperationKey(candidates, refundOperationKey));
    } else {
      requireBalanceOperationEvidence(containsRefundSnapshot(candidates, ledger));
    }
    return ApiResponse.ok(BalanceOperationVO.from(ledger, refundOperationKey));
  }

  /** 主行只证明不可变键/金额和已知终态快照；不推断当前钱包、全账户或TCC外部事实。 */
  private static boolean isProvenBalanceOperationRow(
      BalanceLedgerPO ledger, Long userId, String operationKey) {
    if (!Objects.equals(ledger.operationKey, operationKey)
        || !Objects.equals(ledger.userId, userId)
        || ledger.payNo == null
        || ledger.payNo.isBlank()
        || ledger.orderNo == null
        || ledger.orderNo.isBlank()
        || !validBalanceMoney(ledger.amount)
        || ledger.amount.signum() <= 0
        || !validBalanceMoney(ledger.balanceBefore)
        || !validBalanceMoney(ledger.balanceAfter)) {
      return false;
    }
    if ("DEBIT".equals(ledger.operationType)) {
      return ledger.originalOperationKey == null && isProvenDebitSnapshot(ledger);
    }
    return "REFUND".equals(ledger.operationType)
        && "APPLIED".equals(ledger.state)
        && ledger.originalOperationKey != null
        && !ledger.originalOperationKey.isBlank()
        && !Objects.equals(ledger.originalOperationKey, ledger.operationKey)
        && ledger.balanceAfter.subtract(ledger.balanceBefore).compareTo(ledger.amount) == 0;
  }

  /** selector必须指向同一已证集合，保留原SQL选择策略而不以CI别名替代实际键。 */
  private static boolean containsRefundOperationKey(List<BalanceLedgerPO> candidates, String key) {
    for (BalanceLedgerPO candidate : candidates) {
      if (Objects.equals(candidate.operationKey, key)) return true;
    }
    return false;
  }

  /** 当前退款与同一已证集合的金额/快照需匹配，身份字段已由共同原source保证。 */
  private static boolean containsRefundSnapshot(
      List<BalanceLedgerPO> candidates, BalanceLedgerPO ledger) {
    for (BalanceLedgerPO candidate : candidates) {
      if (Objects.equals(candidate.operationKey, ledger.operationKey)
          && candidate.amount.compareTo(ledger.amount) == 0
          && candidate.balanceBefore.compareTo(ledger.balanceBefore) == 0
          && candidate.balanceAfter.compareTo(ledger.balanceAfter) == 0) return true;
    }
    return false;
  }

  /** 坏查询证据保持固定409，不暴露值或被支付恢复器误认成missing。 */
  private static void requireBalanceOperationEvidence(boolean proven) {
    if (!proven) throw new BizException(ErrorCodes.CONFLICT, "余额账本事实待核对", 409);
  }

  /** 按唯一退款键恢复已应用扣款。 */
  @PostMapping("/internal/users/{userId}/balance/refunds")
  @Transactional(noRollbackFor = BizException.class)
  public ApiResponse<?> refundInternal(
      @PathVariable Long userId,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @Valid @RequestBody BalanceRefundRequestDTO request) {
    // 1. 校验调用者和请求金额，按原顺序锁定用户。
    assertInternalCaller(userId, callerId);
    validateMoney(request.amount, "退款金额不合法");
    UserPO user = userMapper.selectByIdForUpdate(userId);
    if (user == null) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
    // 2. 既有退款按原重放协议核验，禁用用户仍需证明原TCC扣款。
    BalanceLedgerPO existing = balanceLedgerMapper.selectByOperationKey(request.refundOperationKey);
    if (existing != null) {
      if (!Objects.equals(user.status, 1)) {
        BalanceLedgerPO original =
            balanceLedgerMapper.selectDebitForUpdate(request.originalOperationKey);
        requireDisabledTccRefund(original, request, userId);
      }
      ensureRefundMatches(existing, userId, request);
      return ApiResponse.ok(
          BalanceRefundVO.from(existing.operationKey, existing.state, existing.balanceAfter));
    }
    // 3. 新退款必须先证明原扣款身份和资金快照，再计算合计或写余额。
    BalanceLedgerPO original =
        balanceLedgerMapper.selectDebitForUpdate(request.originalOperationKey);
    if (!Objects.equals(user.status, 1)) requireDisabledTccRefund(original, request, userId);
    if (!isProvenRefundOriginalDebit(original, request, userId)) {
      throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
    }
    // 4. 逐行证明有界退款集合，再按原额度、条件credit和账本写序处理。
    BigDecimal refundedAmount = provenRefundedAmount(original);
    if (refundedAmount.add(request.amount).compareTo(original.amount) > 0) {
      throw new BizException("BALANCE_REFUND_EXCEEDED", "退款金额超过原扣款", 409);
    }
    LocalDateTime currentTime = LocalDateTime.now();
    BigDecimal balanceBefore = user.balance;
    if (!validBalanceMoney(balanceBefore)) {
      throw new BizException("BALANCE_REFUND_FAILED", "余额退款失败", 409);
    }
    BigDecimal newBalance = balanceBefore.add(request.amount);
    if (newBalance.compareTo(MAX_BALANCE_MONEY) > 0) {
      throw new BizException("BALANCE_REFUND_FAILED", "余额退款失败", 409);
    }
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
    refund.balanceAfter = newBalance;
    refund.createdAt = currentTime;
    if (balanceLedgerMapper.insert(refund) != 1) throw new IllegalStateException("退款账本写入未影响唯一记录");
    return ApiResponse.ok(
        BalanceRefundVO.from(refund.operationKey, refund.state, refund.balanceAfter));
  }

  /** 禁用用户先核归属，再复核原确认冻结及账本快照；只读本地事实，不扩展Legacy退款。 */
  private boolean confirmedTccRefund(BalanceLedgerPO original, BalanceRefundRequestDTO request) {
    BalanceDebitAttemptPO attempt = balanceDebitAttemptMapper.selectByPayNo(request.payNo);
    if (attempt == null
        || !"TCC".equals(attempt.mode)
        || !"CONFIRMED".equals(attempt.state)
        || attempt.xid == null
        || attempt.xid.isBlank()
        || attempt.branchId == null
        || attempt.branchId <= 0
        || !Objects.equals(attempt.userId, original.userId)
        || !Objects.equals(attempt.payNo, original.payNo)
        || !Objects.equals(attempt.orderNo, original.orderNo)
        || !validBalanceMoney(attempt.amount)
        || attempt.amount.signum() <= 0
        || attempt.amount.compareTo(original.amount) != 0
        || !Objects.equals(attempt.ledgerKey, request.originalOperationKey)
        || !Objects.equals(attempt.operationKey, "tcc-debit:" + request.payNo)) return false;
    BalanceTccDebitPO frozen = balanceTccDebitMapper.selectByPayNo(request.payNo);
    return frozen != null
        && "CONFIRMED".equals(frozen.state)
        && Objects.equals(frozen.userId, attempt.userId)
        && Objects.equals(frozen.payNo, attempt.payNo)
        && Objects.equals(frozen.orderNo, attempt.orderNo)
        && Objects.equals(frozen.operationKey, attempt.operationKey)
        && Objects.equals(frozen.xid, attempt.xid)
        && Objects.equals(frozen.branchId, attempt.branchId)
        && validBalanceMoney(frozen.amount)
        && frozen.amount.signum() > 0
        && frozen.amount.compareTo(original.amount) == 0
        && validBalanceMoney(frozen.balanceBefore)
        && validBalanceMoney(frozen.balanceAfterTry)
        && frozen.balanceBefore.compareTo(original.balanceBefore) == 0
        && frozen.balanceAfterTry.compareTo(original.balanceAfter) == 0
        && frozen.balanceBefore.subtract(frozen.balanceAfterTry).compareTo(frozen.amount) == 0;
  }

  /** disabled 退款必须先证明原 DEBIT/APPLIED 和完整已确认 TCC 归属。 */
  private void requireDisabledTccRefund(
      BalanceLedgerPO original, BalanceRefundRequestDTO request, Long userId) {
    if (!isProvenRefundOriginalDebit(original, request, userId)) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
    if (!confirmedTccRefund(original, request)) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
  }

  /** 原锁定候选须为精确DEBIT和合法快照；只核原字段，不推断当前钱包或全部退款因果。 */
  private static boolean isProvenRefundOriginalDebit(
      BalanceLedgerPO original, BalanceRefundRequestDTO request, Long userId) {
    return original != null
        && Objects.equals(original.operationKey, request.originalOperationKey)
        && "DEBIT".equals(original.operationType)
        && "APPLIED".equals(original.state)
        && original.originalOperationKey == null
        && Objects.equals(original.userId, userId)
        && Objects.equals(original.payNo, request.payNo)
        && Objects.equals(original.orderNo, request.orderNo)
        && validBalanceMoney(original.amount)
        && original.amount.signum() > 0
        && validBalanceMoney(original.balanceBefore)
        && validBalanceMoney(original.balanceAfter)
        && original.balanceBefore.subtract(original.balanceAfter).compareTo(original.amount) == 0;
  }

  /** 新退款保留原单次候选读取与错误协议，随后复用纯字段逐行kernel。 */
  private BigDecimal provenRefundedAmount(BalanceLedgerPO original) {
    return provenRefundedAmount(
        original, balanceLedgerMapper.findLinkedRefundCandidates(original.operationKey));
  }

  /** 已读取候选必须完整逐行证明后累计；不提前超额隐藏坏行，不推断全账户因果。 */
  private static BigDecimal provenRefundedAmount(
      BalanceLedgerPO original, List<BalanceLedgerPO> candidates) {
    if (candidates == null || candidates.size() > MAX_REFUND_CANDIDATES) {
      throw new BizException("BALANCE_REFUND_INVALID", "退款累计证据不完整或不一致", 409);
    }
    Set<String> operationKeys = new HashSet<>();
    BigDecimal refundedAmount = BigDecimal.ZERO;
    for (BalanceLedgerPO candidate : candidates) {
      if (candidate == null
          || candidate.operationKey == null
          || candidate.operationKey.isBlank()
          || Objects.equals(candidate.operationKey, original.operationKey)
          || !operationKeys.add(candidate.operationKey)
          || !"REFUND".equals(candidate.operationType)
          || !"APPLIED".equals(candidate.state)
          || !Objects.equals(candidate.userId, original.userId)
          || !Objects.equals(candidate.originalOperationKey, original.operationKey)
          || !Objects.equals(candidate.payNo, original.payNo)
          || !Objects.equals(candidate.orderNo, original.orderNo)
          || !validBalanceMoney(candidate.amount)
          || candidate.amount.signum() <= 0
          || !validBalanceMoney(candidate.balanceBefore)
          || !validBalanceMoney(candidate.balanceAfter)
          || candidate.balanceAfter.subtract(candidate.balanceBefore).compareTo(candidate.amount)
              != 0) {
        throw new BizException("BALANCE_REFUND_INVALID", "退款累计证据不完整或不一致", 409);
      }
      refundedAmount = refundedAmount.add(candidate.amount);
    }
    return refundedAmount;
  }

  /** 持久金额必须存在、非负且无损落在18.2域，允许零余额和数值等值尾零。 */
  private static boolean validBalanceMoney(BigDecimal value) {
    return value != null
        && value.signum() >= 0
        && value.stripTrailingZeros().scale() <= 2
        && value.compareTo(MAX_BALANCE_MONEY) <= 0;
  }

  /** 在用户行锁保护下认领唯一归属，再同事务执行扣款与账本写入。 */
  private ApiResponse<?> debitFromLedger(
      Long userId, Long callerId, BalanceDebitRequestDTO request) {
    // 1. 校验载荷并锁定用户，保持现有 Legacy 对启用用户的要求。
    assertInternalCaller(userId, callerId);
    if (request == null
        || request.operationKey == null
        || request.operationKey.isBlank()
        || request.payNo == null
        || request.payNo.isBlank()
        || request.orderNo == null
        || request.orderNo.isBlank()) {
      throw new BizException(ErrorCodes.INVALID, "扣款参数不完整", 400);
    }
    validateMoney(request.amount, "扣款金额不合法");
    UserPO user = userMapper.selectActiveForUpdate(userId);
    if (user == null) throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);

    // 2. 先核对共享归属和历史账本；历史自定义 operationKey 也按 payNo 互斥。
    BalanceDebitAttemptPO attempt = balanceDebitAttemptMapper.selectByPayNo(request.payNo);
    if (attempt != null) ensureLegacyAttemptMatches(attempt, userId, request);
    BalanceLedgerPO historical = balanceLedgerMapper.selectDebitByPayNo(request.payNo);
    if (historical != null && !Objects.equals(historical.operationKey, request.operationKey))
      throw new BizException("IDEMPOTENCY_CONFLICT", "支付号已绑定其他扣款操作", 409);
    BalanceLedgerPO existing = balanceLedgerMapper.selectByOperationKey(request.operationKey);
    if (existing != null) {
      ensureDebitMatches(existing, userId, request);
      if (attempt != null && !Objects.equals(attempt.state, existing.state))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款归属与账本状态待核对", 409);
      if (!"APPLIED".equals(existing.state) && !"REJECTED".equals(existing.state))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款账本状态待核对", 409);
      if (!isProvenDebitSnapshot(existing))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款账本资金快照待核对", 409);
      if ("REJECTED".equals(existing.state)) throw new BizException("PAYMENT_FAILED", "余额不足", 409);
      cacheDebitResult(userId, existing);
      return ApiResponse.ok(
          BalanceDebitVO.from(existing.operationKey, existing.state, existing.balanceAfter));
    }
    if (attempt != null || historical != null)
      throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款归属缺少对应账本事实", 409);

    // 3. 先核当前钱包域，再用唯一键认领最终账本键，避免坏余额或跨协议并发双扣。
    if (!validBalanceMoney(user.balance))
      throw new BizException("BALANCE_DEBIT_UNKNOWN", "用户余额资金事实待核对", 409);
    LocalDateTime currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    boolean rejected = user.balance.compareTo(request.amount) < 0;
    attempt = new BalanceDebitAttemptPO();
    attempt.id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    attempt.payNo = request.payNo;
    attempt.operationKey = request.operationKey;
    attempt.ledgerKey = request.operationKey;
    attempt.mode = "LEGACY";
    attempt.userId = userId;
    attempt.orderNo = request.orderNo;
    attempt.amount = request.amount;
    attempt.state = rejected ? "REJECTED" : "APPLIED";
    attempt.createdAt = currentTime;
    attempt.updatedAt = currentTime;
    try {
      if (balanceDebitAttemptMapper.insert(attempt) != 1)
        throw new IllegalStateException("扣款归属写入未影响唯一记录");
    } catch (DuplicateKeyException duplicate) {
      // 该异常发生在本事务资金写入之前；noRollbackFor 不会留下已扣余额。
      throw new BizException("IDEMPOTENCY_CONFLICT", "支付号或账本键已被其他扣款占用", 409);
    }

    // 4. 余额和账本一起提交；意外条件更新失败抛可回滚异常，不保留半完成归属。
    BalanceLedgerPO debit = new BalanceLedgerPO();
    debit.id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    debit.userId = userId;
    debit.operationKey = request.operationKey;
    debit.operationType = "DEBIT";
    debit.payNo = request.payNo;
    debit.orderNo = request.orderNo;
    debit.amount = request.amount;
    debit.balanceBefore = user.balance;
    debit.balanceAfter = rejected ? user.balance : user.balance.subtract(request.amount);
    debit.state = attempt.state;
    debit.createdAt = currentTime;
    if (!rejected && userMapper.debitBalance(userId, request.amount, currentTime) != 1)
      throw new IllegalStateException("扣款条件更新未影响唯一用户");
    if (balanceLedgerMapper.insert(debit) != 1) throw new IllegalStateException("余额账本写入未影响唯一记录");
    if (rejected) throw new BizException("PAYMENT_FAILED", "余额不足", 409);
    cacheDebitResult(userId, debit);
    return ApiResponse.ok(BalanceDebitVO.from(debit.operationKey, debit.state, debit.balanceAfter));
  }

  /** 校验原归属模式和全部不可变字段，不能因当前开关关闭而绕过 TCC 归属。 */
  private void ensureLegacyAttemptMatches(
      BalanceDebitAttemptPO attempt, Long userId, BalanceDebitRequestDTO request) {
    if (!"LEGACY".equals(attempt.mode)
        || !Objects.equals(attempt.userId, userId)
        || !Objects.equals(attempt.payNo, request.payNo)
        || !Objects.equals(attempt.orderNo, request.orderNo)
        || !Objects.equals(attempt.operationKey, request.operationKey)
        || !Objects.equals(attempt.ledgerKey, request.operationKey)
        || attempt.xid != null
        || attempt.branchId != null
        || !validBalanceMoney(attempt.amount)
        || attempt.amount.signum() <= 0
        || attempt.amount.compareTo(request.amount) != 0) {
      throw new BizException("IDEMPOTENCY_CONFLICT", "扣款归属对应的请求不一致", 409);
    }
  }

  /** 校验内部调用者身份。 */
  private void assertInternalCaller(Long userId, Long callerId) {
    if (!Objects.equals(userId, callerId)) {
      throw new BizException(ErrorCodes.FORBIDDEN, "内部用户身份无效", 403);
    }
  }

  /** 请求金额必须为正且无损落在18.2域，避免SQL隐式舍入、溢出或零金额流水。 */
  private void validateMoney(BigDecimal amount, String message) {
    if (amount == null
        || amount.signum() <= 0
        || amount.compareTo(MAX_BALANCE_MONEY) > 0
        || amount.stripTrailingZeros().scale() > 2) {
      throw new BizException(ErrorCodes.INVALID, message, 400);
    }
  }

  /** 原扣款实际键和不可变载荷须精确匹配，来源必须NULL；金额缺证沿原幂等冲突拒绝。 */
  private void ensureDebitMatches(
      BalanceLedgerPO existing, Long userId, BalanceDebitRequestDTO request) {
    if (!Objects.equals(existing.operationKey, request.operationKey)
        || !Objects.equals(existing.userId, userId)
        || !"DEBIT".equals(existing.operationType)
        || existing.originalOperationKey != null
        || !Objects.equals(existing.payNo, request.payNo)
        || !Objects.equals(existing.orderNo, request.orderNo)
        || !validBalanceMoney(existing.amount)
        || existing.amount.signum() <= 0
        || existing.amount.compareTo(request.amount) != 0) {
      throw new BizException("IDEMPOTENCY_CONFLICT", "扣款操作键对应的请求不一致", 409);
    }
  }

  /**
   * 已知DEBIT终态必须由原合法余额快照证明；已核金额由调用方保证，不能用当前钱包改写历史决策。
   * APPLIED证明差额，REJECTED证明未扣款且原余额不足；状态冲突及未知态先由原调用方拒绝。
   */
  private static boolean isProvenDebitSnapshot(BalanceLedgerPO existing) {
    if (!validBalanceMoney(existing.balanceBefore) || !validBalanceMoney(existing.balanceAfter)) {
      return false;
    }
    if ("APPLIED".equals(existing.state)) {
      return existing.balanceBefore.subtract(existing.balanceAfter).compareTo(existing.amount) == 0;
    }
    return "REJECTED".equals(existing.state)
        && existing.balanceBefore.compareTo(existing.balanceAfter) == 0
        && existing.balanceBefore.compareTo(existing.amount) < 0;
  }

  /** 原退款必须精确匹配已应用载荷及余额差额，缺字段或坏历史不能成为重放证明。 */
  private void ensureRefundMatches(
      BalanceLedgerPO existing, Long userId, BalanceRefundRequestDTO request) {
    if (!Objects.equals(existing.operationKey, request.refundOperationKey)
        || !Objects.equals(existing.userId, userId)
        || !"REFUND".equals(existing.operationType)
        || !Objects.equals(existing.originalOperationKey, request.originalOperationKey)
        || !Objects.equals(existing.payNo, request.payNo)
        || !Objects.equals(existing.orderNo, request.orderNo)
        || !"APPLIED".equals(existing.state)
        || !validBalanceMoney(existing.amount)
        || existing.amount.signum() <= 0
        || existing.amount.compareTo(request.amount) != 0
        || !validBalanceMoney(existing.balanceBefore)
        || !validBalanceMoney(existing.balanceAfter)
        || existing.balanceAfter.subtract(existing.balanceBefore).compareTo(existing.amount) != 0) {
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
