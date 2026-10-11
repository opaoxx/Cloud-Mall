package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.controller.PayTccUserLinkedRefundEvidenceController;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.dto.UserLinkedRefundEvidenceDTO;
import com.cloudmall.pay.domain.vo.PayTccLocalFactVO;
import com.cloudmall.pay.domain.vo.PayTccReconciliationSnapshotVO;
import com.cloudmall.pay.domain.vo.PayTccReconciliationVO;
import com.cloudmall.pay.domain.vo.PayTccUserLinkedRefundReconciliationVO;
import com.cloudmall.pay.feign.UserLinkedRefundEvidenceClient;
import com.cloudmall.pay.service.impl.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.PayTccReconciliationService;
import com.cloudmall.pay.service.impl.PayTccUserLinkedRefundEvidenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seata.core.context.RootContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 原生User报告、双次Pay快照与GET上下文隔离，未注入任何资金写资源。 */
class PayTccUserLinkedRefundEvidenceTest {
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
    verifyNoInteractions(fixture.reader, fixture.users);
  }

  @Test
  void noTrustedLocalAnchorSkipsUserAndPreservesAllExternalGaps() {
    Fixture fixture = new Fixture();
    when(fixture.reader.capture("pay1"))
        .thenReturn(new PayTccEvidenceCaptureDTO(fixture.capture.localReport(), null));
    var report = fixture.inspect();
    assertFalse(report.linkedRefundEvidence.queryAttempted());
    assertTrue(report.reasonCodes.contains("LOCAL_ANCHOR_NOT_TRUSTED"));
    assertTrue(report.externalSystemsNotQueried.contains("USER"));
    verifyNoInteractions(fixture.users);
  }

  @Test
  void sourceConflictOrIncompleteCannotBeUpgradedByMatchingBaseOrTotals() {
    for (String classification :
        List.of("LOCAL_CONFLICT", "BLOCKED_LOCAL_EVIDENCE", "private-unknown")) {
      Fixture fixture = new Fixture();
      fixture.observed.put("classification", classification);
      var report = fixture.inspect();
      assertEquals(
          "LOCAL_CONFLICT".equals(classification)
              ? "USER_EVIDENCE_CONFLICT"
              : "USER_EVIDENCE_INCOMPLETE",
          report.classification);
      assertNull(report.linkedRefundEvidence.observedCount());
    }
  }

  @Test
  void nestedOriginalIdentityHashesAndProjectionKeepTheExistingT10Gate() {
    for (String variant :
        List.of("userType", "amountType", "wrongPay", "hash", "sourceConflict", "projection")) {
      Fixture fixture = new Fixture();
      switch (variant) {
        case "userType" -> fixture.baseObserved.put("userId", 7.0);
        case "amountType" -> fixture.baseObserved.put("amount", 10);
        case "wrongPay" -> fixture.baseObserved.put("payNo", "other");
        case "hash" ->
            ((ObjectNode) fixture.baseObserved.get("identifiers")).put("xidSha256", "0".repeat(64));
        case "sourceConflict" -> fixture.baseObserved.put("classification", "LOCAL_CONFLICT");
        case "projection" -> fixture.account().put("frozenProjectionMatched", false);
        default -> throw new AssertionError(variant);
      }
      assertEquals(
          List.of("wrongPay", "hash", "sourceConflict", "projection").contains(variant)
              ? "USER_EVIDENCE_CONFLICT"
              : "USER_EVIDENCE_INCOMPLETE",
          fixture.inspect().classification,
          variant);
      assertNull(fixture.inspect().linkedRefundEvidence.vectorSha256());
    }
  }

  @Test
  void matchedSummaryIsImmutableAndNoOriginalBodyIsSerialized() throws Exception {
    Fixture fixture = new Fixture();
    fixture.observed.put("rawXid", "private-password");
    var report = fixture.inspect();
    String json = new ObjectMapper().writeValueAsString(report);
    for (String raw :
        List.of("private-xid", "instance-token", "tcc-debit:pay1", "private-password"))
      assertFalse(json.contains(raw));
    assertThrows(UnsupportedOperationException.class, () -> report.reasonCodes.add("fake"));
    fixture.account().put("balance", "999.00");
    assertEquals("100.00", report.userAccountEvidence.balance());
  }

  @Test
  void transportFailuresContextRestorationAndLocalChangesTakePriority() {
    for (boolean fail : List.of(false, true)) {
      Fixture fixture = new Fixture();
      AuthContext.set(99L, "ADMIN");
      try {
        doAnswer(
                invocation -> {
                  assertNull(AuthContext.userId());
                  assertNull(AuthContext.role());
                  if (fail) throw new IllegalStateException("private-error-password");
                  return ApiResponse.ok(dto(fixture.observed));
                })
            .when(fixture.users)
            .inspect(anyLong(), anyString(), anyString(), anyString());
        assertEquals(
            fail ? "USER_EVIDENCE_UNAVAILABLE" : "BLOCKED_REMAINING_EVIDENCE",
            fixture.inspect().classification);
        assertEquals(99L, AuthContext.userId());
        assertEquals("ADMIN", AuthContext.role());
        when(fixture.reader.capture("pay1"))
            .thenReturn(
                fixture.capture, new PayTccEvidenceCaptureDTO(fixture.capture.localReport(), null));
        assertEquals("LOCAL_EVIDENCE_CHANGED", fixture.inspect().classification);
      } finally {
        AuthContext.clear();
      }
    }
    Fixture unavailable = new Fixture();
    when(unavailable.reader.capture("pay1"))
        .thenThrow(new DataAccessResourceFailureException("jdbc-secret"));
    assertEquals(503, assertThrows(BizException.class, unavailable::inspect).getHttpStatus());
    verifyNoInteractions(unavailable.users);
  }

  @Test
  void envelopesAndHttpErrorsDoNotLeakDataOrBecomeSafeNoRefund() throws Exception {
    Fixture fixture = new Fixture();
    doReturn(null).when(fixture.users).inspect(anyLong(), anyString(), anyString(), anyString());
    assertEquals("USER_EVIDENCE_UNAVAILABLE", fixture.inspect().classification);
    doReturn(ApiResponse.error("private-code", "private-error"))
        .when(fixture.users)
        .inspect(anyLong(), anyString(), anyString(), anyString());
    assertEquals("USER_EVIDENCE_UNAVAILABLE", fixture.inspect().classification);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new PayTccUserLinkedRefundEvidenceController(fixture.service(true, true)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route = "/api/internal/payments/pay1/tcc-reconciliation/user-linked-refund-evidence";
    mvc.perform(get(route))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    doThrow(new BizException("FIXTURE_CONFLICT", "固定错误", 409)).when(fixture.reader).capture("pay1");
    mvc.perform(
            get(route)
                .header("X-Internal-Service", "cm18-reconciliation")
                .header("X-Internal-Callback-Token", "secret"))
        .andExpect(status().isConflict())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void fullCollectionAllowsOnlyItsFixedCoverageGapAndEmptyIsScoped() {
    for (String variant : List.of("empty", "otherOnly", "fixedAndOther", "fixedFull")) {
      Fixture fixture =
          new Fixture("COMMITTED", "CONFIRMED", "empty".equals(variant) ? "PENDING" : "REFUNDED");
      if (!"empty".equals(variant)) fixture.linked(2, "10.00");
      if ("fixedAndOther".equals(variant)) fixture.fixedPartial();
      if ("fixedFull".equals(variant)) fixture.refund("10.00");
      var report = fixture.inspect();
      assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification, variant);
      assertEquals("PAY_USER_LINKED_REFUNDS_OBSERVED_ONLY", report.scope);
      assertTrue(report.unlinkedLedgerRowsNotInspected);
      assertTrue(report.accountCausalityNotProven);
      assertFalse(report.automaticActionsAllowed);
      assertEquals("MATCHED", report.linkedRefundEvidence.status());
      assertEquals(
          "empty".equals(variant) ? "0.00" : "10.00",
          report.linkedRefundEvidence.validatedTotalAmount());
      assertEquals(
          "fixedAndOther".equals(variant) ? "5.00" : "fixedFull".equals(variant) ? "10.00" : null,
          report.userAccountEvidence.fixedRefundAmount());
    }
    Fixture cancelled = new Fixture("ROLLEDBACK", "CANCELLED", "PENDING");
    assertEquals("BLOCKED_REMAINING_EVIDENCE", cancelled.inspect().classification);
  }

  @Test
  void nativeScopeCountsMoneyAndVectorCannotBeCoercedOrOmitted() {
    for (String field :
        List.of(
            "scope",
            "automaticActionsAllowed",
            "unlinkedLedgerRowsNotInspected",
            "candidateLimit",
            "classification",
            "queryAttempted",
            "candidatesComplete",
            "observedCount",
            "validatedTotalAmount",
            "vectorSha256",
            "baseEvidence")) {
      Fixture fixture = new Fixture();
      fixture.observed.remove(field);
      assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification, field);
      assertNull(fixture.inspect().userAccountEvidence);
    }
    for (String field :
        List.of(
            "automaticActionsAllowed",
            "unlinkedLedgerRowsNotInspected",
            "queryAttempted",
            "candidatesComplete")) {
      Fixture fixture = new Fixture();
      fixture.observed.put(field, "true");
      assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification);
    }
    for (String variant :
        List.of(
            "text",
            "float",
            "negative",
            "overflow",
            "tooLarge",
            "limit",
            "moneyNumber",
            "moneyScale",
            "moneyOverflow",
            "moneySpace",
            "hash")) {
      Fixture fixture = new Fixture();
      switch (variant) {
        case "text" -> fixture.observed.put("observedCount", "1");
        case "float" -> fixture.observed.put("observedCount", 1.0);
        case "negative" -> fixture.observed.put("observedCount", -1);
        case "overflow" -> fixture.observed.put("observedCount", Long.MAX_VALUE);
        case "tooLarge" -> fixture.observed.put("observedCount", 101);
        case "limit" -> fixture.observed.put("candidateLimit", 100.0);
        case "moneyNumber" -> fixture.observed.put("validatedTotalAmount", 10);
        case "moneyScale" -> fixture.observed.put("validatedTotalAmount", "10.001");
        case "moneyOverflow" ->
            fixture.observed.put("validatedTotalAmount", "10000000000000000.00");
        case "moneySpace" -> fixture.observed.put("validatedTotalAmount", " 10.00");
        case "hash" -> fixture.observed.put("vectorSha256", "A".repeat(64));
        default -> throw new AssertionError(variant);
      }
      assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification, variant);
      assertNull(fixture.inspect().linkedRefundEvidence.validatedTotalAmount());
    }
  }

  @Test
  void fullTotalDoesNotEraseUnknownBaseReasonsOrFixedProofConflicts() {
    for (String variant :
        List.of(
            "missing",
            "text",
            "null",
            "unknown",
            "empty",
            "onlyExternal",
            "externalPartial",
            "absent",
            "full",
            "wrongHash",
            "delta",
            "projection",
            "wrongId")) {
      Fixture fixture = new Fixture();
      fixture.linked(2, "10.00");
      fixture.fixedPartial();
      switch (variant) {
        case "missing" -> fixture.baseObserved.remove("reasonCodes");
        case "text" -> fixture.baseObserved.put("reasonCodes", "REFUND_PARTIAL_OBSERVED");
        case "null" -> fixture.baseObserved.putArray("reasonCodes").addNull();
        case "unknown" ->
            fixture
                .baseObserved
                .putArray("reasonCodes")
                .add("REFUND_PARTIAL_OBSERVED")
                .add("private-unknown");
        case "empty" -> fixture.baseObserved.putArray("reasonCodes");
        case "onlyExternal" ->
            fixture.baseObserved.putArray("reasonCodes").add("EXTERNAL_EVIDENCE_NOT_QUERIED");
        case "externalPartial" ->
            fixture.baseObserved.put("classification", "BLOCKED_EXTERNAL_EVIDENCE");
        case "absent" -> {
          fixture.refundNode().put("present", false);
          for (String field :
              List.of(
                  "state",
                  "amount",
                  "balanceBefore",
                  "balanceAfter",
                  "identityMatched",
                  "operationKeySha256",
                  "originalOperationKeySha256")) fixture.refundNode().putNull(field);
        }
        case "full" -> fixture.refund("10.00");
        case "wrongHash" -> fixture.refundNode().put("originalOperationKeySha256", "0".repeat(64));
        case "delta" -> fixture.refundNode().put("balanceAfter", "90.00");
        case "projection" -> fixture.account().put("frozenProjectionMatched", false);
        case "wrongId" -> fixture.baseObserved.put("userId", 8);
        default -> throw new AssertionError(variant);
      }
      assertEquals(
          List.of("missing", "text", "null", "unknown", "empty", "onlyExternal", "externalPartial")
                  .contains(variant)
              ? "USER_EVIDENCE_INCOMPLETE"
              : "USER_EVIDENCE_CONFLICT",
          fixture.inspect().classification,
          variant);
      assertNull(fixture.inspect().userAccountEvidence);
      assertNull(fixture.inspect().linkedRefundEvidence.vectorSha256());
    }
  }

  @Test
  void contradictoryTotalsEmptyVectorsAndRefundStatesAreNeverRefundProof() {
    for (String variant :
        List.of(
            "partial",
            "negative",
            "over",
            "zeroPositive",
            "positiveZero",
            "emptyHash",
            "positiveCancelled",
            "subset")) {
      Fixture fixture =
          "positiveCancelled".equals(variant)
              ? new Fixture("ROLLEDBACK", "CANCELLED", "PENDING")
              : new Fixture();
      switch (variant) {
        case "partial" -> fixture.linked(1, "5.00");
        case "negative" -> fixture.linked(1, "-1.00");
        case "over" -> fixture.linked(1, "10.01");
        case "zeroPositive" -> fixture.linked(0, "10.00");
        case "positiveZero" -> fixture.linked(1, "0.00");
        case "emptyHash" -> fixture.observed.put("vectorSha256", "0".repeat(64));
        case "positiveCancelled" -> fixture.linked(1, "10.00");
        case "subset" -> fixture.refund("10.00");
        default -> throw new AssertionError(variant);
      }
      assertEquals("USER_EVIDENCE_CONFLICT", fixture.inspect().classification, variant);
      assertNull(fixture.inspect().linkedRefundEvidence.observedCount());
    }
    Fixture refunded = new Fixture("COMMITTED", "CONFIRMED", "REFUNDED");
    assertEquals("USER_EVIDENCE_INCOMPLETE", refunded.inspect().classification);
  }

  /** 原生JSON报告到实际Feign DTO，不改类型。 */
  private static UserLinkedRefundEvidenceDTO dto(ObjectNode value) {
    try {
      return new ObjectMapper().treeToValue(value, UserLinkedRefundEvidenceDTO.class);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  /** 对原标识形成规范指纹，不通过应用Service实现构造测试预期。 */
  private static String sha(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  /** 固定原Pay锚点与User原生字段，所有资源仅有读方法。 */
  private static final class Fixture {
    /** 独立RR入口替身。 */
    final PayTccReconciliationReadService reader = mock(PayTccReconciliationReadService.class);

    /** GET-only User接口替身。 */
    final UserLinkedRefundEvidenceClient users = mock(UserLinkedRefundEvidenceClient.class);

    /** 原Pay快照及锚点。 */
    final PayTccEvidenceCaptureDTO capture;

    /** 可变的原User JSON，用于观察类型/缺字段/矛盾。 */
    final ObjectNode baseObserved = new ObjectMapper().createObjectNode();

    /** 原User16T11外层native字段。 */
    final ObjectNode observed = new ObjectMapper().createObjectNode();

    Fixture() {
      this("COMMITTED", "CONFIRMED", "PENDING");
    }

    Fixture(String executionState, String userState, String paymentState) {
      var snapshot =
          new PayTccReconciliationSnapshotVO(
              "pay1",
              "TCC",
              paymentState,
              "202610order1",
              7L,
              "10.00",
              new PayTccLocalFactVO(true, executionState),
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
                  executionState,
                  "ROLLEDBACK".equals(executionState) ? "ROLLBACK" : "COMMIT"));
      baseObserved
          .put("scope", "USER_ACCOUNT_FROZEN_REFUND_OBSERVED_ONLY")
          .put("automaticActionsAllowed", false)
          .put("otherRefundsNotInspected", true)
          .put("userId", 7L)
          .put("payNo", "pay1")
          .put("orderNo", "202610order1")
          .put("amount", "10.00")
          .put("tccState", userState)
          .put("ledgerApplied", "CONFIRMED".equals(userState))
          .put("classification", "BLOCKED_EXTERNAL_EVIDENCE");
      baseObserved
          .putObject("identifiers")
          .put("xidSha256", sha("private-xid"))
          .put("branchIdSha256", sha("202"))
          .put("operationKeySha256", sha("tcc-debit:pay1"))
          .put("ledgerKeySha256", sha("pay1"));
      baseObserved
          .putObject("account")
          .put("present", true)
          .put("status", "ACTIVE")
          .put("balance", "100.00")
          .put("frozenBalance", "0.00")
          .put("tryingFrozenTotal", "0.00")
          .put("frozenProjectionMatched", true);
      var refund = baseObserved.putObject("refund");
      refund.put("present", false);
      for (String field :
          List.of(
              "state",
              "amount",
              "balanceBefore",
              "balanceAfter",
              "identityMatched",
              "operationKeySha256",
              "originalOperationKeySha256")) refund.putNull(field);
      observed
          .put("scope", "USER_LINKED_REFUNDS_OBSERVED_ONLY")
          .put("automaticActionsAllowed", false)
          .put("unlinkedLedgerRowsNotInspected", true)
          .put("candidateLimit", 100)
          .put("classification", "BLOCKED_EXTERNAL_EVIDENCE")
          .put("queryAttempted", true)
          .put("candidatesComplete", true)
          .put("observedCount", 0)
          .put("validatedTotalAmount", "0.00")
          .put("vectorSha256", sha(""));
      observed.set("baseEvidence", baseObserved);
      when(reader.capture("pay1")).thenReturn(capture);
      when(users.inspect(anyLong(), anyString(), anyString(), anyString()))
          .thenAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertNull(RootContext.getXID());
                assertNull(AuthContext.userId());
                assertNull(AuthContext.role());
                return ApiResponse.ok(dto(observed));
              });
    }

    /** 明确当前原关联候选总额；空候选使用规范空向量。 */
    void linked(int count, String total) {
      observed
          .put("observedCount", count)
          .put("validatedTotalAmount", total)
          .put("vectorSha256", count == 0 ? sha("") : sha("opaque-linked-vector"));
    }

    /** 原T9固定部分退款确切原因，不伪造B_EXTERNAL。 */
    void fixedPartial() {
      refund("5.00");
      baseObserved.put("classification", "BLOCKED_LOCAL_EVIDENCE");
      baseObserved
          .putArray("reasonCodes")
          .add("REFUND_PARTIAL_OBSERVED")
          .add("EXTERNAL_EVIDENCE_NOT_QUERIED");
    }

    ObjectNode account() {
      return (ObjectNode) baseObserved.get("account");
    }

    ObjectNode refundNode() {
      return (ObjectNode) baseObserved.get("refund");
    }

    void refund(String amount) {
      var refund = refundNode();
      refund
          .put("present", true)
          .put("state", "APPLIED")
          .put("identityMatched", true)
          .put("amount", amount)
          .put("balanceBefore", "90.00")
          .put(
              "balanceAfter",
              new java.math.BigDecimal("90.00")
                  .add(new java.math.BigDecimal(amount))
                  .toPlainString())
          .put("operationKeySha256", sha("refund:pay1"))
          .put("originalOperationKeySha256", sha("pay1"));
    }

    PayTccUserLinkedRefundEvidenceService service(boolean enabled, boolean localEnabled) {
      return new PayTccUserLinkedRefundEvidenceService(
          new PayTccReconciliationService(reader, localEnabled, "secret"),
          reader,
          users,
          enabled,
          "instance-token");
    }

    PayTccUserLinkedRefundReconciliationVO inspect() {
      return service(true, true).inspect("pay1", "cm18-reconciliation", "secret");
    }
  }
}
