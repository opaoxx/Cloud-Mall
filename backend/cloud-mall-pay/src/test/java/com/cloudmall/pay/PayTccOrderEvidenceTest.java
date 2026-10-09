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
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Order基本事实分类、安全门及事务外唯一GET验证，不读取User或库存镜像。 */
class PayTccOrderEvidenceTest {
  @Test
  void flagsAuthenticationSyntaxAndTransactionContextFailBeforeAnyIo() {
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
    for (String[] request :
        new String[][] {
          {"pay1", "other", "ops-secret"},
          {"pay1", "cm18-reconciliation", "bad"},
          {"bad\nkey", "cm18-reconciliation", "ops-secret"}
        })
      assertThrows(
          BizException.class,
          () -> fixture.service(true, true).inspect(request[0], request[1], request[2]));
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
    verifyNoInteractions(fixture.reader, fixture.orders);
  }

  @Test
  void untrustedLocalAnchorSkipsOrderWithoutRemovingAnyExternalGap() {
    for (String classification :
        List.of(
            "LEGACY_NOT_INSPECTED",
            "BLOCKED_BEGIN_UNKNOWN",
            "LOCAL_CONFLICT",
            "EXECUTION_MISSING",
            "BLOCKED_LOCAL_EVIDENCE",
            "BLOCKED_EXTERNAL_EVIDENCE")) {
      Fixture fixture = new Fixture();
      when(fixture.reader.capture("pay1"))
          .thenReturn(
              new PayTccEvidenceCaptureDTO(
                  new PayTccReconciliationVO(
                      classification,
                      List.of("MISSING"),
                      fixture.capture.localReport().snapshot,
                      Map.of()),
                  null));
      var report = fixture.inspect();
      assertEquals(classification, report.classification);
      assertTrue(report.reasonCodes.contains("LOCAL_ANCHOR_NOT_TRUSTED"));
      assertFalse(report.orderEvidence.queryAttempted());
      assertTrue(report.externalSystemsNotQueried.contains("ORDER"));
      verifyNoInteractions(fixture.orders);
    }
  }

