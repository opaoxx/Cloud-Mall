package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserTccReconciliationController;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.TccFenceLogPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.po.UserTccReceivePO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.evidence.UserTccReconciliationReadService;
import com.cloudmall.user.service.impl.evidence.UserTccReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 实际原User状态校验器加账户/冻结/退款观察，全部依赖仅读取替身。 */
class UserTccReconciliationTest {
  @Test
  void disabledAuthenticationRangeAndContextGatesNeverRead() {
    var reader = mock(UserTccReconciliationReadService.class);
    var disabled = new UserTccReconciliationService(reader, false, "ops-secret");
    assertEquals(
        503,
        assertThrows(BizException.class, () -> disabled.inspect(7L, "pay1", null, null))
            .getHttpStatus());
    var service = new UserTccReconciliationService(reader, true, "ops-secret");
    for (String[] headers :
        new String[][] {{null, null}, {"other", "ops-secret"}, {"cm18-reconciliation", "bad"}})
      assertEquals(
          403,
          assertThrows(
                  BizException.class, () -> service.inspect(7L, "pay1", headers[0], headers[1]))
              .getHttpStatus());
    for (Long userId : new Long[] {null, 0L, -1L})
      assertEquals(
          400,
          assertThrows(
                  BizException.class,
                  () -> service.inspect(userId, "pay1", "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    for (String payNo : List.of("", "bad%", "中文", "bad\nkey", "a".repeat(65)))
      assertEquals(
          400,
          assertThrows(
                  BizException.class,
                  () -> service.inspect(7L, payNo, "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () -> service.inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    } finally {
      TransactionSynchronizationManager.clear();
    }
    try {
      RootContext.bind("inherited-private-xid");
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () -> service.inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    } finally {
      RootContext.unbind();
    }
    assertEquals(
        403,
        assertThrows(
                BizException.class,
                () ->
                    new UserTccReconciliationService(reader, true, "")
                        .inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    verifyNoInteractions(reader);
  }

  @Test
  void httpErrorsHaveNoStoreAndFixedUnavailableMessage() throws Exception {
    var reader = mock(UserTccReconciliationReadService.class);
    var service = new UserTccReconciliationService(reader, true, "ops-secret");
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserTccReconciliationController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route = "/api/internal/users/7/balance/tcc-reconciliation/pay1";
    mvc.perform(get(route))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            get("/api/internal/users/0/balance/tcc-reconciliation/pay1")
                .header("X-Internal-Service", "cm18-reconciliation")
                .header("X-Internal-Callback-Token", "ops-secret"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"));
    for (String invalidId : List.of("abc", "9223372036854775808"))
      mvc.perform(
              get("/api/internal/users/" + invalidId + "/balance/tcc-reconciliation/pay1")
                  .header("X-Internal-Service", "cm18-reconciliation")
                  .header("X-Internal-Callback-Token", "ops-secret"))
          .andExpect(status().isBadRequest())
          .andExpect(header().string("Cache-Control", "no-store"));
    verifyNoInteractions(reader);
    for (int statusCode : List.of(404, 409)) {
      doThrow(new BizException("FIXTURE_CONFLICT", "固定诊断错误", statusCode))
          .when(reader)
          .inspect(7L, "pay1");
      mvc.perform(
              get(route)
                  .header("X-Internal-Service", "cm18-reconciliation")
                  .header("X-Internal-Callback-Token", "ops-secret"))
          .andExpect(status().is(statusCode))
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    doThrow(new DataAccessResourceFailureException("private-db-password"))
        .when(reader)
        .inspect(7L, "pay1");
    var response =
        mvc.perform(
                get(route)
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("USER_TCC_RECONCILIATION_UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains("private-db-password"));
  }

  @Test
  void knownStatesAndOtherTransactionsFrozenFundsRemainOnlyObserved() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
      Fixture fixture = new Fixture(state);
      fixture.account.frozenBalance = new BigDecimal("30.00");
      fixture.aggregate("30.00", 0L);
      var report = fixture.inspect();
      assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification, state);
      assertTrue(report.account.frozenProjectionMatched());
      assertFalse(report.automaticActionsAllowed);
      assertEquals("30.00", report.account.tryingFrozenTotal());
      assertEquals(state, report.tccState);
      assertEquals("USER_ACCOUNT_FROZEN_REFUND_OBSERVED_ONLY", report.scope);
      assertTrue(report.otherRefundsNotInspected);
      assertFalse(report.refund.present());
      verify(fixture.receives).findByPayNo("pay-query-1");
      verify(fixture.receives).findByOperationKey("tcc-debit:pay-query-1");
      verify(fixture.users).selectBalanceEvidenceById(7L);
      verify(fixture.debits).selectFrozenEvidence(7L);
      verify(fixture.attempts).selectByPayNo("pay-query-1");
      verify(fixture.debits).selectByPayNo("pay-query-1");
      verify(fixture.ledgers).selectByOperationKey("pay-query-1");
      verify(fixture.ledgers).selectByOperationKey("refund:pay-query-1");
      if ("CONFIRMED".equals(state))
        verify(fixture.ledgers).findDebitCandidatesByPayNo("pay-query-1");
      else verify(fixture.ledgers).findDebitCandidatesByPayNo("pay-query-1");
      if (!"CONFIRMED".equals(state)) {
        verify(fixture.ledgers).findPaymentLedgerCandidates("pay-query-1");
      }
      verify(fixture.fences).findByBranch(fixture.receive.xid, 11L);
      verifyNoMoreInteractions(
          fixture.receives,
          fixture.users,
          fixture.debits,
          fixture.ledgers,
          fixture.attempts,
          fixture.fences);
    }
  }

  @Test
  void missingFundsAndMissingAccountsStayIncomplete() {
    Fixture unknown = new Fixture("UNKNOWN");
    assertEquals("BLOCKED_LOCAL_EVIDENCE", unknown.inspect().classification);
    assertTrue(unknown.inspect().reasonCodes.contains("USER_TCC_FUNDS_EVIDENCE_MISSING"));
    Fixture absent = new Fixture("CONFIRMED");
    when(absent.users.selectBalanceEvidenceById(7L)).thenReturn(null);
    assertTrue(absent.inspect().reasonCodes.contains("ACCOUNT_ROW_MISSING"));
    assertNull(absent.inspect().account.balance());
    Fixture missingMoney = new Fixture("CONFIRMED");
    missingMoney.account.balance = null;
    assertEquals("BLOCKED_LOCAL_EVIDENCE", missingMoney.inspect().classification);
  }

  @Test
  void invalidReceiveCannotProvideAnAnchorOrReadOtherSources() {
    for (String variant :
        List.of(
            "pay",
            "user",
            "operation",
            "ledger",
            "xid",
            "amount",
            "zero",
            "order",
            "state",
            "branch",
            "absent")) {
      Fixture fixture = new Fixture("CONFIRMED");
      switch (variant) {
        case "pay" -> fixture.receive.payNo = "PAY-query-1";
        case "user" -> fixture.receive.userId = 8L;
        case "operation" -> fixture.receive.operationKey = "tcc-debit:other";
        case "ledger" -> fixture.receive.ledgerKey = "other";
        case "xid" -> fixture.receive.xid = " ";
        case "amount" -> fixture.receive.amount = new BigDecimal("1.001");
        case "zero" -> fixture.receive.amount = BigDecimal.ZERO;
        case "order" -> fixture.receive.orderNo = " ";
        case "state" -> fixture.receive.state = "claimed";
        case "branch" -> fixture.receive.branchId = 0L;
        case "absent" -> when(fixture.receives.findByPayNo("pay-query-1")).thenReturn(null);
        default -> throw new AssertionError(variant);
      }
      assertEquals(
          "absent".equals(variant) ? 404 : 409,
          assertThrows(BizException.class, fixture::inspect).getHttpStatus(),
          variant);
      verify(fixture.receives).findByPayNo("pay-query-1");
      verifyNoMoreInteractions(fixture.receives);
      verifyNoInteractions(
          fixture.users, fixture.attempts, fixture.debits, fixture.ledgers, fixture.fences);
    }
  }

  @Test
  void aggregateMetadataAndBadOriginalRowsCannotAuthorizeAProjection() {
    for (Map<String, Object> aggregate :
        List.<Map<String, Object>>of(
            Map.of(),
            Map.of("frozen_total", 0.0, "invalid_row_count", 0L),
            Map.of("frozen_total", BigDecimal.ZERO, "invalid_row_count", 0),
            Map.of("frozen_total", new BigDecimal("-1"), "invalid_row_count", 0L))) {
      Fixture fixture = new Fixture("CONFIRMED");
      when(fixture.debits.selectFrozenEvidence(7L)).thenReturn(aggregate);
      assertEquals("BLOCKED_LOCAL_EVIDENCE", fixture.inspect().classification);
      assertNull(fixture.inspect().account.frozenProjectionMatched());
    }
    Fixture badRows = new Fixture("CONFIRMED");
    badRows.aggregate("-1", 1L);
    assertEquals("LOCAL_CONFLICT", badRows.inspect().classification);
    assertTrue(badRows.inspect().reasonCodes.contains("FROZEN_SOURCE_ROWS_INVALID"));
    Fixture mismatch = new Fixture("CONFIRMED");
    mismatch.aggregate("10.00", 0L);
    assertTrue(mismatch.inspect().reasonCodes.contains("FROZEN_PROJECTION_CONFLICT"));
    assertFalse(mismatch.inspect().account.frozenProjectionMatched());
  }

  @Test
  void disabledAccountIsValidButNegativeMoneyOrUnknownStatusIsConflict() {
    Fixture disabled = new Fixture("CONFIRMED");
    disabled.account.status = 0;
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", disabled.inspect().classification);
    assertEquals("DISABLED", disabled.inspect().account.status());
    for (String variant : List.of("balance", "frozen", "status", "identity")) {
      Fixture fixture = new Fixture("CONFIRMED");
      switch (variant) {
        case "balance" -> fixture.account.balance = new BigDecimal("-1");
        case "frozen" -> fixture.account.frozenBalance = new BigDecimal("-1");
        case "status" -> fixture.account.status = 3;
        case "identity" -> fixture.account.id = 9L;
        default -> throw new AssertionError(variant);
      }
      assertEquals("LOCAL_CONFLICT", fixture.inspect().classification, variant);
    }
  }

  @Test
  void fullAndPartialRefundsRespectOriginalMoneyWithoutComparingCurrentBalance() {
    Fixture full = new Fixture("CONFIRMED");
    full.refund("10.00");
    full.account.balance = new BigDecimal("200.00");
    var observed = full.inspect();
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", observed.classification);
    assertTrue(observed.refund.identityMatched());
    assertTrue(observed.otherRefundsNotInspected);
    Fixture partial = new Fixture("CONFIRMED");
    partial.refund("5.00");
    assertEquals("BLOCKED_LOCAL_EVIDENCE", partial.inspect().classification);
    assertTrue(partial.inspect().reasonCodes.contains("REFUND_PARTIAL_OBSERVED"));
    partial.refund.balanceBefore = null;
    assertTrue(partial.inspect().reasonCodes.contains("REFUND_BALANCE_BEFORE_MISSING"));
  }

  @Test
  void invalidRefundIdentityMoneyStateAndDeltaAreConflict() {
    for (String variant :
        List.of(
            "user",
            "pay",
            "order",
            "key",
            "original",
            "type",
            "state",
            "zero",
            "over",
            "negative",
            "before",
            "after",
            "delta")) {
      Fixture fixture = new Fixture("CONFIRMED");
      fixture.refund("10.00");
      switch (variant) {
        case "user" -> fixture.refund.userId = 8L;
        case "pay" -> fixture.refund.payNo = "PAY-query-1";
        case "order" -> fixture.refund.orderNo = "wrong";
        case "key" -> fixture.refund.operationKey = "refund:PAY-query-1";
        case "original" -> fixture.refund.originalOperationKey = "wrong";
        case "type" -> fixture.refund.operationType = "DEBIT";
        case "state" -> fixture.refund.state = "private-unknown";
        case "zero" -> fixture.refund.amount = BigDecimal.ZERO;
        case "over" -> fixture.refund.amount = new BigDecimal("11");
        case "negative" -> fixture.refund.amount = new BigDecimal("-1");
        case "before" -> fixture.refund.balanceBefore = new BigDecimal("-1");
        case "after" -> fixture.refund.balanceAfter = new BigDecimal("-1");
        case "delta" -> fixture.refund.balanceAfter = new BigDecimal("101");
        default -> throw new AssertionError(variant);
      }
      assertEquals("LOCAL_CONFLICT", fixture.inspect().classification, variant);
    }
  }

  @Test
  void refundNeedsOriginalConfirmedEvidenceAndKeepsKnownOppositeStates() {
    for (String state : List.of("TRYING", "CANCELLED", "UNKNOWN")) {
      Fixture fixture = new Fixture(state);
      fixture.refund("10.00");
      assertEquals(
          "UNKNOWN".equals(state) ? "BLOCKED_LOCAL_EVIDENCE" : "LOCAL_CONFLICT",
          fixture.inspect().classification,
          state);
    }
  }

  @Test
  void reportsAreImmutableRedactedAndCarryShanghaiTime() throws Exception {
    Fixture fixture = new Fixture("CONFIRMED");
    fixture.refund("10.00");
    var report = fixture.inspect();
    String json = new ObjectMapper().writeValueAsString(report);
    for (String secret :
        List.of(
            fixture.receive.xid,
            fixture.receive.operationKey,
            "refund:pay-query-1",
            "private-password",
            "private-phone",
            "private-role")) assertFalse(json.contains(secret), secret);
    assertEquals(ZoneOffset.ofHours(8), OffsetDateTime.parse(report.collectedAt).getOffset());
    assertThrows(UnsupportedOperationException.class, () -> report.reasonCodes.add("fake"));
    assertThrows(
        UnsupportedOperationException.class, () -> report.identifiers.put("fake", "secret"));
    fixture.account.balance = BigDecimal.ZERO;
    fixture.refund.amount = BigDecimal.ZERO;
    assertEquals("100.00", report.account.balance());
    assertEquals("10.00", report.refund.amount());
    assertTrue(report.identifiers.get("xidSha256").matches("[0-9a-f]{64}"));
  }

  /** 三个原点读只把直接或标准直接包装的多结果分类为持久事实409。 */
  @Test
  void baselineDuplicatePointsHaveFixedConflictAndOriginalReadOrder() {
    for (String point : List.of("receive", "account", "refund")) {
      for (boolean wrapped : List.of(false, true)) {
        var fixture = new Fixture("CONFIRMED");
        RuntimeException duplicate =
            new org.apache.ibatis.exceptions.TooManyResultsException("private-duplicate");
        if (wrapped) duplicate = new org.mybatis.spring.MyBatisSystemException(duplicate);
        throwAtBaselinePoint(fixture, point, duplicate);
        clearInvocations(
            fixture.receives,
            fixture.attempts,
            fixture.debits,
            fixture.ledgers,
            fixture.fences,
            fixture.users);
        var failure = assertThrows(BizException.class, fixture::inspect);
        assertEquals("USER_TCC_RECONCILIATION_CONFLICT", failure.getCode());
        assertEquals("本地User资金证据不唯一", failure.getMessage());
        assertEquals(409, failure.getHttpStatus());
        verify(fixture.receives).findByPayNo("pay-query-1");
        if ("receive".equals(point)) {
          verifyNoMoreInteractions(fixture.receives);
          verifyNoInteractions(
              fixture.users, fixture.attempts, fixture.debits, fixture.ledgers, fixture.fences);
        } else {
          verify(fixture.users).selectBalanceEvidenceById(7L);
          if ("account".equals(point)) {
            verify(fixture.debits, never()).selectFrozenEvidence(any());
            verify(fixture.ledgers, never()).selectByOperationKey("refund:pay-query-1");
          } else {
            verify(fixture.debits).selectFrozenEvidence(7L);
            verify(fixture.ledgers).selectByOperationKey("refund:pay-query-1");
          }
        }
      }
    }
  }

  /** 深包装、其它DAO/Runtime仍按原对象传播，外层数据库503语义不变。 */
  @Test
  void otherBaselinePointFailuresRemainUnchanged() {
    for (String point : List.of("receive", "account", "refund")) {
      var duplicate = new org.apache.ibatis.exceptions.TooManyResultsException("private-duplicate");
      for (RuntimeException original :
          List.of(
              new org.mybatis.spring.MyBatisSystemException(new IllegalStateException(duplicate)),
              new org.mybatis.spring.MyBatisSystemException(
                  new IllegalStateException("private-other")),
              new DataAccessResourceFailureException("private-database"),
              new IllegalStateException("private-runtime"))) {
        var fixture = new Fixture("CONFIRMED");
        throwAtBaselinePoint(fixture, point, original);
        assertSame(original, assertThrows(RuntimeException.class, fixture::inspect));
        var guarded = new UserTccReconciliationService(fixture.reader, true, "ops-secret");
        if (original instanceof org.springframework.dao.DataAccessException) {
          var failure =
              assertThrows(
                  BizException.class,
                  () -> guarded.inspect(7L, "pay-query-1", "cm18-reconciliation", "ops-secret"));
          assertEquals("USER_TCC_RECONCILIATION_UNAVAILABLE", failure.getCode());
          assertEquals(503, failure.getHttpStatus());
        } else
          assertSame(
              original,
              assertThrows(
                  RuntimeException.class,
                  () -> guarded.inspect(7L, "pay-query-1", "cm18-reconciliation", "ops-secret")));
      }
    }
  }

  /** 在固定的原点读注入同一异常，不影响前面的真实校验器替身。 */
  private void throwAtBaselinePoint(Fixture fixture, String point, RuntimeException failure) {
    switch (point) {
      case "receive" -> doThrow(failure).when(fixture.receives).findByPayNo("pay-query-1");
      case "account" -> doThrow(failure).when(fixture.users).selectBalanceEvidenceById(7L);
      case "refund" ->
          doThrow(failure).when(fixture.ledgers).selectByOperationKey("refund:pay-query-1");
      default -> throw new AssertionError(point);
    }
  }

  /** 固定原TCC请求的完整替身，只允许由实际读服务调用SELECT。 */
  private final class Fixture {
    /** 接收来源。 */
    final UserTccReceiveMapper receives = mock(UserTccReceiveMapper.class);

    /** 归属来源。 */
    final BalanceDebitAttemptMapper attempts = mock(BalanceDebitAttemptMapper.class);

    /** 冻结来源。 */
    final BalanceTccDebitMapper debits = mock(BalanceTccDebitMapper.class);

    /** 账本来源。 */
    final BalanceLedgerMapper ledgers = mock(BalanceLedgerMapper.class);

    /** Fence来源。 */
    final TccFenceLogMapper fences = mock(TccFenceLogMapper.class);

    /** 账户资金投影来源。 */
    final UserMapper users = mock(UserMapper.class);

    /** 原接收载荷。 */
    final UserTccReceivePO receive = receive(11L);

    /** 当前账户资金样本。 */
    final UserPO account = new UserPO();

    /** 固定退款样本，存在时使用。 */
    BalanceLedgerPO refund;

    /** 实际原校验器和新读服务，未持有RM或写入资源。 */
    final UserTccReconciliationReadService reader =
        new UserTccReconciliationReadService(receives, attempts, debits, ledgers, fences, users);

    Fixture(String state) {
      when(receives.findByPayNo("pay-query-1")).thenReturn(receive);
      when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
      if (!"UNKNOWN".equals(state)) {
        when(attempts.selectByPayNo(receive.payNo)).thenReturn(attempt(state, 11L));
        when(debits.selectByPayNo(receive.payNo)).thenReturn(debit(state, 11L));
        when(fences.findByBranch(receive.xid, 11L))
            .thenReturn(fence("TRYING".equals(state) ? 1 : "CONFIRMED".equals(state) ? 2 : 3));
      }
      if ("CONFIRMED".equals(state)) {
        BalanceLedgerPO originalLedger = ledger();
        when(ledgers.selectByOperationKey(receive.ledgerKey)).thenReturn(originalLedger);
        when(ledgers.selectDebitByPayNo(receive.payNo)).thenReturn(originalLedger);
        when(ledgers.findDebitCandidatesByPayNo(receive.payNo)).thenReturn(List.of(originalLedger));
      }
      account.id = 7L;
      account.balance = new BigDecimal("100.00");
      account.frozenBalance = new BigDecimal("TRYING".equals(state) ? "10.00" : "0.00");
      account.status = 1;
      account.passwordHash = "private-password";
      account.phone = "private-phone";
      account.role = "private-role";
      when(users.selectBalanceEvidenceById(7L)).thenReturn(account);
      aggregate(account.frozenBalance.toPlainString(), 0L);
    }

    void aggregate(String total, long invalidRows) {
      when(debits.selectFrozenEvidence(7L))
          .thenReturn(
              Map.of("frozen_total", new BigDecimal(total), "invalid_row_count", invalidRows));
    }

    void refund(String amount) {
      refund = new BalanceLedgerPO();
      refund.userId = 7L;
      refund.payNo = receive.payNo;
      refund.orderNo = receive.orderNo;
      refund.operationKey = "refund:" + receive.payNo;
      refund.originalOperationKey = receive.payNo;
      refund.operationType = "REFUND";
      refund.state = "APPLIED";
      refund.amount = new BigDecimal(amount);
      refund.balanceBefore = new BigDecimal("90.00");
      refund.balanceAfter = refund.balanceBefore.add(refund.amount);
      when(ledgers.selectByOperationKey(refund.operationKey)).thenReturn(refund);
    }

    com.cloudmall.user.domain.vo.UserTccReconciliationVO inspect() {
      return reader.inspect(7L, "pay-query-1");
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

  private TccFenceLogPO fence(int status) {
    TccFenceLogPO fence = new TccFenceLogPO();
    fence.xid = "xid-query-1";
    fence.branchId = 11L;
    fence.actionName = BalanceTccDebitLocalResource.ACTION_NAME;
    fence.status = status;
    return fence;
  }
}
