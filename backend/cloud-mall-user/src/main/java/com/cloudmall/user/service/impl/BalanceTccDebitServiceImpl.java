package com.cloudmall.user.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.BalanceTccDebitFinalizeDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitTryDTO;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.BalanceTccDebitVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.BalanceTccDebitService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User 本地资金事务；后续 Seata 适配器可加入相同的 REQUIRED 事务。 */
@Service
public class BalanceTccDebitServiceImpl implements BalanceTccDebitService {
  /** DATETIME 的固定业务时区。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** DECIMAL(18,2) 的最大合法金额。 */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");

  /** 用户锁与原子余额更新。 */
  private final UserMapper userMapper;

  /** 支付、分支和最终账本键的唯一归属。 */
  private final BalanceDebitAttemptMapper attemptMapper;

  /** 已完成扣款与退款事实。 */
  private final BalanceLedgerMapper ledgerMapper;

  /** 持久冻结快照及状态。 */
  private final BalanceTccDebitMapper tccMapper;

  /** 仅限制新 Try，不阻断已有冻结清算。 */
  @Value("${cloudmall.user.tcc-debit.enabled:false}")
  private boolean enabled;

  /** 创建使用同一 User 数据源的资金资源。 */
  public BalanceTccDebitServiceImpl(
      UserMapper userMapper,
      BalanceDebitAttemptMapper attemptMapper,
      BalanceLedgerMapper ledgerMapper,
      BalanceTccDebitMapper tccMapper) {
    this.userMapper = userMapper;
    this.attemptMapper = attemptMapper;
    this.ledgerMapper = ledgerMapper;
    this.tccMapper = tccMapper;
  }

  /** 仅首次 Try 冻结余额，重放必须符合原始载荷和分支归属。 */
  @Override
  @Transactional
  public BalanceTccDebitVO tryDebit(BalanceTccDebitTryDTO request) {
    // 1. 校验载荷和开关，以用户行锁串行化资金操作。
    validate(request);
    if (!enabled) throw new BizException("PAY_DEBIT_MODE_UNAVAILABLE", "当前未启用此扣款方式", 503);
    UserPO user = lockUser(request.userId);
    if (!Objects.equals(user.status, 1)) throw conflict("用户不可用");
    BalanceDebitAttemptPO attempt = existingAttempt(request);
    BalanceTccDebitPO debit = tccMapper.selectByPayNo(request.payNo);
    if (attempt != null) {
      if (debit == null && "CANCELLED".equals(attempt.state)) return view(attempt);
      requireConsistent(attempt, debit, request);
      if ("CONFIRMED".equals(debit.state)) requireLedger(attempt, debit, request);
      return BalanceTccDebitVO.from(debit);
    }
    if (debit != null) throw unknown();
    requireNoLedger(request);
    requireValidAccountFunds(user);
    if (user.balance.compareTo(request.amount) < 0)
      throw new BizException("PAYMENT_FAILED", "余额不足", 409);
    if (user.frozenBalance.add(request.amount).compareTo(MAX_AMOUNT) > 0) throw unknown();
    // 2. 先认领唯一归属，再冻结；缺失归属不预先加间隙锁。
    LocalDateTime currentTime = now();
    claim(request, "TRYING", currentTime);
    requireOne(userMapper.freezeBalance(request.userId, request.amount, currentTime));
    // 3. 冻结快照与资金同事务提交，任何失败全部回滚。
    debit = new BalanceTccDebitPO();
    debit.id = identifier();
    debit.userId = request.userId;
    debit.payNo = request.payNo;
    debit.orderNo = request.orderNo;
    debit.operationKey = request.operationKey;
    debit.xid = request.xid;
    debit.branchId = request.branchId;
    debit.amount = request.amount;
    debit.state = "TRYING";
    debit.balanceBefore = user.balance;
    debit.balanceAfterTry = user.balance.subtract(request.amount);
    debit.createdAt = currentTime;
    debit.updatedAt = currentTime;
    requireOne(tccMapper.insert(debit));
    return BalanceTccDebitVO.from(debit);
  }

