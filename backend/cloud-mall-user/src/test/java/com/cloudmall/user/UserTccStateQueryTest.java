package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.cloudmall.user.service.impl.UserTccStateQuery;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** User TCC 只读状态查询的事实一致性和终态语义测试。 */
class UserTccStateQueryTest {
  private UserTccReceiveMapper receives;
  private BalanceDebitAttemptMapper attempts;
  private BalanceTccDebitMapper debits;
  private BalanceLedgerMapper ledgers;
  private TccFenceLogMapper fences;
  private UserTccStateQuery query;

  /** 准备只读查询依赖。 */
  @BeforeEach
  void setUp() {
    receives = mock(UserTccReceiveMapper.class);
    attempts = mock(BalanceDebitAttemptMapper.class);
    debits = mock(BalanceTccDebitMapper.class);
    ledgers = mock(BalanceLedgerMapper.class);
    fences = mock(TccFenceLogMapper.class);
    query = new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
  }

  /** 只有接收 CLAIMED 事实时返回 UNKNOWN，不能伪造 Try 成功。 */
  @Test
  void claimedWithoutFundsReturnsUnknown() {
    UserTccReceivePO receive = receive(null);
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(attempts.selectByPayNo(receive.payNo)).thenReturn(null);
    when(debits.selectByPayNo(receive.payNo)).thenReturn(null);
    when(ledgers.selectByOperationKey(receive.ledgerKey)).thenReturn(null);

    UserTccStateVO state = query.read(7L, receive.operationKey, receive.xid);

    assertEquals("UNKNOWN", state.state);
    assertEquals(7L, state.userId);
  }

  /** TRYING 必须有冻结事实、无 APPLIED 账本和正确 Fence 状态。 */
  @Test
  void tryingMeansFrozenButNotFinalDebit() {
    UserTccReceivePO receive = receive(11L);
    BalanceDebitAttemptPO attempt = attempt("TRYING", 11L);
    BalanceTccDebitPO debit = debit("TRYING", 11L);
    TccFenceLogPO fence = fence(1);
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(attempts.selectByPayNo(receive.payNo)).thenReturn(attempt);
    when(debits.selectByPayNo(receive.payNo)).thenReturn(debit);
    when(ledgers.selectByOperationKey(receive.ledgerKey)).thenReturn(null);
    when(fences.findByBranch(receive.xid, 11L)).thenReturn(fence);

    UserTccStateVO state = query.read(7L, receive.operationKey, receive.xid);

    assertEquals("TRYING", state.state);
    assertEquals(11L, state.branchId);
    org.junit.jupiter.api.Assertions.assertFalse(state.ledgerApplied);
  }

  /** CONFIRMED 必须同时拥有匹配 APPLIED DEBIT 账本和 Fence 提交状态。 */
  @Test
  void confirmedRequiresAppliedLedger() {
    UserTccReceivePO receive = receive(12L);
    BalanceDebitAttemptPO attempt = attempt("CONFIRMED", 12L);
    BalanceTccDebitPO debit = debit("CONFIRMED", 12L);
    BalanceLedgerPO ledger = ledger();
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(attempts.selectByPayNo(receive.payNo)).thenReturn(attempt);
    when(debits.selectByPayNo(receive.payNo)).thenReturn(debit);
    when(ledgers.selectByOperationKey(receive.ledgerKey)).thenReturn(ledger);
    when(fences.findByBranch(receive.xid, 12L)).thenReturn(fence(2));

    UserTccStateVO state = query.read(7L, receive.operationKey, receive.xid);

    assertEquals("CONFIRMED", state.state);
    org.junit.jupiter.api.Assertions.assertTrue(state.ledgerApplied);
  }

  /** CANCELLED tombstone 可以没有 TCC 行，但必须有取消归属和 Fence 事实。 */
  @Test
  void cancelledTombstoneIsAStableTerminalState() {
    UserTccReceivePO receive = receive(13L);
    BalanceDebitAttemptPO attempt = attempt("CANCELLED", 13L);
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(attempts.selectByPayNo(receive.payNo)).thenReturn(attempt);
    when(debits.selectByPayNo(receive.payNo)).thenReturn(null);
    when(ledgers.selectByOperationKey(receive.ledgerKey)).thenReturn(null);
    when(fences.findByBranch(receive.xid, 13L)).thenReturn(fence(4));

    UserTccStateVO state = query.read(7L, receive.operationKey, receive.xid);

    assertEquals("CANCELLED", state.state);
    org.junit.jupiter.api.Assertions.assertFalse(state.ledgerApplied);
  }

