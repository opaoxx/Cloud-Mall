package com.cloudmall.user.service.impl.balance;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import java.math.BigDecimal;
import java.util.Objects;

/** 资金身份、金额域和单行快照的纯规则，不访问数据库或线程上下文。 */
final class BalanceLedgerRules {
  /** 资金DECIMAL(18,2)数值上限，禁止舍入或截断。 */
  static final BigDecimal MAX_BALANCE_MONEY = new BigDecimal("9999999999999999.99");

  /** 禁止实例化纯规则容器。 */
  private BalanceLedgerRules() {}

  /** 校验内部调用者身份。 */
  static void assertInternalCaller(Long userId, Long callerId) {
    if (!Objects.equals(userId, callerId)) {
      throw new BizException(ErrorCodes.FORBIDDEN, "内部用户身份无效", 403);
    }
  }

  /** 请求金额必须为正且无损落在18.2域，避免SQL隐式舍入、溢出或零金额流水。 */
  static void validateMoney(BigDecimal amount, String message) {
    if (amount == null
        || amount.signum() <= 0
        || amount.compareTo(MAX_BALANCE_MONEY) > 0
        || amount.stripTrailingZeros().scale() > 2) {
      throw new BizException(ErrorCodes.INVALID, message, 400);
    }
  }

  /** 持久金额必须存在、非负且无损落在18.2域，允许零余额和数值等值尾零。 */
  static boolean validBalanceMoney(BigDecimal value) {
    return value != null
        && value.signum() >= 0
        && value.stripTrailingZeros().scale() <= 2
        && value.compareTo(MAX_BALANCE_MONEY) <= 0;
  }

  /**
   * 已知DEBIT终态必须由原合法余额快照证明；已核金额由调用方保证，不能用当前钱包改写历史决策。
   * APPLIED证明差额，REJECTED证明未扣款且原余额不足；状态冲突及未知态先由原调用方拒绝。
   */
  static boolean isProvenDebitSnapshot(BalanceLedgerPO existing) {
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

  /** 主行只证明不可变键/金额和已知终态快照；不推断当前钱包、全账户或TCC外部事实。 */
  static boolean isProvenBalanceOperationRow(
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

  /** 原锁定候选须为精确DEBIT和合法快照；只核原字段，不推断当前钱包或全部退款因果。 */
  static boolean isProvenRefundOriginalDebit(
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

  /** 坏查询证据保持固定409，不暴露值或被支付恢复器误认成missing。 */
  static void requireBalanceOperationEvidence(boolean proven) {
    if (!proven) throw new BizException(ErrorCodes.CONFLICT, "余额账本事实待核对", 409);
  }
}