  /** 只清算冻结并写一次账本，不再次减少可用余额。 */
  @Override
  @Transactional
  public BalanceTccDebitVO confirmDebit(BalanceTccDebitFinalizeDTO completion) {
    // 1. 按用户、归属、冻结的顺序核验，清算不读取 Try 开关。
    BalanceTccDebitTryDTO request = request(completion);
    UserPO user = lockUser(request.userId);
    BalanceDebitAttemptPO attempt = existingAttempt(request);
    BalanceTccDebitPO debit = tccMapper.selectByPayNo(request.payNo);
    requireConsistent(attempt, debit, request);
    if ("CONFIRMED".equals(debit.state)) {
      requireLedger(attempt, debit, request);
      return BalanceTccDebitVO.from(debit);
    }
    if (!"TRYING".equals(debit.state)) throw conflict("已取消的冻结不能确认");
    requireNoLedger(request);
    requireValidAccountFunds(user);
    if (user.frozenBalance.compareTo(request.amount) < 0) throw unknown();
    // 2. 清算冻结与账本同事务，账本失败必须恢复冻结。
    LocalDateTime currentTime = now();
    requireOne(userMapper.clearFrozenBalance(request.userId, request.amount, currentTime));
    BalanceLedgerPO ledger = new BalanceLedgerPO();
    ledger.id = identifier();
    ledger.userId = request.userId;
    ledger.operationKey = attempt.ledgerKey;
    ledger.operationType = "DEBIT";
    ledger.payNo = request.payNo;
    ledger.orderNo = request.orderNo;
    ledger.amount = request.amount;
    ledger.state = "APPLIED";
    ledger.balanceBefore = debit.balanceBefore;
    ledger.balanceAfter = debit.balanceAfterTry;
    ledger.createdAt = currentTime;
    requireOne(ledgerMapper.insert(ledger));
    // 3. 两份资金状态必须同时从 TRYING 收敛。
    requireOne(tccMapper.confirmTrying(request.operationKey, currentTime));
    requireOne(attemptMapper.transitionState(request.payNo, "TRYING", "CONFIRMED", currentTime));
    debit.state = "CONFIRMED";
    return BalanceTccDebitVO.from(debit);
  }

  /** 取消已有冻结；空取消只写永久归属，不增加可用余额。 */
  @Override
  @Transactional
  public BalanceTccDebitVO cancelDebit(BalanceTccDebitFinalizeDTO completion) {
    // 1. 关闭开关或禁用用户仍能清算，但必须携带完整原载荷。
    BalanceTccDebitTryDTO request = request(completion);
    UserPO user = lockUser(request.userId);
    BalanceDebitAttemptPO attempt = existingAttempt(request);
    BalanceTccDebitPO debit = tccMapper.selectByPayNo(request.payNo);
    if (attempt == null) {
      if (debit != null) throw unknown();
      requireNoLedger(request);
      return view(claim(request, "CANCELLED", now()));
    }
    if (debit == null && "CANCELLED".equals(attempt.state)) return view(attempt);
    requireConsistent(attempt, debit, request);
    if ("CONFIRMED".equals(debit.state)) throw conflict("已确认的冻结不能取消");
    if ("CANCELLED".equals(debit.state)) return BalanceTccDebitVO.from(debit);
    requireNoLedger(request);
    requireValidAccountFunds(user);
    if (user.frozenBalance.compareTo(request.amount) < 0
        || user.balance.add(request.amount).compareTo(MAX_AMOUNT) > 0) throw unknown();
    // 2. 仅按原冻结金额释放一次，任何条件更新失败均整笔回滚。
    LocalDateTime currentTime = now();
    requireOne(userMapper.releaseFrozenBalance(request.userId, request.amount, currentTime));
    requireOne(tccMapper.cancelTrying(request.operationKey, currentTime));
    requireOne(attemptMapper.transitionState(request.payNo, "TRYING", "CANCELLED", currentTime));
    // 3. 保留归属，后续 Try 只能读取已取消结果。
    debit.state = "CANCELLED";
    return BalanceTccDebitVO.from(debit);
  }

  /** 只对已存在且匹配的归属加锁，避免缺失唯一键的间隙锁升级。 */
  private BalanceDebitAttemptPO existingAttempt(BalanceTccDebitTryDTO request) {
    BalanceDebitAttemptPO attempt = attemptMapper.selectByPayNo(request.payNo);
    if (attempt == null) return null;
    validateAttempt(attempt, request);
    attempt = attemptMapper.selectByPayNoForUpdate(request.payNo);
    if (attempt == null) throw unknown();
    validateAttempt(attempt, request);
    return attempt;
  }