  /** 分支或账本载荷不一致时必须返回冲突，不能输出资金状态。 */
  @Test
  void mismatchedBranchAndLedgerAreRejected() {
    UserTccReceivePO receive = receive(14L);
    BalanceDebitAttemptPO attempt = attempt("TRYING", 15L);
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(attempts.selectByPayNo(receive.payNo)).thenReturn(attempt);
    when(debits.selectByPayNo(receive.payNo)).thenReturn(debit("TRYING", 15L));
    when(ledgers.selectByOperationKey(receive.ledgerKey)).thenReturn(null);

    assertThrows(BizException.class, () -> query.read(7L, receive.operationKey, receive.xid));
  }

  /** CONFIRMED 的账本键不一致时不能报告已扣款。 */
  @Test
  void confirmedLedgerKeyMismatchIsRejected() {
    UserTccReceivePO receive = receive(16L);
    BalanceDebitAttemptPO attempt = attempt("CONFIRMED", 16L);
    BalanceTccDebitPO debit = debit("CONFIRMED", 16L);
    BalanceLedgerPO ledger = ledger();
    ledger.operationKey = "other-ledger-key";
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(attempts.selectByPayNo(receive.payNo)).thenReturn(attempt);
    when(debits.selectByPayNo(receive.payNo)).thenReturn(debit);
    when(ledgers.selectByOperationKey(receive.ledgerKey)).thenReturn(ledger);

    assertThrows(BizException.class, () -> query.read(7L, receive.operationKey, receive.xid));
  }

  private UserTccReceivePO receive(Long branchId) {
    UserTccReceivePO receive = new UserTccReceivePO();
    receive.userId = 7L;
    receive.payNo = "pay-query-1";
    receive.orderNo = "order-query-1";
    receive.amount = new BigDecimal("10.00");
    receive.xid = "xid-query-1";
    receive.branchId = branchId;
    receive.operationKey = "tcc-debit:pay-query-1";
    receive.ledgerKey = "pay-query-1";
    receive.state = "CLAIMED";
    return receive;
  }

  private BalanceDebitAttemptPO attempt(String state, Long branchId) {
    BalanceDebitAttemptPO attempt = new BalanceDebitAttemptPO();
    attempt.mode = "TCC";
    attempt.userId = 7L;
    attempt.payNo = "pay-query-1";
    attempt.orderNo = "order-query-1";
    attempt.amount = new BigDecimal("10.00");
    attempt.xid = "xid-query-1";
    attempt.branchId = branchId;
    attempt.operationKey = "tcc-debit:pay-query-1";
    attempt.ledgerKey = "pay-query-1";
    attempt.state = state;
    return attempt;
  }

  private BalanceTccDebitPO debit(String state, Long branchId) {
    BalanceTccDebitPO debit = new BalanceTccDebitPO();
    debit.userId = 7L;
    debit.payNo = "pay-query-1";
    debit.orderNo = "order-query-1";
    debit.amount = new BigDecimal("10.00");
    debit.xid = "xid-query-1";
    debit.branchId = branchId;
    debit.operationKey = "tcc-debit:pay-query-1";
    debit.state = state;
    return debit;
  }

  private BalanceLedgerPO ledger() {
    BalanceLedgerPO ledger = new BalanceLedgerPO();
    ledger.userId = 7L;
    ledger.payNo = "pay-query-1";
    ledger.orderNo = "order-query-1";
    ledger.amount = new BigDecimal("10.00");
    ledger.operationKey = "pay-query-1";
    ledger.operationType = "DEBIT";
    ledger.state = "APPLIED";
    return ledger;
  }

  private TccFenceLogPO fence(int status) {
    TccFenceLogPO fence = new TccFenceLogPO();
    fence.actionName = BalanceTccDebitLocalResource.ACTION_NAME;
    fence.status = status;
    return fence;
  }
}
