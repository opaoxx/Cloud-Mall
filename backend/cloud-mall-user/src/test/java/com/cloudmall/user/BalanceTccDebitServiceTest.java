package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.BalanceTccDebitFinalizeDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitTryDTO;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.impl.BalanceTccDebitServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** TCC 入口参数和开关的无数据库单元测试。 */
class BalanceTccDebitServiceTest {
  /** 原用户行锁和余额写替身。 */
  private UserMapper userMapper;

  /** 原资金归属读取和状态写替身。 */
  private BalanceDebitAttemptMapper attemptMapper;

  /** 原扣款账本读写替身。 */
  private BalanceLedgerMapper ledgerMapper;

  /** 原冻结载荷和状态写替身。 */
  private BalanceTccDebitMapper tccMapper;

  /** 当前用例原冻结事实，可修改目标快照字段。 */
  private BalanceTccDebitPO originalFrozen;

  /** 当前用例已确认账本，保持原SQL归属基线。 */
  private BalanceLedgerPO originalLedger;

  /** 实际本地资金服务，不连接业务库。 */
  private BalanceTccDebitServiceImpl service;

  @BeforeEach
  void setUp() {
    userMapper = mock(UserMapper.class);
    attemptMapper = mock(BalanceDebitAttemptMapper.class);
    ledgerMapper = mock(BalanceLedgerMapper.class);
    tccMapper = mock(BalanceTccDebitMapper.class);
    service = new BalanceTccDebitServiceImpl(userMapper, attemptMapper, ledgerMapper, tccMapper);
  }

  @Test
  void disabledTryRejectsBeforeAnyMapperCall() {
    BizException failure = assertThrows(BizException.class, () -> service.tryDebit(request()));
    assertEquals("PAY_DEBIT_MODE_UNAVAILABLE", failure.getCode());
    verifyNoInteractions(userMapper, attemptMapper, ledgerMapper, tccMapper);
  }

  @Test
  void malformedAmountAndKeysAreRejectedBeforeFundsAccess() {
    BalanceTccDebitTryDTO invalidAmount = request();
    invalidAmount.amount = new BigDecimal("1.001");
    assertEquals(
        400,
        assertThrows(BizException.class, () -> service.tryDebit(invalidAmount)).getHttpStatus());
    BalanceTccDebitTryDTO invalidKey = request();
    invalidKey.ledgerKey = "different-ledger-key";
    assertEquals(
        400, assertThrows(BizException.class, () -> service.tryDebit(invalidKey)).getHttpStatus());
    verifyNoInteractions(userMapper, attemptMapper, ledgerMapper, tccMapper);
  }

  @Test
  void failedConditionalFreezeCannotBeReportedAsSuccessfulTry() {
    ReflectionTestUtils.setField(service, "enabled", true);
    UserPO user = new UserPO();
    user.id = 7L;
    user.status = 1;
    user.balance = new BigDecimal("100.00");
    user.frozenBalance = BigDecimal.ZERO;
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    when(attemptMapper.insert(any())).thenReturn(1);
    assertThrows(IllegalStateException.class, () -> service.tryDebit(request()));
    verify(tccMapper, never()).insert(any());
  }

  /** 缺归属金额不能在三个资金入口变为NPE或写余额。 */
  @Test
  void nullAttemptAmountIsConflictBeforeAnyFundsWrite() {
    for (String operation : List.of("try", "confirm", "cancel")) {
      installFunds("TRYING", "attempt");
      assertPersistedAmountConflict(operation, "IDEMPOTENCY_CONFLICT");
    }
  }

  /** 缺冻结金额沿原UNKNOWN，而非意外500。 */
  @Test
  void nullFrozenAmountIsUnknownBeforeAnyFundsWrite() {
    for (String operation : List.of("try", "confirm", "cancel")) {
      installFunds("TRYING", "debit");
      assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
    }
  }

