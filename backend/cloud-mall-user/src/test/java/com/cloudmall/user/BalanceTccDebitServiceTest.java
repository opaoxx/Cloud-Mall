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

  /** 空取消迟到Try不能以CANCELLED掩盖原账本占用。 */
  @Test
  void emptyCancelledTryRejectsConflictingLedger() {
    assertCancelledLedgerConflict("try", true);
  }

  /** 空取消重复Cancel仍须核验原账本矛盾。 */
  @Test
  void emptyCancelledCancelRejectsConflictingLedger() {
    assertCancelledLedgerConflict("cancel", true);
  }

  /** 已释放Try重放不能将原DEBIT误报成已取消。 */
  @Test
  void releasedCancelledTryRejectsConflictingLedger() {
    assertCancelledLedgerConflict("try", false);
  }

  /** 已释放Cancel重放不能跳过固定ledgerKey占用。 */
  @Test
  void releasedCancelledCancelRejectsConflictingLedger() {
    assertCancelledLedgerConflict("cancel", false);
  }

  /** 两种原查询来源分别命中；无任何新归属、资金或状态写。 */
  private void assertCancelledLedgerConflict(String operation, boolean emptyCancellation) {
    for (String source : List.of("payDebit", "ledgerKey")) {
      setUp();
      installFunds("CANCELLED", "none");
      if (emptyCancellation) when(tccMapper.selectByPayNo(request().payNo)).thenReturn(null);
      if ("payDebit".equals(source))
        when(ledgerMapper.findDebitCandidatesByPayNo(request().payNo))
            .thenReturn(List.of(originalLedger));
      else when(ledgerMapper.selectByOperationKey(request().ledgerKey)).thenReturn(originalLedger);
      assertPersistedAmountConflict(operation, "IDEMPOTENCY_CONFLICT");
      verify(attemptMapper, never()).insert(any());
    }
  }

  /** TRYING重放也不能跳过同支付DEBIT或固定账本键占用。 */
  @Test
  void tryingReplayRejectsConflictingLedger() {
    for (String source : List.of("payDebit", "ledgerKey")) {
      setUp();
      installFunds("TRYING", "none");
      if ("payDebit".equals(source))
        when(ledgerMapper.findDebitCandidatesByPayNo(request().payNo))
            .thenReturn(List.of(originalLedger));
      else when(ledgerMapper.selectByOperationKey(request().ledgerKey)).thenReturn(originalLedger);
      assertPersistedAmountConflict("try", "IDEMPOTENCY_CONFLICT");
      verify(attemptMapper, never()).insert(any());
    }
  }

  /** 两份原state均NULL时三入口不能在不可变集合contains中抛NPE。 */
  @Test
  void missingPersistedStateIsUnknownAtEveryFundsEntry() {
    for (String operation : List.of("try", "confirm", "cancel")) {
      setUp();
      installFunds(null, "none");
      assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
      verify(attemptMapper, never()).insert(any());
    }
  }

  /** 合法TRYING无账本重放不新增当前钱包门、归属或资金写。 */
  @Test
  void legitimateTryingReplayKeepsNoOpWalletSemantics() {
    installFunds("TRYING", "none");
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(account(null, null));
    assertEquals("TRYING", service.tryDebit(request()).state);
    verify(userMapper, never()).freezeBalance(any(), any(), any());
    verify(userMapper, never()).clearFrozenBalance(any(), any(), any());
    verify(userMapper, never()).releaseFrozenBalance(any(), any(), any());
    verify(attemptMapper, never()).insert(any());
    verify(attemptMapper, never()).transitionState(any(), any(), any(), any());
    verify(tccMapper, never()).insert(any());
    verify(ledgerMapper, never()).insert(any());
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

  /** 固定原键合法但scope缺失/重复/字段损坏，两个已确认重放入口均UNKNOWN且不写资金。 */
  @Test
  void confirmedReplaysRequireExactlyOneMatchingDebitCandidate() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String operation : List.of("try", "confirm")) {
      for (String variant :
          List.of(
              "missing",
              "multiple",
              "null-row",
              "id-missing",
              "id-other",
              "user-other",
              "pay-alias",
              "order-other",
              "operation-alias",
              "type-alias",
              "state-rejected",
              "origin",
              "amount-null",
              "amount-other",
              "before-null",
              "before-other",
              "after-null",
              "after-other")) {
        assertions.add(
            () -> {
              setUp();
              installFunds("CONFIRMED", "none");
              BalanceLedgerPO candidate = copyConfirmedLedger();
              switch (variant) {
                case "id-missing" -> candidate.id = null;
                case "id-other" -> candidate.id = 6L;
                case "user-other" -> candidate.userId = 8L;
                case "pay-alias" ->
                    candidate.payNo = candidate.payNo.toUpperCase(java.util.Locale.ROOT);
                case "order-other" -> candidate.orderNo = "other-order";
                case "operation-alias" ->
                    candidate.operationKey =
                        candidate.operationKey.toUpperCase(java.util.Locale.ROOT);
                case "type-alias" -> candidate.operationType = "debit";
                case "state-rejected" -> candidate.state = "REJECTED";
                case "origin" -> candidate.originalOperationKey = "other";
                case "amount-null" -> candidate.amount = null;
                case "amount-other" -> candidate.amount = new BigDecimal("11.00");
                case "before-null" -> candidate.balanceBefore = null;
                case "before-other" -> candidate.balanceBefore = new BigDecimal("101.00");
                case "after-null" -> candidate.balanceAfter = null;
                case "after-other" -> candidate.balanceAfter = new BigDecimal("89.00");
                default -> {}
              }
              List<BalanceLedgerPO> candidates =
                  switch (variant) {
                    case "missing" -> List.of();
                    case "multiple" -> List.of(originalLedger, candidate);
                    case "null-row" -> java.util.Collections.singletonList(null);
                    default -> List.of(candidate);
                  };
              when(ledgerMapper.findDebitCandidatesByPayNo(request().payNo)).thenReturn(candidates);
              assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
              verify(ledgerMapper).findDebitCandidatesByPayNo(request().payNo);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** DAO故障原对象传播；原固定键已损坏时不会为了scope再读取或覆盖原错误优先级。 */
  @Test
  void debitScopeQueryFaultPropagatesOnlyAfterFixedLedgerProof() {
    for (String operation : List.of("try", "confirm")) {
      setUp();
      installFunds("CONFIRMED", "none");
      var failure =
          new org.springframework.dao.DataAccessResourceFailureException("owned-scope-fault");
      org.mockito.Mockito.doThrow(failure)
          .when(ledgerMapper)
          .findDebitCandidatesByPayNo(request().payNo);
      org.junit.jupiter.api.Assertions.assertSame(
          failure,
          assertThrows(
              org.springframework.dao.DataAccessResourceFailureException.class,
              () -> {
                if ("try".equals(operation)) service.tryDebit(request());
                else service.confirmDebit(completion());
              }));
      verify(ledgerMapper, never()).insert(any(BalanceLedgerPO.class));
      verify(userMapper, never()).clearFrozenBalance(any(), any(), any());
      setUp();
      installFunds("CONFIRMED", "none");
      originalLedger.originalOperationKey = "bad-origin";
      org.mockito.Mockito.doThrow(failure)
          .when(ledgerMapper)
          .findDebitCandidatesByPayNo(request().payNo);
      assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
      verify(ledgerMapper, never()).findDebitCandidatesByPayNo(any());
    }
  }

  /** 唯一候选的数值尾零与原账本相等；钱包后来变化/禁用仍保持已有confirm协议。 */
  @Test
  void matchingDebitScopeKeepsNumericSnapshotAndDisabledReplayCompatibility() {
    installFunds("CONFIRMED", "none");
    BalanceLedgerPO candidate = copyConfirmedLedger();
    candidate.amount = new BigDecimal("10.000");
    candidate.balanceBefore = new BigDecimal("100.000");
    candidate.balanceAfter = new BigDecimal("90.000");
    when(ledgerMapper.findDebitCandidatesByPayNo(request().payNo)).thenReturn(List.of(candidate));
    assertEquals("CONFIRMED", service.tryDebit(request()).state);
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(account(null, null));
    ReflectionTestUtils.setField(service, "enabled", false);
    assertEquals("CONFIRMED", service.confirmDebit(completion()).state);
    verify(userMapper, never()).freezeBalance(any(), any(), any());
    verify(userMapper, never()).clearFrozenBalance(any(), any(), any());
    verify(ledgerMapper, never()).insert(any(BalanceLedgerPO.class));
  }

  /** scope替身与固定原账本独立，以证明只破坏candidate字段而非前置固定proof。 */
  private BalanceLedgerPO copyConfirmedLedger() {
    BalanceLedgerPO candidate = new BalanceLedgerPO();
    candidate.id = originalLedger.id;
    candidate.userId = originalLedger.userId;
    candidate.payNo = originalLedger.payNo;
    candidate.orderNo = originalLedger.orderNo;
    candidate.operationKey = originalLedger.operationKey;
    candidate.operationType = originalLedger.operationType;
    candidate.state = originalLedger.state;
    candidate.originalOperationKey = originalLedger.originalOperationKey;
    candidate.amount = originalLedger.amount;
    candidate.balanceBefore = originalLedger.balanceBefore;
    candidate.balanceAfter = originalLedger.balanceAfter;
    return candidate;
  }

  /** 无账本入口仅接受可靠空DEBIT范围，所有候选在固定key/全type查询前拒绝。 */
  @Test
  void noFundsEntrancesRejectBoundedDebitCandidatesBeforeOtherLedgerReads() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String state : List.of("none", "TRYING", "CANCELLED", "empty-cancel")) {
      for (String operation :
          "TRYING".equals(state) ? List.of("try", "confirm", "cancel") : List.of("try", "cancel")) {
        for (String variant : List.of("single", "two", "three", "null-row", "null-list")) {
          assertions.add(
              () -> {
                prepareNoFundsScope(state);
                List<BalanceLedgerPO> candidates =
                    switch (variant) {
                      case "single" -> List.of(originalLedger);
                      case "two" -> List.of(originalLedger, new BalanceLedgerPO());
                      case "three" ->
                          List.of(originalLedger, new BalanceLedgerPO(), new BalanceLedgerPO());
                      case "null-row" -> java.util.Collections.singletonList(null);
                      case "null-list" -> null;
                      default -> throw new AssertionError(variant);
                    };
                when(ledgerMapper.findDebitCandidatesByPayNo(request().payNo))
                    .thenReturn(candidates);
                assertPersistedAmountConflict(
                    operation,
                    "null-list".equals(variant) ? "BALANCE_TCC_UNKNOWN" : "IDEMPOTENCY_CONFLICT");
                verify(ledgerMapper).findDebitCandidatesByPayNo(request().payNo);
                verify(ledgerMapper, never()).selectDebitByPayNo(any());
                verify(ledgerMapper, never()).selectByOperationKey(any());
                verify(ledgerMapper, never()).findPaymentLedgerCandidates(any());
                verify(attemptMapper, never()).insert(any());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 有界DAO故障保持原异常对象和无写语义，不降为业务冲突或空集合。 */
  @Test
  void noFundsBoundedDebitFaultPropagatesWithoutWriting() {
    for (String operation : List.of("try", "confirm", "cancel")) {
      prepareNoFundsScope("TRYING");
      var failure =
          new org.springframework.dao.DataAccessResourceFailureException(
              "owned-bounded-debit-fault");
      org.mockito.Mockito.doThrow(failure)
          .when(ledgerMapper)
          .findDebitCandidatesByPayNo(request().payNo);
      org.junit.jupiter.api.Assertions.assertSame(
          failure,
          assertThrows(
              org.springframework.dao.DataAccessResourceFailureException.class,
              () -> invokeNoFundsEntry(operation)));
      verify(ledgerMapper, never()).selectDebitByPayNo(any());
      verify(ledgerMapper, never()).selectByOperationKey(any());
      verify(ledgerMapper, never()).findPaymentLedgerCandidates(any());
      verify(userMapper, never()).freezeBalance(any(), any(), any());
      verify(userMapper, never()).clearFrozenBalance(any(), any(), any());
      verify(userMapper, never()).releaseFrozenBalance(any(), any(), any());
      verify(ledgerMapper, never()).insert(any());
      verify(attemptMapper, never()).insert(any());
      verify(attemptMapper, never()).transitionState(any(), any(), any(), any());
      verify(tccMapper, never()).insert(any());
      verify(tccMapper, never()).confirmTrying(any(), any());
      verify(tccMapper, never()).cancelTrying(any(), any());
    }
  }

  /** 无账本的每条状态入口不能忽略原pay其它类型/state/user行或非空null行。 */
  @Test
  void noFundsEntrancesRequireAnActuallyEmptyPaymentLedgerScope() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String state : List.of("none", "TRYING", "CANCELLED", "empty-cancel")) {
      for (String operation :
          "TRYING".equals(state) ? List.of("try", "confirm", "cancel") : List.of("try", "cancel")) {
        for (String variant :
            List.of("refund", "unknown-type", "unknown-state", "other-user", "null-row")) {
          assertions.add(
              () -> {
                prepareNoFundsScope(state);
                BalanceLedgerPO candidate = new BalanceLedgerPO();
                candidate.payNo = request().payNo;
                candidate.userId = "other-user".equals(variant) ? 8L : 7L;
                candidate.operationKey = "orphan-key";
                candidate.operationType = "unknown-type".equals(variant) ? "UNKNOWN" : "REFUND";
                candidate.state = "unknown-state".equals(variant) ? "UNKNOWN" : "APPLIED";
                List<BalanceLedgerPO> rows =
                    "null-row".equals(variant)
                        ? java.util.Collections.singletonList(null)
                        : List.of(candidate);
                when(ledgerMapper.findPaymentLedgerCandidates(request().payNo)).thenReturn(rows);
                assertPersistedAmountConflict(operation, "IDEMPOTENCY_CONFLICT");
                verify(ledgerMapper).findPaymentLedgerCandidates(request().payNo);
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** null列表不能当空，DAO异常原对象传播，两个原门命中保持旧message且不再查scope。 */
  @Test
  void noFundsScopeMissingAndFaultKeepOldPriorityWithoutFundsWrites() {
    for (String operation : List.of("try", "confirm", "cancel")) {
      prepareNoFundsScope("TRYING");
      when(ledgerMapper.findPaymentLedgerCandidates(request().payNo)).thenReturn(null);
      assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
      prepareNoFundsScope("TRYING");
      var failure =
          new org.springframework.dao.DataAccessResourceFailureException(
              "owned-nofunds-scope-fault");
      org.mockito.Mockito.doThrow(failure)
          .when(ledgerMapper)
          .findPaymentLedgerCandidates(request().payNo);
      org.junit.jupiter.api.Assertions.assertSame(
          failure,
          assertThrows(
              org.springframework.dao.DataAccessResourceFailureException.class,
              () -> invokeNoFundsEntry(operation)));
      verify(userMapper, never()).freezeBalance(any(), any(), any());
      verify(userMapper, never()).clearFrozenBalance(any(), any(), any());
      verify(userMapper, never()).releaseFrozenBalance(any(), any(), any());
      for (String oldGate : List.of("pay-debit", "fixed-key")) {
        prepareNoFundsScope("TRYING");
        if ("pay-debit".equals(oldGate))
          when(ledgerMapper.findDebitCandidatesByPayNo(request().payNo))
              .thenReturn(List.of(originalLedger));
        else
          when(ledgerMapper.selectByOperationKey(request().ledgerKey)).thenReturn(originalLedger);
        org.mockito.Mockito.doThrow(failure)
            .when(ledgerMapper)
            .findPaymentLedgerCandidates(request().payNo);
        BizException conflict =
            assertThrows(BizException.class, () -> invokeNoFundsEntry(operation));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
        assertEquals("支付或账本键已有扣款事实", conflict.getMessage());
        verify(ledgerMapper, never()).findPaymentLedgerCandidates(any());
      }
    }
  }

  /** 无资金候选的合法空取消重放不核坏钱包，已确认仍独立走T88扣款proof并容许退款。 */
  @Test
  void emptyNoFundsScopeKeepsCancelledAndConfirmedNoOpSemantics() {
    for (String state : List.of("CANCELLED", "empty-cancel")) {
      prepareNoFundsScope(state);
      when(ledgerMapper.findPaymentLedgerCandidates(request().payNo)).thenReturn(List.of());
      when(userMapper.selectByIdForUpdate(7L)).thenReturn(account(null, null));
      assertEquals("CANCELLED", service.tryDebit(request()).state);
      assertEquals("CANCELLED", service.cancelDebit(completion()).state);
      verify(userMapper, never()).releaseFrozenBalance(any(), any(), any());
    }
    setUp();
    installFunds("CONFIRMED", "none");
    when(ledgerMapper.findPaymentLedgerCandidates(request().payNo))
        .thenReturn(List.of(new BalanceLedgerPO()));
    assertEquals("CONFIRMED", service.tryDebit(request()).state);
    assertEquals("CONFIRMED", service.confirmDebit(completion()).state);
    verify(ledgerMapper, never()).findPaymentLedgerCandidates(any());
  }

  /** 独立替身核同pay存在性，none/仅attempt空取消不补造freeze或账本。 */
  private void prepareNoFundsScope(String state) {
    setUp();
    installFunds(
        "empty-cancel".equals(state) ? "CANCELLED" : "none".equals(state) ? "TRYING" : state,
        "none");
    when(ledgerMapper.findDebitCandidatesByPayNo(request().payNo)).thenReturn(List.of());
    when(ledgerMapper.selectByOperationKey(request().ledgerKey)).thenReturn(null);
    if (List.of("none", "empty-cancel").contains(state))
      when(tccMapper.selectByPayNo(request().payNo)).thenReturn(null);
    if ("none".equals(state)) when(attemptMapper.selectByPayNo(request().payNo)).thenReturn(null);
  }

  /** 仅测试统一调用三个真实入口，不模拟返回值或改写业务字段。 */
  private void invokeNoFundsEntry(String operation) {
    switch (operation) {
      case "try" -> service.tryDebit(request());
      case "confirm" -> service.confirmDebit(completion());
      case "cancel" -> service.cancelDebit(completion());
      default -> throw new AssertionError(operation);
    }
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
    applied.id = 5L;
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
    when(ledgerMapper.selectByOperationKey(original.ledgerKey))
        .thenReturn("CONFIRMED".equals(state) ? applied : null);
    when(ledgerMapper.findDebitCandidatesByPayNo(original.payNo))
        .thenReturn("CONFIRMED".equals(state) ? List.of(applied) : List.of());
  }

  /** 固定409及原code，所有金额/状态写Mapper都不得调用。 */
  /** 确认重放不接受退款专用origin字段，不清算/重扣/改状态。 */
  @Test
  void confirmedReplaysRejectRefundOriginOnOriginalDebit() {
    List<org.junit.jupiter.api.function.Executable> assertions = new java.util.ArrayList<>();
    for (String operation : List.of("try", "confirm")) {
      for (String origin : List.of("", "null", "pay1", "other-pay", "PAY1", " ")) {
        assertions.add(
            () -> {
              installFunds("CONFIRMED", "none");
              originalLedger.originalOperationKey = origin;
              assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    installFunds("CONFIRMED", "none");
    assertEquals("CONFIRMED", service.tryDebit(request()).state);
    assertEquals("CONFIRMED", service.confirmDebit(completion()).state);
  }

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
