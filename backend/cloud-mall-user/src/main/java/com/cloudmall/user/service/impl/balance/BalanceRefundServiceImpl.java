package com.cloudmall.user.service.impl.balance;

import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.MAX_BALANCE_MONEY;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.assertInternalCaller;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.isProvenRefundOriginalDebit;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.validBalanceMoney;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.validateMoney;
import static com.cloudmall.user.service.impl.balance.BalanceRefundEvidence.readRefundLedgerCandidate;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.BalanceRefundVO;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.BalanceRefundService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原扣款证明后的余额退款及幂等重放。 */
@Service
public class BalanceRefundServiceImpl implements BalanceRefundService {
  /** 钱包行锁及条件退款。 */
  private final UserMapper userMapper;

  /** 原扣款与退款账本。 */
  private final BalanceLedgerMapper balanceLedgerMapper;

  /** 原扣款归属及确认来源证明。 */
  private final BalanceOwnershipEvidence ownershipEvidence;

  /** 退款双集合和累计额度证明。 */
  private final BalanceRefundEvidence refundEvidence;

  /** 注入本职责所需的持久化与证明依赖，不执行查询或建立事务。 */
  public BalanceRefundServiceImpl(
      UserMapper userMapper,
      BalanceLedgerMapper balanceLedgerMapper,
      BalanceOwnershipEvidence ownershipEvidence,
      BalanceRefundEvidence refundEvidence) {
    this.userMapper = userMapper;
    this.balanceLedgerMapper = balanceLedgerMapper;
    this.ownershipEvidence = ownershipEvidence;
    this.refundEvidence = refundEvidence;
  }

  /** 按唯一退款键恢复已应用扣款。 */
  @Transactional(noRollbackFor = BizException.class)
  @Override
  public BalanceRefundVO refundInternal(
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
        ownershipEvidence.requireDisabledTccRefund(original, request, userId);
      }
      ensureRefundMatches(existing, userId, request);
      if (Objects.equals(user.status, 1)) {
        original =
            readRefundLedgerCandidate(
                () -> balanceLedgerMapper.selectDebitForUpdate(request.originalOperationKey));
        if (!isProvenRefundOriginalDebit(original, request, userId)
            || existing.amount.compareTo(original.amount) > 0)
          throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
        ownershipEvidence.requireAppliedDebitOwnership(original);
      } else if (existing.amount.compareTo(original.amount) > 0) {
        // disabled原source/TCC与退款行已先证明；复用已读原额，不新增查询或再次入账。
        throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
      }
      // 原row/source/cap/owner已先证明，再核同pay唯一扣款；不重复读取source或入账。
      refundEvidence.requireRefundDebitScope(original, request, userId);
      refundEvidence.requireRefundReplayCollection(original, existing);
      return BalanceRefundVO.from(existing.operationKey, existing.state, existing.balanceAfter);
    }
    // 3. 新退款必须先证明原扣款身份和资金快照，再计算合计或写余额。
    BalanceLedgerPO original =
        readRefundLedgerCandidate(
            () -> balanceLedgerMapper.selectDebitForUpdate(request.originalOperationKey));
    if (!Objects.equals(user.status, 1))
      ownershipEvidence.requireDisabledTccRefund(original, request, userId);
    if (!isProvenRefundOriginalDebit(original, request, userId)) {
      throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
    }
    // 同pay唯一DEBIT候选须复制已证明原来源，不能忽略其它状态或CI别名扣款。
    refundEvidence.requireRefundDebitScope(original, request, userId);
    // active原DEBIT已证明，再核同pay归属/冻结，不重复读取原source。
    if (Objects.equals(user.status, 1)) ownershipEvidence.requireAppliedDebitOwnership(original);
    // 4. 逐行证明集合及金额额度，再核新增后的记录容量，避免创建无法恢复的第101条。
    refundEvidence.requireFreshRefundCollection(original, request.amount);
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
    return BalanceRefundVO.from(refund.operationKey, refund.state, refund.balanceAfter);
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
}