  /** 已确认缺账本金额不能重放为成功，也不能变NPE。 */
  @Test
  void nullConfirmedLedgerAmountIsUnknownBeforeAnyFundsWrite() {
    for (String operation : List.of("try", "confirm")) {
      installFunds("CONFIRMED", "ledger");
      assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
    }
  }

  /** fresh Try缺失/非法可用额不应NPE或取得新的资金归属。 */
  @Test
  void freshTryRejectsMalformedAvailableBalance() {
    rejectWallet("try", "balance");
  }

  /** frozen NULL不能减可用额后仍返回TRYING。 */
  @Test
  void freshTryRejectsMalformedFrozenBalance() {
    rejectWallet("try", "frozen");
  }

  /** 实际清算/释放前校验两个金额，既有终态重放不在该门范围。 */
  @Test
  void actualClearAndReleaseRejectMalformedWallet() {
    for (String operation : List.of("confirm", "cancel"))
      for (String field : List.of("balance", "frozen")) rejectWallet(operation, field);
  }

  /** 已终态和空取消不写资金，当前坏账户不能让本批改掉其既有幂等协议。 */
  @Test
  void terminalReplaysAndEmptyCancellationKeepNoFundsWriteSemantics() {
    installFunds("CONFIRMED", "none");
    UserPO account = account(null, null);
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(account);
    assertEquals("CONFIRMED", service.tryDebit(request()).state);
    assertEquals("CONFIRMED", service.confirmDebit(completion()).state);
    installFunds("CANCELLED", "none");
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(account);
    assertEquals("CANCELLED", service.cancelDebit(completion()).state);
    when(attemptMapper.selectByPayNo(request().payNo)).thenReturn(null);
    when(tccMapper.selectByPayNo(request().payNo)).thenReturn(null);
    when(ledgerMapper.selectByOperationKey(request().ledgerKey)).thenReturn(null);
    when(attemptMapper.insert(any())).thenReturn(1);
    assertEquals("CANCELLED", service.cancelDebit(completion()).state);
    verify(userMapper, never()).freezeBalance(any(), any(), any());
    verify(userMapper, never()).clearFrozenBalance(any(), any(), any());
    verify(userMapper, never()).releaseFrozenBalance(any(), any(), any());
  }

  /** 只让原资金条件写成功，校验缺失前原实现会错误到达写操作。 */
  private void rejectWallet(String operation, String field) {
    for (BigDecimal invalid :
        new BigDecimal[] {
          null, new BigDecimal("-1"), new BigDecimal("100.001"), new BigDecimal("10000000000000000")
        }) {
      installFunds("TRYING", "none");
      if ("try".equals(operation)) {
        when(attemptMapper.selectByPayNo(request().payNo)).thenReturn(null);
        when(tccMapper.selectByPayNo(request().payNo)).thenReturn(null);
      }
      when(ledgerMapper.selectByOperationKey(request().ledgerKey)).thenReturn(null);
      UserPO user =
          account(
              "balance".equals(field) ? invalid : new BigDecimal("100.00"),
              "frozen".equals(field) ? invalid : new BigDecimal("10.00"));
      when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
      when(attemptMapper.insert(any())).thenReturn(1);
      when(userMapper.freezeBalance(any(), any(), any())).thenReturn(1);
      when(userMapper.clearFrozenBalance(any(), any(), any())).thenReturn(1);
      when(userMapper.releaseFrozenBalance(any(), any(), any())).thenReturn(1);
      when(tccMapper.insert(any())).thenReturn(1);
      when(ledgerMapper.insert(any())).thenReturn(1);
      when(tccMapper.confirmTrying(any(), any())).thenReturn(1);
      when(tccMapper.cancelTrying(any(), any())).thenReturn(1);
      when(attemptMapper.transitionState(any(), any(), any(), any())).thenReturn(1);
      BizException failure =
          assertThrows(
              BizException.class,
              () -> {
                switch (operation) {
                  case "try" -> service.tryDebit(request());
                  case "confirm" -> service.confirmDebit(completion());
                  case "cancel" -> service.cancelDebit(completion());
                  default -> throw new AssertionError(operation);
                }
              });
      assertEquals(409, failure.getHttpStatus());
      assertEquals("BALANCE_TCC_UNKNOWN", failure.getCode());
      verify(userMapper, never()).freezeBalance(any(), any(), any());
      verify(userMapper, never()).clearFrozenBalance(any(), any(), any());
      verify(userMapper, never()).releaseFrozenBalance(any(), any(), any());
      verify(ledgerMapper, never()).insert(any());
      verify(attemptMapper, never()).transitionState(any(), any(), any(), any());
      verify(tccMapper, never()).insert(any());
    }
  }