  /** 资金变化前认领支付、账本键及分支；唯一冲突按业务拒绝并整笔回滚。 */
  private BalanceDebitAttemptPO claim(
      BalanceTccDebitTryDTO request, String state, LocalDateTime currentTime) {
    BalanceDebitAttemptPO attempt = new BalanceDebitAttemptPO();
    attempt.id = identifier();
    attempt.payNo = request.payNo;
    attempt.operationKey = request.operationKey;
    attempt.ledgerKey = request.ledgerKey;
    attempt.mode = "TCC";
    attempt.xid = request.xid;
    attempt.branchId = request.branchId;
    attempt.userId = request.userId;
    attempt.orderNo = request.orderNo;
    attempt.amount = request.amount;
    attempt.state = state;
    attempt.createdAt = currentTime;
    attempt.updatedAt = currentTime;
    try {
      requireOne(attemptMapper.insert(attempt));
    } catch (DuplicateKeyException duplicate) {
      throw conflict("支付号、账本键或分支已被占用");
    }
    return attempt;
  }

  /** 两份状态或载荷不一致时停止，不猜测资金结果。 */
  private void requireConsistent(
      BalanceDebitAttemptPO attempt, BalanceTccDebitPO debit, BalanceTccDebitTryDTO request) {
    if (attempt == null || debit == null) throw unknown();
    validateAttempt(attempt, request);
    if (debit.amount == null
        || !validDebitSnapshot(debit.balanceBefore, debit.balanceAfterTry, debit.amount)
        || !Objects.equals(debit.userId, request.userId)
        || !Objects.equals(debit.payNo, request.payNo)
        || !Objects.equals(debit.orderNo, request.orderNo)
        || !Objects.equals(debit.operationKey, request.operationKey)
        || !Objects.equals(debit.xid, request.xid)
        || !Objects.equals(debit.branchId, request.branchId)
        || debit.amount.compareTo(request.amount) != 0
        || !Objects.equals(attempt.state, debit.state)
        || !Set.of("TRYING", "CONFIRMED", "CANCELLED").contains(debit.state)) throw unknown();
  }

  /** 原业务载荷和最终账本键不可因配置、重试或清算而改变。 */
  private void validateAttempt(BalanceDebitAttemptPO attempt, BalanceTccDebitTryDTO request) {
    if (attempt.amount == null
        || !"TCC".equals(attempt.mode)
        || !Objects.equals(attempt.userId, request.userId)
        || !Objects.equals(attempt.payNo, request.payNo)
        || !Objects.equals(attempt.orderNo, request.orderNo)
        || !Objects.equals(attempt.operationKey, request.operationKey)
        || !Objects.equals(attempt.ledgerKey, request.ledgerKey)
        || !Objects.equals(attempt.xid, request.xid)
        || !Objects.equals(attempt.branchId, request.branchId)
        || attempt.amount.compareTo(request.amount) != 0) throw conflict("扣款归属载荷不一致");
  }

  /** 新资金事实不能覆盖历史扣款或占用历史账本键。 */
  private void requireNoLedger(BalanceTccDebitTryDTO request) {
    if (ledgerMapper.selectDebitByPayNo(request.payNo) != null
        || ledgerMapper.selectByOperationKey(request.ledgerKey) != null)
      throw conflict("支付或账本键已有扣款事实");
  }

  /** 已确认必须同时具备真实 APPLIED 账本，不能只凭状态返回成功。 */
  private void requireLedger(
      BalanceDebitAttemptPO attempt, BalanceTccDebitPO debit, BalanceTccDebitTryDTO request) {
    BalanceLedgerPO ledger = ledgerMapper.selectByOperationKey(attempt.ledgerKey);
    if (ledger == null
        || ledger.amount == null
        || !validDebitSnapshot(ledger.balanceBefore, ledger.balanceAfter, ledger.amount)
        || ledger.balanceBefore.compareTo(debit.balanceBefore) != 0
        || ledger.balanceAfter.compareTo(debit.balanceAfterTry) != 0
        || !Objects.equals(ledger.operationKey, attempt.ledgerKey)
        || !"APPLIED".equals(ledger.state)
        || !"DEBIT".equals(ledger.operationType)
        || !Objects.equals(ledger.userId, request.userId)
        || !Objects.equals(ledger.payNo, request.payNo)
        || !Objects.equals(ledger.orderNo, request.orderNo)
        || ledger.amount.compareTo(request.amount) != 0) throw unknown();
  }

