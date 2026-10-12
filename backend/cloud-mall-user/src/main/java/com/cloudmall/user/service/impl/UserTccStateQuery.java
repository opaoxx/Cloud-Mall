package com.cloudmall.user.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.TccFenceLogPO;
import com.cloudmall.user.domain.po.UserTccReceivePO;
import com.cloudmall.user.domain.vo.UserTccStateVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 同一只读快照核对接收、资金和 Fence；缺少事实永远不等于未执行。 */
public class UserTccStateQuery {
  /** 原资金金额DECIMAL(18,2)容量上界，不截断或补造缺失金额。 */
  private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");

  /** 接收事实。 */
  private final UserTccReceiveMapper receives;

  /** 资金归属。 */
  private final BalanceDebitAttemptMapper attempts;

  /** 冻结及清算事实。 */
  private final BalanceTccDebitMapper debits;

  /** 实际扣款账本。 */
  private final BalanceLedgerMapper ledgers;

  /** 官方 Fence，只在已知分支上查询。 */
  private final TccFenceLogMapper fences;

  /** 创建一致快照查询服务。 */
  public UserTccStateQuery(
      UserTccReceiveMapper receives,
      BalanceDebitAttemptMapper attempts,
      BalanceTccDebitMapper debits,
      BalanceLedgerMapper ledgers,
      TccFenceLogMapper fences) {
    this.receives = receives;
    this.attempts = attempts;
    this.debits = debits;
    this.ledgers = ledgers;
    this.fences = fences;
  }

  /** 查询不会绑定 XID、注册分支或修改任何接收/资金记录。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UserTccStateVO read(Long userId, String operationKey, String xid) {
    // 1. 先验证原接收载荷和调用者的查询归属。
    UserTccReceivePO receive = receives.findByOperationKey(operationKey);
    if (receive == null) throw new BizException("COMMON_NOT_FOUND", "未观察到 TCC 接收事实", 404);
    require(
        Objects.equals(receive.userId, userId)
            && Objects.equals(receive.operationKey, operationKey)
            && Objects.equals(receive.xid, xid));
    require(validReceiveMetadata(receive));
    require(validPositiveMoney(receive.amount));
    UserTccStateVO view = new UserTccStateVO();
    view.userId = receive.userId;
    view.payNo = receive.payNo;
    view.orderNo = receive.orderNo;
    view.amount = receive.amount.toPlainString();
    view.operationKey = receive.operationKey;
    view.ledgerKey = receive.ledgerKey;
    view.xid = receive.xid;
    view.state = "UNKNOWN";
    BalanceDebitAttemptPO attempt = attempts.selectByPayNo(receive.payNo);
    BalanceTccDebitPO debit = debits.selectByPayNo(receive.payNo);
    BalanceLedgerPO ledger = ledgers.selectByOperationKey(receive.ledgerKey);
    if (attempt == null) {
      require(debit == null && ledger == null);
      return view;
    }
    // 2. 接收、归属和已知分支必须描述同一笔资金。
    require(
        validPositiveMoney(attempt.amount)
            && "TCC".equals(attempt.mode)
            && Objects.equals(attempt.userId, userId)
            && Objects.equals(attempt.payNo, receive.payNo)
            && Objects.equals(attempt.orderNo, receive.orderNo)
            && Objects.equals(attempt.xid, xid)
            && Objects.equals(attempt.operationKey, operationKey)
            && Objects.equals(attempt.ledgerKey, receive.ledgerKey)
            && attempt.amount.compareTo(receive.amount) == 0
            && attempt.branchId != null
            && attempt.branchId > 0
            && (receive.branchId == null || Objects.equals(receive.branchId, attempt.branchId)));
    if (debit != null)
      require(
          validPositiveMoney(debit.amount)
              && validDebitSnapshot(debit.balanceBefore, debit.balanceAfterTry, debit.amount)
              && Objects.equals(debit.userId, userId)
              && Objects.equals(debit.payNo, receive.payNo)
              && Objects.equals(debit.orderNo, receive.orderNo)
              && Objects.equals(debit.operationKey, operationKey)
              && Objects.equals(debit.xid, xid)
              && Objects.equals(debit.branchId, attempt.branchId)
              && debit.amount.compareTo(receive.amount) == 0
              && Objects.equals(debit.state, attempt.state));
    view.branchId = attempt.branchId;
    // 3. 只有完整持久证据才能报告终态，TRYING 仍只是冻结。
    Integer expectedFence;
    if ("TRYING".equals(attempt.state)) {
      require(debit != null && ledger == null);
      require(ledgers.selectDebitByPayNo(receive.payNo) == null);
      expectedFence = 1;
    } else if ("CONFIRMED".equals(attempt.state)) {
      require(
          debit != null
              && ledger != null
              && validPositiveMoney(ledger.amount)
              && validDebitSnapshot(ledger.balanceBefore, ledger.balanceAfter, ledger.amount)
              && ledger.balanceBefore.compareTo(debit.balanceBefore) == 0
              && ledger.balanceAfter.compareTo(debit.balanceAfterTry) == 0
              && "DEBIT".equals(ledger.operationType)
              && "APPLIED".equals(ledger.state)
              && Objects.equals(ledger.userId, userId)
              && Objects.equals(ledger.payNo, receive.payNo)
              && Objects.equals(ledger.orderNo, receive.orderNo)
              && Objects.equals(ledger.operationKey, receive.ledgerKey)
              && ledger.amount.compareTo(receive.amount) == 0);
      require(matchesDebitScope(ledgers.selectDebitByPayNo(receive.payNo), ledger));
      view.ledgerApplied = true;
      expectedFence = 2;
    } else if ("CANCELLED".equals(attempt.state)) {
      require(ledger == null);
      // 同支付号的其他键或未完成DEBIT也与取消证明矛盾，不把固定键缺行等同于无扣款。
      require(ledgers.selectDebitByPayNo(receive.payNo) == null);
      expectedFence = debit == null ? 4 : 3;
    } else throw conflict();
    TccFenceLogPO fence = fences.findByBranch(xid, attempt.branchId);
    if (fence != null)
      require(
          Objects.equals(fence.xid, xid)
              && Objects.equals(fence.branchId, attempt.branchId)
              && BalanceTccDebitLocalResource.ACTION_NAME.equals(fence.actionName)
              && expectedFence.equals(fence.status));
    else require(!"TRYING".equals(attempt.state));
    view.state = attempt.state;
    return view;
  }

  /** 原固定账本已通过证明；全pay唯一候选须复制原载荷与金额，缺失或别键不能证明确认。 */
  private static boolean matchesDebitScope(BalanceLedgerPO candidate, BalanceLedgerPO original) {
    return candidate != null
        && Objects.equals(candidate.userId, original.userId)
        && Objects.equals(candidate.payNo, original.payNo)
        && Objects.equals(candidate.orderNo, original.orderNo)
        && Objects.equals(candidate.operationKey, original.operationKey)
        && "DEBIT".equals(candidate.operationType)
        && "APPLIED".equals(candidate.state)
        && validPositiveMoney(candidate.amount)
        && validBalance(candidate.balanceBefore)
        && validBalance(candidate.balanceAfter)
        && candidate.amount.compareTo(original.amount) == 0
        && candidate.balanceBefore.compareTo(original.balanceBefore) == 0
        && candidate.balanceAfter.compareTo(original.balanceAfter) == 0;
  }