  /** 账户范围由本次目标字段控制，不把两个余额与旧快照作相等比较。 */
  private static UserPO account(BigDecimal balance, BigDecimal frozen) {
    UserPO user = new UserPO();
    user.id = 7L;
    user.status = 1;
    user.balance = balance;
    user.frozenBalance = frozen;
    return user;
  }

  /** 原合法分支回调的完整载荷。 */
  private BalanceTccDebitFinalizeDTO completion() {
    BalanceTccDebitTryDTO original = request();
    BalanceTccDebitFinalizeDTO value = new BalanceTccDebitFinalizeDTO();
    value.userId = original.userId;
    value.payNo = original.payNo;
    value.orderNo = original.orderNo;
    value.amount = original.amount;
    value.xid = original.xid;
    value.branchId = original.branchId;
    value.operationKey = original.operationKey;
    value.ledgerKey = original.ledgerKey;
    return value;
  }

  /** 错原冻结快照不可在replay/清算/释放时充当原资金证明。 */
  @Test
  void corruptedFrozenSnapshotCannotReachSuccessfulStateOrFundsWrite() {
    for (String operation : List.of("try", "confirm", "cancel")) {
      for (String mutation :
          List.of("delta", "beforeNull", "afterNull", "negative", "scale", "capacity")) {
        installFunds("TRYING", "none");
        when(ledgerMapper.selectByOperationKey(request().ledgerKey)).thenReturn(null);
        switch (mutation) {
          case "delta" -> originalFrozen.balanceAfterTry = new BigDecimal("99.00");
          case "beforeNull" -> originalFrozen.balanceBefore = null;
          case "afterNull" -> originalFrozen.balanceAfterTry = null;
          case "negative" -> originalFrozen.balanceAfterTry = new BigDecimal("-1");
          case "scale" -> originalFrozen.balanceBefore = new BigDecimal("100.001");
          case "capacity" -> originalFrozen.balanceBefore = new BigDecimal("10000000000000000");
          default -> throw new AssertionError(mutation);
        }
        assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
      }
    }
  }

  /** 正确amount不能代替原DEBIT快照的合法差额。 */
  @Test
  void corruptedConfirmedLedgerSnapshotCannotBeAcknowledged() {
    for (String operation : List.of("try", "confirm")) {
      for (String mutation :
          List.of("delta", "beforeNull", "afterNull", "negative", "scale", "capacity")) {
        installFunds("CONFIRMED", "none");
        switch (mutation) {
          case "delta" -> originalLedger.balanceAfter = new BigDecimal("99.00");
          case "beforeNull" -> originalLedger.balanceBefore = null;
          case "afterNull" -> originalLedger.balanceAfter = null;
          case "negative" -> originalLedger.balanceAfter = new BigDecimal("-1");
          case "scale" -> originalLedger.balanceBefore = new BigDecimal("100.001");
          case "capacity" -> originalLedger.balanceBefore = new BigDecimal("10000000000000000");
          default -> throw new AssertionError(mutation);
        }
        assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
      }
    }
  }

  /** 各自delta合法仍必须证明确认ledger复制原冻结pair。 */
  @Test
  void confirmedLedgerMustCopyOriginalFrozenSnapshot() {
    for (String operation : List.of("try", "confirm")) {
      installFunds("CONFIRMED", "none");
      originalLedger.balanceBefore = new BigDecimal("200.00");
      originalLedger.balanceAfter = new BigDecimal("190.00");
      assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
    }
  }

