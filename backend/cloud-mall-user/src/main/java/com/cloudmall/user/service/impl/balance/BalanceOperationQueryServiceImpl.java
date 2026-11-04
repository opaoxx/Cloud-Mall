package com.cloudmall.user.service.impl.balance;

import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.assertInternalCaller;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.isProvenBalanceOperationRow;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.requireBalanceOperationEvidence;
import static com.cloudmall.user.service.impl.balance.BalanceRefundEvidence.containsRefundSnapshot;
import static com.cloudmall.user.service.impl.balance.BalanceRefundEvidence.provenRefundPaymentMembership;
import static com.cloudmall.user.service.impl.balance.BalanceRefundEvidence.provenRefundedAmount;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.vo.BalanceOperationVO;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.service.BalanceOperationQueryService;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 在一个只读RR快照中证明并返回余额操作。 */
@Service
public class BalanceOperationQueryServiceImpl implements BalanceOperationQueryService {
  /** 只读操作及关联账本。 */
  private final BalanceLedgerMapper balanceLedgerMapper;

  /** 同一快照中的原扣款归属证明。 */
  private final BalanceOwnershipEvidence ownershipEvidence;

  /** 注入本职责所需的持久化与证明依赖，不执行查询或建立事务。 */
  public BalanceOperationQueryServiceImpl(
      BalanceLedgerMapper balanceLedgerMapper, BalanceOwnershipEvidence ownershipEvidence) {
    this.balanceLedgerMapper = balanceLedgerMapper;
    this.ownershipEvidence = ownershipEvidence;
  }

  /** 在同一只读快照证明主账本、原扣款及关联/pay双集合，供支付恢复判断事实。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  @Override
  public BalanceOperationVO balanceOperation(Long userId, String operationKey, Long callerId) {
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
        ownershipEvidence.requireAppliedDebitOwnership(original);
      } catch (BizException invalidOwnership) {
        if ("BALANCE_REFUND_INVALID".equals(invalidOwnership.getCode())) {
          throw new BizException(ErrorCodes.CONFLICT, "余额账本事实待核对", 409);
        }
        throw invalidOwnership;
      }
    } else if ("REJECTED".equals(original.state)) {
      ownershipEvidence.requireRejectedDebitOwnership(original);
    }
    // 6. 原各门均通过后，观察退款键的全局CI唯一性；复用primary/source证明不重复COUNT。
    for (BalanceLedgerPO candidate : candidates)
      requireUniqueOperationKey(candidate.operationKey, uniqueOperationKeys);
    return BalanceOperationVO.from(ledger, refundOperationKey);
  }

  /** 已证明字段的观察键仅成功COUNT1后记入集合，异常原样，不访问未观察键或写资金。 */
  private void requireUniqueOperationKey(String operationKey, Set<String> uniqueOperationKeys) {
    if (!uniqueOperationKeys.contains(operationKey)) {
      requireBalanceOperationEvidence(
          Long.valueOf(1).equals(balanceLedgerMapper.countOperationCandidates(operationKey)));
      uniqueOperationKeys.add(operationKey);
    }
  }

  /** selector必须指向同一已证集合，保留原SQL选择策略而不以CI别名替代实际键。 */
  private static boolean containsRefundOperationKey(List<BalanceLedgerPO> candidates, String key) {
    for (BalanceLedgerPO candidate : candidates) {
      if (Objects.equals(candidate.operationKey, key)) return true;
    }
    return false;
  }
}
