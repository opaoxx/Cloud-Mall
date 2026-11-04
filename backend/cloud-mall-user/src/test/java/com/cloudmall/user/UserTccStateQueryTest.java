package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.cloudmall.user.service.impl.tcc.UserTccStateQuery;
import java.math.BigDecimal;
import java.util.List;
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
    TccFenceLogPO fence = fence(1, 11L);
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
    when(ledgers.findDebitCandidatesByPayNo(receive.payNo)).thenReturn(List.of(ledger));
    when(fences.findByBranch(receive.xid, 12L)).thenReturn(fence(2, 12L));

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
    when(fences.findByBranch(receive.xid, 13L)).thenReturn(fence(4, 13L));

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

  /** CI候选原XID必须精确匹配，正确action/status不能替代原身份。 */
  @Test
  void observedFenceXidAliasesOrMissingIdentityCannotProveKnownState() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
      for (String xid :
          new String[] {null, "XID-query-1", "xid-query-1 ", "xíd-query-1", "other"}) {
        TccFenceLogPO observed = installKnownState(state);
        observed.xid = xid;
        BizException failure =
            assertThrows(
                BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
        assertEquals(409, failure.getHttpStatus());
        assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
      }
    }
  }

  /** 返回原Fence的branch不能缺失或指向其他分支。 */
  @Test
  void observedFenceBranchIdentityMustMatchOriginalAttempt() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
      for (Long branchId : new Long[] {null, 0L, 13L}) {
        TccFenceLogPO observed = installKnownState(state);
        observed.branchId = branchId;
        BizException failure =
            assertThrows(
                BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
        assertEquals(409, failure.getHttpStatus());
        assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
      }
    }
  }

  /** 缺行保既有TTL：TRYING拒绝，已终态允许；空取消的Fence仍按原分支核验。 */
  @Test
  void originalFenceAndTerminalExpiryRemainValidButTryingNeedsFence() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
      installKnownState(state);
      assertEquals(state, query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
      when(fences.findByBranch("xid-query-1", 12L)).thenReturn(null);
      if ("TRYING".equals(state)) {
        assertEquals(
            409,
            assertThrows(
                    BizException.class,
                    () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"))
                .getHttpStatus());
      } else assertEquals(state, query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
    }
    installKnownState("CANCELLED");
    when(debits.selectByPayNo("pay-query-1")).thenReturn(null);
    when(fences.findByBranch("xid-query-1", 12L)).thenReturn(fence(4, 12L));
    assertEquals("CANCELLED", query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
  }

  /** 坏原接收即使尚无资金行，也不能用UNKNOWN掩盖源契约损坏。 */
  @Test
  void invalidReceiveMetadataStopsBeforeFundsQueries() {
    for (String variant :
        List.of(
            "stateNull",
            "stateCase",
            "statePadding",
            "stateOther",
            "userNull",
            "userZero",
            "payNull",
            "payInvalid",
            "payLong",
            "orderNull",
            "orderBlank",
            "orderLong",
            "xidNull",
            "xidBlank",
            "xidLong",
            "branchZero",
            "branchNegative",
            "operation",
            "ledger")) {
      UserTccReceivePO original = receive(null);
      switch (variant) {
        case "stateNull" -> original.state = null;
        case "stateCase" -> original.state = "claimed";
        case "statePadding" -> original.state = "CLAIMED ";
        case "stateOther" -> original.state = "APPLIED";
        case "userNull" -> original.userId = null;
        case "userZero" -> original.userId = 0L;
        case "payNull" -> original.payNo = null;
        case "payInvalid" -> original.payNo = "pay:invalid";
        case "payLong" -> original.payNo = "p".repeat(65);
        case "orderNull" -> original.orderNo = null;
        case "orderBlank" -> original.orderNo = " ";
        case "orderLong" -> original.orderNo = "o".repeat(65);
        case "xidNull" -> original.xid = null;
        case "xidBlank" -> original.xid = " ";
        case "xidLong" -> original.xid = "x".repeat(129);
        case "branchZero" -> original.branchId = 0L;
        case "branchNegative" -> original.branchId = -1L;
        case "operation" -> original.operationKey = "tcc-debit:other";
        case "ledger" -> original.ledgerKey = "other-ledger";
        default -> throw new AssertionError(variant);
      }
      when(receives.findByOperationKey(original.operationKey)).thenReturn(original);
      BizException failure =
          assertThrows(
              BizException.class,
              () -> query.read(original.userId, original.operationKey, original.xid),
              variant);
      assertEquals(409, failure.getHttpStatus());
      assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
      verifyNoInteractions(attempts, debits, ledgers, fences);
    }
  }

  /** 相互一致的错误op/ledger键也不能代替原固定支付绑定。 */
  @Test
  void consistentlyAlteredKeysCannotProveKnownFundsState() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
      for (String variant : List.of("operation", "ledger")) {
        UserTccReceivePO original = receive(12L);
        BalanceDebitAttemptPO ownership = attempt(state, 12L);
        BalanceTccDebitPO frozen = debit(state, 12L);
        BalanceLedgerPO applied = ledger();
        if ("operation".equals(variant)) {
          original.operationKey = "tcc-debit:other";
          ownership.operationKey = original.operationKey;
          frozen.operationKey = original.operationKey;
        } else {
          original.ledgerKey = "other-ledger";
          ownership.ledgerKey = original.ledgerKey;
          applied.operationKey = original.ledgerKey;
        }
        when(receives.findByOperationKey(original.operationKey)).thenReturn(original);
        when(attempts.selectByPayNo(original.payNo)).thenReturn(ownership);
        when(debits.selectByPayNo(original.payNo)).thenReturn(frozen);
        when(ledgers.selectByOperationKey(original.ledgerKey))
            .thenReturn("CONFIRMED".equals(state) ? applied : null);
        when(fences.findByBranch(original.xid, 12L))
            .thenReturn(fence("TRYING".equals(state) ? 1 : "CONFIRMED".equals(state) ? 2 : 3, 12L));
        BizException failure =
            assertThrows(
                BizException.class,
                () -> query.read(7L, original.operationKey, original.xid),
                state + variant);
        assertEquals(409, failure.getHttpStatus());
        assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
      }
    }
  }

  /** 合法CLAIMED在未bind接收分支时仍保UNKNOWN或由原attempt观察known状态。 */
  @Test
  void canonicalClaimedReceiveAllowsUnobservedBranchAcrossStates() {
    for (String state : List.of("UNKNOWN", "TRYING", "CONFIRMED", "CANCELLED")) {
      UserTccReceivePO original = receive(null);
      when(receives.findByOperationKey(original.operationKey)).thenReturn(original);
      when(attempts.selectByPayNo(original.payNo))
          .thenReturn("UNKNOWN".equals(state) ? null : attempt(state, 12L));
      when(debits.selectByPayNo(original.payNo))
          .thenReturn("UNKNOWN".equals(state) ? null : debit(state, 12L));
      when(ledgers.selectByOperationKey(original.ledgerKey))
          .thenReturn("CONFIRMED".equals(state) ? ledger() : null);
      when(ledgers.selectDebitByPayNo(original.payNo))
          .thenReturn("CONFIRMED".equals(state) ? ledger() : null);
      when(ledgers.findDebitCandidatesByPayNo(original.payNo))
          .thenReturn("CONFIRMED".equals(state) ? List.of(ledger()) : List.of());
      when(fences.findByBranch(original.xid, 12L))
          .thenReturn(fence("TRYING".equals(state) ? 1 : "CONFIRMED".equals(state) ? 2 : 3, 12L));
      assertEquals(state, query.read(7L, original.operationKey, original.xid).state);
    }
  }

  /** 任意状态/键的同pay DEBIT都与取消证明矛盾，坏金额/外单不应使矛盾消失。 */
  @Test
  void cancelledStateCannotIgnoreAlternateDebitKeysOrStates() {
    List<org.junit.jupiter.api.function.Executable> assertions = new java.util.ArrayList<>();
    for (boolean emptyCancel : List.of(false, true)) {
      for (String ledgerState : List.of("APPLIED", "PENDING", "UNKNOWN")) {
        for (boolean corruptIdentity : List.of(false, true)) {
          assertions.add(
              () -> {
                setUp();
                installKnownState("CANCELLED");
                if (emptyCancel) {
                  when(debits.selectByPayNo("pay-query-1")).thenReturn(null);
                  when(fences.findByBranch("xid-query-1", 12L)).thenReturn(fence(4, 12L));
                }
                var alternate = ledger();
                alternate.operationKey = "another-debit-key";
                alternate.state = ledgerState;
                if (corruptIdentity) {
                  alternate.userId = 42L;
                  alternate.orderNo = "other-order";
                  alternate.amount = null;
                }
                when(ledgers.findDebitCandidatesByPayNo("pay-query-1"))
                    .thenReturn(List.of(alternate));
                BizException failure =
                    assertThrows(
                        BizException.class,
                        () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
                assertEquals(409, failure.getHttpStatus());
                assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
                verifyNoInteractions(fences);
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 新读取故障或同pay多行不能伪装无账本、取消成功，异常仍由原包装层处理。 */
  @Test
  void cancelledDebitScopeFailuresPropagateBeforeFenceQuery() {
    List<org.junit.jupiter.api.function.Executable> assertions = new java.util.ArrayList<>();
    for (boolean emptyCancel : List.of(false, true)) {
      for (RuntimeException unavailable :
          List.of(
              new org.springframework.dao.DataRetrievalFailureException("owned-test-db-failure"),
              new org.apache.ibatis.exceptions.TooManyResultsException(
                  "owned-test-multiple-debits"))) {
        assertions.add(
            () -> {
              setUp();
              installKnownState("CANCELLED");
              if (emptyCancel) when(debits.selectByPayNo("pay-query-1")).thenReturn(null);
              when(ledgers.findDebitCandidatesByPayNo("pay-query-1")).thenThrow(unavailable);
              RuntimeException failure =
                  assertThrows(
                      RuntimeException.class,
                      () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
              org.junit.jupiter.api.Assertions.assertSame(unavailable, failure);
              verifyNoInteractions(fences);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 无同pay扣款时，释放及empty tombstone取消均保留原Fence与历史过期兼容。 */
  @Test
  void cancelledWithoutAnyDebitKeepsOriginalTerminalAndExpirySemantics() {
    for (boolean emptyCancel : List.of(false, true)) {
      for (boolean expiredFence : List.of(false, true)) {
        setUp();
        installKnownState("CANCELLED");
        if (emptyCancel) when(debits.selectByPayNo("pay-query-1")).thenReturn(null);
        when(fences.findByBranch("xid-query-1", 12L))
            .thenReturn(expiredFence ? null : fence(emptyCancel ? 4 : 3, 12L));
        var state = query.read(7L, "tcc-debit:pay-query-1", "xid-query-1");
        assertEquals("CANCELLED", state.state);
        org.junit.jupiter.api.Assertions.assertFalse(state.ledgerApplied);
      }
    }
  }

  /** 固定键冲突仍先短路，UNKNOWN可靠空范围与已知状态按各自scope协议读取。 */
  @Test
  void fixedLedgerConflictAndUnknownStateKeepScopeQueryPriority() {
    setUp();
    installKnownState("CANCELLED");
    when(ledgers.selectByOperationKey("pay-query-1")).thenReturn(ledger());
    assertThrows(BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
    org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
        .findDebitCandidatesByPayNo(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(fences);
    for (String state : List.of("TRYING", "CONFIRMED", "UNKNOWN")) {
      setUp();
      if ("UNKNOWN".equals(state))
        when(receives.findByOperationKey("tcc-debit:pay-query-1")).thenReturn(receive(null));
      else installKnownState(state);
      assertEquals(state, query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
      org.mockito.Mockito.verify(ledgers).findDebitCandidatesByPayNo("pay-query-1");
      if ("UNKNOWN".equals(state)) {
        org.mockito.Mockito.verify(ledgers).findPaymentLedgerCandidates("pay-query-1");
        verifyNoInteractions(fences);
      }
    }
  }

  /** TRYING不能用固定键缺行隐藏同支付号别键扣款。 */
  @Test
  void tryingStateRejectsOtherDebitKeysRegardlessOfCandidateState() {
    List<org.junit.jupiter.api.function.Executable> assertions = new java.util.ArrayList<>();
    for (String ledgerState : List.of("APPLIED", "PENDING", "UNKNOWN")) {
      for (boolean corruptIdentity : List.of(false, true)) {
        assertions.add(
            () -> {
              setUp();
              installKnownState("TRYING");
              var observed = ledger();
              observed.operationKey = "alternate-debit-key";
              observed.state = ledgerState;
              if (corruptIdentity) {
                observed.userId = 42L;
                observed.amount = null;
              }
              when(ledgers.findDebitCandidatesByPayNo("pay-query-1")).thenReturn(List.of(observed));
              var conflict =
                  assertThrows(
                      BizException.class,
                      () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
              assertEquals("USER_TCC_STATE_CONFLICT", conflict.getCode());
              assertEquals(409, conflict.getHttpStatus());
              verifyNoInteractions(fences);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 确认固定键成功还必须有同pay唯一且同原载荷的扣款范围证据。 */
  @Test
  void confirmedStateRejectsMissingOrDifferentDebitScopeEvidence() {
    List<org.junit.jupiter.api.function.Executable> assertions = new java.util.ArrayList<>();
    for (String variant :
        List.of(
            "missing",
            "user",
            "pay",
            "order",
            "key",
            "type",
            "state",
            "amount-null",
            "amount",
            "before-null",
            "before",
            "after-null",
            "after")) {
      assertions.add(
          () -> {
            setUp();
            installKnownState("CONFIRMED");
            var observed = ledger();
            switch (variant) {
              case "user" -> observed.userId = 42L;
              case "pay" -> observed.payNo = "PAY-query-1";
              case "order" -> observed.orderNo = "other-order";
              case "key" -> observed.operationKey = "another-key";
              case "type" -> observed.operationType = "debit";
              case "state" -> observed.state = "REJECTED";
              case "amount-null" -> observed.amount = null;
              case "amount" -> observed.amount = new BigDecimal("11.00");
              case "before-null" -> observed.balanceBefore = null;
              case "before" -> observed.balanceBefore = new BigDecimal("101.00");
              case "after-null" -> observed.balanceAfter = null;
              case "after" -> observed.balanceAfter = new BigDecimal("89.00");
              default -> {}
            }
            when(ledgers.findDebitCandidatesByPayNo("pay-query-1"))
                .thenReturn("missing".equals(variant) ? List.of() : List.of(observed));
            var conflict =
                assertThrows(
                    BizException.class,
                    () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
            assertEquals("USER_TCC_STATE_CONFLICT", conflict.getCode());
            assertEquals(409, conflict.getHttpStatus());
            verifyNoInteractions(fences);
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 范围查询DB故障或非唯一结果不得由固定键已核验掩盖。 */
  @Test
  void knownFundsScopeFailuresPropagateWithoutReportingSuccess() {
    List<org.junit.jupiter.api.function.Executable> assertions = new java.util.ArrayList<>();
    for (String state : List.of("TRYING", "CONFIRMED")) {
      for (RuntimeException unavailable :
          List.of(
              new org.springframework.dao.DataRetrievalFailureException("owned-db-error"),
              new org.apache.ibatis.exceptions.TooManyResultsException("owned-multiple-debits"))) {
        assertions.add(
            () -> {
              setUp();
              installKnownState(state);
              if ("CONFIRMED".equals(state))
                when(ledgers.findDebitCandidatesByPayNo("pay-query-1")).thenThrow(unavailable);
              else when(ledgers.findDebitCandidatesByPayNo("pay-query-1")).thenThrow(unavailable);
              RuntimeException observed =
                  assertThrows(
                      RuntimeException.class,
                      () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
              org.junit.jupiter.api.Assertions.assertSame(unavailable, observed);
              verifyNoInteractions(fences);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 健康scope保数值尾零，fixed键原矛盾仍早停，UNKNOWN与取消原语义保持。 */
  @Test
  void knownFundsScopeKeepsHealthyAmountsAndOriginalGatePriority() {
    for (String state : List.of("TRYING", "CONFIRMED")) {
      setUp();
      installKnownState(state);
      var candidate = ledger();
      candidate.amount = new BigDecimal("10.000");
      candidate.balanceBefore = new BigDecimal("100.000");
      candidate.balanceAfter = new BigDecimal("90.000");
      when(ledgers.selectDebitByPayNo("pay-query-1")).thenReturn(null);
      when(ledgers.findDebitCandidatesByPayNo("pay-query-1"))
          .thenReturn("CONFIRMED".equals(state) ? List.of(candidate) : List.of());
      var view = query.read(7L, "tcc-debit:pay-query-1", "xid-query-1");
      assertEquals(state, view.state);
      assertEquals("CONFIRMED".equals(state), view.ledgerApplied);
      setUp();
      installKnownState(state);
      when(ledgers.selectByOperationKey("pay-query-1"))
          .thenReturn("TRYING".equals(state) ? ledger() : null);
      assertThrows(
          BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
      org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
          .findDebitCandidatesByPayNo(org.mockito.ArgumentMatchers.any());
      verifyNoInteractions(fences);
    }
  }

  /** DEBIT不是退款，原固定账本的非NULL origin必须在scope查询前拒绝。 */
  @Test
  void confirmedFixedDebitCannotCarryRefundOrigin() {
    List<org.junit.jupiter.api.function.Executable> assertions = new java.util.ArrayList<>();
    for (String origin : List.of("", "null", "pay-query-1", "other-pay", "PAY-query-1", " ")) {
      assertions.add(
          () -> {
            setUp();
            installKnownState("CONFIRMED");
            var fixed = ledger();
            fixed.originalOperationKey = origin;
            when(ledgers.selectByOperationKey("pay-query-1")).thenReturn(fixed);
            var conflict =
                assertThrows(
                    BizException.class,
                    () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
            assertEquals(409, conflict.getHttpStatus());
            assertEquals("USER_TCC_STATE_CONFLICT", conflict.getCode());
            org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
                .findDebitCandidatesByPayNo(org.mockito.ArgumentMatchers.any());
            verifyNoInteractions(fences);
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 固定账本合法但范围候选携带origin也不能成为原扣款证明。 */
  @Test
  void confirmedScopeDebitCannotCarryRefundOrigin() {
    List<org.junit.jupiter.api.function.Executable> assertions = new java.util.ArrayList<>();
    for (String origin : List.of("", "null", "pay-query-1", "other-pay", "PAY-query-1", " ")) {
      assertions.add(
          () -> {
            setUp();
            installKnownState("CONFIRMED");
            var candidate = ledger();
            candidate.originalOperationKey = origin;
            when(ledgers.findDebitCandidatesByPayNo("pay-query-1")).thenReturn(List.of(candidate));
            var conflict =
                assertThrows(
                    BizException.class,
                    () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
            assertEquals(409, conflict.getHttpStatus());
            assertEquals("USER_TCC_STATE_CONFLICT", conflict.getCode());
            verifyNoInteractions(fences);
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    setUp();
    installKnownState("CONFIRMED");
    assertEquals("CONFIRMED", query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
  }

  /** 三种未确认形态不能忽略同pay非DEBIT账本或非空nullrow，不继续读Fence。 */
  @Test
  void unconfirmedStatesRequireEmptyFullPaymentLedgerScope() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String state : List.of("TRYING", "CANCELLED", "empty-cancel")) {
      for (String variant :
          List.of(
              "refund", "unknown-type", "unknown-state", "pay-alias", "other-user", "null-row")) {
        assertions.add(
            () -> {
              setUp();
              prepareUnconfirmedScope(state);
              var row = ledger();
              row.operationKey = "other-ledger-key";
              row.operationType = "unknown-type".equals(variant) ? "UNKNOWN" : "REFUND";
              if ("unknown-state".equals(variant)) row.state = "UNKNOWN";
              if ("pay-alias".equals(variant))
                row.payNo = row.payNo.toUpperCase(java.util.Locale.ROOT);
              if ("other-user".equals(variant)) row.userId = 8L;
              when(ledgers.findPaymentLedgerCandidates("pay-query-1"))
                  .thenReturn(
                      "null-row".equals(variant)
                          ? java.util.Collections.singletonList(null)
                          : List.of(row));
              var failure =
                  assertThrows(
                      BizException.class,
                      () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
              assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
              assertEquals(409, failure.getHttpStatus());
              verifyNoInteractions(fences);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** null缺证/DAO故障不当空；固定账本或DEBIT先命中时不覆盖其旧拒绝顺序。 */
  @Test
  void unconfirmedScopeMissingAndDaoFaultPreserveProofPriority() {
    for (String state : List.of("TRYING", "CANCELLED", "empty-cancel")) {
      setUp();
      prepareUnconfirmedScope(state);
      when(ledgers.findPaymentLedgerCandidates("pay-query-1")).thenReturn(null);
      assertEquals(
          "USER_TCC_STATE_CONFLICT",
          assertThrows(
                  BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"))
              .getCode());
      setUp();
      prepareUnconfirmedScope(state);
      var fault =
          new org.springframework.dao.DataAccessResourceFailureException("owned-read-scope-fault");
      org.mockito.Mockito.doThrow(fault).when(ledgers).findPaymentLedgerCandidates("pay-query-1");
      org.junit.jupiter.api.Assertions.assertSame(
          fault,
          assertThrows(
              org.springframework.dao.DataAccessResourceFailureException.class,
              () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1")));
      for (String oldGate : List.of("fixed", "debit")) {
        setUp();
        prepareUnconfirmedScope(state);
        if ("fixed".equals(oldGate))
          when(ledgers.selectByOperationKey("pay-query-1")).thenReturn(ledger());
        else when(ledgers.findDebitCandidatesByPayNo("pay-query-1")).thenReturn(List.of(ledger()));
        org.mockito.Mockito.doThrow(fault).when(ledgers).findPaymentLedgerCandidates("pay-query-1");
        assertEquals(
            "USER_TCC_STATE_CONFLICT",
            assertThrows(
                    BizException.class,
                    () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"))
                .getCode());
        org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
            .findPaymentLedgerCandidates(org.mockito.ArgumentMatchers.any());
      }
    }
  }

  /** 健康empty保持状态，UNKNOWN核同pay空范围但不证明全账户，CONFIRMED沿原DEBIT范围。 */
  @Test
  void emptyScopeKeepsKnownStatesAndUncertainOrConfirmedReadBoundaries() {
    for (String state : List.of("TRYING", "CANCELLED", "empty-cancel")) {
      setUp();
      prepareUnconfirmedScope(state);
      when(ledgers.findPaymentLedgerCandidates("pay-query-1")).thenReturn(List.of());
      assertEquals(
          "TRYING".equals(state) ? "TRYING" : "CANCELLED",
          query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
    }
    setUp();
    var receive = receive(null);
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(ledgers.findPaymentLedgerCandidates(receive.payNo)).thenReturn(List.of(ledger()));
    assertEquals(
        "USER_TCC_STATE_CONFLICT",
        assertThrows(BizException.class, () -> query.read(7L, receive.operationKey, receive.xid))
            .getCode());
    org.mockito.Mockito.verify(ledgers).findDebitCandidatesByPayNo(receive.payNo);
    org.mockito.Mockito.verify(ledgers).findPaymentLedgerCandidates(receive.payNo);
    verifyNoInteractions(fences);
    setUp();
    installKnownState("CONFIRMED");
    when(ledgers.findPaymentLedgerCandidates("pay-query-1")).thenReturn(List.of(ledger()));
    assertEquals("CONFIRMED", query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
    org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
        .findPaymentLedgerCandidates(org.mockito.ArgumentMatchers.any());
  }

  /** 仅准备原状态/形态，不补空取消freeze；后续作用于独立的scope替身。 */
  private void prepareUnconfirmedScope(String state) {
    TccFenceLogPO observed = installKnownState("empty-cancel".equals(state) ? "CANCELLED" : state);
    if ("empty-cancel".equals(state)) {
      when(debits.selectByPayNo("pay-query-1")).thenReturn(null);
      observed.status = 4;
    }
  }

  /** LIMIT2范围缺证/重复/nullrow明确冲突，不将重复事实模拟成底层DAO故障。 */
  @Test
  void confirmedBoundedCandidatesRejectMissingAndDuplicateFactsBeforeFence() {
    for (String variant : List.of("null-list", "empty", "null-row", "two", "three")) {
      setUp();
      installKnownState("CONFIRMED");
      List<BalanceLedgerPO> candidates =
          switch (variant) {
            case "null-list" -> null;
            case "empty" -> List.of();
            case "null-row" -> java.util.Collections.singletonList(null);
            case "two" -> List.of(ledger(), ledger());
            default -> List.of(ledger(), ledger(), ledger());
          };
      when(ledgers.findDebitCandidatesByPayNo("pay-query-1")).thenReturn(candidates);
      var conflict =
          assertThrows(
              BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
      assertEquals(409, conflict.getHttpStatus());
      assertEquals("USER_TCC_STATE_CONFLICT", conflict.getCode());
      verifyNoInteractions(fences);
      org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
          .selectDebitByPayNo(org.mockito.ArgumentMatchers.any());
    }
  }

  /** 未确认三形态缺/单/多候选均先冲突，只有可靠empty才继续raw范围与Fence。 */
  @Test
  void unconfirmedBoundedDebitScopeRejectsUnavailableAndExistingRows() {
    for (String state : List.of("TRYING", "CANCELLED", "empty-cancel")) {
      for (String variant : List.of("null-list", "single", "null-row", "two", "three")) {
        setUp();
        prepareUnconfirmedScope(state);
        List<BalanceLedgerPO> candidates =
            switch (variant) {
              case "null-list" -> null;
              case "single" -> List.of(ledger());
              case "null-row" -> java.util.Collections.singletonList(null);
              case "two" -> List.of(ledger(), ledger());
              default -> List.of(ledger(), ledger(), ledger());
            };
        when(ledgers.findDebitCandidatesByPayNo("pay-query-1")).thenReturn(candidates);
        var failure =
            assertThrows(
                BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
        assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
        assertEquals(409, failure.getHttpStatus());
        org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
            .findPaymentLedgerCandidates(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(fences);
      }
    }
  }

  /** 完整原资金载荷，返回可变Fence以只改变本次被测来源。 */
  private TccFenceLogPO installKnownState(String state) {
    UserTccReceivePO receive = receive(12L);
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(attempts.selectByPayNo(receive.payNo)).thenReturn(attempt(state, 12L));
    when(debits.selectByPayNo(receive.payNo)).thenReturn(debit(state, 12L));
    when(ledgers.selectByOperationKey(receive.ledgerKey))
        .thenReturn("CONFIRMED".equals(state) ? ledger() : null);
    when(ledgers.selectDebitByPayNo(receive.payNo))
        .thenReturn("CONFIRMED".equals(state) ? ledger() : null);
    when(ledgers.findDebitCandidatesByPayNo(receive.payNo))
        .thenReturn("CONFIRMED".equals(state) ? List.of(ledger()) : List.of());
    TccFenceLogPO observed =
        fence("TRYING".equals(state) ? 1 : "CONFIRMED".equals(state) ? 2 : 3, 12L);
    when(fences.findByBranch(receive.xid, 12L)).thenReturn(observed);
    return observed;
  }

  /** 无归属的两个接收分支形态均拒绝缺证或任意账本候选，不按类型/状态/身份过滤。 */
  @Test
  void unknownStateRequiresReliableEmptyPaymentLedgerScopes() {
    for (boolean branchKnown : List.of(false, true)) {
      for (String phase : List.of("debit", "payment")) {
        for (String variant :
            List.of(
                "missing",
                "null-row",
                "debit-applied",
                "debit-pending",
                "debit-rejected",
                "refund",
                "unknown-type",
                "unknown-state",
                "foreign",
                "alias",
                "two")) {
          setUp();
          UserTccReceivePO receive = receive(branchKnown ? 12L : null);
          when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
          var row = ledger();
          row.operationKey = "other-operation-key";
          if ("debit-pending".equals(variant)) row.state = "PENDING";
          if ("debit-rejected".equals(variant)) row.state = "REJECTED";
          if ("refund".equals(variant)) row.operationType = "REFUND";
          if ("unknown-type".equals(variant)) row.operationType = "UNKNOWN";
          if ("unknown-state".equals(variant)) row.state = "UNKNOWN";
          if ("foreign".equals(variant)) row.userId = 8L;
          if ("alias".equals(variant)) row.payNo = "PAY-QUERY-1";
          List<BalanceLedgerPO> candidates =
              "missing".equals(variant)
                  ? null
                  : "null-row".equals(variant)
                      ? java.util.Collections.singletonList(null)
                      : "two".equals(variant) ? List.of(row, row) : List.of(row);
          if ("debit".equals(phase))
            when(ledgers.findDebitCandidatesByPayNo(receive.payNo)).thenReturn(candidates);
          else when(ledgers.findPaymentLedgerCandidates(receive.payNo)).thenReturn(candidates);
          var failure =
              assertThrows(
                  BizException.class, () -> query.read(7L, receive.operationKey, receive.xid));
          assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
          assertEquals("TCC 持久事实不一致", failure.getMessage());
          assertEquals(409, failure.getHttpStatus());
          org.mockito.Mockito.verify(ledgers).findDebitCandidatesByPayNo(receive.payNo);
          org.mockito.Mockito.verify(
                  ledgers, org.mockito.Mockito.times("payment".equals(phase) ? 1 : 0))
              .findPaymentLedgerCandidates(receive.payNo);
          verifyNoInteractions(fences);
          assertStatePointReadsOnly();
        }
      }
    }
  }

  /** UNKNOWN两范围DAO原对象，合法empty顺序/预算与旧固定来源空门优先保持。 */
  @Test
  void unknownLedgerScopesKeepHealthySemanticsAndOldPriority() {
    for (boolean branchKnown : List.of(false, true)) {
      for (String phase : List.of("debit", "payment")) {
        setUp();
        UserTccReceivePO receive = receive(branchKnown ? 12L : null);
        when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
        var failure =
            new org.springframework.dao.DataAccessResourceFailureException(
                "unknown-scope-jdbc-fault");
        if ("debit".equals(phase))
          org.mockito.Mockito.doThrow(failure)
              .when(ledgers)
              .findDebitCandidatesByPayNo(receive.payNo);
        else
          org.mockito.Mockito.doThrow(failure)
              .when(ledgers)
              .findPaymentLedgerCandidates(receive.payNo);
        org.junit.jupiter.api.Assertions.assertSame(
            failure,
            assertThrows(
                RuntimeException.class, () -> query.read(7L, receive.operationKey, receive.xid)));
        verifyNoInteractions(fences);
        assertStatePointReadsOnly();
      }
      setUp();
      UserTccReceivePO receive = receive(branchKnown ? 12L : null);
      when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
      var view = query.read(7L, receive.operationKey, receive.xid);
      assertEquals("UNKNOWN", view.state);
      assertEquals(null, view.branchId);
      org.junit.jupiter.api.Assertions.assertFalse(view.ledgerApplied);
      var ordered = org.mockito.Mockito.inOrder(ledgers);
      ordered.verify(ledgers).selectByOperationKey(receive.ledgerKey);
      ordered.verify(ledgers).findDebitCandidatesByPayNo(receive.payNo);
      ordered.verify(ledgers).findPaymentLedgerCandidates(receive.payNo);
      ordered.verifyNoMoreInteractions();
      verifyNoInteractions(fences);
      assertStatePointReadsOnly();
    }
    for (String originalConflict : List.of("freeze", "fixed-key")) {
      setUp();
      UserTccReceivePO receive = receive(null);
      when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
      if ("freeze".equals(originalConflict))
        when(debits.selectByPayNo(receive.payNo)).thenReturn(debit("TRYING", 12L));
      else when(ledgers.selectByOperationKey(receive.ledgerKey)).thenReturn(ledger());
      org.mockito.Mockito.doThrow(new AssertionError("old source conflict must precede scopes"))
          .when(ledgers)
          .findDebitCandidatesByPayNo(receive.payNo);
      assertEquals(
          "USER_TCC_STATE_CONFLICT",
          assertThrows(BizException.class, () -> query.read(7L, receive.operationKey, receive.xid))
              .getCode());
      org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
          .findDebitCandidatesByPayNo(org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
          .findPaymentLedgerCandidates(org.mockito.ArgumentMatchers.any());
      verifyNoInteractions(fences);
      assertStatePointReadsOnly();
    }
  }

  /** 五类点读直接/标准直接包装多结果均固定事实冲突，不猜missing/UNKNOWN或终态。 */
  @Test
  void statePointMultipleResultsKeepBusinessConflict() {
    for (String phase : List.of("receive", "owner", "freeze", "ledger", "fence")) {
      for (boolean wrapped : List.of(false, true)) {
        setUp();
        installKnownState("CONFIRMED");
        var duplicate =
            new org.apache.ibatis.exceptions.TooManyResultsException("state-point-duplicate");
        RuntimeException failure =
            wrapped ? new org.mybatis.spring.MyBatisSystemException(duplicate) : duplicate;
        failStatePointRead(phase, failure);
        var rejected =
            assertThrows(
                BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
        assertEquals("USER_TCC_STATE_CONFLICT", rejected.getCode());
        assertEquals("TCC 持久事实不一致", rejected.getMessage());
        assertEquals(409, rejected.getHttpStatus());
        verifyStatePointOnce(phase);
        assertStatePointReadsOnly();
      }
    }
  }

  /** 非目标故障原对象、初接收缺失404及原身份/metadata先级保持。 */
  @Test
  void statePointClassificationKeepsOtherFaultsAndMissingReceive() {
    for (String phase : List.of("receive", "owner", "freeze", "ledger", "fence")) {
      for (String shape :
          List.of(
              "resource",
              "sql-wrapper",
              "nested-too-many",
              "other-wrapper",
              "runtime-cause",
              "message-only")) {
        setUp();
        installKnownState("CONFIRMED");
        RuntimeException failure =
            switch (shape) {
              case "resource" ->
                  new org.springframework.dao.DataAccessResourceFailureException(
                      "state-point-jdbc-fault");
              case "sql-wrapper" ->
                  new org.mybatis.spring.MyBatisSystemException(
                      new java.sql.SQLException("state-point-sql-fault"));
              case "nested-too-many" ->
                  new org.mybatis.spring.MyBatisSystemException(
                      new org.apache.ibatis.exceptions.PersistenceException(
                          "outer",
                          new org.apache.ibatis.exceptions.TooManyResultsException("nested")));
              case "other-wrapper" ->
                  new org.apache.ibatis.exceptions.PersistenceException(
                      "outer", new org.apache.ibatis.exceptions.TooManyResultsException("nested"));
              case "runtime-cause" ->
                  new IllegalArgumentException(
                      "outer", new org.apache.ibatis.exceptions.TooManyResultsException("nested"));
              default -> new IllegalArgumentException("TooManyResultsException is only text");
            };
        failStatePointRead(phase, failure);
        org.junit.jupiter.api.Assertions.assertSame(
            failure,
            assertThrows(
                RuntimeException.class,
                () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1")));
        verifyStatePointOnce(phase);
        assertStatePointReadsOnly();
      }
    }
    setUp();
    var missing =
        assertThrows(
            BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
    assertEquals("COMMON_NOT_FOUND", missing.getCode());
    assertEquals(404, missing.getHttpStatus());
    verifyNoInteractions(attempts, debits, ledgers, fences);
    for (String phase : List.of("owner", "freeze", "ledger", "fence")) {
      for (String gate : List.of("foreign", "metadata")) {
        setUp();
        installKnownState("CONFIRMED");
        UserTccReceivePO source = receive(12L);
        if ("foreign".equals(gate)) source.userId = 8L;
        else source.state = "UNKNOWN";
        when(receives.findByOperationKey(source.operationKey)).thenReturn(source);
        failStatePointRead(
            phase,
            new org.apache.ibatis.exceptions.TooManyResultsException("downstream-must-not-run"));
        assertEquals(
            "USER_TCC_STATE_CONFLICT",
            assertThrows(BizException.class, () -> query.read(7L, source.operationKey, source.xid))
                .getCode());
        verifyNoInteractions(attempts, debits, ledgers, fences);
      }
    }
  }

  /** 只替换目标点读，完整CONFIRMED来源使之前的校验正常通过。 */
  private void failStatePointRead(String phase, RuntimeException failure) {
    switch (phase) {
      case "receive" ->
          org.mockito.Mockito.doThrow(failure)
              .when(receives)
              .findByOperationKey("tcc-debit:pay-query-1");
      case "owner" ->
          org.mockito.Mockito.doThrow(failure).when(attempts).selectByPayNo("pay-query-1");
      case "freeze" ->
          org.mockito.Mockito.doThrow(failure).when(debits).selectByPayNo("pay-query-1");
      case "ledger" ->
          org.mockito.Mockito.doThrow(failure).when(ledgers).selectByOperationKey("pay-query-1");
      case "fence" ->
          org.mockito.Mockito.doThrow(failure).when(fences).findByBranch("xid-query-1", 12L);
      default -> throw new AssertionError("Unexpected state point phase");
    }
  }

  /** 目标point恰一次，使用原查询参数，不新增COUNT或任何预算。 */
  private void verifyStatePointOnce(String phase) {
    switch (phase) {
      case "receive" ->
          org.mockito.Mockito.verify(receives).findByOperationKey("tcc-debit:pay-query-1");
      case "owner" -> org.mockito.Mockito.verify(attempts).selectByPayNo("pay-query-1");
      case "freeze" -> org.mockito.Mockito.verify(debits).selectByPayNo("pay-query-1");
      case "ledger" -> org.mockito.Mockito.verify(ledgers).selectByOperationKey("pay-query-1");
      case "fence" -> org.mockito.Mockito.verify(fences).findByBranch("xid-query-1", 12L);
      default -> throw new AssertionError("Unexpected state point phase");
    }
    org.mockito.Mockito.verify(ledgers, org.mockito.Mockito.never())
        .countOperationCandidates(org.mockito.ArgumentMatchers.any());
  }

  /** 所有Mapper交互都是现有select/find，不允许补写接收、绑定分支或修复账本。 */
  private void assertStatePointReadsOnly() {
    for (Object mapper : List.of(receives, attempts, debits, ledgers, fences))
      for (var invocation : org.mockito.Mockito.mockingDetails(mapper).getInvocations()) {
        String methodName = invocation.getMethod().getName();
        org.junit.jupiter.api.Assertions.assertTrue(
            methodName.startsWith("select") || methodName.startsWith("find"));
      }
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
    debit.balanceBefore = new BigDecimal("100.00");
    debit.balanceAfterTry = new BigDecimal("90.00");
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
    ledger.balanceBefore = new BigDecimal("100.00");
    ledger.balanceAfter = new BigDecimal("90.00");
    return ledger;
  }

  private TccFenceLogPO fence(int status, Long branchId) {
    TccFenceLogPO fence = new TccFenceLogPO();
    fence.xid = "xid-query-1";
    fence.branchId = branchId;
    fence.actionName = BalanceTccDebitLocalResource.ACTION_NAME;
    fence.status = status;
    return fence;
  }
}
