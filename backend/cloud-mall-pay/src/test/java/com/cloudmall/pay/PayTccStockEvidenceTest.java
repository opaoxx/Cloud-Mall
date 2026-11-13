package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.*;
import com.cloudmall.pay.domain.vo.*;
import com.cloudmall.pay.feign.StockEvidenceClient;
import com.cloudmall.pay.service.impl.*;
import com.cloudmall.pay.service.impl.evidence.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.evidence.PayTccReconciliationService;
import com.cloudmall.pay.service.impl.evidence.PayTccStockEvidenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stock独立只读窗口、原生token、向量语义与不授权资金动作回归。 */
class PayTccStockEvidenceTest {
  @Test
  void gatesRejectBeforeAnyDatabaseOrFeign() {
    Fixture fixture = new Fixture();
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () -> fixture.service(false, true).inspect("pay1", "cm18-reconciliation", "secret"))
            .getHttpStatus());
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () -> fixture.service(true, false).inspect("pay1", "cm18-reconciliation", "secret"))
            .getHttpStatus());
    for (String[] request :
        new String[][] {
          {"pay1", "other", "secret"},
          {"pay1", "cm18-reconciliation", "bad"},
          {"bad\nkey", "cm18-reconciliation", "secret"}
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
      RootContext.bind("untrusted-xid");
      assertEquals(409, assertThrows(BizException.class, fixture::inspect).getHttpStatus());
    } finally {
      RootContext.unbind();
    }
    verifyNoInteractions(fixture.reader, fixture.stocks);
  }

  @Test
  void noTrustedLocalAnchorSkipsStockAndPreservesAllExternalGaps() {
    Fixture fixture = new Fixture();
    when(fixture.reader.capture("pay1"))
        .thenReturn(new PayTccEvidenceCaptureDTO(fixture.capture.localReport(), null));
    var report = fixture.inspect();
    assertFalse(report.stockEvidence.queryAttempted());
    assertTrue(report.reasonCodes.contains("LOCAL_ANCHOR_NOT_TRUSTED"));
    assertTrue(report.externalSystemsNotQueried.contains("STOCK"));
    verifyNoInteractions(fixture.stocks);
  }

  @Test
  void allThreeStatesRemainBlockedIncludingRolledBackWithCommittedPay() throws Exception {
    for (String state : List.of("RESERVED", "CONFIRMED", "ROLLED_BACK")) {
      Fixture fixture = new Fixture();
      fixture.observed = json(state, "[{\"skuId\":7,\"quantity\":2,\"status\":\"" + state + "\"}]");
      var report = fixture.inspect();
      assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification);
      assertEquals("PAY_STOCK_OBSERVED_ONLY", report.scope);
      assertFalse(report.automaticActionsAllowed);
      assertTrue(report.stockEvidence.identityMatched());
      assertEquals(1, report.stockEvidence.observedItemCount());
      assertEquals(64, report.stockEvidence.itemVectorSha256().length());
      assertEquals(List.of("USER", "ORDER", "TC", "MQ"), report.externalSystemsNotQueried);
      verify(fixture.stocks).reservation("202610order1");
      verifyNoMoreInteractions(fixture.stocks);
      verify(fixture.reader, times(2)).capture("pay1");
      String output = new ObjectMapper().writeValueAsString(report);
      for (String raw :
          List.of(
              "private-xid",
              "tcc-debit:pay1",
              "\"skuId\"",
              "\"quantity\"",
              "trustedAnchor",
              "secret")) assertFalse(output.contains(raw), raw);
    }
  }

  @Test
  void sortingObservedItemsKeepsTheSameUnambiguousFingerprint() {
    Fixture fixture = new Fixture();
    fixture.observed =
        json(
            "CONFIRMED",
            "[{\"skuId\":8,\"quantity\":3,\"status\":\"CONFIRMED\"},{\"skuId\":7,\"quantity\":2,\"status\":\"CONFIRMED\"}]");
    String first = fixture.inspect().stockEvidence.itemVectorSha256();
    fixture.observed =
        json(
            "CONFIRMED",
            "[{\"skuId\":7,\"quantity\":2,\"status\":\"CONFIRMED\"},{\"skuId\":8,\"quantity\":3,\"status\":\"CONFIRMED\"}]");
    assertEquals(first, fixture.inspect().stockEvidence.itemVectorSha256());
  }

  @Test
  void unknownAndMissingStockNeverProveNoReservationOrSafeRefund() {
    for (String state : List.of("UNKNOWN", "NOT_FOUND", "REJECTED", "INIT", "untrusted-error")) {
      Fixture fixture = new Fixture();
      fixture.observed = json(state, "[]");
      var report = fixture.inspect();
      assertEquals("STOCK_EVIDENCE_INCOMPLETE", report.classification);
      assertFalse(report.stockEvidence.identityMatched());
      assertNull(report.stockEvidence.itemVectorSha256());
    }
  }

  @Test
  void missingNonNativeNonPositiveAndOverCapacityItemFieldsAreIncomplete() {
    for (String items :
        List.of(
            "null",
            "{}",
            "[]",
            "[null]",
            "[{\"skuId\":7,\"quantity\":2}]",
            "[{\"skuId\":7,\"quantity\":2,\"status\":\"CONFIRMED\",\"extra\":1}]",
            "[{\"skuId\":\"7\",\"quantity\":2,\"status\":\"CONFIRMED\"}]",
            "[{\"skuId\":7,\"quantity\":2.5,\"status\":\"CONFIRMED\"}]",
            "[{\"skuId\":0,\"quantity\":2,\"status\":\"CONFIRMED\"}]",
            "[{\"skuId\":7,\"quantity\":0,\"status\":\"CONFIRMED\"}]",
            "[{\"skuId\":9223372036854775808,\"quantity\":2,\"status\":\"CONFIRMED\"}]",
            "[{\"skuId\":7,\"quantity\":2147483648,\"status\":\"CONFIRMED\"}]")) {
      Fixture fixture = new Fixture();
      fixture.observed = json("CONFIRMED", items);
      assertEquals("STOCK_EVIDENCE_INCOMPLETE", fixture.inspect().classification, items);
    }
  }

  @Test
  void wrongOrderDuplicateSkuAndOppositeItemStateAreConflicts() throws Exception {
    for (String variant : List.of("order", "duplicate", "state")) {
      Fixture fixture = new Fixture();
      if ("order".equals(variant))
        fixture.observed.orderNo = new ObjectMapper().valueToTree("other-private-order");
      if ("duplicate".equals(variant))
        fixture.observed =
            json(
                "CONFIRMED",
                "[{\"skuId\":7,\"quantity\":2,\"status\":\"CONFIRMED\"},{\"skuId\":7,\"quantity\":2,\"status\":\"CONFIRMED\"}]");
      if ("state".equals(variant))
        fixture.observed =
            json("CONFIRMED", "[{\"skuId\":7,\"quantity\":2,\"status\":\"ROLLED_BACK\"}]");
      var report = fixture.inspect();
      assertEquals("STOCK_EVIDENCE_CONFLICT", report.classification);
      assertNull(report.stockEvidence.itemVectorSha256());
      assertFalse(new ObjectMapper().writeValueAsString(report).contains("other-private-order"));
    }
    Fixture fixture = new Fixture();
    fixture.observed.orderNo = new ObjectMapper().valueToTree(1);
    assertEquals("STOCK_EVIDENCE_INCOMPLETE", fixture.inspect().classification);
  }

  @Test
  void remoteFailuresRecapturePayAndRestoreCallerContextWithoutLeakingErrors() {
    for (String variant : List.of("envelope", "body", "error", "409", "decode")) {
      Fixture fixture = new Fixture();
      when(fixture.stocks.reservation(anyString()))
          .thenAnswer(
              invocation -> {
                assertNull(AuthContext.userId());
                assertNull(AuthContext.role());
                if ("409".equals(variant)) throw new BizException("untrusted", "secret", 409);
                if ("decode".equals(variant)) throw new IllegalStateException("password=secret");
                return "envelope".equals(variant)
                    ? null
                    : "body".equals(variant)
                        ? ApiResponse.ok(null)
                        : ApiResponse.error("private-code", "secret");
              });
      AuthContext.set(99L, "ADMIN");
      try {
        assertEquals("STOCK_EVIDENCE_UNAVAILABLE", fixture.inspect().classification);
        assertEquals(99L, AuthContext.userId());
        assertEquals("ADMIN", AuthContext.role());
      } finally {
        AuthContext.clear();
      }
      verify(fixture.reader, times(2)).capture("pay1");
    }
  }

  @Test
  void localChangeAndLocalSqlFailureHavePriorityOverRemoteConclusion() {
    Fixture fixture = new Fixture();
    when(fixture.reader.capture("pay1"))
        .thenReturn(
            fixture.capture,
            new PayTccEvidenceCaptureDTO(
                new PayTccReconciliationVO(
                    "LOCAL_CONFLICT",
                    List.of("CHANGED"),
                    fixture.capture.localReport().snapshot,
                    Map.of()),
                null));
    assertEquals("LOCAL_EVIDENCE_CHANGED", fixture.inspect().classification);
    Fixture unavailable = new Fixture();
    when(unavailable.reader.capture("pay1"))
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException("password=secret"));
    var failure = assertThrows(BizException.class, unavailable::inspect);
    assertEquals(503, failure.getHttpStatus());
    assertFalse(failure.getMessage().contains("secret"));
    verifyNoInteractions(unavailable.stocks);
  }

  /** 构造保持原JSON token类型的实际Feign载体。 */
  private static StockReservationEvidenceDTO json(String state, String items) {
    try {
      return new ObjectMapper()
          .readValue(
              "{\"orderNo\":\"202610order1\",\"status\":\"" + state + "\",\"items\":" + items + "}",
              StockReservationEvidenceDTO.class);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  /** 明确原Pay终态和只读依赖，未创建任何Stock写客户端。 */
  private static final class Fixture {
    /** 独立Pay RR替身。 */
    final PayTccReconciliationReadService reader = mock(PayTccReconciliationReadService.class);

    /** GET-only Stock接口替身。 */
    final StockEvidenceClient stocks = mock(StockEvidenceClient.class);

    /** 原Pay报告和可信查询锚点。 */
    final PayTccEvidenceCaptureDTO capture;

    /** 可替换的原生JSON观察值。 */
    StockReservationEvidenceDTO observed =
        json("CONFIRMED", "[{\"skuId\":7,\"quantity\":2,\"status\":\"CONFIRMED\"}]");

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
      when(stocks.reservation(anyString()))
          .thenAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertNull(RootContext.getXID());
                assertNull(AuthContext.userId());
                return ApiResponse.ok(observed);
              });
    }

    PayTccStockEvidenceService service(boolean enabled, boolean localEnabled) {
      return new PayTccStockEvidenceService(
          new PayTccReconciliationService(reader, localEnabled, "secret"), reader, stocks, enabled);
    }

    PayTccStockReconciliationVO inspect() {
      return service(true, true).inspect("pay1", "cm18-reconciliation", "secret");
    }
  }
}