  /** 原claim载荷先于任何资金查询核验，null分支是尚未bind的合法状态，不补造接收事实。 */
  private static boolean validReceiveMetadata(UserTccReceivePO receive) {
    return "CLAIMED".equals(receive.state)
        && receive.userId != null
        && receive.userId > 0
        && receive.payNo != null
        && receive.payNo.matches("[A-Za-z0-9_-]{1,64}")
        && Objects.equals(receive.operationKey, "tcc-debit:" + receive.payNo)
        && Objects.equals(receive.ledgerKey, receive.payNo)
        && receive.orderNo != null
        && !receive.orderNo.isBlank()
        && receive.orderNo.length() <= 64
        && receive.xid != null
        && !receive.xid.isBlank()
        && receive.xid.length() <= 128
        && (receive.branchId == null || receive.branchId > 0);
  }

  /** 原金额缺失或非法均为持久事实冲突，避免NULL变500或相等坏值被当成资金证明。 */
  private static boolean validPositiveMoney(BigDecimal amount) {
    return amount != null
        && amount.signum() > 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_MONEY) <= 0;
  }

  /** 调用方已证明正amount；余额快照必须存在且合法，差额证明实际冻结/扣款量纲。 */
  private static boolean validDebitSnapshot(
      BigDecimal balanceBefore, BigDecimal balanceAfter, BigDecimal amount) {
    return validBalance(balanceBefore)
        && validBalance(balanceAfter)
        && balanceBefore.subtract(balanceAfter).compareTo(amount) == 0;
  }

  /** 零余额合法，不比较当前账户余额；原快照不能缺失、负数、截断或超容量。 */
  private static boolean validBalance(BigDecimal balance) {
    return balance != null
        && balance.signum() >= 0
        && balance.stripTrailingZeros().scale() <= 2
        && balance.compareTo(MAX_MONEY) <= 0;
  }

  /** 不一致事实不能被输出为扣款成功或安全取消。 */
  private void require(boolean matches) {
    if (!matches) throw conflict();
  }

  /** 明确拒绝冲突，调用者保留原事务等待核验。 */
  private BizException conflict() {
    return new BizException("USER_TCC_STATE_CONFLICT", "TCC 持久事实不一致", 409);
  }
}
