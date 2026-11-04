package com.cloudmall.user.service.impl.balance;

import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.isProvenBalanceOperationRow;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.isProvenRefundOriginalDebit;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.validBalanceMoney;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.stereotype.Component;

/** 退款来源及双集合证明，沿用调用方事务和原有读取预算。 */
@Component
public final class BalanceRefundEvidence {
  /** 关联退款最多100条，第101条用于拒绝超范围证明。 */
  private static final int MAX_REFUND_CANDIDATES = 100;

  /** 原扣款和退款候选集合。 */
  private final BalanceLedgerMapper balanceLedgerMapper;

  /** 注入本职责所需的持久化与证明依赖，不执行查询或建立事务。 */
  public BalanceRefundEvidence(BalanceLedgerMapper balanceLedgerMapper) {
    this.balanceLedgerMapper = balanceLedgerMapper;
  }

  /** 仅退款点读的直接多结果或标准直接包装转业务409，其它异常/nullable候选原样。 */
  static BalanceLedgerPO readRefundLedgerCandidate(Supplier<BalanceLedgerPO> ledgerRead) {
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
  void requireRefundReplayCollection(BalanceLedgerPO original, BalanceLedgerPO existing) {
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
  void requireRefundDebitScope(
      BalanceLedgerPO original, BalanceRefundRequestDTO request, Long userId) {
    List<BalanceLedgerPO> debitCandidates =
        balanceLedgerMapper.findDebitCandidatesByPayNo(request.payNo);
    if (debitCandidates == null
        || debitCandidates.size() != 1
        || !matchesRefundDebitScope(original, debitCandidates.get(0), request, userId))
      throw new BizException("BALANCE_REFUND_INVALID", "原扣款不存在或未应用", 409);
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
  void requireFreshRefundCollection(BalanceLedgerPO original, BigDecimal requestAmount) {
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
  static BigDecimal provenRefundPaymentMembership(
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
  static BigDecimal provenRefundedAmount(
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

  /** 当前退款与同一已证集合的金额/快照需匹配，身份字段已由共同原source保证。 */
  static boolean containsRefundSnapshot(List<BalanceLedgerPO> candidates, BalanceLedgerPO ledger) {
    for (BalanceLedgerPO candidate : candidates) {
      if (Objects.equals(candidate.operationKey, ledger.operationKey)
          && candidate.amount.compareTo(ledger.amount) == 0
          && candidate.balanceBefore.compareTo(ledger.balanceBefore) == 0
          && candidate.balanceAfter.compareTo(ledger.balanceAfter) == 0) return true;
    }
    return false;
  }
}
