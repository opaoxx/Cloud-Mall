package com.cloudmall.user.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.dto.DebitRequestDTO;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.BalanceDebitVO;
import com.cloudmall.user.domain.vo.BalanceOperationVO;
import com.cloudmall.user.domain.vo.BalanceRefundVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceLedgerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.mybatis.spring.MyBatisSystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 余额扣款、退款与账本事实查询，保持共享归属和本地事务边界。 */
@Service
public class BalanceLedgerServiceImpl implements BalanceLedgerService {
  /** 同库TCC接收来源，阻止跨协议占用已接收支付号。 */
  @Autowired private UserTccReceiveMapper userTccReceiveMapper;

  /** 可选Redis加速缓存失败日志。 */
  private static final Logger log = LoggerFactory.getLogger(BalanceLedgerServiceImpl.class);

  /** 钱包及账本DECIMAL(18,2)金额上限。 */
  private static final BigDecimal MAX_BALANCE_MONEY = new BigDecimal("9999999999999999.99");

  /** 关联退款读取的100条上限，额外一条为哨兵。 */
  private static final int MAX_REFUND_CANDIDATES = 100;

  /** 钱包事实和条件资金写入。 */
  @Autowired private UserMapper userMapper;

  /** 持久化资金账本。 */
  @Autowired private BalanceLedgerMapper balanceLedgerMapper;

  /** Legacy/TCC共享扣款归属。 */
  @Autowired private BalanceDebitAttemptMapper balanceDebitAttemptMapper;

  /** 原TCC冻结及确认事实。 */
  @Autowired private BalanceTccDebitMapper balanceTccDebitMapper;

  /** 可选余额幂等加速缓存。 */
  @Autowired private StringRedisTemplate redis;

  /** 创建由Spring注入持久化依赖的资金服务。 */
  public BalanceLedgerServiceImpl() {}

