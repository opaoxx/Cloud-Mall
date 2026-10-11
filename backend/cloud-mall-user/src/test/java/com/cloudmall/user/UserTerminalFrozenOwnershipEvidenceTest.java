package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserTerminalFrozenOwnershipEvidenceController;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
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
import com.cloudmall.user.service.impl.UserTccReconciliationReadService;
import com.cloudmall.user.service.impl.UserTccReconciliationService;
import com.cloudmall.user.service.impl.UserTerminalFrozenOwnershipEvidenceReadService;
import com.cloudmall.user.service.impl.UserTerminalFrozenOwnershipEvidenceService;
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

/** 历史终态冻结PO、实际原peer校验器、金额归属/上限/源保留验证。 */
class UserTerminalFrozenOwnershipEvidenceTest {
  @Test
  void localAndRefundFlagsAuthenticationRangeAndContextGatesAreBeforeReaders() {
    var base = mock(UserTccReconciliationReadService.class);
    var reader = mock(UserTerminalFrozenOwnershipEvidenceReadService.class);
    var gate = new UserTccReconciliationService(base, true, "ops-secret");
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new UserTerminalFrozenOwnershipEvidenceService(gate, reader, false)
                        .inspect(7L, "pay1", null, null))
            .getHttpStatus());
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new UserTerminalFrozenOwnershipEvidenceService(
                            new UserTccReconciliationService(base, false, "ops-secret"),
                            reader,
                            true)
                        .inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    var service = new UserTerminalFrozenOwnershipEvidenceService(gate, reader, true);
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
  void emptyAndMixedTerminalsAreBoundedObservedCountsOnly() throws Exception {
    Fixture empty = new Fixture();
    assertEquals(0, empty.inspect().validatedConfirmedCount);
    Fixture fixture = new Fixture();
    fixture.addPeer("pay2", 12L, "CONFIRMED");
    fixture.addPeer("pay3", 13L, "CANCELLED");
    var report = fixture.inspect();
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
    assertEquals(1, report.validatedConfirmedCount);
    assertEquals(1, report.validatedCancelledCount);
    assertEquals(2, report.observedCount);
    assertTrue(report.candidatesComplete);
    assertTrue(report.otherDebitStatesNotInspected);
    assertTrue(report.attemptOnlyCancelledNotInspected);
    assertTrue(report.accountCausalityNotProven);
    assertTrue(report.terminalFenceMayBeExpired);
    assertFalse(report.automaticActionsAllowed);
    String json = new ObjectMapper().writeValueAsString(report);
    for (String secret :
        List.of("pay2", "pay3", "private-xid", "tcc-debit:pay", "validatedFrozenTotal"))
      assertFalse(json.contains(secret), secret);
    Fixture reverse = new Fixture();
    reverse.addPeer("pay3", 13L, "CANCELLED");
    reverse.addPeer("pay2", 12L, "CONFIRMED");
    assertEquals(report.vectorSha256, reverse.inspect().vectorSha256);
    assertThrows(UnsupportedOperationException.class, () -> report.reasonCodes.add("fake"));
  }

  @Test
  void sourceConflictSkipsCandidatesAndOtherGapsDoNotBecomeComplete() {
    Fixture conflict = new Fixture();
    conflict.base = base("LOCAL_CONFLICT", "0.00", "0.00", List.of("SOURCE_CONFLICT"));
    assertFalse(conflict.inspect().queryAttempted);
    verifyNoInteractions(
        conflict.debits, conflict.receives, conflict.attempts, conflict.ledgers, conflict.fences);
    Fixture gap = new Fixture();
    gap.addPeer("pay2", 12L, "CONFIRMED");
    gap.base = base("BLOCKED_LOCAL_EVIDENCE", "0.00", "0.00", List.of("REFUND_PARTIAL_OBSERVED"));
    var report = gap.inspect();
    assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
    assertNull(report.validatedConfirmedCount);
    assertNull(report.validatedCancelledCount);
    assertNull(report.vectorSha256);
  }

  @Test
  void hundredAndSentinelBoundsDoNotQueryTruncatedPeerEvidence() {
    for (int count : List.of(100, 101)) {
      Fixture fixture = new Fixture();
      for (int index = 0; index < count; index++)
        fixture.addPeer("pay" + (index + 2), index + 12L, "CANCELLED");
      var report = fixture.inspect();
      assertEquals(count, report.observedCount);
      if (count == 100) {
        assertTrue(report.candidatesComplete);
        assertEquals(100, report.validatedCancelledCount);
      } else {
        assertFalse(report.candidatesComplete);
        assertNull(report.vectorSha256);
        verifyNoInteractions(fixture.receives, fixture.attempts, fixture.ledgers, fixture.fences);
      }
    }
  }

  @Test
  void candidateIdentityMoneyAndSnapshotMustBeProvenBeforePeerLookup() {
    for (String mutation :
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
      fixture.addPeer("pay2", 12L, "CONFIRMED");
      var row = fixture.candidates.get(0);
      switch (mutation) {
        case "user" -> row.userId = 8L;
        case "pay" -> row.payNo = "bad%";
        case "operation" -> row.operationKey = "wrong";
        case "order" -> row.orderNo = " ";
        case "xid" -> row.xid = " ";
        case "branch" -> row.branchId = 0L;
        case "state" -> row.state = "confirmed";
        case "amount" -> row.amount = BigDecimal.ZERO;
        case "nullAmount" -> row.amount = null;
        case "before" -> row.balanceBefore = new BigDecimal("-1");
        case "after" -> row.balanceAfterTry = null;
        case "delta" -> row.balanceAfterTry = new BigDecimal("91");
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
      verifyNoInteractions(fixture.receives, fixture.attempts, fixture.ledgers, fixture.fences);
    }
  }

  @Test
  void actualPeerProofRejectsMissingOriginOrWrongModeBranchAndFence() {
    for (String mutation :
        List.of(
            "receive",
            "receiveState",
            "attempt",
            "mode",
            "branch",
            "fenceState",
            "xid",
            "nullAmount",
            "ledger")) {
      Fixture fixture = new Fixture();
      fixture.addPeer("pay2", 12L, "CONFIRMED");
      switch (mutation) {
        case "receive" ->
            when(fixture.receives.findByOperationKey("tcc-debit:pay2")).thenReturn(null);
        case "receiveState" -> fixture.receiveRows.get(0).state = "claimed";
        case "attempt" -> when(fixture.attempts.selectByPayNo("pay2")).thenReturn(null);
        case "mode" -> fixture.attemptRows.get(0).mode = "LEGACY";
        case "branch" -> fixture.attemptRows.get(0).branchId = 99L;
        case "fenceState" -> fixture.fenceRows.get(0).status = 3;
        case "xid" -> fixture.receiveRows.get(0).xid = "wrong";
        case "nullAmount" -> fixture.receiveRows.get(0).amount = null;
        case "ledger" -> when(fixture.ledgers.selectByOperationKey("pay2")).thenReturn(null);
        default -> throw new AssertionError(mutation);
      }
      var report = fixture.inspect();
      assertEquals(
          "receive".equals(mutation) ? "BLOCKED_LOCAL_EVIDENCE" : "LOCAL_CONFLICT",
          report.classification,
          mutation);
      assertNull(report.vectorSha256);
    }
  }

  @Test
  void expiredTerminalFenceDoesNotInventNewPermanentRequirement() {
    for (String state : List.of("CONFIRMED", "CANCELLED")) {
      Fixture fixture = new Fixture();
      fixture.addPeer("pay2", 12L, state);
      when(fixture.fences.findByBranch("private-xid-pay2", 12L)).thenReturn(null);
      assertTrue(fixture.inspect().candidatesComplete);
    }
  }

  @Test
  void cancelledAnyDebitAndConfirmedGlobalKeyMismatchRemainConflicts() {
    Fixture cancelled = new Fixture();
    cancelled.addPeer("pay2", 12L, "CANCELLED");
    when(cancelled.ledgers.selectDebitByPayNo("pay2")).thenReturn(new BalanceLedgerPO());
    assertEquals("LOCAL_CONFLICT", cancelled.inspect().classification);
    Fixture confirmed = new Fixture();
    confirmed.addPeer("pay2", 12L, "CONFIRMED");
    BalanceLedgerPO wrong = new BalanceLedgerPO();
    wrong.amount = new BigDecimal("10.00");
    wrong.balanceBefore = new BigDecimal("100.00");
    wrong.balanceAfter = new BigDecimal("90.00");
    wrong.userId = 7L;
    wrong.payNo = "pay2";
    wrong.orderNo = "202610order-pay2";
    wrong.operationKey = "legacy:pay2";
    wrong.operationType = "DEBIT";
    wrong.state = "APPLIED";
    when(confirmed.ledgers.selectDebitByPayNo("pay2")).thenReturn(wrong);
    assertEquals("LOCAL_CONFLICT", confirmed.inspect().classification);
    when(confirmed.ledgers.selectDebitByPayNo("pay2")).thenReturn(null);
    assertEquals("BLOCKED_LOCAL_EVIDENCE", confirmed.inspect().classification);
  }

  @Test
  void missingCandidatesAndDuplicatePaymentsDoNotProduceVectors() {
    Fixture missing = new Fixture();
    when(missing.debits.findTerminalOwnershipCandidates(7L)).thenReturn(null);
    assertNull(missing.inspect().vectorSha256);
    Fixture duplicate = new Fixture();
    duplicate.addPeer("pay2", 12L, "CANCELLED");
    duplicate.candidates.add(duplicate.candidates.get(0));
    assertEquals("LOCAL_CONFLICT", duplicate.inspect().classification);
    assertNull(duplicate.inspect().vectorSha256);
  }

  @Test
  void databaseFailuresStayUnavailableWithoutRawErrorText() {
    Fixture fixture = new Fixture();
    fixture.addPeer("pay2", 12L, "CONFIRMED");
    when(fixture.receives.findByOperationKey("tcc-debit:pay2"))
        .thenThrow(new DataAccessResourceFailureException("private-password"));
    var service =
        new UserTerminalFrozenOwnershipEvidenceService(
            new UserTccReconciliationService(fixture.baseReader, true, "ops-secret"),
            fixture.reader,
            true);
    var error =
        assertThrows(
            BizException.class,
            () -> service.inspect(7L, "pay1", "cm18-reconciliation", "ops-secret"));
    assertEquals(503, error.getHttpStatus());
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

    /** 可故障注入的原确认账本。 */
    final List<BalanceLedgerPO> ledgerRows = new ArrayList<>();

    /** 可故障注入的原Fence集合。 */
    final List<TccFenceLogPO> fenceRows = new ArrayList<>();

    /** 当前User源报告。 */
    UserTccReconciliationVO base =
        base("BLOCKED_EXTERNAL_EVIDENCE", "0.00", "0.00", List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"));

    /** 实际本批算法及原peer校验器。 */
    final UserTerminalFrozenOwnershipEvidenceReadService reader =
        new UserTerminalFrozenOwnershipEvidenceReadService(
            baseReader, receives, attempts, debits, ledgers, fences);

    Fixture() {
      when(baseReader.inspect(7L, "pay1")).thenAnswer(invocation -> base);
      when(debits.findTerminalOwnershipCandidates(7L)).thenReturn(candidates);
    }

    com.cloudmall.user.domain.vo.UserTerminalFrozenOwnershipEvidenceVO inspect() {
      return reader.inspect(7L, "pay1");
    }

    void addPeer(String payNo, Long branchId, String state) {
      BalanceTccDebitPO row = new BalanceTccDebitPO();
      row.userId = 7L;
      row.payNo = payNo;
      row.orderNo = "202610order-" + payNo;
      row.operationKey = "tcc-debit:" + payNo;
      row.xid = "private-xid-" + payNo;
      row.branchId = branchId;
      row.state = state;
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
      attempt.state = state;
      attempt.mode = "TCC";
      attemptRows.add(attempt);
      TccFenceLogPO fence = new TccFenceLogPO();
      fence.xid = row.xid;
      fence.branchId = branchId;
      fence.actionName = BalanceTccDebitLocalResource.ACTION_NAME;
      fence.status = "CONFIRMED".equals(state) ? 2 : 3;
      fenceRows.add(fence);
      when(receives.findByOperationKey(row.operationKey)).thenReturn(receive);
      when(attempts.selectByPayNo(payNo)).thenReturn(attempt);
      when(debits.selectByPayNo(payNo)).thenReturn(row);
      when(fences.findByBranch(row.xid, branchId)).thenReturn(fence);
      if ("CONFIRMED".equals(state)) {
        BalanceLedgerPO ledger = new BalanceLedgerPO();
        ledger.userId = row.userId;
        ledger.payNo = row.payNo;
        ledger.orderNo = row.orderNo;
        ledger.operationKey = row.payNo;
        ledger.operationType = "DEBIT";
        ledger.state = "APPLIED";
        ledger.amount = row.amount;
        ledger.balanceBefore = row.balanceBefore;
        ledger.balanceAfter = row.balanceAfterTry;
        ledgerRows.add(ledger);
        when(ledgers.selectByOperationKey(payNo)).thenReturn(ledger);
        when(ledgers.selectDebitByPayNo(payNo)).thenReturn(ledger);
      }
    }
  }

  @Test
  void httpBindingAndDatabaseFailuresKeepNoStoreAndHideErrors() throws Exception {
    var base = mock(UserTccReconciliationReadService.class);
    var reader = mock(UserTerminalFrozenOwnershipEvidenceReadService.class);
    var service =
        new UserTerminalFrozenOwnershipEvidenceService(
            new UserTccReconciliationService(base, true, "ops-secret"), reader, true);
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserTerminalFrozenOwnershipEvidenceController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route =
        "/api/internal/users/7/balance/tcc-reconciliation/pay1/terminal-frozen-ownership-evidence";
    mvc.perform(get(route))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            get(
                "/api/internal/users/abc/balance/tcc-reconciliation/pay1/terminal-frozen-ownership-evidence"))
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
            .andExpect(
                jsonPath("$.code").value("USER_TERMINAL_FROZEN_OWNERSHIP_EVIDENCE_UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("private-db-password"));
  }
}
