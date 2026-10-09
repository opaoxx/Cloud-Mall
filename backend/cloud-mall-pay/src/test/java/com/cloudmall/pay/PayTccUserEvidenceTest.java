package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.vo.*;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 双快照、只读远端参数、固定分类和缺字段语义回归。 */
class PayTccUserEvidenceTest {
  @Test
  void bothFlagsAuthenticationSyntaxAndContextFailBeforeAnyDatabaseOrFeign() {
    Fixture fixture = new Fixture();
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    fixture
                        .service(false, true)
                        .inspect("pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    fixture
                        .service(true, false)
                        .inspect("pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    for (String[] input :
        new String[][] {
          {"pay1", "other", "ops-secret"},
          {"pay1", "cm18-reconciliation", "wrong"},
          {"bad\nkey", "cm18-reconciliation", "ops-secret"}
        })
      assertThrows(
          BizException.class,
          () -> fixture.service(true, true).inspect(input[0], input[1], input[2]));
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertEquals(409, assertThrows(BizException.class, fixture::inspect).getHttpStatus());
    } finally {
      TransactionSynchronizationManager.clear();
    }
    try {
      RootContext.bind("inherited-xid");
      assertEquals(409, assertThrows(BizException.class, fixture::inspect).getHttpStatus());
    } finally {
      RootContext.unbind();
    }
    verifyNoInteractions(fixture.reader, fixture.user);
  }

  @Test
  void untrustedLocalEvidenceSkipsUserAndPreservesEveryExternalGap() {
    for (String classification :
        List.of(
            "LEGACY_NOT_INSPECTED",
            "BLOCKED_BEGIN_UNKNOWN",
            "LOCAL_CONFLICT",
            "EXECUTION_MISSING",
            "BLOCKED_LOCAL_EVIDENCE",
            "BLOCKED_EXTERNAL_EVIDENCE")) {
      Fixture fixture = new Fixture();
      var capture =
          new PayTccEvidenceCaptureDTO(
              new PayTccReconciliationVO(
                  classification,
                  List.of("LOCAL_MISSING"),
                  fixture.capture.localReport().snapshot,
                  Map.of()),
              null);
      when(fixture.reader.capture("pay1")).thenReturn(capture);
      var report = fixture.inspect();
      assertEquals(classification, report.classification);
      assertTrue(report.reasonCodes.contains("LOCAL_ANCHOR_NOT_TRUSTED"));
      assertFalse(report.userEvidence.queryAttempted());
      assertTrue(report.externalSystemsNotQueried.contains("USER"));
      verifyNoInteractions(fixture.user);
      verify(fixture.reader).capture("pay1");
    }
  }

  @Test
  void remoteSuccessAndFailureRestoreCallerContextAfterAnIsolatedGet() {
    for (boolean fails : List.of(false, true)) {
      Fixture fixture = new Fixture();
      when(fixture.user.state(
              anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString()))
          .thenAnswer(
              invocation -> {
                assertNull(AuthContext.userId());
                assertNull(AuthContext.role());
                if (fails) throw new IllegalStateException("response lost");
                return ApiResponse.ok(fixture.observed);
              });
      AuthContext.set(99L, "ADMIN");
      try {
        assertEquals(
            fails ? "USER_EVIDENCE_UNAVAILABLE" : "BLOCKED_REMAINING_EVIDENCE",
            fixture.inspect().classification);
        assertEquals(99L, AuthContext.userId());
        assertEquals("ADMIN", AuthContext.role());
      } finally {
        AuthContext.clear();
      }
      verify(fixture.reader, times(2)).capture("pay1");
    }
  }

