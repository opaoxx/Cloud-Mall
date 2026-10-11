package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.TccFenceLogPO;
import com.cloudmall.user.domain.po.UserTccReceivePO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.UserTccStateQuery;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 原资金快照差额与确认账本来源核验，不比较当前余额。 */
class UserTccStateBalanceSnapshotTest {
  /** 原接收事实替身。 */
  private UserTccReceiveMapper receives;

  /** 原资金归属替身。 */
  private BalanceDebitAttemptMapper attempts;

  /** 原冻结事实替身。 */
  private BalanceTccDebitMapper debits;

  /** 原扣款账本替身。 */
  private BalanceLedgerMapper ledgers;

  /** 原分支Fence替身。 */
  private TccFenceLogMapper fences;

  /** 实际只读原查询器。 */
  private UserTccStateQuery query;

  /** 当前用例原冻结行。 */
  private BalanceTccDebitPO frozenRow;

  /** 当前用例确认扣款账本。 */
  private BalanceLedgerPO debitLedger;

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

  /** 冻结前余额缺失、负数、非法精度或容量不能充当原快照。 */
  @Test
  void frozenBeforeMustBeNonnegativeLosslessMoney() {
    rejectBalance("frozenBefore");
  }

  /** Try后余额必须存在并具有合法量纲。 */
  @Test
  void frozenAfterMustBeNonnegativeLosslessMoney() {
    rejectBalance("frozenAfter");
  }

  /** 原扣款前余额不能缺失或非法。 */
  @Test
  void ledgerBeforeMustBeNonnegativeLosslessMoney() {
    rejectBalance("ledgerBefore");
  }

  /** 原扣款后余额不能缺失或非法。 */
  @Test
  void ledgerAfterMustBeNonnegativeLosslessMoney() {
    rejectBalance("ledgerAfter");
  }

  /** 原冻结三个已知状态及确认账本均须有真实amount差额。 */
  @Test
  void wrongFrozenOrDebitDeltaCannotProveFunds() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
      baseline(state);
      frozenRow.balanceAfterTry = new BigDecimal("91.00");
      assertConflict();
    }
    baseline("CONFIRMED");
    debitLedger.balanceAfter = new BigDecimal("91.00");
    assertConflict();
  }

  /** 两组各自差额正确也不能证明DEBIT源于该次冻结。 */
  @Test
  void confirmedLedgerMustUseOriginalFrozenSnapshot() {
    baseline("CONFIRMED");
    debitLedger.balanceBefore = new BigDecimal("200.00");
    debitLedger.balanceAfter = new BigDecimal("190.00");
    assertConflict();
  }

  /** 合法零余额、无损尾零、容量边界以及终态Fence过期保留。 */
  @Test
  void validBalancesKeepKnownStatesAndTerminalFenceExpiry() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
      for (BigDecimal before :
          List.of(new BigDecimal("10.000"), new BigDecimal("9999999999999999.99"))) {
        baseline(state);
        frozenRow.balanceBefore = before;
        frozenRow.balanceAfterTry = before.subtract(new BigDecimal("10.00"));
        debitLedger.balanceBefore = before.stripTrailingZeros();
        debitLedger.balanceAfter = frozenRow.balanceAfterTry.stripTrailingZeros();
        assertEquals(state, query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
        if (!"TRYING".equals(state)) {
          when(fences.findByBranch("xid-query-1", 12L)).thenReturn(null);
          assertEquals(state, query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
        }
      }
    }
  }

  /** 空取消只含原归属，不补造不存在的冻结快照。 */
  @Test
  void emptyCancellationStillNeedsNoFrozenBalancePair() {
    baseline("CANCELLED");
    when(debits.selectByPayNo("pay-query-1")).thenReturn(null);
    when(fences.findByBranch("xid-query-1", 12L)).thenReturn(fence(4));
    assertEquals("CANCELLED", query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
  }

  /** 原完整事实只修改目标快照值。 */
  private void baseline(String state) {
    BigDecimal amount = new BigDecimal("10.00");
    install(state, amount, amount, amount, amount);
  }

  /** 单字段遗留值不应被amount相等门遗漏。 */
  private void rejectBalance(String field) {
    for (BigDecimal value :
        new BigDecimal[] {
          null, new BigDecimal("-1"), new BigDecimal("100.001"), new BigDecimal("10000000000000000")
        }) {
      baseline("CONFIRMED");
      switch (field) {
        case "frozenBefore" -> frozenRow.balanceBefore = value;
        case "frozenAfter" -> frozenRow.balanceAfterTry = value;
        case "ledgerBefore" -> debitLedger.balanceBefore = value;
        case "ledgerAfter" -> debitLedger.balanceAfter = value;
        default -> throw new AssertionError(field);
      }
      assertConflict();
    }
  }

  /** 原409冲突不允许输出资金终态。 */
  private void assertConflict() {
    BizException failure =
        assertThrows(
            BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
    assertEquals(409, failure.getHttpStatus());
    assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
  }

  /** 完整原元组及状态，只有金额由用例控制，不屏蔽旧身份/Fence检查。 */
  private void install(
      String state,
      BigDecimal receiveAmount,
      BigDecimal attemptAmount,
      BigDecimal debitAmount,
      BigDecimal ledgerAmount) {
    UserTccReceivePO receive = receive(12L);
    receive.amount = receiveAmount;
    BalanceDebitAttemptPO attempt = attempt(state, 12L);
    attempt.amount = attemptAmount;
    BalanceTccDebitPO debit = debit(state, 12L);
    debit.amount = debitAmount;
    debit.balanceBefore = new BigDecimal("100.00");
    debit.balanceAfterTry = new BigDecimal("90.00");
    frozenRow = debit;
    BalanceLedgerPO ledger = ledger();
    ledger.amount = ledgerAmount;
    ledger.balanceBefore = new BigDecimal("100.00");
    ledger.balanceAfter = new BigDecimal("90.00");
    debitLedger = ledger;
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(attempts.selectByPayNo(receive.payNo))
        .thenReturn("UNKNOWN".equals(state) ? null : attempt);
    when(debits.selectByPayNo(receive.payNo)).thenReturn("UNKNOWN".equals(state) ? null : debit);
    when(ledgers.selectByOperationKey(receive.ledgerKey))
        .thenReturn("CONFIRMED".equals(state) ? ledger : null);
    when(fences.findByBranch(receive.xid, 12L))
        .thenReturn(fence("TRYING".equals(state) ? 1 : "CONFIRMED".equals(state) ? 2 : 3));
  }

  /** 原接收载荷合法基线。 */
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

  /** 原TCC归属合法基线。 */
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

  /** 原冻结载荷合法基线。 */
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

  /** 原扣款账本合法基线。 */
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

  /** 原官方Fence状态基线。 */
  private TccFenceLogPO fence(int status) {
    TccFenceLogPO fence = new TccFenceLogPO();
    fence.xid = "xid-query-1";
    fence.branchId = 12L;
    fence.actionName = BalanceTccDebitLocalResource.ACTION_NAME;
    fence.status = status;
    return fence;
  }
}