  /** 按幂等支付键扣减用户余额。 */
  @Transactional(noRollbackFor = BizException.class)
  @Override
  public ApiResponse<?> debit(Long userId, Long callerId, DebitRequestDTO request) {
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
  @Transactional(noRollbackFor = BizException.class)
  @Override
  public ApiResponse<?> debitInternal(Long userId, Long callerId, BalanceDebitRequestDTO request) {
    return debitFromLedger(userId, callerId, request);
  }

  /** 在同一只读快照证明主账本、原扣款及关联/pay双集合，供支付恢复判断事实。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  @Override
  public ApiResponse<?> balanceOperation(Long userId, String operationKey, Long callerId) {
    // 1. 保持调用者、主行缺失和foreign的原优先级，再证明主行字段。
    assertInternalCaller(userId, callerId);
    Long primaryCount = balanceLedgerMapper.countOperationCandidates(operationKey);
    if (Long.valueOf(0).equals(primaryCount)) {
      throw new BizException(ErrorCodes.NOT_FOUND, "余额操作不存在", 404);
    }
    requireBalanceOperationEvidence(Long.valueOf(1).equals(primaryCount));
    Set<String> uniqueOperationKeys = new HashSet<>();
    uniqueOperationKeys.add(operationKey);
    BalanceLedgerPO ledger = balanceLedgerMapper.selectByOperationKey(operationKey);
    if (ledger == null || !Objects.equals(userId, ledger.userId)) {
      throw new BizException(ErrorCodes.NOT_FOUND, "余额操作不存在", 404);
    }
    requireBalanceOperationEvidence(isProvenBalanceOperationRow(ledger, userId, operationKey));
    // 2. REFUND必须观察实际原DEBIT；缺原来源不能伪装成主操作404。
    BalanceLedgerPO original = ledger;
    if ("REFUND".equals(ledger.operationType)) {
      requireUniqueOperationKey(ledger.originalOperationKey, uniqueOperationKeys);
      original = balanceLedgerMapper.selectByOperationKey(ledger.originalOperationKey);
      requireBalanceOperationEvidence(
          original != null
              && isProvenBalanceOperationRow(original, userId, ledger.originalOperationKey)
              && "DEBIT".equals(original.operationType)
              && "APPLIED".equals(original.state)
              && Objects.equals(original.payNo, ledger.payNo)
              && Objects.equals(original.orderNo, ledger.orderNo));
    }
    // 3. 复用关联kernel和同pay完整集合证明，再核累计及当前退款membership。
    List<BalanceLedgerPO> candidates =
        balanceLedgerMapper.findLinkedRefundCandidates(original.operationKey);
    BigDecimal refundedAmount;
    try {
      refundedAmount = provenRefundedAmount(original, candidates);
      refundedAmount =
          provenRefundPaymentMembership(
              original,
              candidates,
              refundedAmount,
              balanceLedgerMapper.findRefundPaymentLedgerCandidates(original.payNo));
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
    // 5. 原selector/current membership已先证明，再核对应终态的本地归属，不读当前钱包。
    if ("APPLIED".equals(original.state)) {
      try {
        requireAppliedDebitOwnership(original);
      } catch (BizException invalidOwnership) {
        if ("BALANCE_REFUND_INVALID".equals(invalidOwnership.getCode())) {
          throw new BizException(ErrorCodes.CONFLICT, "余额账本事实待核对", 409);
        }
        throw invalidOwnership;
      }
    } else if ("REJECTED".equals(original.state)) {
      requireRejectedDebitOwnership(original);
    }
    // 6. 原各门均通过后，观察退款键的全局CI唯一性；复用primary/source证明不重复COUNT。
    for (BalanceLedgerPO candidate : candidates)
      requireUniqueOperationKey(candidate.operationKey, uniqueOperationKeys);
    return ApiResponse.ok(BalanceOperationVO.from(ledger, refundOperationKey));
  }

  /** 已证明字段的观察键仅成功COUNT1后记入集合，异常原样，不访问未观察键或写资金。 */
  private void requireUniqueOperationKey(String operationKey, Set<String> uniqueOperationKeys) {
    if (!uniqueOperationKeys.contains(operationKey)) {
      requireBalanceOperationEvidence(
          Long.valueOf(1).equals(balanceLedgerMapper.countOperationCandidates(operationKey)));
      uniqueOperationKeys.add(operationKey);
    }
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
  @Transactional(noRollbackFor = BizException.class)
  @Override
  public ApiResponse<?> refundInternal(
      Long userId, Long callerId, BalanceRefundRequestDTO request) {
    // 1. 校验调用者和请求金额，按原顺序锁定用户。
    assertInternalCaller(userId, callerId);
    validateMoney(request.amount, "退款金额不合法");
    UserPO user = userMapper.selectByIdForUpdate(userId);
    if (user == null) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
    // 2. 既有退款先保原proof/优先级；active核原DEBIT，disabled仍原TCC来源404。
    BalanceLedgerPO existing =
        readRefundLedgerCandidate(
            () -> balanceLedgerMapper.selectByOperationKey(request.refundOperationKey));
    if (existing != null) {
      BalanceLedgerPO original = null;
      if (!Objects.equals(user.status, 1)) {
        original =
            readRefundLedgerCandidate(
                () -> balanceLedgerMapper.selectDebitForUpdate(request.originalOperationKey));
        requireDisabledTccRefund(original, request, userId);
      }
      ensureRefundMatches(existing, userId, request);
      if (Objects.equals(user.status, 1)) {
        original =
            readRefundLedgerCandidate(
                () -> balanceLedgerMapper.selectDebitForUpdate(request.originalOperationKey));
        if (!isProvenRefundOriginalDebit(original, request, userId)
            || existing.amount.compareTo(original.amount) > 0)
          throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
        requireAppliedDebitOwnership(original);
      } else if (existing.amount.compareTo(original.amount) > 0) {
        // disabled原source/TCC与退款行已先证明；复用已读原额，不新增查询或再次入账。
        throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
      }
      // 原row/source/cap/owner已先证明，再核同pay唯一扣款；不重复读取source或入账。
      requireRefundDebitScope(original, request, userId);
      requireRefundReplayCollection(original, existing);
      return ApiResponse.ok(
          BalanceRefundVO.from(existing.operationKey, existing.state, existing.balanceAfter));
    }
    // 3. 新退款必须先证明原扣款身份和资金快照，再计算合计或写余额。
    BalanceLedgerPO original =
        readRefundLedgerCandidate(
            () -> balanceLedgerMapper.selectDebitForUpdate(request.originalOperationKey));
    if (!Objects.equals(user.status, 1)) requireDisabledTccRefund(original, request, userId);
    if (!isProvenRefundOriginalDebit(original, request, userId)) {
      throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
    }
    // 同pay唯一DEBIT候选须复制已证明原来源，不能忽略其它状态或CI别名扣款。
    requireRefundDebitScope(original, request, userId);
    // active原DEBIT已证明，再核同pay归属/冻结，不重复读取原source。
    if (Objects.equals(user.status, 1)) requireAppliedDebitOwnership(original);
    // 4. 逐行证明集合及金额额度，再核新增后的记录容量，避免创建无法恢复的第101条。
    requireFreshRefundCollection(original, request.amount);
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

  /** 仅退款点读的直接多结果或标准直接包装转业务409，其它异常/nullable候选原样。 */
  private BalanceLedgerPO readRefundLedgerCandidate(Supplier<BalanceLedgerPO> ledgerRead) {
    try {
      return ledgerRead.get();
    } catch (TooManyResultsException | MyBatisSystemException failure) {
      if (failure instanceof TooManyResultsException
          || failure.getCause() instanceof TooManyResultsException) {
        throw new BizException("BALANCE_REFUND_INVALID", "退款累计证据不完整或不一致", 409);
      }
      throw failure;
    }
  }

  /** 已有退款各门已先证明；当前退款须在一致双集合中且累计不超原额，不再次加请求金额。 */
  private void requireRefundReplayCollection(BalanceLedgerPO original, BalanceLedgerPO existing) {
    List<BalanceLedgerPO> linkedRefunds =
        balanceLedgerMapper.findLinkedRefundCandidates(original.operationKey);
    BigDecimal linkedTotal = provenRefundedAmount(original, linkedRefunds);
    provenRefundPaymentMembership(
        original,
        linkedRefunds,
        linkedTotal,
        balanceLedgerMapper.findRefundPaymentLedgerCandidates(original.payNo));
    requireRefundMembership(
        containsRefundSnapshot(linkedRefunds, existing)
            && linkedTotal.compareTo(original.amount) <= 0);
    requireRefundGlobalKeys(original, linkedRefunds);
  }

  /** 原source/双集合及额度已先证明；观察键全局COUNT1，不以局部范围掩盖其它pay重复。 */
  private void requireRefundGlobalKeys(
      BalanceLedgerPO original, List<BalanceLedgerPO> linkedRefunds) {
    requireRefundMembership(
        Long.valueOf(1)
            .equals(balanceLedgerMapper.countOperationCandidates(original.operationKey)));
    for (BalanceLedgerPO linked : linkedRefunds)
      requireRefundMembership(
          Long.valueOf(1)
              .equals(balanceLedgerMapper.countOperationCandidates(linked.operationKey)));
  }

  /** 原扣款已证明；同pay有界DEBIT须唯一且复制原来源，DAO异常原样传播，不写资金。 */
  private void requireRefundDebitScope(
      BalanceLedgerPO original, BalanceRefundRequestDTO request, Long userId) {
    List<BalanceLedgerPO> debitCandidates =
        balanceLedgerMapper.findDebitCandidatesByPayNo(request.payNo);
    if (debitCandidates == null
        || debitCandidates.size() != 1
        || !matchesRefundDebitScope(original, debitCandidates.get(0), request, userId))
      throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
  }

  /** APPLIED原DEBIT已先证明；同pay来源及Legacy接收互斥只读验证，非重复数据库故障原样。 */
  private void requireAppliedDebitOwnership(BalanceLedgerPO original) {
    BalanceDebitAttemptPO attempt =
        readDebitOwnershipCandidate(
            original.payNo, () -> new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409));
    if (attempt != null
        && !("LEGACY".equals(attempt.mode)
            ? isProvenLegacyDebitOwnership(original, attempt)
            : confirmedTccRefund(original, attempt)))
      throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
    // 原Legacy归属已先核；无owner/Legacy都须同pay无冻结，不按state/身份过滤矛盾来源。
    if ((attempt == null || "LEGACY".equals(attempt.mode)) && hasTccFreezeConflict(original.payNo))
      throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
    // 原归属/冻结已先证明；Legacy或缺归属历史不得忽略独立接收事实，TCC保持原确认链。
    if ((attempt == null || "LEGACY".equals(attempt.mode)) && hasTccReceiveConflict(original.payNo))
      throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
  }

  /** 原DEBIT终态已证明；Legacy须复制其状态、键/身份/金额且无TCC分支。 */
  private static boolean isProvenLegacyDebitOwnership(
      BalanceLedgerPO original, BalanceDebitAttemptPO attempt) {
    return "LEGACY".equals(attempt.mode)
        && Objects.equals(attempt.state, original.state)
        && Objects.equals(attempt.userId, original.userId)
        && Objects.equals(attempt.payNo, original.payNo)
        && Objects.equals(attempt.orderNo, original.orderNo)
        && Objects.equals(attempt.operationKey, original.operationKey)
        && Objects.equals(attempt.ledgerKey, original.operationKey)
        && attempt.xid == null
        && attempt.branchId == null
        && validBalanceMoney(attempt.amount)
        && attempt.amount.signum() > 0
        && attempt.amount.compareTo(original.amount) == 0;
  }

  /** 拒款原快照已先证明；有效Legacy或null历史均须无同pay冻结/接收，非重复数据库故障原样。 */
  private void requireRejectedDebitOwnership(BalanceLedgerPO original) {
    BalanceDebitAttemptPO attempt =
        readDebitOwnershipCandidate(
            original.payNo, () -> new BizException(ErrorCodes.CONFLICT, "余额账本事实待核对", 409));
    requireBalanceOperationEvidence(
        attempt == null || isProvenLegacyDebitOwnership(original, attempt));
    requireBalanceOperationEvidence(!hasTccFreezeConflict(original.payNo));
    // 原账本/归属/冻结门已通过；拒款不能忽略独立接收事实或按其元数据过滤。
    requireBalanceOperationEvidence(!hasTccReceiveConflict(original.payNo));
  }

  /** 单次冻结点读观察存在性；精确多结果表示存在冲突，其它故障原样且不伪造PO。 */
  private boolean hasTccFreezeConflict(String payNo) {
    try {
      return balanceTccDebitMapper.selectByPayNo(payNo) != null;
    } catch (TooManyResultsException | MyBatisSystemException failure) {
      if (failure instanceof TooManyResultsException
          || failure.getCause() instanceof TooManyResultsException) return true;
      throw failure;
    }
  }

  /** 确认来源必须唯一；精确多结果返回null使原proof失败，其它故障及正常候选原样。 */
  private BalanceTccDebitPO readConfirmableTccFreeze(String payNo) {
    try {
      return balanceTccDebitMapper.selectByPayNo(payNo);
    } catch (TooManyResultsException | MyBatisSystemException failure) {
      if (failure instanceof TooManyResultsException
          || failure.getCause() instanceof TooManyResultsException) return null;
      throw failure;
    }
  }

  /** 归属保持原单次点读；仅精确多结果调用所属入口异常工厂，其它故障/null/单行原样。 */
  private BalanceDebitAttemptPO readDebitOwnershipCandidate(
      String payNo, Supplier<BizException> duplicateConflictSupplier) {
    try {
      return balanceDebitAttemptMapper.selectByPayNo(payNo);
    } catch (TooManyResultsException | MyBatisSystemException failure) {
      if (failure instanceof TooManyResultsException
          || failure.getCause() instanceof TooManyResultsException) {
        throw duplicateConflictSupplier.get();
      }
      throw failure;
    }
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

  /** 单次点读观察接收存在性；仅精确多结果及其直接MyBatis包装表示冲突，其它故障原样。 */
  private boolean hasTccReceiveConflict(String payNo) {
    try {
      return userTccReceiveMapper.findByPayNo(payNo) != null;
    } catch (TooManyResultsException duplicate) {
      return true;
    } catch (MyBatisSystemException failure) {
      if (failure.getCause() instanceof TooManyResultsException) return true;
      throw failure;
    }
  }

  /** 禁用用户保留原单次归属查询及404调用链，再复用本地确认来源门。 */
  private boolean confirmedTccRefund(BalanceLedgerPO original, BalanceRefundRequestDTO request) {
    return confirmedTccRefund(
        original,
        readDebitOwnershipCandidate(
            request.payNo, () -> new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404)));
  }

  /** 调用方已观察归属；复核原TCC元组和唯一确认冻结，重复冻结不能作为确认来源。 */
  private boolean confirmedTccRefund(BalanceLedgerPO original, BalanceDebitAttemptPO attempt) {
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
        || !Objects.equals(attempt.ledgerKey, original.operationKey)
        || !Objects.equals(attempt.operationKey, "tcc-debit:" + original.payNo)) return false;
    BalanceTccDebitPO frozen = readConfirmableTccFreeze(original.payNo);
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

  /** 原DEBIT已通过proof；唯一候选必须复制其主键/精确载荷/数值快照，缺证或别行不能作为来源。 */
  private static boolean matchesRefundDebitScope(
      BalanceLedgerPO original,
      BalanceLedgerPO candidate,
      BalanceRefundRequestDTO request,
      Long userId) {
    return candidate != null
        && candidate.id != null
        && Objects.equals(candidate.id, original.id)
        && isProvenRefundOriginalDebit(candidate, request, userId)
        && candidate.amount.compareTo(original.amount) == 0
        && candidate.balanceBefore.compareTo(original.balanceBefore) == 0
        && candidate.balanceAfter.compareTo(original.balanceAfter) == 0;
  }

  /** 新退款先证明双集合与原金额额度，再限制新增后至多100条；复用已读集合，不新增查询。 */
  private void requireFreshRefundCollection(BalanceLedgerPO original, BigDecimal requestAmount) {
    List<BalanceLedgerPO> linkedRefunds =
        balanceLedgerMapper.findLinkedRefundCandidates(original.operationKey);
    BigDecimal linkedTotal = provenRefundedAmount(original, linkedRefunds);
    provenRefundPaymentMembership(
        original,
        linkedRefunds,
        linkedTotal,
        balanceLedgerMapper.findRefundPaymentLedgerCandidates(original.payNo));
    if (linkedTotal.add(requestAmount).compareTo(original.amount) > 0) {
      throw new BizException("BALANCE_REFUND_EXCEEDED", "退款金额超过原扣款", 409);
    }
    if (linkedRefunds.size() >= MAX_REFUND_CANDIDATES) {
      throw new BizException("BALANCE_REFUND_INVALID", "退款记录数量已达上限", 409);
    }
    requireRefundGlobalKeys(original, linkedRefunds);
  }

  /** linked已按原kernel证明；同一快照的pay集合必须完整复制来源及每条退款，不重复查询。 */
  private static BigDecimal provenRefundPaymentMembership(
      BalanceLedgerPO original,
      List<BalanceLedgerPO> linkedRefunds,
      BigDecimal linkedTotal,
      List<BalanceLedgerPO> paymentRows) {
    requireRefundMembership(paymentRows != null && paymentRows.size() <= MAX_REFUND_CANDIDATES + 1);
    List<BalanceLedgerPO> paymentRefunds = new java.util.ArrayList<>();
    boolean originalObserved = false;
    for (BalanceLedgerPO row : paymentRows) {
      if (row != null && Objects.equals(row.operationKey, original.operationKey)) {
        requireRefundMembership(!originalObserved && matchesRefundPaymentOriginal(original, row));
        originalObserved = true;
      } else paymentRefunds.add(row);
    }
    requireRefundMembership(originalObserved);
    BigDecimal paymentTotal = provenRefundedAmount(original, paymentRefunds);
    requireRefundMembership(
        linkedRefunds.size() == paymentRefunds.size() && linkedTotal.compareTo(paymentTotal) == 0);
    for (BalanceLedgerPO linked : linkedRefunds)
      requireRefundMembership(containsRefundSnapshot(paymentRefunds, linked));
    return linkedTotal;
  }

  /** 原DEBIT已证明；同pay集合必须观察同一主键及精确载荷/数值快照，不能复制别行。 */
  private static boolean matchesRefundPaymentOriginal(
      BalanceLedgerPO original, BalanceLedgerPO candidate) {
    return candidate.id != null
        && Objects.equals(candidate.id, original.id)
        && "DEBIT".equals(candidate.operationType)
        && Objects.equals(candidate.state, original.state)
        && isProvenBalanceOperationRow(candidate, original.userId, original.operationKey)
        && Objects.equals(candidate.payNo, original.payNo)
        && Objects.equals(candidate.orderNo, original.orderNo)
        && candidate.amount.compareTo(original.amount) == 0
        && candidate.balanceBefore.compareTo(original.balanceBefore) == 0
        && candidate.balanceAfter.compareTo(original.balanceAfter) == 0;
  }

  /** 两集合已读证据缺失、超限或错配保持原累计证据409，不泄露原字段。 */
  private static void requireRefundMembership(boolean matches) {
    if (!matches) throw new BizException("BALANCE_REFUND_INVALID", "退款累计证据不完整或不一致", 409);
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
    BalanceDebitAttemptPO attempt =
        readDebitOwnershipCandidate(
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
      if (hasTccFreezeConflict(request.payNo))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款来源存在TCC冻结事实", 409);
      // 历史原来源/冻结门已通过；任意接收事实都要求核对，不缓存矛盾历史结果。
      if (hasTccReceiveConflict(request.payNo))
        throw new BizException("BALANCE_DEBIT_UNKNOWN", "扣款来源存在 TCC 接收事实", 409);
      if ("REJECTED".equals(existing.state)) throw new BizException("PAYMENT_FAILED", "余额不足", 409);
      cacheDebitResult(userId, existing);
      return ApiResponse.ok(
          BalanceDebitVO.from(existing.operationKey, existing.state, existing.balanceAfter));
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
    if (hasTccFreezeConflict(request.payNo))
      throw new BizException("IDEMPOTENCY_CONFLICT", "支付号已有TCC冻结事实", 409);
    // 原账本/冻结门已通过；任意已观察receive均阻断，不用flag或元数据隐藏原来源。
    if (hasTccReceiveConflict(request.payNo))
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
}