  /** CI读取原行不能以操作键别名充当当前原ledger。 */
  @Test
  void confirmedLedgerOperationKeyMustMatchExactly() {
    for (String operation : List.of("try", "confirm")) {
      installFunds("CONFIRMED", "none");
      originalLedger.operationKey = "PAY-UNIT-1";
      assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
    }
  }

  /** 单值合法但冻结加法溢出，不能先claim或错误TRYING。 */
  @Test
  void freshFreezeResultCannotExceedMoneyCapacity() {
    prepareTransition("try", new BigDecimal("100.00"), new BigDecimal("9999999999999999.99"));
    when(attemptMapper.insert(any())).thenReturn(1);
    when(userMapper.freezeBalance(any(), any(), any())).thenReturn(1);
    when(tccMapper.insert(any())).thenReturn(1);
    assertPersistedAmountConflict("try", "BALANCE_TCC_UNKNOWN");
    when(userMapper.selectByIdForUpdate(7L))
        .thenReturn(account(new BigDecimal("5.00"), new BigDecimal("9999999999999999.99")));
    BizException insufficient = assertThrows(BizException.class, () -> service.tryDebit(request()));
    assertEquals("PAYMENT_FAILED", insufficient.getCode());
    verify(attemptMapper, never()).insert(any());
  }

  /** 冻结不足不能落到UPDATE0的运行时500。 */
  @Test
  void firstConfirmationRequiresEnoughCurrentFrozenFunds() {
    prepareTransition("confirm", new BigDecimal("90.00"), new BigDecimal("9.99"));
    assertPersistedAmountConflict("confirm", "BALANCE_TCC_UNKNOWN");
  }

  /** 取消释放前必须仍有原金额可释放，未知事实不得猜返还。 */
  @Test
  void releaseRequiresEnoughCurrentFrozenFunds() {
    prepareTransition("cancel", new BigDecimal("90.00"), new BigDecimal("9.99"));
    assertPersistedAmountConflict("cancel", "BALANCE_TCC_UNKNOWN");
  }

  /** 返还容量溢出不执行余额写或错误CANCELLED。 */
  @Test
  void releaseResultCannotExceedMoneyCapacity() {
    prepareTransition("cancel", new BigDecimal("9999999999999999.99"), new BigDecimal("10.00"));
    when(userMapper.releaseFrozenBalance(any(), any(), any())).thenReturn(1);
    when(tccMapper.cancelTrying(any(), any())).thenReturn(1);
    when(attemptMapper.transitionState(any(), any(), any(), any())).thenReturn(1);
    assertPersistedAmountConflict("cancel", "BALANCE_TCC_UNKNOWN");
  }

  /** 合法原冻结10和当前钱包，fresh场景原归属/冻结不存在。 */
  private void prepareTransition(String operation, BigDecimal balance, BigDecimal frozen) {
    installFunds("TRYING", "none");
    if ("try".equals(operation)) {
      when(attemptMapper.selectByPayNo(request().payNo)).thenReturn(null);
      when(tccMapper.selectByPayNo(request().payNo)).thenReturn(null);
    }
    when(ledgerMapper.selectByOperationKey(request().ledgerKey)).thenReturn(null);
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(account(balance, frozen));
  }

