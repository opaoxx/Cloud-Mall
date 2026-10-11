package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserFrozenOwnershipEvidenceController;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.TccFenceLogPO;
import com.cloudmall.user.domain.po.UserTccReceivePO;
import com.cloudmall.user.domain.vo.UserAccountEvidenceVO;
import com.cloudmall.user.domain.vo.UserRefundEvidenceVO;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
import com.cloudmall.user.domain.vo.UserTccStateVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.UserFrozenOwnershipEvidenceReadService;
import com.cloudmall.user.service.impl.UserFrozenOwnershipEvidenceService;
import com.cloudmall.user.service.impl.UserTccReconciliationReadService;
import com.cloudmall.user.service.impl.UserTccReconciliationService;
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

/** 活跃冻结PO、实际原peer校验器、金额归属/上限/源保留验证。 */
class UserFrozenOwnershipEvidenceTest {
  @Test
  void localAndRefundFlagsAuthenticationRangeAndContextGatesAreBeforeReaders() {
    var base = mock(UserTccReconciliationReadService.class);
    var reader = mock(UserFrozenOwnershipEvidenceReadService.class);
    var gate = new UserTccReconciliationService(base, true, "ops-secret");
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new UserFrozenOwnershipEvidenceService(gate, reader, false)
                        .inspect(7L, "pay1", null, null))
            .getHttpStatus());
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new UserFrozenOwnershipEvidenceService(
                            new UserTccReconciliationService(base, false, "ops-secret"),
                            reader,
                            true)
                        .inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    var service = new UserFrozenOwnershipEvidenceService(gate, reader, true);
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
  void emptyAndTwoOtherTryingTransactionsHaveOnlyObservedOwnership() throws Exception {
    Fixture empty = new Fixture();
    var zero = empty.inspect();
    assertEquals("0.00", zero.validatedFrozenTotal);
    assertTrue(zero.terminalFrozenRowsNotInspected);
    Fixture fixture = new Fixture();
    fixture.addPeer("pay2", 12L);
    fixture.addPeer("pay3", 13L);
    var report = fixture.inspect();
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
    assertEquals("20.00", report.validatedFrozenTotal);
    assertTrue(report.candidatesComplete);
    assertFalse(report.automaticActionsAllowed);
    assertEquals(2, report.observedCount);
    assertEquals("USER_TRYING_FROZEN_OWNERSHIP_OBSERVED_ONLY", report.scope);
    String json = new ObjectMapper().writeValueAsString(report);
    for (String secret : List.of("pay2", "pay3", "private-xid", "tcc-debit:pay"))
      assertFalse(json.contains(secret), secret);
    Fixture reverse = new Fixture();
    reverse.addPeer("pay3", 13L);
    reverse.addPeer("pay2", 12L);
    assertEquals(report.vectorSha256, reverse.inspect().vectorSha256);
    assertThrows(UnsupportedOperationException.class, () -> report.reasonCodes.add("fake"));
  }

  @Test
  void sourceConflictAndMissingMoneyStayBlockedWithoutCandidateQueries() {
    Fixture conflicted = new Fixture();
    conflicted.base = base("LOCAL_CONFLICT", "0.00", "0.00", List.of("SOURCE_CONFLICT"));
    assertFalse(conflicted.inspect().queryAttempted);
    verifyNoInteractions(
        conflicted.debits,
        conflicted.receives,
        conflicted.attempts,
        conflicted.ledgers,
        conflicted.fences);
    for (String amount :
        new String[] {null, "bad-money", "-1.00", "1.001", "10000000000000000.00"}) {
      Fixture fixture = new Fixture();
      fixture.base =
          base("BLOCKED_LOCAL_EVIDENCE", amount, "0.00", List.of("ACCOUNT_FROZEN_MISSING"));
      var report = fixture.inspect();
      assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
      assertFalse(report.queryAttempted);
      assertNull(report.vectorSha256);
      verifyNoInteractions(fixture.debits);
    }
  }

  @Test
  void candidateLimitStopsBeforeAnyPeerEvidence() {
    Fixture fixture = new Fixture();
    for (int index = 0; index < 101; index++) fixture.addPeer("pay" + (index + 2), index + 12L);
    var report = fixture.inspect();
    assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
    assertEquals(101, report.observedCount);
    assertNull(report.validatedFrozenTotal);
    assertNull(report.vectorSha256);
    verify(fixture.debits).findTryingOwnershipCandidates(7L);
    verifyNoMoreInteractions(fixture.debits);
    verifyNoInteractions(fixture.receives, fixture.attempts, fixture.ledgers, fixture.fences);
  }

  @Test
  void candidateIdentityMoneyAndFreezeDeltaMustMatchBeforePeerLookup() {
    for (String variant :
        List.of(
            "user",
            "pay",
            "operation",
            "order",
            "xid",
            "branch",
            "state",
            "amount",
            "nullAmount",
            "before",
            "after",
            "delta")) {
      Fixture fixture = new Fixture();
      fixture.addPeer("pay2", 12L);
      var candidate = fixture.candidates.get(0);
      switch (variant) {
        case "user" -> candidate.userId = 8L;
        case "pay" -> candidate.payNo = "bad%";
        case "operation" -> candidate.operationKey = "other";
        case "order" -> candidate.orderNo = " ";
        case "xid" -> candidate.xid = " ";
        case "branch" -> candidate.branchId = 0L;
        case "state" -> candidate.state = "trying";
        case "amount" -> candidate.amount = BigDecimal.ZERO;
        case "nullAmount" -> candidate.amount = null;
        case "before" -> candidate.balanceBefore = new BigDecimal("-1");
        case "after" -> candidate.balanceAfterTry = null;
        case "delta" -> candidate.balanceAfterTry = new BigDecimal("91.00");
        default -> throw new AssertionError(variant);
      }
      var report = fixture.inspect();
      assertEquals(
          List.of("nullAmount", "after").contains(variant)
              ? "BLOCKED_LOCAL_EVIDENCE"
              : "LOCAL_CONFLICT",
          report.classification,
          variant);
      assertNull(report.validatedFrozenTotal);
      assertNull(report.vectorSha256);
      verifyNoInteractions(fixture.receives, fixture.attempts, fixture.ledgers, fixture.fences);
    }
  }

  @Test
  void actualOriginalQueryRejectsMissingReceiveAttemptBadModeAndFence() {
    for (String variant :
        List.of(
            "receive", "attempt", "mode", "branch", "fence", "fenceStatus", "xid", "legacyNull")) {
      Fixture fixture = new Fixture();
      fixture.addPeer("pay2", 12L);
      switch (variant) {
        case "receive" ->
            when(fixture.receives.findByOperationKey("tcc-debit:pay2")).thenReturn(null);
        case "attempt" -> when(fixture.attempts.selectByPayNo("pay2")).thenReturn(null);
        case "mode" -> fixture.attemptRows.get(0).mode = "LEGACY";
        case "branch" -> fixture.attemptRows.get(0).branchId = 99L;
        case "fence" -> when(fixture.fences.findByBranch("private-xid-pay2", 12L)).thenReturn(null);
        case "fenceStatus" -> fixture.fenceRows.get(0).status = 2;
        case "xid" -> fixture.receiveRows.get(0).xid = "other";
        case "legacyNull" -> fixture.receiveRows.get(0).amount = null;
        default -> throw new AssertionError(variant);
      }
      var report = fixture.inspect();
      assertEquals(
          List.of("receive", "legacyNull").contains(variant)
              ? "BLOCKED_LOCAL_EVIDENCE"
              : "LOCAL_CONFLICT",
          report.classification,
          variant);
      assertNull(report.vectorSha256);
    }
  }

  @Test
  void aggregateEqualityDoesNotOverrideOtherSourceGapsOrDuplicateRows() {
    Fixture gap = new Fixture();
    gap.addPeer("pay2", 12L);
    gap.base = base("BLOCKED_LOCAL_EVIDENCE", "10.00", "10.00", List.of("REFUND_PARTIAL_OBSERVED"));
    assertEquals("BLOCKED_LOCAL_EVIDENCE", gap.inspect().classification);
    assertNull(gap.inspect().validatedFrozenTotal);
    Fixture totals = new Fixture();
    totals.addPeer("pay2", 12L);
    totals.base =
        base(
            "BLOCKED_EXTERNAL_EVIDENCE",
            "20.00",
            "20.00",
            List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"));
    assertTrue(totals.inspect().reasonCodes.contains("FROZEN_OWNERSHIP_TOTAL_CONFLICT"));
    Fixture duplicate = new Fixture();
    duplicate.addPeer("pay2", 12L);
    duplicate.candidates.add(duplicate.candidates.get(0));
    assertEquals("LOCAL_CONFLICT", duplicate.inspect().classification);
    assertNull(duplicate.inspect().vectorSha256);
  }

  @Test
  void peerDatabaseFailuresAreUnavailableAndNeverPretendNoFunds() {
    Fixture fixture = new Fixture();
    fixture.addPeer("pay2", 12L);
    when(fixture.receives.findByOperationKey("tcc-debit:pay2"))
        .thenThrow(new DataAccessResourceFailureException("private-db-password"));
    var service =
        new UserFrozenOwnershipEvidenceService(
            new UserTccReconciliationService(fixture.baseReader, true, "ops-secret"),
            fixture.reader,
            true);
    var error =
        assertThrows(
            BizException.class,
            () -> service.inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"));
    assertEquals(503, error.getHttpStatus());
    assertEquals("USER_FROZEN_OWNERSHIP_EVIDENCE_UNAVAILABLE", error.getCode());
    assertFalse(error.getMessage().contains("private"));
  }

  /** 明确源账户值与固定缺口，未输入客户端资金参数。 */
  private static UserTccReconciliationVO base(
      String classification, String frozen, String total, List<String> reasons) {
    UserTccStateVO state = new UserTccStateVO();
    state.userId = 7L;
    state.payNo = "pay1";
    state.orderNo = "202610order1";
    state.amount = "10.00";
    state.state = "CONFIRMED";
    state.ledgerApplied = true;
    return new UserTccReconciliationVO(
        state,
        classification,
        reasons,
        new UserAccountEvidenceVO(true, "ACTIVE", "90.00", frozen, total, true),
        new UserRefundEvidenceVO(false, null, null, null, null, null, null, null),
        Map.of());
  }

  /** 使用实际UserTccStateQuery，所有原事实Mapper为仅读取替身。 */
  private static final class Fixture {
    /** 原基线RR代理替身。 */
    final UserTccReconciliationReadService baseReader =
        mock(UserTccReconciliationReadService.class);

    /** 原接收只读接口。 */
    final UserTccReceiveMapper receives = mock(UserTccReceiveMapper.class);

    /** 原TCC归属只读接口。 */
    final BalanceDebitAttemptMapper attempts = mock(BalanceDebitAttemptMapper.class);

    /** 原冻结事实与候选接口。 */
    final BalanceTccDebitMapper debits = mock(BalanceTccDebitMapper.class);

    /** DEBIT原账本只读接口。 */
    final BalanceLedgerMapper ledgers = mock(BalanceLedgerMapper.class);

    /** 原Fence只读接口。 */
    final TccFenceLogMapper fences = mock(TccFenceLogMapper.class);

    /** 候选原PO集合。 */
    final List<BalanceTccDebitPO> candidates = new ArrayList<>();

    /** 可故障注入的原receive集合。 */
    final List<UserTccReceivePO> receiveRows = new ArrayList<>();

    /** 可故障注入的原attempt集合。 */
    final List<BalanceDebitAttemptPO> attemptRows = new ArrayList<>();

    /** 可故障注入的原Fence集合。 */
    final List<TccFenceLogPO> fenceRows = new ArrayList<>();

    /** 当前User源报告。 */
    UserTccReconciliationVO base =
        base("BLOCKED_EXTERNAL_EVIDENCE", "0.00", "0.00", List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"));

    /** 实际本批算法及原peer校验器。 */
    final UserFrozenOwnershipEvidenceReadService reader =
        new UserFrozenOwnershipEvidenceReadService(
            baseReader, receives, attempts, debits, ledgers, fences);

    Fixture() {
      when(baseReader.inspect(7L, "pay1")).thenAnswer(invocation -> base);
      when(debits.findTryingOwnershipCandidates(7L)).thenReturn(candidates);
    }

    com.cloudmall.user.domain.vo.UserFrozenOwnershipEvidenceVO inspect() {
      return reader.inspect(7L, "pay1");
    }

    void addPeer(String payNo, Long branchId) {
      BalanceTccDebitPO row = new BalanceTccDebitPO();
      row.userId = 7L;
      row.payNo = payNo;
      row.orderNo = "202610order-" + payNo;
      row.operationKey = "tcc-debit:" + payNo;
      row.xid = "private-xid-" + payNo;
      row.branchId = branchId;
      row.state = "TRYING";
      row.amount = new BigDecimal("10.00");
      row.balanceBefore = new BigDecimal("100.00");
      row.balanceAfterTry = new BigDecimal("90.00");
      candidates.add(row);
      UserTccReceivePO receive = new UserTccReceivePO();
      receive.userId = row.userId;
      receive.payNo = row.payNo;
      receive.orderNo = row.orderNo;
      receive.operationKey = row.operationKey;
      receive.ledgerKey = row.payNo;
      receive.xid = row.xid;
      receive.branchId = row.branchId;
      receive.amount = row.amount;
      receive.state = "CLAIMED";
      receiveRows.add(receive);
      BalanceDebitAttemptPO attempt = new BalanceDebitAttemptPO();
      attempt.userId = row.userId;
      attempt.payNo = row.payNo;
      attempt.orderNo = row.orderNo;
      attempt.operationKey = row.operationKey;
      attempt.ledgerKey = row.payNo;
      attempt.xid = row.xid;
      attempt.branchId = row.branchId;
      attempt.amount = row.amount;
      attempt.state = "TRYING";
      attempt.mode = "TCC";
      attemptRows.add(attempt);
      TccFenceLogPO fence = new TccFenceLogPO();
      fence.actionName = BalanceTccDebitLocalResource.ACTION_NAME;
      fence.status = 1;
      fenceRows.add(fence);
      when(receives.findByOperationKey(row.operationKey)).thenReturn(receive);
      when(attempts.selectByPayNo(payNo)).thenReturn(attempt);
      when(debits.selectByPayNo(payNo)).thenReturn(row);
      when(fences.findByBranch(row.xid, branchId)).thenReturn(fence);
      String amount =
          new BigDecimal("10.00").multiply(BigDecimal.valueOf(candidates.size())).toPlainString();
      base =
          base(
              "BLOCKED_EXTERNAL_EVIDENCE",
              amount,
              amount,
              List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"));
    }
  }

  @Test
  void httpBindingAndDatabaseFailuresKeepNoStoreAndHideErrors() throws Exception {
    var base = mock(UserTccReconciliationReadService.class);
    var reader = mock(UserFrozenOwnershipEvidenceReadService.class);
    var service =
        new UserFrozenOwnershipEvidenceService(
            new UserTccReconciliationService(base, true, "ops-secret"), reader, true);
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserFrozenOwnershipEvidenceController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route =
        "/api/internal/users/7/balance/tcc-reconciliation/pay1/frozen-ownership-evidence";
    mvc.perform(get(route))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            get(
                "/api/internal/users/abc/balance/tcc-reconciliation/pay1/frozen-ownership-evidence"))
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
            .andExpect(jsonPath("$.code").value("USER_FROZEN_OWNERSHIP_EVIDENCE_UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("private-db-password"));
  }
}
