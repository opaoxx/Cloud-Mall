package com.cloudmall.user.service.impl.balance;

import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.assertInternalCaller;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.isProvenDebitSnapshot;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.validBalanceMoney;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.validateMoney;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.DebitRequestDTO;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.BalanceDebitVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.BalanceDebitService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.mybatis.spring.MyBatisSystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Legacy余额扣款、重放与原支付键兼容入口。 */
@Service
public class BalanceDebitServiceImpl implements BalanceDebitService {
  /** 记录可选缓存写失败。 */
  private static final Logger log = LoggerFactory.getLogger(BalanceDebitServiceImpl.class);

  /** 钱包行锁与条件扣款。 */
  private final UserMapper userMapper;

  /** 持久化扣款账本。 */
  private final BalanceLedgerMapper balanceLedgerMapper;

  /** Legacy扣款归属认领。 */
  private final BalanceDebitAttemptMapper balanceDebitAttemptMapper;

  /** 可丢弃的扣款加速缓存。 */
  private final StringRedisTemplate redis;

  /** 跨协议归属、冻结及接收来源证明。 */
  private final BalanceOwnershipEvidence ownershipEvidence;

  /** 注入本职责所需的持久化与证明依赖，不执行查询或建立事务。 */
  public BalanceDebitServiceImpl(
      UserMapper userMapper,
      BalanceLedgerMapper balanceLedgerMapper,
      BalanceDebitAttemptMapper balanceDebitAttemptMapper,
      StringRedisTemplate redis,
      BalanceOwnershipEvidence ownershipEvidence) {
    this.userMapper = userMapper;
    this.balanceLedgerMapper = balanceLedgerMapper;
    this.balanceDebitAttemptMapper = balanceDebitAttemptMapper;
    this.redis = redis;
    this.ownershipEvidence = ownershipEvidence;
  }

  /** 按幂等支付键扣减用户余额。 */
  @Transactional(noRollbackFor = BizException.class)
  @Override
  public BalanceDebitVO debit(Long userId, Long callerId, DebitRequestDTO request) {
    // 1. 校验内部调用者身份、支付幂等键和扣款金额。
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
    // 2. 将旧支付键映射为原operation/pay/order三个字段。
    BalanceDebitRequestDTO internalRequest = new BalanceDebitRequestDTO();
    internalRequest.operationKey = request.paymentKey;
    internalRequest.payNo = request.paymentKey;
    internalRequest.orderNo = request.paymentKey;
    internalRequest.amount = request.amount;
    // 3. 在本入口的同一事务中执行原扣款流程。
    return debitFromLedger(userId, callerId, internalRequest);
  }

  /** 按持久化操作键执行余额扣款。 */
  @Transactional(noRollbackFor = BizException.class)
  @Override
  public BalanceDebitVO debitInternal(Long userId, Long callerId, BalanceDebitRequestDTO request) {
    // 1. 进入本入口的事务后复用持久化扣款流程，保持原检查顺序。
    return debitFromLedger(userId, callerId, request);
  }

  /** 在用户行锁保护下认领唯一归属，再同事务执行扣款与账本写入。 */
  private BalanceDebitVO debitFromLedger(
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
    BalanceDebitAttemptPO attempt =
        ownershipEvidence.readDebitOwnershipCandidate(
            request.payNo, () -> new BizException("IDEMPOTENCY_CONFLICT", "支付号存在重复扣款归属记录", 409));
    if (attempt != null) ensureLegacyAttemptMatches(attempt, userId, request);
    // 有界候选保留全状态/CI历史；缺证不能作空，重复在单结果Mapper抛异常前按业务拒绝。
    List<BalanceLedgerPO> historicalCandidates =
        balanceLedgerMapper.findDebitCandidatesByPayNo(request.payNo);
    if (historicalCandidates == null
        || (historicalCandidates.size() == 1 && historicalCandidates.get(0) == null))
      throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款归属缺少对应账本事实", 409);
    if (historicalCandidates.size() > 1)
      throw new BizException("IDEMPOTENCY_CONFLICT", "支付号已绑定其他扣款操作", 409);
    BalanceLedgerPO historical =
        historicalCandidates.isEmpty() ? null : historicalCandidates.get(0);
    if (historical != null && !Objects.equals(historical.operationKey, request.operationKey))
      throw new BizException("IDEMPOTENCY_CONFLICT", "支付号已绑定其他扣款操作", 409);
    BalanceLedgerPO existing = readLegacyDebitLedgerCandidate(request.operationKey);
    if (existing != null) {
      ensureDebitMatches(existing, userId, request);
      if (attempt != null && !Objects.equals(attempt.state, existing.state))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款归属与账本状态待核对", 409);
      if (!"APPLIED".equals(existing.state) && !"REJECTED".equals(existing.state))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款账本状态待核对", 409);
      if (!isProvenDebitSnapshot(existing))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款账本资金快照待核对", 409);
      // 原tuple/state/snapshot已先证明；Legacy历史不能忽略同pay矛盾冻结或更新缓存。
      if (ownershipEvidence.hasTccFreezeConflict(request.payNo))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款来源存在TCC冻结事实", 409);
      // 历史原来源/冻结门已通过；任意接收事实都要求核对，不缓存矛盾历史结果。
      if (ownershipEvidence.hasTccReceiveConflict(request.payNo))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款来源存在 TCC 接收事实", 409);
      if ("REJECTED".equals(existing.state)) throw new BizException("PAYMENT_FAILED", "余额不足", 409);
      cacheDebitResult(userId, existing);
      return BalanceDebitVO.from(existing.operationKey, existing.state, existing.balanceAfter);
    }
    if (attempt != null || historical != null)
      throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款归属缺少对应账本事实", 409);

    // 首次扣款须同pay全类型账本可靠为空；已有历史重放已在上方独立返回。
    List<BalanceLedgerPO> paymentLedgerCandidates =
        balanceLedgerMapper.findPaymentLedgerCandidates(request.payNo);
    if (paymentLedgerCandidates == null)
      throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款归属缺少对应账本事实", 409);
    if (!paymentLedgerCandidates.isEmpty())
      throw new BizException("IDEMPOTENCY_CONFLICT", "支付号已有资金账本事实", 409);
    // 原归属/账本门已先核；首次Legacy不得与同pay残留TCC冻结并存，不按state过滤。
    if (ownershipEvidence.hasTccFreezeConflict(request.payNo))
      throw new BizException("IDEMPOTENCY_CONFLICT", "支付号已有TCC冻结事实", 409);
    // 原账本/冻结门已通过；任意已观察receive均阻断，不用flag或元数据隐藏原来源。
    if (ownershipEvidence.hasTccReceiveConflict(request.payNo))
      throw new BizException("IDEMPOTENCY_CONFLICT", "支付号已有 TCC 接收事实", 409);

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
    return BalanceDebitVO.from(debit.operationKey, debit.state, debit.balanceAfter);
  }

  /** Legacy固定操作键保持原单次点读，仅直接多结果或其标准直接包装按幂等冲突拒绝。 */
  private BalanceLedgerPO readLegacyDebitLedgerCandidate(String operationKey) {
    try {
      return balanceLedgerMapper.selectByOperationKey(operationKey);
    } catch (TooManyResultsException | MyBatisSystemException failure) {
      if (failure instanceof TooManyResultsException
          || failure.getCause() instanceof TooManyResultsException) {
        throw new BizException("IDEMPOTENCY_CONFLICT", "扣款操作键存在重复账本记录", 409);
      }
      throw failure;
    }
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

  /** 在原事务位置尝试写入可丢弃的Redis加速值，缓存故障不替代数据库结果。 */
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
}
