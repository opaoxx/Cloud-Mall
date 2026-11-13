package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.OrderBasicEvidenceDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.vo.*;
import com.cloudmall.pay.feign.OrderBasicEvidenceClient;
import com.cloudmall.pay.service.impl.*;
import com.cloudmall.pay.service.impl.evidence.PayTccOrderEvidenceService;
import com.cloudmall.pay.service.impl.evidence.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.evidence.PayTccReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  void nonNativeOrderIdentityAndMoneyTokensCannotBecomeMatched() {
    java.util.List<org.junit.jupiter.api.function.Executable> assertions =
        new java.util.ArrayList<>();
    for (String[] tokens :
        new String[][] {{"\"7\"", "\"10.00\""}, {"7.9", "\"10.00\""}, {"7", "10.00"}}) {
      assertions.add(
          () -> {
            Fixture fixture = new Fixture();
            fixture.observed =
                new ObjectMapper()
                    .readValue(
                        "{\"orderNo\":\"202610order1\",\"status\":\"PAID\",\"userId\":"
                            + tokens[0]
                            + ",\"payAmount\":"
                            + tokens[1]
                            + "}",
                        OrderBasicEvidenceDTO.class);
            var report = fixture.inspect();
            assertEquals(
                "ORDER_EVIDENCE_INCOMPLETE",
                report.classification,
                "userId=" + tokens[0] + "; payAmount=" + tokens[1]);
            assertFalse(report.orderEvidence.identityMatched());
          });
    }
    assertAll(assertions);
  }

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
      fixture.observed = order("202610order1", state, new BigDecimal("10.0000"), 7L, "CONFLICT");
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
    for (OrderBasicEvidenceDTO observed :
        List.of(
            order("other-private-order", "PAID", BigDecimal.TEN, 7L),
            order("202610order1", "PAID", BigDecimal.TEN, 8L),
            order("202610order1", "PAID", new BigDecimal("10.01"), 7L))) {
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
    for (OrderBasicEvidenceDTO observed :
        List.of(
            order(null, "PAID", BigDecimal.TEN, 7L),
            order("202610order1", "PAID", null, 7L),
            order("202610order1", "PAID", BigDecimal.TEN, null),
            order("202610order1", null, BigDecimal.TEN, 7L),
            order("202610order1", "password=secret", BigDecimal.TEN, 7L))) {
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

  @Test
  void wrongNativeTypesFormatsAndBoundsNeverProduceIdentityProof() throws Exception {
    for (String field : List.of("orderNo", "status", "userId", "payAmount")) {
      for (String variant : List.of("null", "object", "array", "boolean")) {
        Fixture fixture = new Fixture();
        ObjectNode raw = validJson();
        switch (variant) {
          case "null" -> raw.putNull(field);
          case "object" -> raw.putObject(field).put("private", "password=secret");
          case "array" -> raw.putArray(field).add("password=secret");
          case "boolean" -> raw.put(field, true);
          default -> throw new AssertionError(variant);
        }
        fixture.observed = new ObjectMapper().treeToValue(raw, OrderBasicEvidenceDTO.class);
        var report = fixture.inspect();
        assertEquals("ORDER_EVIDENCE_INCOMPLETE", report.classification, field + variant);
        assertFalse(report.orderEvidence.identityMatched());
        assertFalse(new ObjectMapper().writeValueAsString(report).contains("secret"));
        if ("orderNo".equals(field)) assertNull(report.orderEvidence.orderNoSha256());
        if ("userId".equals(field)) assertNull(report.orderEvidence.userIdSha256());
        if ("payAmount".equals(field)) assertNull(report.orderEvidence.payAmountSha256());
      }
    }
    for (String rawId : List.of("7.0", "0", "-1", "9223372036854775808")) {
      Fixture fixture = new Fixture();
      ObjectNode raw = validJson();
      raw.set("userId", new ObjectMapper().readTree(rawId));
      fixture.observed = new ObjectMapper().treeToValue(raw, OrderBasicEvidenceDTO.class);
      assertEquals("ORDER_EVIDENCE_INCOMPLETE", fixture.inspect().classification);
      assertNull(fixture.inspect().orderEvidence.userIdSha256());
    }
  }

  @Test
  void nativeMoneyPreservesLosslessTrailingZerosAndBlocksInvalidRepresentation() throws Exception {
    for (String amount : List.of("10", "10.0", "10.0000", "10." + "0".repeat(100))) {
      Fixture fixture = new Fixture();
      fixture.observed = order("202610order1", "PAID", new BigDecimal(amount), 7L);
      assertEquals("BLOCKED_REMAINING_EVIDENCE", fixture.inspect().classification, amount);
    }
    for (String amount : List.of("0", "-10.00", "10.01")) {
      Fixture fixture = new Fixture();
      fixture.observed = order("202610order1", "PAID", new BigDecimal(amount), 7L);
      assertEquals("ORDER_EVIDENCE_CONFLICT", fixture.inspect().classification, amount);
    }
    for (String amount :
        List.of(
            "10.001",
            "1e1",
            "+10",
            " 10.00",
            "10.00 ",
            "010.00",
            "NaN",
            "10000000000000000.00",
            "10." + "0".repeat(126))) {
      Fixture fixture = new Fixture();
      ObjectNode raw = validJson();
      raw.put("payAmount", amount);
      fixture.observed = new ObjectMapper().treeToValue(raw, OrderBasicEvidenceDTO.class);
      assertEquals("ORDER_EVIDENCE_INCOMPLETE", fixture.inspect().classification, amount);
      assertNull(fixture.inspect().orderEvidence.payAmountSha256());
    }
  }

  @Test
  void boundedNativeTextAndUnknownBodyFieldsCannotLeakIntoImmutableReport() throws Exception {
    for (String field : List.of("orderNo", "status")) {
      Fixture fixture = new Fixture();
      ObjectNode raw = validJson();
      raw.put(field, "x".repeat(129));
      fixture.observed = new ObjectMapper().treeToValue(raw, OrderBasicEvidenceDTO.class);
      assertEquals("ORDER_EVIDENCE_INCOMPLETE", fixture.inspect().classification);
    }
    Fixture fixture = new Fixture();
    ObjectNode raw = validJson();
    raw.put("privateExtra", "password=secret");
    fixture.observed = new ObjectMapper().treeToValue(raw, OrderBasicEvidenceDTO.class);
    var report = fixture.inspect();
    assertFalse(new ObjectMapper().writeValueAsString(report).contains("secret"));
    assertThrows(UnsupportedOperationException.class, () -> report.reasonCodes.add("private"));
    fixture.observed.payAmount = new ObjectMapper().readTree("\"999.00\"");
    assertEquals("MATCHED", report.orderEvidence.status());
  }

  /** 真实响应契约：金额文本、用户整数；仍可携带本范围忽略的库存字段。 */
  private static OrderBasicEvidenceDTO order(
      String orderNo, String state, BigDecimal amount, Long userId) {
    return order(orderNo, state, amount, userId, null);
  }

  /** 库存状态仍仅为本基本范围忽略的原字段。 */
  private static OrderBasicEvidenceDTO order(
      String orderNo, String state, BigDecimal amount, Long userId, String ignoredStockAction) {
    ObjectNode raw = new ObjectMapper().createObjectNode();
    raw.put("orderNo", orderNo).put("status", state);
    if (amount == null) raw.putNull("payAmount");
    else raw.put("payAmount", amount.toPlainString());
    if (userId == null) raw.putNull("userId");
    else raw.put("userId", userId);
    raw.put("stockActionStatus", ignoredStockAction);
    try {
      return new ObjectMapper().treeToValue(raw, OrderBasicEvidenceDTO.class);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  /** 独立原生JSON正例，不从Service实现推导预期。 */
  private static ObjectNode validJson() {
    return new ObjectMapper()
        .createObjectNode()
        .put("orderNo", "202610order1")
        .put("status", "PAID")
        .put("userId", 7)
        .put("payAmount", "10.00");
  }

  /** 原可信Pay锚点以及唯一Order GET替身。 */
  private static final class Fixture {
    /** 本地只读快照替身。 */
    final PayTccReconciliationReadService reader = mock(PayTccReconciliationReadService.class);

    /** Order GET替身，其他方法不得调用。 */
    final OrderBasicEvidenceClient orders = mock(OrderBasicEvidenceClient.class);

    /** 不可变原Pay快照。 */
    final PayTccEvidenceCaptureDTO capture;

    /** 本次远端响应。 */
    OrderBasicEvidenceDTO observed = order("202610order1", "PAID", BigDecimal.TEN, 7L, null);

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