  @Test
  void everyLegalOrderStateIncludingCancelledWithCommittedPayOnlyProvesBasicIdentity()
      throws Exception {
    for (String state : List.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED")) {
      Fixture fixture = new Fixture();
      fixture.observed =
          new OrderClient.OrderView(
              "202610order1", state, new BigDecimal("10.0000"), 7L, "CONFLICT");
      var report = fixture.inspect();
      assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification);
      assertEquals("PAY_ORDER_BASIC_ONLY", report.scope);
      assertFalse(report.automaticActionsAllowed);
      assertTrue(report.orderEvidence.identityMatched());
      assertEquals(state, report.orderEvidence.state());
      assertEquals(List.of("USER", "STOCK", "TC", "MQ"), report.externalSystemsNotQueried);
      verify(fixture.orders).get("202610order1", 7L);
      verifyNoMoreInteractions(fixture.orders);
      verify(fixture.reader, times(2)).capture("pay1");
      String json = new ObjectMapper().writeValueAsString(report);
      for (String raw :
          List.of(
              "private-xid",
              "tcc-debit:pay1",
              "ops-secret",
              "stockActionStatus",
              "userEvidence",
              "trustedAnchor")) assertFalse(json.contains(raw), raw);
    }
  }

  @Test
  void wrongOrderUserAndNumericAmountAreConflictsWithoutEchoingWrongIdentity() throws Exception {
    for (OrderClient.OrderView observed :
        List.of(
            new OrderClient.OrderView("other-private-order", "PAID", BigDecimal.TEN, 7L),
            new OrderClient.OrderView("202610order1", "PAID", BigDecimal.TEN, 8L),
            new OrderClient.OrderView("202610order1", "PAID", new BigDecimal("10.01"), 7L))) {
      Fixture fixture = new Fixture();
      fixture.observed = observed;
      var report = fixture.inspect();
      assertEquals("ORDER_EVIDENCE_CONFLICT", report.classification);
      assertFalse(report.orderEvidence.identityMatched());
      assertFalse(new ObjectMapper().writeValueAsString(report).contains("other-private-order"));
    }
  }

  @Test
  void missingBasicFieldsAndUnknownStateRemainIncompleteAndDoNotExposeRawStatus() throws Exception {
    for (OrderClient.OrderView observed :
        List.of(
            new OrderClient.OrderView(null, "PAID", BigDecimal.TEN, 7L),
            new OrderClient.OrderView("202610order1", "PAID", null, 7L),
            new OrderClient.OrderView("202610order1", "PAID", BigDecimal.TEN, null),
            new OrderClient.OrderView("202610order1", null, BigDecimal.TEN, 7L),
            new OrderClient.OrderView("202610order1", "password=secret", BigDecimal.TEN, 7L))) {
      Fixture fixture = new Fixture();
      fixture.observed = observed;
      var report = fixture.inspect();
      assertEquals("ORDER_EVIDENCE_INCOMPLETE", report.classification);
      assertFalse(new ObjectMapper().writeValueAsString(report).contains("secret"));
    }
  }

  @Test
  void nullErrorOrRemoteExceptionRecapturesPayAndRestoresCallerContext() throws Exception {
    for (String variant :
        List.of("envelope", "body", "error", "403", "404", "409", "503", "decode")) {
      Fixture fixture = new Fixture();
      when(fixture.orders.get(anyString(), anyLong()))
          .thenAnswer(
              invocation -> {
                assertNull(AuthContext.userId());
                assertNull(AuthContext.role());
                if (variant.matches("[0-9]+"))
                  throw new BizException("untrusted", "secret", Integer.parseInt(variant));
                if ("decode".equals(variant)) throw new IllegalStateException("password=secret");
                return "envelope".equals(variant)
                    ? null
                    : "body".equals(variant)
                        ? ApiResponse.ok(null)
                        : ApiResponse.error("private-code", "password=secret");
              });
      AuthContext.set(99L, "ADMIN");
      try {
        var report = fixture.inspect();
        assertEquals("ORDER_EVIDENCE_UNAVAILABLE", report.classification);
        assertEquals(99L, AuthContext.userId());
        assertEquals("ADMIN", AuthContext.role());
        assertFalse(new ObjectMapper().writeValueAsString(report).contains("secret"));
      } finally {
        AuthContext.clear();
      }
      verify(fixture.reader, times(2)).capture("pay1");
    }
  }

  @Test
  void localEvidenceChangesOrIdentityLossOverrideTheRemoteResult() {
    for (boolean fails : List.of(false, true)) {
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
      if (fails)
        when(fixture.orders.get(anyString(), anyLong()))
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
  void sqlFailuresRemainFixed503AndCannotBeTreatedAsNoPayment() {
    Fixture fixture = new Fixture();
    when(fixture.reader.capture("pay1"))
        .thenThrow(new DataAccessResourceFailureException("password=secret"));
    var failure = assertThrows(BizException.class, fixture::inspect);
    assertEquals(503, failure.getHttpStatus());
    assertEquals("PAY_TCC_RECONCILIATION_UNAVAILABLE", failure.getCode());
    assertFalse(failure.getMessage().contains("secret"));
    verifyNoInteractions(fixture.orders);
  }

  /** 原可信Pay锚点以及唯一Order GET替身。 */
  private static final class Fixture {
    /** 本地只读快照替身。 */
    final PayTccReconciliationReadService reader = mock(PayTccReconciliationReadService.class);

    /** Order GET替身，其他方法不得调用。 */
    final OrderClient orders = mock(OrderClient.class);

    /** 不可变原Pay快照。 */
    final PayTccEvidenceCaptureDTO capture;

    /** 本次远端响应。 */
    OrderClient.OrderView observed =
        new OrderClient.OrderView("202610order1", "PAID", BigDecimal.TEN, 7L, null);

    Fixture() {
      var snapshot =
          new PayTccReconciliationSnapshotVO(
              "pay1",
              "TCC",
              "PENDING",
              "202610order1",
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
                  "202610order1",
                  "10.00",
                  "private-xid",
                  "tcc-debit:pay1",
                  "pay1",
                  101L,
                  202L,
                  "COMMITTED",
                  "COMMIT"));
      when(reader.capture("pay1")).thenReturn(capture);
      when(orders.get(anyString(), anyLong()))
          .thenAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertNull(RootContext.getXID());
                assertNull(AuthContext.userId());
                return ApiResponse.ok(observed);
              });
    }

    PayTccOrderEvidenceService service(boolean enabled, boolean localEnabled) {
      return new PayTccOrderEvidenceService(
          new PayTccReconciliationService(reader, localEnabled, "ops-secret"),
          reader,
          orders,
          enabled);
    }

    PayTccOrderReconciliationVO inspect() {
      return service(true, true).inspect("pay1", "cm18-reconciliation", "ops-secret");
    }
  }
}