  @Test
  void matchedEvidenceUsesOnlyOriginalIdentityAndStillBlocksAllFundsActions() throws Exception {
    Fixture fixture = new Fixture();
    var report = fixture.inspect();
    assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification);
    assertEquals("PAY_USER_READ_ONLY", report.scope);
    assertFalse(report.automaticActionsAllowed);
    assertTrue(report.userEvidence.identityMatched());
    assertEquals(Boolean.TRUE, report.userEvidence.ledgerApplied());
    assertEquals(List.of("ORDER", "STOCK", "TC", "MQ"), report.externalSystemsNotQueried);
    verify(fixture.user)
        .state(7L, "tcc-debit:pay1", 7L, "cloud-mall-pay", "ops-secret", "private-xid");
    verifyNoMoreInteractions(fixture.user);
    verify(fixture.reader, times(2)).capture("pay1");
    String json = new ObjectMapper().writeValueAsString(report);
    for (String forbidden :
        List.of(
            "private-xid", "tcc-debit:pay1", "ops-secret", "trustedAnchor", "ledgerAppliedPresent"))
      assertFalse(json.contains(forbidden), forbidden);
  }

  @Test
  void allIdentityAmountAndBranchDifferencesAreConflicts() {
    List<Consumer<UserTccStateVO>> mutations =
        List.of(
            observed -> observed.userId = 8L,
            observed -> observed.payNo = "other",
            observed -> observed.orderNo = "other",
            observed -> observed.amount = "10.01",
            observed -> observed.operationKey = "other",
            observed -> observed.ledgerKey = "other",
            observed -> observed.xid = "other",
            observed -> observed.branchId = 101L,
            observed -> observed.branchId = 999L);
    for (Consumer<UserTccStateVO> mutation : mutations) {
      Fixture fixture = new Fixture();
      mutation.accept(fixture.observed);
      var report = fixture.inspect();
      assertEquals("USER_EVIDENCE_CONFLICT", report.classification);
      assertFalse(report.userEvidence.identityMatched());
      assertNull(report.userEvidence.ledgerApplied());
    }
  }

  @Test
  void oppositeTerminalAndLedgerStateConflictsCannotAuthorizeFunds() {
    for (String variant : List.of("opposite", "confirmed-no-ledger", "trying-with-ledger")) {
      Fixture fixture = new Fixture();
      if ("opposite".equals(variant)) {
        fixture.observed.state = "CANCELLED";
        fixture.observed.setLedgerApplied(false);
      } else if ("confirmed-no-ledger".equals(variant)) fixture.observed.setLedgerApplied(false);
      else fixture.observed.state = "TRYING";
      assertEquals("USER_EVIDENCE_CONFLICT", fixture.inspect().classification, variant);
    }
  }

  @Test
  void missingUnknownMalformedFieldsAndMissingLedgerBooleanRemainIncomplete() throws Exception {
    List<Consumer<UserTccStateVO>> mutations =
        List.of(
            observed -> observed.state = "UNKNOWN",
            observed -> observed.state = "private-error",
            observed -> observed.state = null,
            observed -> observed.branchId = null,
            observed -> observed.branchId = 0L,
            observed -> observed.amount = "bad",
            observed -> observed.amount = null,
            observed -> observed.xid = null,
            observed -> observed.setLedgerApplied(null));
    for (Consumer<UserTccStateVO> mutation : mutations) {
      Fixture fixture = new Fixture();
      mutation.accept(fixture.observed);
      assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification);
    }
    Fixture fixture = new Fixture();
    String json = new ObjectMapper().writeValueAsString(fixture.observed);
    var missing =
        new ObjectMapper()
            .readValue(json.replace(",\"ledgerApplied\":true", ""), UserTccStateVO.class);
    assertFalse(missing.ledgerAppliedPresent);
    when(fixture.user.state(
            anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(ApiResponse.ok(missing));
    assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification);
  }

  @Test
  void tryingUserCannotBeCalledMatchedAgainstTerminalPay() {
    Fixture fixture = new Fixture();
    fixture.observed.state = "TRYING";
    fixture.observed.setLedgerApplied(false);
    var report = fixture.inspect();
    assertEquals("USER_EVIDENCE_INCOMPLETE", report.classification);
    assertEquals(List.of("USER_TERMINAL_EVIDENCE_PENDING"), report.reasonCodes);
    assertFalse(report.userEvidence.identityMatched());
    assertNull(report.userEvidence.ledgerApplied());
  }

  @Test
  void nullEnvelopeErrorEnvelopeAndNullBodyAreExplicitlyBlocked() {
    for (String variant : List.of("envelope", "error", "body")) {
      Fixture fixture = new Fixture();
      when(fixture.user.state(
              anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString()))
          .thenReturn(
              "envelope".equals(variant)
                  ? null
                  : "body".equals(variant)
                      ? ApiResponse.ok(null)
                      : ApiResponse.error("private-code", "password=secret"));
      assertEquals("USER_EVIDENCE_UNAVAILABLE", fixture.inspect().classification);
      verify(fixture.reader, times(2)).capture("pay1");
    }
  }

  @Test
  void remoteFailuresAlwaysRecapturePayAndOnlyTrustedConflictIsClassifiedAsConflict()
      throws Exception {
    for (RuntimeException failure :
        List.of(
            new IllegalStateException("password=secret"),
            new BizException("COMMON_NOT_FOUND", "secret", 404),
            new BizException("untrusted", "secret", 409),
            new BizException("PAY_TCC_DISPATCH_CONFLICT", "secret", 409))) {
      Fixture fixture = new Fixture();
      when(fixture.user.state(
              anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString()))
          .thenThrow(failure);
      var report = fixture.inspect();
      assertEquals(
          failure instanceof BizException business
                  && "PAY_TCC_DISPATCH_CONFLICT".equals(business.getCode())
              ? "USER_EVIDENCE_CONFLICT"
              : "USER_EVIDENCE_UNAVAILABLE",
          report.classification);
      assertFalse(new ObjectMapper().writeValueAsString(report).contains("secret"));
      verify(fixture.reader, times(2)).capture("pay1");
    }
  }

  @Test
  void localChangesOrIdentityLossOverrideSuccessfulAndFailedRemoteEvidence() {
    for (boolean remoteFails : List.of(false, true)) {
      Fixture fixture = new Fixture();
      var changed =
          new PayTccEvidenceCaptureDTO(
              new PayTccReconciliationVO(
                  "LOCAL_CONFLICT",
                  List.of("CHANGED"),
                  fixture.capture.localReport().snapshot,
                  Map.of()),
              null);
      when(fixture.reader.capture("pay1")).thenReturn(fixture.capture, changed);
      if (remoteFails)
        when(fixture.user.state(
                anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("lost"));
      assertEquals("LOCAL_EVIDENCE_CHANGED", fixture.inspect().classification);
    }
    Fixture fixture = new Fixture();
    when(fixture.reader.capture("pay1"))
        .thenReturn(fixture.capture)
        .thenThrow(new BizException("COMMON_NOT_FOUND", "lost", 404));
    assertEquals("LOCAL_EVIDENCE_CHANGED", fixture.inspect().classification);
  }

  @Test
  void databaseFailuresRemainFixed503AndNeverMasqueradeAsMissingFacts() {
    Fixture fixture = new Fixture();
    when(fixture.reader.capture("pay1"))
        .thenThrow(new DataAccessResourceFailureException("password=secret"));
    var failure = assertThrows(BizException.class, fixture::inspect);
    assertEquals(503, failure.getHttpStatus());
    assertEquals("PAY_TCC_RECONCILIATION_UNAVAILABLE", failure.getCode());
    assertFalse(failure.getMessage().contains("secret"));
    verifyNoInteractions(fixture.user);
  }

  /** 隔离编排器与DB替身，远端state载荷经真实Jackson解码。 */
  private static final class Fixture {
    /** 本地快照代理替身。 */
    final PayTccReconciliationReadService reader = mock(PayTccReconciliationReadService.class);

    /** 仅允许state的远端替身。 */
    final UserTccClient user = mock(UserTccClient.class);

    /** 不可变原Pay快照与可信锚点。 */
    final PayTccEvidenceCaptureDTO capture;

    /** 真实JSON解码后的User证据。 */
    final UserTccStateVO observed;

    Fixture() {
      var snapshot =
          new PayTccReconciliationSnapshotVO(
              "pay1",
              "TCC",
              "PENDING",
              "order1",
              7L,
              "10.00",
              new PayTccLocalFactVO(true, "COMMITTED"),
              new PayTccLocalFactVO(true, "CONFIRMED"),
              new PayTccLocalFactVO(true, "CONFIRMED"),
              new PayTccLocalFactVO(true, "2"),
              new PayTccLocalFactVO(false, null),
              Map.of());
      capture =
          new PayTccEvidenceCaptureDTO(
              new PayTccReconciliationVO(
                  "BLOCKED_EXTERNAL_EVIDENCE",
                  List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"),
                  snapshot,
                  Map.of()),
              new PayTccEvidenceAnchorDTO(
                  "pay1",
                  7L,
                  "order1",
                  "10.00",
                  "private-xid",
                  "tcc-debit:pay1",
                  "pay1",
                  101L,
                  202L,
                  "COMMITTED",
                  "COMMIT"));
      try {
        observed =
            new ObjectMapper()
                .readValue(
                    """
                    {"userId":7,"payNo":"pay1","orderNo":"order1","amount":"10.000",
                     "operationKey":"tcc-debit:pay1","ledgerKey":"pay1","xid":"private-xid",
                     "branchId":202,"state":"CONFIRMED","ledgerApplied":true}
                    """,
                    UserTccStateVO.class);
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
      when(reader.capture("pay1")).thenReturn(capture);
      when(user.state(anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString()))
          .thenAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertNull(RootContext.getXID());
                return ApiResponse.ok(observed);
              });
    }

    PayTccUserEvidenceService service(boolean userEnabled, boolean localEnabled) {
      return new PayTccUserEvidenceService(
          new PayTccReconciliationService(reader, localEnabled, "ops-secret"),
          reader,
          user,
          userEnabled,
          "ops-secret");
    }

    PayTccUserReconciliationVO inspect() {
      return service(true, true).inspect("pay1", "cm18-reconciliation", "ops-secret");
    }
  }
}
