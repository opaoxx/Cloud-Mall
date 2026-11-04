package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserPaymentLedgerEvidenceController;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.vo.UserAccountEvidenceVO;
import com.cloudmall.user.domain.vo.UserLinkedRefundEvidenceVO;
import com.cloudmall.user.domain.vo.UserPaymentLedgerEvidenceVO;
import com.cloudmall.user.domain.vo.UserRefundEvidenceVO;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
import com.cloudmall.user.domain.vo.UserTccStateVO;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.service.impl.evidence.UserLinkedRefundEvidenceReadService;
import com.cloudmall.user.service.impl.evidence.UserPaymentLedgerEvidenceReadService;
import com.cloudmall.user.service.impl.evidence.UserPaymentLedgerEvidenceService;
import com.cloudmall.user.service.impl.evidence.UserTccReconciliationReadService;
import com.cloudmall.user.service.impl.evidence.UserTccReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 关联退款候选全核验、源保留、上限和无资金动作的行为回归。 */
class UserPaymentLedgerEvidenceTest {
  @Test
  void localAndRefundFlagsAuthenticationRangeAndContextGatesAreBeforeReaders() {
    var base = mock(UserTccReconciliationReadService.class);
    var reader = mock(UserPaymentLedgerEvidenceReadService.class);
    var gate = new UserTccReconciliationService(base, true, "ops-secret");
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new UserPaymentLedgerEvidenceService(gate, reader, false)
                        .inspect(7L, "pay1", null, null))
            .getHttpStatus());
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new UserPaymentLedgerEvidenceService(
                            new UserTccReconciliationService(base, false, "ops-secret"),
                            reader,
                            true)
                        .inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    var service = new UserPaymentLedgerEvidenceService(gate, reader, true);
    assertEquals(
        403,
        assertThrows(BizException.class, () -> service.inspect(7L, "pay1", "wrong", "ops-secret"))
            .getHttpStatus());
    assertEquals(
        400,
        assertThrows(
                BizException.class,
                () -> service.inspect(0L, "pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    assertEquals(
        400,
        assertThrows(
                BizException.class,
                () -> service.inspect(7L, "bad%", "cm18-reconciliation", "ops-secret"))
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
      RootContext.bind("private-xid");
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () -> service.inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    } finally {
      RootContext.unbind();
    }
    verifyNoInteractions(base, reader);
  }

  @Test
  void fullAndPartialRefundTotalsExcludeOriginalDebitAndKeepClassification() {
    for (String amount : List.of("5.00", "10.00")) {
      Fixture fixture = new Fixture();
      fixture.addRefund("private-refund-key", amount);
      var report = fixture.inspect();
      assertTrue(report.candidatesComplete);
      assertEquals(1, report.validatedDebitCount);
      assertEquals(1, report.validatedRefundCount);
      assertEquals(amount, report.validatedRefundTotalAmount);
      assertEquals(
          "5.00".equals(amount) ? "BLOCKED_LOCAL_EVIDENCE" : "BLOCKED_EXTERNAL_EVIDENCE",
          report.classification);
      assertFalse(report.automaticActionsAllowed);
      assertTrue(report.otherPaymentRowsNotInspected);
      assertTrue(report.accountCausalityNotProven);
    }
  }

  @Test
  void unlinkedOrWrongOriginCannotBeInferredFromSamePaymentIdentity() {
    for (String link : new String[] {null, "wrong-private-origin"}) {
      Fixture fixture = new Fixture();
      fixture.addRefund("private-refund-key", "10.00");
      fixture.candidates.get(1).originalOperationKey = link;
      var report = fixture.inspect();
      assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
      assertTrue(report.reasonCodes.contains("PAYMENT_REFUND_ORIGIN_UNPROVEN"));
      assertNull(report.validatedRefundTotalAmount);
      assertNull(report.vectorSha256);
    }
  }

  @Test
  void sourceConflictsAndIncompleteEvidenceSkipNewPaymentQueries() {
    for (String classification : List.of("LOCAL_CONFLICT", "BLOCKED_LOCAL_EVIDENCE")) {
      Fixture fixture = new Fixture();
      fixture.source =
          new UserLinkedRefundEvidenceVO(
              classification, List.of("SOURCE_GAP"), fixture.base, false, null, false, null, null);
      var report = fixture.inspect();
      assertFalse(report.queryAttempted);
      assertNull(report.vectorSha256);
      verifyNoInteractions(fixture.ledgers);
    }
    Fixture missing = new Fixture();
    missing.base = base("BLOCKED_EXTERNAL_EVIDENCE", List.of(), "CONFIRMED");
    missing.source =
        new UserLinkedRefundEvidenceVO(
            "BLOCKED_EXTERNAL_EVIDENCE", List.of(), missing.base, true, 0, true, null, null);
    assertFalse(missing.inspect().queryAttempted);
    verifyNoInteractions(missing.ledgers);
  }

  @Test
  void rowIdentityStateMoneyAndDeltaMustHoldForEveryLedgerRow() {
    for (String mutation :
        List.of(
            "user",
            "pay",
            "order",
            "key",
            "type",
            "state",
            "amount",
            "nullAmount",
            "before",
            "after",
            "delta")) {
      Fixture fixture = new Fixture();
      fixture.addRefund("private-refund-key", "10.00");
      var row = fixture.candidates.get(1);
      switch (mutation) {
        case "user" -> row.userId = 8L;
        case "pay" -> row.payNo = "PAY1";
        case "order" -> row.orderNo = "other";
        case "key" -> row.operationKey = " ";
        case "type" -> row.operationType = "refund";
        case "state" -> row.state = "applied";
        case "amount" -> row.amount = BigDecimal.ZERO;
        case "nullAmount" -> row.amount = null;
        case "before" -> row.balanceBefore = new BigDecimal("-1");
        case "after" -> row.balanceAfter = null;
        case "delta" -> row.balanceAfter = new BigDecimal("99");
        default -> throw new AssertionError(mutation);
      }
      var report = fixture.inspect();
      assertEquals(
          List.of("nullAmount", "after").contains(mutation)
              ? "BLOCKED_LOCAL_EVIDENCE"
              : "LOCAL_CONFLICT",
          report.classification,
          mutation);
      assertNull(report.vectorSha256);
    }
  }

  @Test
  void canonicalOriginalDebitMustExistAndCannotBeMixedWithTryingOrCancelled() {
    Fixture missing = new Fixture();
    missing.candidates.clear();
    assertTrue(missing.inspect().reasonCodes.contains("PAYMENT_LEDGER_DEBIT_MISSING"));
    for (String mutation : List.of("key", "amount", "origin")) {
      Fixture fixture = new Fixture();
      var row = fixture.candidates.get(0);
      switch (mutation) {
        case "key" -> row.operationKey = "other-debit";
        case "amount" -> {
          row.amount = new BigDecimal("9");
          row.balanceAfter = new BigDecimal("91");
        }
        case "origin" -> row.originalOperationKey = "wrong";
        default -> throw new AssertionError(mutation);
      }
      assertEquals("LOCAL_CONFLICT", fixture.inspect().classification);
    }
    for (String state : List.of("TRYING", "CANCELLED")) {
      Fixture fixture = new Fixture();
      fixture.base = base("BLOCKED_EXTERNAL_EVIDENCE", List.of(), state);
      fixture.refresh();
      assertEquals("LOCAL_CONFLICT", fixture.inspect().classification);
      fixture.candidates.clear();
      assertTrue(fixture.inspect().candidatesComplete);
      assertEquals(0, fixture.inspect().validatedDebitCount);
    }
  }

  @Test
  void duplicateAndGlobalKeyCardinalityDoNotBecomeCompleteCandidates() {
    for (Long count : new Long[] {null, 0L, -1L, 2L}) {
      Fixture fixture = new Fixture();
      when(fixture.ledgers.countOperationCandidates("pay1")).thenReturn(count);
      assertEquals(
          count != null && count > 1 ? "LOCAL_CONFLICT" : "BLOCKED_LOCAL_EVIDENCE",
          fixture.inspect().classification);
      assertNull(fixture.inspect().vectorSha256);
    }
    Fixture duplicate = new Fixture();
    duplicate.candidates.add(duplicate.candidates.get(0));
    assertEquals("LOCAL_CONFLICT", duplicate.inspect().classification);
  }

  @Test
  void paymentAndOriginalLinkedRefundCountAndTotalMustBothAgree() {
    for (String mutation : List.of("count", "total", "excess")) {
      Fixture fixture = new Fixture();
      fixture.addRefund("private-refund-key", "10.00");
      if ("count".equals(mutation))
        fixture.source =
            new UserLinkedRefundEvidenceVO(
                "BLOCKED_EXTERNAL_EVIDENCE", List.of(), fixture.base, true, 0, true, "10.00", null);
      if ("total".equals(mutation))
        fixture.source =
            new UserLinkedRefundEvidenceVO(
                "BLOCKED_EXTERNAL_EVIDENCE", List.of(), fixture.base, true, 1, true, "9.00", null);
      if ("excess".equals(mutation)) {
        fixture.candidates.get(1).amount = new BigDecimal("11.00");
        fixture.candidates.get(1).balanceAfter = new BigDecimal("101.00");
      }
      assertEquals("LOCAL_CONFLICT", fixture.inspect().classification);
    }
  }

  @Test
  void hundredRowBoundaryIncludesDebitAndSentinelNeverCountsTruncatedKeys() {
    Fixture full = new Fixture();
    for (int index = 0; index < 99; index++) full.addRefund("key-" + index, "0.01");
    var report = full.inspect();
    assertEquals(100, report.observedCount);
    assertEquals(99, report.validatedRefundCount);
    assertTrue(report.candidatesComplete);
    Fixture overflow = new Fixture();
    for (int index = 0; index < 100; index++) overflow.addRefund("key-" + index, "0.01");
    var blocked = overflow.inspect();
    assertEquals(101, blocked.observedCount);
    assertNull(blocked.vectorSha256);
    verify(overflow.ledgers).findPaymentLedgerCandidates("pay1");
    verifyNoMoreInteractions(overflow.ledgers);
  }

  @Test
  void vectorIsStableAndNoRawKeysOrExceptionsLeaveTheReport() throws Exception {
    Fixture first = new Fixture();
    first.addRefund("private-refund-a", "5.00");
    first.addRefund("private-refund-b", "5.00");
    var report = first.inspect();
    java.util.Collections.reverse(first.candidates);
    assertEquals(report.vectorSha256, first.inspect().vectorSha256);
    String json = new ObjectMapper().writeValueAsString(report);
    assertFalse(json.contains("private-refund"));
    when(first.ledgers.findPaymentLedgerCandidates("pay1"))
        .thenThrow(new DataAccessResourceFailureException("private-db-password"));
    var service =
        new UserPaymentLedgerEvidenceService(
            new UserTccReconciliationService(
                mock(UserTccReconciliationReadService.class), true, "ops-secret"),
            first.reader,
            true);
    var error =
        assertThrows(
            BizException.class,
            () -> service.inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"));
    assertEquals(503, error.getHttpStatus());
    assertFalse(error.getMessage().contains("private"));
  }

  @Test
  void httpBindingAndDatabaseFailuresKeepNoStoreAndHideErrors() throws Exception {
    var base = mock(UserTccReconciliationReadService.class);
    var reader = mock(UserPaymentLedgerEvidenceReadService.class);
    var service =
        new UserPaymentLedgerEvidenceService(
            new UserTccReconciliationService(base, true, "ops-secret"), reader, true);
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserPaymentLedgerEvidenceController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route = "/api/internal/users/7/balance/tcc-reconciliation/pay1/payment-ledger-evidence";
    mvc.perform(get(route))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            get("/api/internal/users/abc/balance/tcc-reconciliation/pay1/payment-ledger-evidence"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"));
    verifyNoInteractions(reader, base);
    when(reader.inspect(7L, "pay1"))
        .thenThrow(new DataAccessResourceFailureException("private-db-password"));
    String body =
        mvc.perform(
                get(route)
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("USER_PAYMENT_LEDGER_EVIDENCE_UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("private-db-password"));
  }

  /** 构造明确原User资金归属摘要，不输入任何外部资金参数。 */
  private static UserTccReconciliationVO base(
      String classification, List<String> reasons, String state) {
    UserTccStateVO source = new UserTccStateVO();
    source.userId = 7L;
    source.payNo = "pay1";
    source.orderNo = "202610order1";
    source.amount = "10.00";
    source.state = state;
    source.ledgerApplied = "CONFIRMED".equals(state);
    return new UserTccReconciliationVO(
        source,
        classification,
        reasons,
        new UserAccountEvidenceVO(true, "ACTIVE", "100.00", "0.00", "0.00", true),
        new UserRefundEvidenceVO(false, null, null, null, null, null, null, null),
        Map.of());
  }

  /** 明确原T11报告与原行，不根据被测实现推导错误行是否合法。 */
  private static final class Fixture {
    /** 同RR原关联报告代理替身。 */
    final UserLinkedRefundEvidenceReadService linkedReader =
        mock(UserLinkedRefundEvidenceReadService.class);

    /** 原pay候选与全局操作键COUNT替身。 */
    final BalanceLedgerMapper ledgers = mock(BalanceLedgerMapper.class);

    /** 当前原User事实。 */
    UserTccReconciliationVO base =
        base("BLOCKED_EXTERNAL_EVIDENCE", List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"), "CONFIRMED");

    /** 原关联范围报告，可显式注入源缺证。 */
    UserLinkedRefundEvidenceVO source;

    /** 原pay范围行。 */
    final List<BalanceLedgerPO> candidates = new ArrayList<>();

    /** 原关联范围已确认退款行数。 */
    int linkedCount;

    /** 原关联范围已确认退款合计。 */
    BigDecimal linkedTotal = new BigDecimal("0.00");

    /** 实际同pay校验器。 */
    final UserPaymentLedgerEvidenceReadService reader =
        new UserPaymentLedgerEvidenceReadService(linkedReader, ledgers);

    Fixture() {
      when(linkedReader.inspect(7L, "pay1")).thenAnswer(invocation -> source);
      when(ledgers.findPaymentLedgerCandidates("pay1")).thenReturn(candidates);
      BalanceLedgerPO debit = row("pay1", "10.00", "DEBIT");
      candidates.add(debit);
      when(ledgers.countOperationCandidates("pay1")).thenReturn(1L);
      refresh();
    }

    /** 原关联结果只根据合法准备值更新，变造行后不重新解释。 */
    void refresh() {
      boolean partial = linkedCount > 0 && linkedTotal.compareTo(new BigDecimal("10.00")) < 0;
      source =
          new UserLinkedRefundEvidenceVO(
              partial ? "BLOCKED_LOCAL_EVIDENCE" : "BLOCKED_EXTERNAL_EVIDENCE",
              partial
                  ? List.of("REFUND_TOTAL_PARTIAL", "EXTERNAL_EVIDENCE_NOT_QUERIED")
                  : List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"),
              base,
              true,
              linkedCount,
              true,
              linkedTotal.toPlainString(),
              null);
    }

    /** 添加已确认的原关联退款夹具。 */
    void addRefund(String key, String amount) {
      candidates.add(row(key, amount, "REFUND"));
      when(ledgers.countOperationCandidates(key)).thenReturn(1L);
      linkedCount++;
      linkedTotal = linkedTotal.add(new BigDecimal(amount));
      refresh();
    }

    /** 构造目标类型的合法原delta。 */
    static BalanceLedgerPO row(String key, String amount, String type) {
      BalanceLedgerPO row = new BalanceLedgerPO();
      row.userId = 7L;
      row.payNo = "pay1";
      row.orderNo = "202610order1";
      row.operationKey = key;
      row.operationType = type;
      row.state = "APPLIED";
      row.amount = new BigDecimal(amount);
      row.balanceBefore = new BigDecimal("100.00");
      row.balanceAfter =
          "DEBIT".equals(type)
              ? row.balanceBefore.subtract(row.amount)
              : row.balanceBefore.add(row.amount);
      row.originalOperationKey = "REFUND".equals(type) ? "pay1" : null;
      return row;
    }

    /** 只调用被测读算法。 */
    UserPaymentLedgerEvidenceVO inspect() {
      return reader.inspect(7L, "pay1");
    }
  }
}
