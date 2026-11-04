package com.cloudmall.user.service.impl.balance;

import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.isProvenRefundOriginalDebit;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.requireBalanceOperationEvidence;
import static com.cloudmall.user.service.impl.balance.BalanceLedgerRules.validBalanceMoney;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.stereotype.Component;

/** 在调用方现有事务中核对Legacy/TCC归属、冻结和接收事实；不写资金。 */
@Component
public final class BalanceOwnershipEvidence {
  /** 共享扣款归属。 */
  private final BalanceDebitAttemptMapper balanceDebitAttemptMapper;

  /** 同库冻结及确认事实。 */
  private final BalanceTccDebitMapper balanceTccDebitMapper;

  /** 同库HTTP接收来源。 */
  private final UserTccReceiveMapper userTccReceiveMapper;

  /** 注入本职责所需的持久化与证明依赖，不执行查询或建立事务。 */
  public BalanceOwnershipEvidence(
      BalanceDebitAttemptMapper balanceDebitAttemptMapper,
      BalanceTccDebitMapper balanceTccDebitMapper,
      UserTccReceiveMapper userTccReceiveMapper) {
    this.balanceDebitAttemptMapper = balanceDebitAttemptMapper;
    this.balanceTccDebitMapper = balanceTccDebitMapper;
    this.userTccReceiveMapper = userTccReceiveMapper;
  }

  /** APPLIED原DEBIT已先证明；同pay来源及Legacy接收互斥只读验证，非重复数据库故障原样。 */
  void requireAppliedDebitOwnership(BalanceLedgerPO original) {
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
  void requireRejectedDebitOwnership(BalanceLedgerPO original) {
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
  boolean hasTccFreezeConflict(String payNo) {
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
  BalanceDebitAttemptPO readDebitOwnershipCandidate(
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

  /** 单次点读观察接收存在性；仅精确多结果及其直接MyBatis包装表示冲突，其它故障原样。 */
  boolean hasTccReceiveConflict(String payNo) {
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
  void requireDisabledTccRefund(
      BalanceLedgerPO original, BalanceRefundRequestDTO request, Long userId) {
    if (!isProvenRefundOriginalDebit(original, request, userId)) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
    if (!confirmedTccRefund(original, request)) {
      throw new BizException(ErrorCodes.NOT_FOUND, "用户不存在", 404);
    }
  }
}
