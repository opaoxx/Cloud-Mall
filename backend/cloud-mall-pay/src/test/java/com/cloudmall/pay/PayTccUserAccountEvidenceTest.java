package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.controller.PayTccUserAccountEvidenceController;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.dto.UserAccountEvidenceDTO;
import com.cloudmall.pay.domain.vo.PayTccLocalFactVO;
import com.cloudmall.pay.domain.vo.PayTccReconciliationSnapshotVO;
import com.cloudmall.pay.domain.vo.PayTccReconciliationVO;
import com.cloudmall.pay.domain.vo.PayTccUserAccountReconciliationVO;
import com.cloudmall.pay.feign.UserAccountEvidenceClient;
import com.cloudmall.pay.service.impl.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.PayTccReconciliationService;
import com.cloudmall.pay.service.impl.PayTccUserAccountEvidenceService;
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
class PayTccUserAccountEvidenceTest {
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
    assertFalse(report.userAccountEvidence.queryAttempted());
    assertTrue(report.reasonCodes.contains("LOCAL_ANCHOR_NOT_TRUSTED"));
    assertTrue(report.externalSystemsNotQueried.contains("USER"));
    verifyNoInteractions(fixture.users);
  }

  @Test
  void matchingAccountsAndLegitimateRefundsNeverAuthorizeFunds() {
    for (String variant :
        List.of("absent", "full", "disabled", "otherFrozen", "pendingTry", "cancelled")) {
      Fixture fixture =
          "pendingTry".equals(variant)
              ? new Fixture("COMMIT_REQUESTED", "TRYING", "PENDING")
              : "cancelled".equals(variant)
                  ? new Fixture("ROLLEDBACK", "CANCELLED", "FAILED")
                  : new Fixture();
      if ("full".equals(variant)) fixture.refund("10.00");
      if ("disabled".equals(variant)) fixture.account().put("status", "DISABLED");
      if ("otherFrozen".equals(variant)) {
        fixture.account().put("frozenBalance", "30.00");
        fixture.account().put("tryingFrozenTotal", "30.00");
      }
      var report = fixture.inspect();
      assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification, variant);
      assertTrue(report.userAccountEvidence.identityMatched());
      assertFalse(report.automaticActionsAllowed);
      assertTrue(report.otherRefundsNotInspected);
      assertEquals("PAY_USER_ACCOUNT_OBSERVED_ONLY", report.scope);
      assertEquals(List.of("ORDER", "STOCK", "TC", "MQ"), report.externalSystemsNotQueried);
      verify(fixture.reader, times(2)).capture("pay1");
      verify(fixture.users).inspect(7L, "pay1", "cm18-reconciliation", "instance-token");
      verifyNoMoreInteractions(fixture.reader, fixture.users);
    }
  }

  @Test
  void allMissingRootFieldsAndNativeTypeCoercionsStayIncomplete() {
    for (String field :
        List.of(
            "scope",
            "automaticActionsAllowed",
            "otherRefundsNotInspected",
            "userId",
            "payNo",
            "orderNo",
            "amount",
            "tccState",
            "ledgerApplied",
            "classification",
            "identifiers",
            "account",
            "refund")) {
      Fixture fixture = new Fixture();
      fixture.observed.remove(field);
      assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification, field);
    }
    for (String field :
        List.of("automaticActionsAllowed", "otherRefundsNotInspected", "ledgerApplied")) {
      Fixture fixture = new Fixture();
      fixture.observed.put(field, "false");
      assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification, field);
    }
    for (String variant :
        List.of(
            "numericAmount",
            "textUser",
            "decimalUser",
            "overflowUser",
            "autoTrue",
            "otherFalse",
            "scope",
            "classification")) {
      Fixture fixture = new Fixture();
      switch (variant) {
        case "numericAmount" -> fixture.observed.put("amount", 10.0);
        case "textUser" -> fixture.observed.put("userId", "7");
        case "decimalUser" -> fixture.observed.put("userId", 7.0);
        case "overflowUser" ->
            fixture.observed.put("userId", new java.math.BigInteger("9223372036854775808"));
        case "autoTrue" -> fixture.observed.put("automaticActionsAllowed", true);
        case "otherFalse" -> fixture.observed.put("otherRefundsNotInspected", false);
        case "scope" -> fixture.observed.put("scope", "other");
        case "classification" -> fixture.observed.put("classification", "password-private-code");
        default -> throw new AssertionError(variant);
      }
      assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification, variant);
    }
  }

  @Test
  void wrongOriginalIdentityOrAnyOfFourValidButDifferentHashesIsConflict() {
    for (String field :
        List.of(
            "userId",
            "payNo",
            "orderNo",
            "amount",
            "xidSha256",
            "branchIdSha256",
            "operationKeySha256",
            "ledgerKeySha256",
            "ledgerApplied")) {
      Fixture fixture = new Fixture();
      if (field.endsWith("Sha256"))
        ((ObjectNode) fixture.observed.get("identifiers")).put(field, "0".repeat(64));
      else
        switch (field) {
          case "userId" -> fixture.observed.put(field, 8L);
          case "payNo" -> fixture.observed.put(field, "PAY1");
          case "orderNo" -> fixture.observed.put(field, "wrong-order");
          case "amount" -> fixture.observed.put(field, "11.00");
          case "ledgerApplied" -> fixture.observed.put(field, false);
          default -> throw new AssertionError(field);
        }
      assertEquals("USER_EVIDENCE_CONFLICT", fixture.inspect().classification, field);
    }
    Fixture hashType = new Fixture();
    ((ObjectNode) hashType.observed.get("identifiers")).put("xidSha256", false);
    assertEquals("USER_EVIDENCE_INCOMPLETE", hashType.inspect().classification);
  }

  @Test
  void sourceConflictAndMissingReportsCannotBeUpgradedOrLeakUnverifiedMoney() throws Exception {
    for (String classification : List.of("LOCAL_CONFLICT", "BLOCKED_LOCAL_EVIDENCE")) {
      Fixture fixture = new Fixture();
      fixture.observed.put("classification", classification);
      fixture.account().put("balance", "private-money-error");
      fixture.observed.put("amount", false);
      ((ObjectNode) fixture.observed.get("identifiers")).put("xidSha256", "private-bad-hash");
      fixture.observed.put("userId", 99L);
      fixture.observed.putArray("reasonCodes").add("private-raw-error");
      var report = fixture.inspect();
      assertEquals(
          "LOCAL_CONFLICT".equals(classification)
              ? "USER_EVIDENCE_CONFLICT"
              : "USER_EVIDENCE_INCOMPLETE",
          report.classification);
      assertNull(report.userAccountEvidence.balance());
      assertFalse(new ObjectMapper().writeValueAsString(report).contains("private-"));
    }
  }

  @Test
  void oppositeTerminalStatesAndTerminalPayWithTryingUserStayBlocked() {
    assertEquals(
        "USER_EVIDENCE_CONFLICT",
        new Fixture("COMMITTED", "CANCELLED", "PENDING").inspect().classification);
    assertEquals(
        "USER_EVIDENCE_CONFLICT",
        new Fixture("ROLLEDBACK", "CONFIRMED", "FAILED").inspect().classification);
    assertEquals(
        "USER_EVIDENCE_INCOMPLETE",
        new Fixture("COMMITTED", "TRYING", "PENDING").inspect().classification);
    Fixture unknown = new Fixture();
    unknown.observed.put("tccState", "UNKNOWN");
    assertEquals("USER_EVIDENCE_INCOMPLETE", unknown.inspect().classification);
  }

  @Test
  void accountFieldsProjectionAndExtendedSumPrecisionAreCheckedWithoutDefaults() {
    for (String field :
        List.of(
            "present",
            "status",
            "balance",
            "frozenBalance",
            "tryingFrozenTotal",
            "frozenProjectionMatched")) {
      Fixture fixture = new Fixture();
      fixture.account().remove(field);
      assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification, field);
    }
    for (String variant :
        List.of("negative", "mismatch", "falseProof", "hugeSum", "numericMoney")) {
      Fixture fixture = new Fixture();
      switch (variant) {
        case "negative" -> fixture.account().put("balance", "-1.00");
        case "mismatch" -> fixture.account().put("tryingFrozenTotal", "10.00");
        case "falseProof" -> fixture.account().put("frozenProjectionMatched", false);
        case "hugeSum" ->
            fixture.account().put("tryingFrozenTotal", "100000000000000000000000000000.00");
        case "numericMoney" -> fixture.account().put("balance", 100.0);
        default -> throw new AssertionError(variant);
      }
      assertEquals(
          "numericMoney".equals(variant) ? "USER_EVIDENCE_INCOMPLETE" : "USER_EVIDENCE_CONFLICT",
          fixture.inspect().classification,
          variant);
    }
  }

  @Test
  void everyAbsentRefundFieldMustBeExplicitNullAndRefundedPayNeedsFullProof() {
    for (String field :
        List.of(
            "state",
            "amount",
            "balanceBefore",
            "balanceAfter",
            "identityMatched",
            "operationKeySha256",
            "originalOperationKeySha256")) {
      Fixture fixture = new Fixture();
      fixture.refundNode().remove(field);
      assertEquals("USER_EVIDENCE_INCOMPLETE", fixture.inspect().classification, field);
    }
    Fixture refunded = new Fixture("COMMITTED", "CONFIRMED", "REFUNDED");
    assertEquals("USER_EVIDENCE_INCOMPLETE", refunded.inspect().classification);
    refunded.refund("10.00");
    assertEquals("BLOCKED_REMAINING_EVIDENCE", refunded.inspect().classification);
    refunded.refund("5.00");
    assertEquals("USER_EVIDENCE_INCOMPLETE", refunded.inspect().classification);
    assertTrue(refunded.inspect().reasonCodes.contains("REFUND_PARTIAL_OBSERVED"));
  }

  @Test
  void refundIdentityStatusAmountsAndDeltaCannotBeAcceptedWithoutProof() {
    for (String variant :
        List.of(
            "state", "identity", "amount", "delta", "negative", "hash", "originalHash", "type")) {
      Fixture fixture = new Fixture();
      fixture.refund("10.00");
      switch (variant) {
        case "state" -> fixture.refundNode().put("state", "REJECTED");
        case "identity" -> fixture.refundNode().put("identityMatched", false);
        case "amount" -> fixture.refundNode().put("amount", "11.00");
        case "delta" -> fixture.refundNode().put("balanceAfter", "99.00");
        case "negative" -> fixture.refundNode().put("balanceBefore", "-1.00");
        case "hash" -> fixture.refundNode().put("operationKeySha256", "0".repeat(64));
        case "originalHash" ->
            fixture.refundNode().put("originalOperationKeySha256", "0".repeat(64));
        case "type" -> fixture.refundNode().put("amount", 10.0);
        default -> throw new AssertionError(variant);
      }
      assertEquals(
          "type".equals(variant) ? "USER_EVIDENCE_INCOMPLETE" : "USER_EVIDENCE_CONFLICT",
          fixture.inspect().classification,
          variant);
    }
    Fixture trying = new Fixture("COMMIT_REQUESTED", "TRYING", "PENDING");
    trying.refund("10.00");
    assertEquals("USER_EVIDENCE_CONFLICT", trying.inspect().classification);
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
                new PayTccUserAccountEvidenceController(fixture.service(true, true)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route = "/api/internal/payments/pay1/tcc-reconciliation/user-account-evidence";
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
  void matchedSummaryIsImmutableAndNeverSerializesTheOriginalBody() throws Exception {
    Fixture fixture = new Fixture();
    fixture.refund("10.00");
    fixture.observed.put("rawXid", "private-password");
    var report = fixture.inspect();
    String body = new ObjectMapper().writeValueAsString(report);
    for (String secret :
        List.of(
            "private-xid", "instance-token", "tcc-debit:pay1", "refund:pay1", "private-password"))
      assertFalse(body.contains(secret));
    assertThrows(UnsupportedOperationException.class, () -> report.reasonCodes.add("bad"));
    fixture.account().put("balance", "999.00");
    assertEquals("100.00", report.userAccountEvidence.balance());
  }

  /** 原生JSON报告到实际Feign DTO，不改类型。 */
  private static UserAccountEvidenceDTO dto(ObjectNode value) {
    try {
      return new ObjectMapper().treeToValue(value, UserAccountEvidenceDTO.class);
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
    final UserAccountEvidenceClient users = mock(UserAccountEvidenceClient.class);

    /** 原Pay快照及锚点。 */
    final PayTccEvidenceCaptureDTO capture;

    /** 可变的原User JSON，用于观察类型/缺字段/矛盾。 */
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
      observed
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
      observed
          .putObject("identifiers")
          .put("xidSha256", sha("private-xid"))
          .put("branchIdSha256", sha("202"))
          .put("operationKeySha256", sha("tcc-debit:pay1"))
          .put("ledgerKeySha256", sha("pay1"));
      observed
          .putObject("account")
          .put("present", true)
          .put("status", "ACTIVE")
          .put("balance", "100.00")
          .put("frozenBalance", "0.00")
          .put("tryingFrozenTotal", "0.00")
          .put("frozenProjectionMatched", true);
      var refund = observed.putObject("refund");
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

    ObjectNode account() {
      return (ObjectNode) observed.get("account");
    }

    ObjectNode refundNode() {
      return (ObjectNode) observed.get("refund");
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

    PayTccUserAccountEvidenceService service(boolean enabled, boolean localEnabled) {
      return new PayTccUserAccountEvidenceService(
          new PayTccReconciliationService(reader, localEnabled, "secret"),
          reader,
          users,
          enabled,
          "instance-token");
    }

    PayTccUserAccountReconciliationVO inspect() {
      return service(true, true).inspect("pay1", "cm18-reconciliation", "secret");
    }
  }
}
