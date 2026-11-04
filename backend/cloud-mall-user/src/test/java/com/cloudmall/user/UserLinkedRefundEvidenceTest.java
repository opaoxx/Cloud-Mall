package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserLinkedRefundEvidenceController;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.vo.UserAccountEvidenceVO;
import com.cloudmall.user.domain.vo.UserRefundEvidenceVO;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
import com.cloudmall.user.domain.vo.UserTccStateVO;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.service.impl.evidence.UserLinkedRefundEvidenceReadService;
import com.cloudmall.user.service.impl.evidence.UserLinkedRefundEvidenceService;
import com.cloudmall.user.service.impl.evidence.UserTccReconciliationReadService;
import com.cloudmall.user.service.impl.evidence.UserTccReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 关联退款候选全核验、源保留、上限和无资金动作的行为回归。 */
class UserLinkedRefundEvidenceTest {
  @Test
  void localAndRefundFlagsAuthenticationRangeAndContextGatesAreBeforeReaders() {
    var base = mock(UserTccReconciliationReadService.class);
    var reader = mock(UserLinkedRefundEvidenceReadService.class);
    var gate = new UserTccReconciliationService(base, true, "ops-secret");
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new UserLinkedRefundEvidenceService(gate, reader, false)
                        .inspect(7L, "pay1", null, null))
            .getHttpStatus());
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new UserLinkedRefundEvidenceService(
                            new UserTccReconciliationService(base, false, "ops-secret"),
                            reader,
                            true)
                        .inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    var service = new UserLinkedRefundEvidenceService(gate, reader, true);
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
  void validMultiKeyFullAndPartialRefundsRetainTheCorrectScope() {
    Fixture fixture = new Fixture();
    fixture.add("custom-a", "4.00");
    fixture.add("custom-b", "6.00");
    var full = fixture.inspect();
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", full.classification);
    assertEquals("10.00", full.validatedTotalAmount);
    assertTrue(full.candidatesComplete);
    assertFalse(full.automaticActionsAllowed);
    assertTrue(full.unlinkedLedgerRowsNotInspected);
    assertEquals("USER_LINKED_REFUNDS_OBSERVED_ONLY", full.scope);
    assertEquals(2, full.observedCount);
    Fixture partial = new Fixture();
    partial.add("custom-a", "4.00");
    var report = partial.inspect();
    assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
    assertTrue(report.reasonCodes.contains("REFUND_TOTAL_PARTIAL"));
    assertTrue(report.candidatesComplete);
    assertEquals("4.00", report.validatedTotalAmount);
    assertNotNull(report.vectorSha256);
    verify(fixture.ledgers).findLinkedRefundCandidates("pay1");
    verify(fixture.ledgers).countOperationCandidates("custom-a");
    verify(fixture.ledgers).countOperationCandidates("custom-b");
    verifyNoMoreInteractions(fixture.ledgers);
  }

  @Test
  void boundedEmptyAndOverflowSetsNeverClaimUnlinkedRefundAbsence() {
    Fixture empty = new Fixture();
    var report = empty.inspect();
    assertEquals("0.00", report.validatedTotalAmount);
    assertTrue(report.unlinkedLedgerRowsNotInspected);
    assertTrue(report.candidatesComplete);
    Fixture overflow = new Fixture();
    for (int index = 0; index < 101; index++) overflow.add("custom-" + index, "0.01");
    var blocked = overflow.inspect();
    assertEquals("BLOCKED_LOCAL_EVIDENCE", blocked.classification);
    assertEquals(101, blocked.observedCount);
    assertFalse(blocked.candidatesComplete);
    assertNull(blocked.validatedTotalAmount);
    assertNull(blocked.vectorSha256);
    verify(overflow.ledgers).findLinkedRefundCandidates("pay1");
    verifyNoMoreInteractions(overflow.ledgers);
  }

  @Test
  void tupleTypeStateAndKeyProblemsCannotBeHiddenByAnAmountSum() {
    for (String variant :
        List.of("user", "pay", "order", "original", "key", "blank", "type", "state", "duplicate")) {
      Fixture fixture = new Fixture();
      fixture.add("custom-a", "5.00");
      var candidate = fixture.candidates.get(0);
      switch (variant) {
        case "user" -> candidate.userId = 8L;
        case "pay" -> candidate.payNo = "PAY1";
        case "order" -> candidate.orderNo = "wrong";
        case "original" -> candidate.originalOperationKey = "PAY1";
        case "key" -> candidate.operationKey = "pay1";
        case "blank" -> candidate.operationKey = " ";
        case "type" -> candidate.operationType = "refund";
        case "state" -> candidate.state = "applied";
        case "duplicate" -> fixture.candidates.add(candidate);
        default -> throw new AssertionError(variant);
      }
      var report = fixture.inspect();
      assertEquals("LOCAL_CONFLICT", report.classification, variant);
      assertNull(report.validatedTotalAmount);
      assertNull(report.vectorSha256);
    }
  }

  @Test
  void moneyMissingOrInvalidDeltaAndTotalsRemainUnprovenOrConflict() {
    for (String variant :
        List.of(
            "nullAmount",
            "nullBefore",
            "nullAfter",
            "negative",
            "zero",
            "scale",
            "before",
            "delta",
            "over")) {
      Fixture fixture = new Fixture();
      fixture.add("custom-a", "5.00");
      var candidate = fixture.candidates.get(0);
      switch (variant) {
        case "nullAmount" -> candidate.amount = null;
        case "nullBefore" -> candidate.balanceBefore = null;
        case "nullAfter" -> candidate.balanceAfter = null;
        case "negative" -> candidate.amount = new BigDecimal("-1");
        case "zero" -> candidate.amount = BigDecimal.ZERO;
        case "scale" -> candidate.amount = new BigDecimal("1.001");
        case "before" -> candidate.balanceBefore = new BigDecimal("-1");
        case "delta" -> candidate.balanceAfter = new BigDecimal("101");
        case "over" -> {
          candidate.amount = new BigDecimal("11.00");
          candidate.balanceAfter = candidate.balanceBefore.add(candidate.amount);
        }
        default -> throw new AssertionError(variant);
      }
      var report = fixture.inspect();
      assertEquals(
          variant.startsWith("null") ? "BLOCKED_LOCAL_EVIDENCE" : "LOCAL_CONFLICT",
          report.classification,
          variant);
      assertFalse(report.candidatesComplete);
      assertNull(report.validatedTotalAmount);
      assertNull(report.vectorSha256);
    }
  }

  @Test
  void globalCiKeyCardinalityAndDuplicateOriginalOperationsAreRequired() {
    for (Long count : new Long[] {null, 0L, -1L, 2L}) {
      Fixture fixture = new Fixture();
      fixture.add("custom-a", "5.00");
      when(fixture.ledgers.countOperationCandidates("custom-a")).thenReturn(count);
      var report = fixture.inspect();
      assertEquals(
          Long.valueOf(2).equals(count) ? "LOCAL_CONFLICT" : "BLOCKED_LOCAL_EVIDENCE",
          report.classification);
      assertNull(report.vectorSha256);
      assertNull(report.validatedTotalAmount);
    }
  }

  @Test
  void onlyFixedPartialCanBeResolvedAndOtherSourceGapsNeverUpgrade() {
    Fixture resolved = new Fixture();
    resolved.base =
        base(
            "BLOCKED_LOCAL_EVIDENCE",
            List.of("REFUND_PARTIAL_OBSERVED", "EXTERNAL_EVIDENCE_NOT_QUERIED"),
            "CONFIRMED");
    resolved.add("refund:pay1", "4.00");
    resolved.add("custom-b", "6.00");
    var report = resolved.inspect();
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
    assertEquals("BLOCKED_LOCAL_EVIDENCE", report.baseEvidence.classification);
    for (List<String> reasons :
        List.of(
            List.of("ACCOUNT_ROW_MISSING"),
            List.of("REFUND_PARTIAL_OBSERVED", "ACCOUNT_ROW_MISSING"),
            List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"))) {
      Fixture missing = new Fixture();
      missing.base = base("BLOCKED_LOCAL_EVIDENCE", reasons, "CONFIRMED");
      missing.add("custom-a", "10.00");
      var blocked = missing.inspect();
      assertEquals("BLOCKED_LOCAL_EVIDENCE", blocked.classification);
      assertNull(blocked.validatedTotalAmount);
      assertNull(blocked.vectorSha256);
    }
    Fixture conflicted = new Fixture();
    conflicted.base = base("LOCAL_CONFLICT", List.of("FROZEN_PROJECTION_CONFLICT"), "CONFIRMED");
    assertFalse(conflicted.inspect().queryAttempted);
    verifyNoInteractions(conflicted.ledgers);
  }

  @Test
  void malformedOrMissingOriginalAmountKeepsSourceBlockedWithoutQueryingCandidates() {
    for (String amount :
        new String[] {null, "bad-money", "0.00", "-1.00", "1.001", "10000000000000000.00"}) {
      Fixture fixture = new Fixture();
      UserTccStateVO state = new UserTccStateVO();
      state.payNo = "pay1";
      state.userId = 7L;
      state.orderNo = "202610order1";
      state.state = "CONFIRMED";
      state.ledgerApplied = true;
      state.amount = amount;
      fixture.base =
          new UserTccReconciliationVO(
              state,
              "BLOCKED_LOCAL_EVIDENCE",
              List.of("SOURCE_AMOUNT_MISSING"),
              fixture.base.account,
              fixture.base.refund,
              Map.of());
      var report = fixture.inspect();
      assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
      assertFalse(report.queryAttempted);
      assertNull(report.validatedTotalAmount);
      assertNull(report.vectorSha256);
      verifyNoInteractions(fixture.ledgers);
    }
  }

  @Test
  void originalConfirmedDebitIsRequiredForNonemptyRefunds() {
    for (String state : List.of("UNKNOWN", "TRYING", "CANCELLED")) {
      Fixture fixture = new Fixture();
      fixture.base =
          base("BLOCKED_LOCAL_EVIDENCE", List.of("USER_TCC_FUNDS_EVIDENCE_MISSING"), state);
      fixture.add("custom-a", "5.00");
      var report = fixture.inspect();
      assertEquals(
          "UNKNOWN".equals(state) ? "BLOCKED_LOCAL_EVIDENCE" : "LOCAL_CONFLICT",
          report.classification,
          state);
      assertNull(report.vectorSha256);
    }
  }

  @Test
  void vectorIsOrderIndependentImmutableAndDoesNotExposeKeys() throws Exception {
    Fixture first = new Fixture();
    first.add("custom-a", "4.00");
    first.add("custom-b", "6.00");
    Fixture reverse = new Fixture();
    reverse.add("custom-b", "6.00");
    reverse.add("custom-a", "4.00");
    var report = first.inspect();
    assertEquals(report.vectorSha256, reverse.inspect().vectorSha256);
    List<String> expected =
        new ArrayList<>(
            List.of(
                sha("custom-a") + ":4.00:90.00:94.00;", sha("custom-b") + ":6.00:90.00:96.00;"));
    expected.sort(String::compareTo);
    assertEquals(sha(String.join("", expected)), report.vectorSha256);
    String json = new ObjectMapper().writeValueAsString(report);
    assertFalse(json.contains("custom-a"));
    assertFalse(json.contains("custom-b"));
    assertThrows(UnsupportedOperationException.class, () -> report.reasonCodes.add("fake"));
    first.candidates.get(0).amount = BigDecimal.ZERO;
    assertEquals("10.00", report.validatedTotalAmount);
  }

  @Test
  void httpBindingAndDatabaseFailuresKeepNoStoreAndHideErrors() throws Exception {
    var base = mock(UserTccReconciliationReadService.class);
    var reader = mock(UserLinkedRefundEvidenceReadService.class);
    var service =
        new UserLinkedRefundEvidenceService(
            new UserTccReconciliationService(base, true, "ops-secret"), reader, true);
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserLinkedRefundEvidenceController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route = "/api/internal/users/7/balance/tcc-reconciliation/pay1/refund-evidence";
    mvc.perform(get(route))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/api/internal/users/abc/balance/tcc-reconciliation/pay1/refund-evidence"))
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
            .andExpect(jsonPath("$.code").value("USER_LINKED_REFUND_EVIDENCE_UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("private-db-password"));
  }

  /** 独立于应用实现生成向量测试预期。 */
  private static String sha(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
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

  /** 原基线为替身，候选校验器为实际实现，Ledger仅允许读调用。 */
  private static final class Fixture {
    /** 原User RR报告代理替身。 */
    final UserTccReconciliationReadService baseReader =
        mock(UserTccReconciliationReadService.class);

    /** 原账本纯读取接口替身。 */
    final BalanceLedgerMapper ledgers = mock(BalanceLedgerMapper.class);

    /** 可替换的原基线观察。 */
    UserTccReconciliationVO base =
        base("BLOCKED_EXTERNAL_EVIDENCE", List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"), "CONFIRMED");

    /** 测试候选行，不是实际外部响应。 */
    final List<BalanceLedgerPO> candidates = new ArrayList<>();

    /** 实际只读取证算法。 */
    final UserLinkedRefundEvidenceReadService reader =
        new UserLinkedRefundEvidenceReadService(baseReader, ledgers);

    Fixture() {
      when(baseReader.inspect(7L, "pay1")).thenAnswer(invocation -> base);
      when(ledgers.findLinkedRefundCandidates("pay1")).thenReturn(candidates);
    }

    void add(String key, String amount) {
      BalanceLedgerPO row = new BalanceLedgerPO();
      row.userId = 7L;
      row.payNo = "pay1";
      row.orderNo = "202610order1";
      row.originalOperationKey = "pay1";
      row.operationKey = key;
      row.operationType = "REFUND";
      row.state = "APPLIED";
      row.amount = new BigDecimal(amount);
      row.balanceBefore = new BigDecimal("90.00");
      row.balanceAfter = row.balanceBefore.add(row.amount);
      candidates.add(row);
      when(ledgers.countOperationCandidates(key)).thenReturn(1L);
    }

    com.cloudmall.user.domain.vo.UserLinkedRefundEvidenceVO inspect() {
      return reader.inspect(7L, "pay1");
    }
  }
}