  /** 完整原元组仅将目标金额置NULL，不使用资金写替身掩盖错误。 */
  private void installFunds(String state, String missing) {
    ReflectionTestUtils.setField(service, "enabled", true);
    BalanceTccDebitTryDTO original = request();
    UserPO user = new UserPO();
    user.id = 7L;
    user.status = 1;
    user.balance = new BigDecimal("100.00");
    user.frozenBalance = new BigDecimal("TRYING".equals(state) ? "10.00" : "0.00");
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    BalanceDebitAttemptPO owner = new BalanceDebitAttemptPO();
    owner.mode = "TCC";
    owner.userId = original.userId;
    owner.payNo = original.payNo;
    owner.orderNo = original.orderNo;
    owner.xid = original.xid;
    owner.branchId = original.branchId;
    owner.operationKey = original.operationKey;
    owner.ledgerKey = original.ledgerKey;
    owner.amount = "attempt".equals(missing) ? null : original.amount;
    owner.state = state;
    when(attemptMapper.selectByPayNo(original.payNo)).thenReturn(owner);
    when(attemptMapper.selectByPayNoForUpdate(original.payNo)).thenReturn(owner);
    BalanceTccDebitPO frozen = new BalanceTccDebitPO();
    frozen.userId = original.userId;
    frozen.payNo = original.payNo;
    frozen.orderNo = original.orderNo;
    frozen.xid = original.xid;
    frozen.branchId = original.branchId;
    frozen.operationKey = original.operationKey;
    frozen.amount = "debit".equals(missing) ? null : original.amount;
    frozen.state = state;
    frozen.balanceBefore = new BigDecimal("100.00");
    frozen.balanceAfterTry = new BigDecimal("90.00");
    originalFrozen = frozen;
    when(tccMapper.selectByPayNo(original.payNo)).thenReturn(frozen);
    BalanceLedgerPO applied = new BalanceLedgerPO();
    applied.userId = original.userId;
    applied.payNo = original.payNo;
    applied.orderNo = original.orderNo;
    applied.operationKey = original.ledgerKey;
    applied.operationType = "DEBIT";
    applied.state = "APPLIED";
    applied.amount = "ledger".equals(missing) ? null : original.amount;
    applied.balanceBefore = new BigDecimal("100.00");
    applied.balanceAfter = new BigDecimal("90.00");
    originalLedger = applied;
    when(ledgerMapper.selectByOperationKey(original.ledgerKey)).thenReturn(applied);
  }

  /** 固定409及原code，所有金额/状态写Mapper都不得调用。 */
  private void assertPersistedAmountConflict(String operation, String code) {
    BalanceTccDebitTryDTO original = request();
    BalanceTccDebitFinalizeDTO completion = new BalanceTccDebitFinalizeDTO();
    completion.userId = original.userId;
    completion.payNo = original.payNo;
    completion.orderNo = original.orderNo;
    completion.amount = original.amount;
    completion.xid = original.xid;
    completion.branchId = original.branchId;
    completion.operationKey = original.operationKey;
    completion.ledgerKey = original.ledgerKey;
    BizException failure =
        assertThrows(
            BizException.class,
            () -> {
              switch (operation) {
                case "try" -> service.tryDebit(original);
                case "confirm" -> service.confirmDebit(completion);
                case "cancel" -> service.cancelDebit(completion);
                default -> throw new AssertionError(operation);
              }
            });
    assertEquals(409, failure.getHttpStatus());
    assertEquals(code, failure.getCode());
    verify(userMapper, never()).freezeBalance(any(), any(), any());
    verify(userMapper, never()).clearFrozenBalance(any(), any(), any());
    verify(userMapper, never()).releaseFrozenBalance(any(), any(), any());
    verify(ledgerMapper, never()).insert(any());
    verify(attemptMapper, never()).transitionState(any(), any(), any(), any());
    verify(tccMapper, never()).insert(any());
    verify(tccMapper, never()).confirmTrying(any(), any());
    verify(tccMapper, never()).cancelTrying(any(), any());
  }

  private BalanceTccDebitTryDTO request() {
    BalanceTccDebitTryDTO request = new BalanceTccDebitTryDTO();
    request.userId = 7L;
    request.payNo = "pay-unit-1";
    request.orderNo = "order-unit-1";
    request.operationKey = "tcc-debit:pay-unit-1";
    request.ledgerKey = "pay-unit-1";
    request.xid = "xid-unit-1";
    request.branchId = 1L;
    request.amount = new BigDecimal("10.00");
    return request;
  }
}