  /** 调用处已判金额非NULL；原快照须合法且差额对应实际资金量，不修复原记录。 */
  private static boolean validDebitSnapshot(
      BigDecimal balanceBefore, BigDecimal balanceAfter, BigDecimal amount) {
    return validAccountMoney(balanceBefore)
        && validAccountMoney(balanceAfter)
        && balanceBefore.subtract(balanceAfter).compareTo(amount) == 0;
  }

  /** 仅实际资金写前核当前金额，不影响终态重放/空取消，也不补0或比旧快照。 */
  private void requireValidAccountFunds(UserPO user) {
    if (!validAccountMoney(user.balance) || !validAccountMoney(user.frozenBalance)) throw unknown();
  }

  /** 零余额和无损尾零合法；当前账户金额必须可无损表示在DECIMAL(18,2)中。 */
  private static boolean validAccountMoney(BigDecimal amount) {
    return amount != null
        && amount.signum() >= 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_AMOUNT) <= 0;
  }

  /** 用户行是所有资金方法的第一把锁；清算不要求用户仍启用。 */
  private UserPO lockUser(Long userId) {
    UserPO user = userMapper.selectByIdForUpdate(userId);
    if (user == null) throw new BizException("USER_NOT_FOUND", "用户不存在", 404);
    return user;
  }

  /** 归一化可信清算载荷，使三个入口复用同一校验。 */
  private BalanceTccDebitTryDTO request(BalanceTccDebitFinalizeDTO completion) {
    if (completion == null) throw invalid();
    BalanceTccDebitTryDTO request = new BalanceTccDebitTryDTO();
    request.userId = completion.userId;
    request.payNo = completion.payNo;
    request.orderNo = completion.orderNo;
    request.operationKey = completion.operationKey;
    request.ledgerKey = completion.ledgerKey;
    request.xid = completion.xid;
    request.branchId = completion.branchId;
    request.amount = completion.amount;
    validate(request);
    return request;
  }

  /** 拒绝超长键、变造操作键及不能无损写入 DECIMAL(18,2) 的金额。 */
  private void validate(BalanceTccDebitTryDTO request) {
    if (request == null
        || request.userId == null
        || request.userId <= 0
        || invalidKey(request.payNo, 64)
        || invalidKey(request.orderNo, 64)
        || invalidKey(request.xid, 128)
        || !Objects.equals(request.operationKey, "tcc-debit:" + request.payNo)
        || !Objects.equals(request.ledgerKey, request.payNo)
        || request.branchId == null
        || request.branchId <= 0
        || request.amount == null
        || request.amount.signum() <= 0
        || request.amount.compareTo(MAX_AMOUNT) > 0
        || request.amount.stripTrailingZeros().scale() > 2) throw invalid();
  }

  /** 校验键长度，防止数据库截断改变幂等语义。 */
  private boolean invalidKey(String value, int maximum) {
    return value == null || value.isBlank() || value.length() > maximum;
  }

  /** 空取消仅有归属，从已校验事实返回摘要。 */
  private BalanceTccDebitVO view(BalanceDebitAttemptPO attempt) {
    BalanceTccDebitPO summary = new BalanceTccDebitPO();
    summary.state = attempt.state;
    summary.operationKey = attempt.operationKey;
    summary.payNo = attempt.payNo;
    summary.xid = attempt.xid;
    summary.branchId = attempt.branchId;
    return BalanceTccDebitVO.from(summary);
  }

  /** 预期唯一写入未生效时强制事务回滚。 */
  private void requireOne(int affected) {
    if (affected != 1) throw new IllegalStateException("资金状态更新未影响唯一记录");
  }

  /** 生成非负主键，碰撞由数据库拒绝。 */
  private long identifier() {
    return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
  }

  /** 使用固定业务时区生成数据库时间。 */
  private LocalDateTime now() {
    return LocalDateTime.now(BUSINESS_ZONE);
  }

  /** 参数错误不进入资金处理。 */
  private BizException invalid() {
    return new BizException("INVALID", "TCC 扣款参数不合法", 400);
  }

  /** 已决状态或不可变载荷冲突。 */
  private BizException conflict(String message) {
    return new BizException("IDEMPOTENCY_CONFLICT", message, 409);
  }

  /** 矛盾或缺失事实必须核对，不猜测资金结果。 */
  private BizException unknown() {
    return new BizException("BALANCE_TCC_UNKNOWN", "资金事实待核对", 409);
  }
}
