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
import java.util.Objects;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 同一只读快照核对接收、资金和 Fence；缺少事实永远不等于未执行。 */
public class UserTccStateQuery {
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
        "TCC".equals(attempt.mode)
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
          Objects.equals(debit.userId, userId)
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
      expectedFence = 1;
    } else if ("CONFIRMED".equals(attempt.state)) {
      require(
          debit != null
              && ledger != null
              && "DEBIT".equals(ledger.operationType)
              && "APPLIED".equals(ledger.state)
              && Objects.equals(ledger.userId, userId)
              && Objects.equals(ledger.payNo, receive.payNo)
              && Objects.equals(ledger.orderNo, receive.orderNo)
              && Objects.equals(ledger.operationKey, receive.ledgerKey)
              && ledger.amount.compareTo(receive.amount) == 0);
      view.ledgerApplied = true;
      expectedFence = 2;
    } else if ("CANCELLED".equals(attempt.state)) {
      require(ledger == null);
      expectedFence = debit == null ? 4 : 3;
    } else throw conflict();
    TccFenceLogPO fence = fences.findByBranch(xid, attempt.branchId);
    if (fence != null)
      require(
          BalanceTccDebitLocalResource.ACTION_NAME.equals(fence.actionName)
              && expectedFence.equals(fence.status));
    else require(!"TRYING".equals(attempt.state));
    view.state = attempt.state;
    return view;
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
